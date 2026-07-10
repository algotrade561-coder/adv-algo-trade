package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gamma Exposure (GEX) Service.
 *
 * <p>GEX per strike = (CE gamma × CE OI − PE gamma × PE OI) × spot²
 *
 * <p>Interpretation:
 * <ul>
 *   <li>Positive GEX strike → dealers are long gamma → they BUY dips / SELL rips near that strike
 *       (acts as a support/resistance magnet, dampens moves)</li>
 *   <li>Negative GEX strike → dealers are short gamma → they SELL dips / BUY rips
 *       (amplifies moves, price can run freely)</li>
 *   <li>GEX flip point → strike where aggregate GEX crosses zero; below it dealers amplify,
 *       above it they dampen. Price tends to accelerate when it crosses the flip point.</li>
 * </ul>
 *
 * <p>All inputs (gamma, OI) are already live in {@link LiveInstrumentCache} — this service
 * is purely a computation layer with no new data dependencies.
 */
@Service
public class GammaExposureService {

    private static final Logger log = LoggerFactory.getLogger(GammaExposureService.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    /** Cached GEX snapshot per index — refreshed on every call to evaluate(). */
    private final Map<IndexType, GexSnapshot> cache = new ConcurrentHashMap<>();

    public GammaExposureService(LiveInstrumentCache liveInstrumentCache,
                                 ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compute and cache the full GEX profile for an index.
     * Call once per strategy tick (not per option tick — too expensive).
     */
    public GexSnapshot evaluate(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return GexSnapshot.empty(indexType);

        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        List<OptionInstrument> chain = liveInstrumentCache.getStrikeChain(indexType, expiry);
        if (chain.isEmpty()) return GexSnapshot.empty(indexType);

        // Aggregate CE and PE gamma×OI per strike
        Map<Integer, double[]> byStrike = new TreeMap<>(); // strike → [ceGex, peGex]
        for (OptionInstrument opt : chain) {
            if (opt.getGamma() <= 0 || opt.getOpenInterest() <= 0) continue;
            double gex = opt.getGamma() * opt.getOpenInterest() * spot * spot / 1_000_000.0; // scale to readable units
            byStrike.compute(opt.getStrikePrice(), (k, v) -> {
                if (v == null) v = new double[]{0, 0};
                if (opt.isCE()) v[0] += gex;
                else            v[1] += gex;
                return v;
            });
        }

        if (byStrike.isEmpty()) return GexSnapshot.empty(indexType);

        // Build per-strike net GEX list and find flip point
        List<StrikeGex> strikes = new ArrayList<>(byStrike.size());
        double totalGex = 0;
        for (Map.Entry<Integer, double[]> e : byStrike.entrySet()) {
            double netGex = e.getValue()[0] - e.getValue()[1]; // CE GEX − PE GEX
            strikes.add(new StrikeGex(e.getKey(), netGex, e.getValue()[0], e.getValue()[1]));
            totalGex += netGex;
        }

        // GEX flip point: strike where cumulative GEX (from lowest strike up) crosses zero.
        // This is where dealer hedging regime flips from dampening to amplifying.
        int flipStrike = findFlipPoint(strikes, spot);

        // Nearest positive-GEX wall above spot (dealer support ceiling)
        int wallAbove = findNearestWall(strikes, spot, true);
        // Nearest positive-GEX wall below spot (dealer support floor)
        int wallBelow = findNearestWall(strikes, spot, false);

        // ATM net GEX (±1 strike band)
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        double atmGex = strikes.stream()
                .filter(s -> Math.abs(s.strike - atm) <= interval)
                .mapToDouble(s -> s.netGex)
                .sum();

        GexSnapshot snap = new GexSnapshot(indexType, spot, totalGex, atmGex,
                flipStrike, wallAbove, wallBelow, strikes);
        cache.put(indexType, snap);

        log.debug("[GEX][{}] total={} atm={} flip={} wallAbove={} wallBelow={}",
                indexType, String.format("%.0f", totalGex), String.format("%.0f", atmGex),
                flipStrike, wallAbove, wallBelow);
        return snap;
    }

    /** Latest cached snapshot — returns empty if evaluate() hasn't been called yet. */
    public GexSnapshot getLatest(IndexType indexType) {
        return cache.getOrDefault(indexType, GexSnapshot.empty(indexType));
    }

    /**
     * Quick signal for strategy gates:
     * +1 = spot above flip (dealers dampen → mean-reversion regime, tighter stops)
     * -1 = spot below flip (dealers amplify → trending regime, wider stops / momentum entries)
     *  0 = no flip computed
     */
    public int dealerRegime(IndexType indexType) {
        GexSnapshot snap = cache.get(indexType);
        if (snap == null || snap.flipStrike <= 0) return 0;
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;
        return spot >= snap.flipStrike ? +1 : -1;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Find the strike where cumulative GEX (summed from lowest strike upward) crosses zero.
     * This is the standard "GEX flip point" used by SpotGamma / Tier1Alpha methodology.
     */
    private int findFlipPoint(List<StrikeGex> strikes, double spot) {
        double cumulative = 0;
        int prev = 0;
        for (StrikeGex s : strikes) {
            double prevCum = cumulative;
            cumulative += s.netGex;
            if (prev > 0 && prevCum < 0 && cumulative >= 0) return s.strike; // crossed zero upward
            if (prev > 0 && prevCum > 0 && cumulative <= 0) return s.strike; // crossed zero downward
            prev = s.strike;
        }
        // Fallback: strike with smallest absolute net GEX near ATM (closest to zero)
        return strikes.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s.netGex)))
                .map(s -> s.strike)
                .orElse(0);
    }

    /** Nearest strike with positive net GEX above (aboveSpot=true) or below spot. */
    private int findNearestWall(List<StrikeGex> strikes, double spot, boolean aboveSpot) {
        return strikes.stream()
                .filter(s -> aboveSpot ? s.strike > spot : s.strike < spot)
                .filter(s -> s.netGex > 0)
                .min(Comparator.comparingDouble(s -> Math.abs(s.strike - spot)))
                .map(s -> s.strike)
                .orElse(0);
    }

    // ── Data types ────────────────────────────────────────────────────────────

    public record StrikeGex(int strike, double netGex, double ceGex, double peGex) {}

    public record GexSnapshot(
            IndexType indexType,
            double spot,
            double totalGex,      // Sum of all net GEX across chain
            double atmGex,        // Net GEX in ATM ±1 strike band
            int flipStrike,       // Strike where dealer regime flips
            int wallAbove,        // Nearest positive-GEX wall above spot
            int wallBelow,        // Nearest positive-GEX wall below spot
            List<StrikeGex> strikes
    ) {
        static GexSnapshot empty(IndexType ix) {
            return new GexSnapshot(ix, 0, 0, 0, 0, 0, 0, List.of());
        }

        public boolean isValid() { return spot > 0 && !strikes.isEmpty(); }

        /** True when spot is above the GEX flip → dealers dampen moves (mean-reversion). */
        public boolean isDampeningRegime(double currentSpot) {
            return flipStrike > 0 && currentSpot >= flipStrike;
        }

        /** True when spot is below the GEX flip → dealers amplify moves (trending). */
        public boolean isAmplifyingRegime(double currentSpot) {
            return flipStrike > 0 && currentSpot < flipStrike;
        }
    }
}
