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
 * OI + Price 4-Cell Matrix Service.
 *
 * <p>Classifies each strike into one of four canonical cells based on the
 * direction of OI change and price change over the last 3 minutes:
 *
 * <pre>
 *              OI Rising          OI Falling
 * Price Up  │ LONG_BUILDUP    │ SHORT_COVERING  │
 * Price Down│ SHORT_BUILDUP   │ LONG_UNWINDING  │
 * </pre>
 *
 * <p>The information already exists implicitly across OiDivergenceMonitor,
 * OperatorAccumulationDetector, etc. This service materialises it as a
 * first-class, per-strike signal so that:
 * <ul>
 *   <li>DecisionAggregator can read a clean directional summary</li>
 *   <li>The tuning loop can measure per-cell hit rates independently</li>
 *   <li>Logs become interpretable ("3 LONG_BUILDUP strikes above ATM")</li>
 * </ul>
 *
 * <p>OI change uses {@link OptionInstrument#getOiChangeSince(int)} (3-min window).
 * Price change uses {@code lastPrice} vs {@code closePrice} (day open baseline).
 * For intraday use, a 3-min price change is approximated from the 5-min high/low midpoint.
 */
@Service
public class OiPriceMatrixService {

    private static final Logger log = LoggerFactory.getLogger(OiPriceMatrixService.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    private final Map<IndexType, MatrixSnapshot> cache = new ConcurrentHashMap<>();

    public OiPriceMatrixService(LiveInstrumentCache liveInstrumentCache,
                                 ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    public enum Cell {
        LONG_BUILDUP,    // OI up + price up   → longs entering, bullish
        SHORT_BUILDUP,   // OI up + price down  → shorts entering, bearish
        SHORT_COVERING,  // OI down + price up  → shorts exiting, bullish (but weakening)
        LONG_UNWINDING,  // OI down + price down → longs exiting, bearish (but weakening)
        NEUTRAL          // insufficient data
    }

    public record StrikeCell(int strike, String optionType, Cell cell, long oiChange, double priceChangePct) {}

    public record MatrixSnapshot(
            IndexType indexType,
            List<StrikeCell> cells,
            // ATM-band summary (±3 strikes)
            int longBuildupCount,
            int shortBuildupCount,
            int shortCoveringCount,
            int longUnwindingCount,
            /** Net directional bias: (LONG_BUILDUP + SHORT_COVERING) − (SHORT_BUILDUP + LONG_UNWINDING) */
            int netBullishCells
    ) {
        static MatrixSnapshot empty(IndexType ix) {
            return new MatrixSnapshot(ix, List.of(), 0, 0, 0, 0, 0);
        }

        /** Dominant cell type in the ATM band. NEUTRAL if tied or no data. */
        public Cell dominantCell() {
            int bullish = longBuildupCount + shortCoveringCount;
            int bearish = shortBuildupCount + longUnwindingCount;
            if (bullish == bearish || (bullish == 0 && bearish == 0)) return Cell.NEUTRAL;
            if (bullish > bearish) return longBuildupCount >= shortCoveringCount
                    ? Cell.LONG_BUILDUP : Cell.SHORT_COVERING;
            return shortBuildupCount >= longUnwindingCount
                    ? Cell.SHORT_BUILDUP : Cell.LONG_UNWINDING;
        }

        /** +1 bullish, -1 bearish, 0 neutral — for use as a direction signal. */
        public int directionSignal() {
            if (netBullishCells > 1) return +1;
            if (netBullishCells < -1) return -1;
            return 0;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compute the OI+Price matrix for the ATM ± N strikes of an index.
     * Call once per strategy tick.
     *
     * @param strikesEachSide how many strikes each side of ATM to classify (typically 5)
     */
    public MatrixSnapshot evaluate(IndexType indexType, int strikesEachSide) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return MatrixSnapshot.empty(indexType);

        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();

        List<StrikeCell> cells = new ArrayList<>();
        int lb = 0, sb = 0, sc = 0, lu = 0;

        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int strike = atm + (i * interval);
            for (String optType : List.of("CE", "PE")) {
                Optional<OptionInstrument> optOpt = liveInstrumentCache.getOption(indexType, strike, optType, expiry);
                if (optOpt.isEmpty()) continue;
                OptionInstrument opt = optOpt.get();

                long oiChange = opt.getOiChangeSince(3);
                double priceChangePct = priceChangePct(opt);

                if (opt.getOpenInterest() <= 0 || opt.getLastPrice() <= 0) continue;

                Cell cell = classify(oiChange, priceChangePct);
                cells.add(new StrikeCell(strike, optType, cell, oiChange, priceChangePct));

                // Count only ATM ± 3 strikes for the summary
                if (Math.abs(i) <= 3) {
                    switch (cell) {
                        case LONG_BUILDUP   -> lb++;
                        case SHORT_BUILDUP  -> sb++;
                        case SHORT_COVERING -> sc++;
                        case LONG_UNWINDING -> lu++;
                        default -> {}
                    }
                }
            }
        }

        int netBullish = (lb + sc) - (sb + lu);
        MatrixSnapshot snap = new MatrixSnapshot(indexType, cells, lb, sb, sc, lu, netBullish);
        cache.put(indexType, snap);

        if (log.isDebugEnabled()) {
            log.debug("[OiMatrix][{}] LB={} SB={} SC={} LU={} net={} dominant={}",
                    indexType, lb, sb, sc, lu, netBullish, snap.dominantCell());
        }
        return snap;
    }

    /** Latest cached snapshot. */
    public MatrixSnapshot getLatest(IndexType indexType) {
        return cache.getOrDefault(indexType, MatrixSnapshot.empty(indexType));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Cell classify(long oiChange, double priceChangePct) {
        // Require a minimum threshold to avoid noise
        boolean oiUp   = oiChange > 5_000;
        boolean oiDown = oiChange < -5_000;
        boolean priceUp   = priceChangePct > 0.3;
        boolean priceDown = priceChangePct < -0.3;

        if (oiUp   && priceUp)   return Cell.LONG_BUILDUP;
        if (oiUp   && priceDown) return Cell.SHORT_BUILDUP;
        if (oiDown && priceUp)   return Cell.SHORT_COVERING;
        if (oiDown && priceDown) return Cell.LONG_UNWINDING;
        return Cell.NEUTRAL;
    }

    /**
     * Intraday price change % for an option instrument, time-aligned with the 3-min OI window.
     *
     * <p>Primary baseline is the recent 5-min high/low midpoint — an intraday reference on the
     * same seconds-to-minutes timescale as {@link OptionInstrument#getOiChangeSince(int)}. The
     * previous-day close is deliberately NOT used as the primary: for options it is dominated by
     * overnight theta decay and the spot gap (a days-scale move), so pairing it with a 3-min OI
     * delta mis-buckets strikes (e.g. "price down 15% since yesterday" from decay while actually
     * rising intraday). Prior close is only a last resort before any intraday range exists.
     */
    private double priceChangePct(OptionInstrument opt) {
        double current = opt.getLastPrice();
        if (current <= 0) return 0;

        // Primary: intraday 5-min midpoint (aligned with the 3-min OI window).
        double high = opt.getHigh5m();
        double low  = opt.getLow5m();
        if (high > 0 && low > 0 && high >= low) {
            double mid = (high + low) / 2.0;
            if (mid > 0) return (current - mid) / mid * 100.0;
        }

        // Last resort: prior close, only when no intraday range is available yet (e.g. first ticks).
        double base = opt.getClosePrice();
        if (base > 0) return (current - base) / base * 100.0;
        return 0;
    }
}
