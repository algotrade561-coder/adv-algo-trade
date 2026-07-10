package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.GreeksCalculator;
import com.algo.trade.indicator.IVRankTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Extends InstrumentCache with live OptionInstrument objects enriched
 * with real-time price, OI, bid/ask, and Greeks from the WebSocket tick feed.
 *
 * Populated when instruments are loaded from Kite master CSV.
 * Updated on every tick via updateOptionMarketData().
 */
@Component
public class LiveInstrumentCache {

    private static final Logger log = LoggerFactory.getLogger(LiveInstrumentCache.class);

    private final GreeksCalculator greeksCalculator;
    private final IVRankTracker ivRankTracker;
    private final ExpiryCalendar expiryCalendar;

    // token → OptionInstrument (live enriched)
    private final Map<Long, OptionInstrument> byToken = new ConcurrentHashMap<>();
    // symbol → OptionInstrument
    private final Map<String, OptionInstrument> bySymbol = new ConcurrentHashMap<>();
    // "INDEXTYPE|strike|CE_or_PE|expiry" → OptionInstrument for O(1) getOption() lookup
    private final Map<String, OptionInstrument> byCompoundKey = new ConcurrentHashMap<>();
    // IndexType → current spot price (index LTP; NOTE: this is spot, not the futures price)
    private final Map<IndexType, Double> futuresPriceCache = new ConcurrentHashMap<>();
    // IndexType → previous day's closing price (seeded at startup from REST historical API)
    private final Map<IndexType, Double> previousDayClose = new ConcurrentHashMap<>();
    // "INDEXTYPE|expiry" → market forward (put-call parity at ATM); used as the Black-76 underlying
    private final Map<String, Double> forwardCache = new ConcurrentHashMap<>();

    /** When true, IV/greeks use the put-call-parity forward (Sensibull-style Black-76); when false
     *  they use the spot-carry forward, which reproduces the legacy spot+q=0 numbers exactly. */
    @Value("${greeks.use-synthetic-forward:true}")
    private boolean useSyntheticForward;

    public LiveInstrumentCache(GreeksCalculator greeksCalculator, IVRankTracker ivRankTracker,
                               ExpiryCalendar expiryCalendar) {
        this.greeksCalculator = greeksCalculator;
        this.ivRankTracker = ivRankTracker;
        this.expiryCalendar = expiryCalendar;
    }

    // ── Population ────────────────────────────────────────────────────────────

    /** Build OptionInstrument objects from the raw Instrument list after refresh. */
    public void populate(List<Instrument> instruments) {
        byToken.clear();
        bySymbol.clear();
        byCompoundKey.clear();
        int count = 0;
        for (Instrument inst : instruments) {
            if (!inst.tradable() || inst.optionType().isEmpty()) continue;
            IndexType indexType = resolveIndexType(inst);
            if (indexType == null) continue;

            OptionInstrument opt = new OptionInstrument(
                    inst.instrumentToken(),
                    inst.tradingSymbol(),
                    inst.exchange(),
                    indexType,
                    inst.strike().map(s -> s.intValue()).orElse(0),
                    inst.optionType().get().name(),
                    inst.expiry().orElse(LocalDate.MAX),
                    inst.lotSize()
            );
            byToken.put(inst.instrumentToken(), opt);
            bySymbol.put(inst.tradingSymbol(), opt);
            byCompoundKey.put(compoundKey(opt), opt);
            count++;
        }
        log.info("LiveInstrumentCache populated: {} option instruments", count);
    }

    /** All live option instruments (for tuning chain snapshots). */
    public Collection<OptionInstrument> allOptions() {
        return Collections.unmodifiableCollection(byToken.values());
    }

    private static String compoundKey(OptionInstrument o) {
        return o.getIndexType() + "|" + o.getStrikePrice() + "|" + o.getOptionType() + "|" + o.getExpiry();
    }

    private static String compoundKey(IndexType indexType, int strike, String optionType, LocalDate expiry) {
        return indexType + "|" + strike + "|" + optionType + "|" + expiry;
    }

    // ── Live updates from WebSocket ───────────────────────────────────────────

    /** Called on every option tick from KiteWebSocketClient. */
    public void updateOptionMarketData(long token, double price, long volume,
                                        long oi, double bestBid, double bestAsk,
                                        long bidQty, long askQty) {
        OptionInstrument inst = byToken.get(token);
        if (inst == null) return;

        inst.setLastPrice(price);
        inst.updateHigh5mLow5m(price); // maintain clock-aligned 5-min high/low for snapshots and live reads
        inst.setLastTickTimeMs(System.currentTimeMillis());
        if (volume > 0) inst.setVolume(volume);
        if (oi > 0) {
            if (inst.getOpenInterest() > 0) inst.setPrevOpenInterest(inst.getOpenInterest());
            inst.setOpenInterest(oi);
            // Seed a back-dated anchor on the first WS OI tick so getOiChangeSince(3min)
            // returns a non-zero delta immediately instead of waiting 3 real minutes.
            // Mirrors the same logic in applyRestQuoteData().
            if (inst.isOiRingBufferEmpty()) {
                inst.seedOiRingBuffer(oi, System.currentTimeMillis() - 180_000L);
            }
            inst.sampleOiIfDue(); // Feed OI ring buffer for time-series lookback
        }
        if (bestBid > 0) inst.setBestBid(bestBid);
        if (bestAsk > 0) inst.setBestAsk(bestAsk);
        if (bidQty > 0) inst.setBestBidQty(bidQty);
        if (askQty > 0) inst.setBestAskQty(askQty);

        // Recalculate Greeks on every price update
        double spot = futuresPriceCache.getOrDefault(inst.getIndexType(), 0.0);
        if (spot > 0) {
            greeksCalculator.calculateAndUpdate(inst, forwardFor(inst.getIndexType(), inst.getExpiry(), spot));
        }
    }

    /** Update spot price — called when index spot tick arrives (param is the index LTP, i.e. spot). */
    public void updateFuturesPrice(IndexType indexType, double price) {
        futuresPriceCache.put(indexType, price);
        // Refresh the market forward (put-call parity at ATM) and record ATM IV for IV-rank tracking.
        try {
            LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
            int atm = indexType.roundToATM(price);
            var ceOpt = getOption(indexType, atm, "CE", expiry);
            var peOpt = getOption(indexType, atm, "PE", expiry);
            if (useSyntheticForward && ceOpt.isPresent() && peOpt.isPresent()) {
                double f = greeksCalculator.forwardFromParity(
                        atm, ceOpt.get().getLastPrice(), peOpt.get().getLastPrice(), expiry);
                // Sanity: a real forward sits within ~3% of spot. Reject stale/illiquid ATM reads so a
                // bad quote never poisons IV/greeks — the calc then falls back to the spot-carry forward.
                if (!Double.isNaN(f) && f > 0 && Math.abs(f - price) / price <= 0.03) {
                    forwardCache.put(fwdKey(indexType, expiry), f);
                }
            }
            ceOpt.ifPresent(ce -> {
                if (ce.getImpliedVolatility() > 0) {
                    ivRankTracker.recordIV(indexType, ce.getImpliedVolatility());
                }
            });
        } catch (Exception ignored) {}
    }

    private static String fwdKey(IndexType idx, LocalDate expiry) { return idx.name() + "|" + expiry; }

    /** Black-76 underlying for (index, expiry): the cached put-call-parity forward when available,
     *  else the spot-carry forward (which reproduces the legacy spot+q=0 numbers exactly). */
    private double forwardFor(IndexType idx, LocalDate expiry, double spot) {
        if (useSyntheticForward) {
            Double f = forwardCache.get(fwdKey(idx, expiry));
            if (f != null && f > 0) return f;
        }
        return greeksCalculator.forwardFromSpot(spot, expiry);
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public Optional<OptionInstrument> getByToken(long token) {
        return Optional.ofNullable(byToken.get(token));
    }

    public Optional<OptionInstrument> getBySymbol(String symbol) {
        return Optional.ofNullable(bySymbol.get(symbol));
    }

    public Optional<OptionInstrument> getOption(IndexType indexType, int strike,
                                                  String optionType, LocalDate expiry) {
        return Optional.ofNullable(byCompoundKey.get(compoundKey(indexType, strike, optionType, expiry)));
    }

    /** All options for a given index and expiry, sorted by strike. */
    public List<OptionInstrument> getStrikeChain(IndexType indexType, LocalDate expiry) {
        return byToken.values().stream()
                .filter(o -> o.getIndexType() == indexType)
                .filter(o -> o.getExpiry().equals(expiry))
                .sorted(Comparator.comparingInt(OptionInstrument::getStrikePrice))
                .collect(Collectors.toList());
    }

    /** All tokens to subscribe for a given index + expiry (ATM ± N strikes). */
    public List<Long> getSubscriptionTokens(IndexType indexType, LocalDate expiry, int strikesEachSide) {
        double spot = futuresPriceCache.getOrDefault(indexType, 0.0);
        if (spot == 0) return List.of();
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        List<Long> tokens = new ArrayList<>();
        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int strike = atm + (i * interval);
            getOption(indexType, strike, "CE", expiry).ifPresent(o -> tokens.add(o.getInstrumentToken()));
            getOption(indexType, strike, "PE", expiry).ifPresent(o -> tokens.add(o.getInstrumentToken()));
        }
        return tokens;
    }

    public double getFuturesPrice(IndexType indexType) {
        return futuresPriceCache.getOrDefault(indexType, 0.0);
    }

    // ── Previous Day Close (for gap detection) ────────────────────────────────

    /** Store previous day's closing price for an index. Called at startup from REST historical API. */
    public void setPreviousDayClose(IndexType indexType, double close) {
        previousDayClose.put(indexType, close);
        log.info("Previous day close stored: {} = {}", indexType, close);
    }

    /** Get previous day's closing price for an index. Returns 0.0 if not available. */
    public double getPreviousDayClose(IndexType indexType) {
        return previousDayClose.getOrDefault(indexType, 0.0);
    }

    public boolean isReady() { return !byToken.isEmpty(); }

    // ── REST Fallback ─────────────────────────────────────────────────────────

    /**
     * Apply OI and price data fetched via REST /quote (OI fallback path).
     * Mirrors the WS updateOptionMarketData() path so:
     *  - openInterest (persistent field) is updated → Operator Framework stays fresh
     *  - sampleOiIfDue() is called → populates OI ring buffer for getOiChangeSince()
     *  - lastTickTimeMs is refreshed → prevents redundant REST calls for this instrument
     *  - Greeks are recalculated with the fresh price
     *
     * Called by OiRestFallbackService when per-instrument OI ticks are stale.
     *
     * @param tradingSymbol e.g. "NIFTY23800CE26MAY"
     * @param lastPrice     last traded price from REST (0 = unavailable, skip price update)
     * @param oi            open interest from REST (0 = unavailable, skip OI update)
     */
    public void applyRestQuoteData(String tradingSymbol, double lastPrice, long oi) {
        OptionInstrument inst = bySymbol.get(tradingSymbol);
        if (inst == null) {
            log.debug("[RestFallback] Unknown tradingSymbol: {}", tradingSymbol);
            return;
        }
        if (lastPrice > 0) inst.setLastPrice(lastPrice);
        inst.setLastTickTimeMs(System.currentTimeMillis()); // marks instrument as freshly updated
        if (oi > 0) {
            long prev = inst.getOpenInterest();
            if (prev > 0) inst.setPrevOpenInterest(prev);
            inst.setOpenInterest(oi);
            // Seed the ring buffer with a back-dated anchor on the first REST injection.
            // Without this, getOiChangeSince(3) returns 0 for 3 full minutes (needs a
            // sample ≥3 min old) — keeping oiAvailable=false even after REST data arrives.
            // The back-dated anchor gives the strategy an immediate lookback reference.
            if (inst.isOiRingBufferEmpty()) {
                inst.seedOiRingBuffer(oi, System.currentTimeMillis() - 180_000L);
            }
            inst.sampleOiIfDue(); // writes current OI at now; feeds OI ring buffer → resolves CASE5_SKIP
        }
        double spot = futuresPriceCache.getOrDefault(inst.getIndexType(), 0.0);
        if (spot > 0 && lastPrice > 0) {
            greeksCalculator.calculateAndUpdate(inst, forwardFor(inst.getIndexType(), inst.getExpiry(), spot));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexType resolveIndexType(Instrument inst) {
        String name = inst.name();
        String exchange = inst.exchange();
        if ("BFO".equals(exchange) && name.contains("SENSEX")) return IndexType.SENSEX;
        if (name.equals("BANKNIFTY") || name.startsWith("BANKNIFTY")) return IndexType.BANKNIFTY;
        if (name.equals("FINNIFTY") || name.startsWith("FINNIFTY")) return IndexType.FINNIFTY;
        if (name.equals("MIDCPNIFTY") || name.startsWith("MIDCPNIFTY")) return IndexType.MIDCPNIFTY;
        if (name.equals("NIFTY") || name.startsWith("NIFTY")) return IndexType.NIFTY;
        return null;
    }

    /**
     * Compute real-time PCR from subscribed options' live OI (no REST call).
     * Less accurate than full-chain PCR (misses far OTM unsubscribed strikes)
     * but updates on every tick — suitable for 1-sec strategy loops.
     *
     * @param indexType the index to compute PCR for
     * @return put OI / call OI ratio, or 0 if insufficient data
     */
    public double getRealtimePcr(IndexType indexType) {
        long callOi = 0, putOi = 0;
        for (OptionInstrument opt : byToken.values()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() <= 0) continue;
            if ("CE".equals(opt.getOptionType())) callOi += opt.getOpenInterest();
            else putOi += opt.getOpenInterest();
        }
        return callOi > 0 ? (double) putOi / callOi : 0;
    }

    /**
     * Total open interest across all subscribed CE strikes for an index.
     * Added 2 Jun 2026 to power the OperatorSqueezeDetector — a sudden drop in
     * total CE OI is the canonical signature of a call short-squeeze.
     */
    public long getTotalCeOi(IndexType indexType) {
        long sum = 0;
        for (OptionInstrument opt : byToken.values()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() <= 0) continue;
            if ("CE".equals(opt.getOptionType())) sum += opt.getOpenInterest();
        }
        return sum;
    }

    /**
     * Total open interest across all subscribed PE strikes for an index.
     * Symmetric to {@link #getTotalCeOi} — sudden drop signals a put short-squeeze.
     */
    public long getTotalPeOi(IndexType indexType) {
        long sum = 0;
        for (OptionInstrument opt : byToken.values()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() <= 0) continue;
            if ("PE".equals(opt.getOptionType())) sum += opt.getOpenInterest();
        }
        return sum;
    }

    /**
     * ATM call implied volatility (latest tick). Returns 0 if no subscribed
     * CE matches the given ATM strike or its IV is not populated yet.
     * Used by OperatorSqueezeDetector to detect IV-expansion confirmation of
     * a regime change.
     */
    public double getAtmCeIv(IndexType indexType, int atmStrike) {
        for (OptionInstrument opt : byToken.values()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getStrikePrice() != atmStrike) continue;
            if (!"CE".equals(opt.getOptionType())) continue;
            return opt.getImpliedVolatility();
        }
        return 0;
    }

    /** ATM put implied volatility — symmetric to {@link #getAtmCeIv}. */
    public double getAtmPeIv(IndexType indexType, int atmStrike) {
        for (OptionInstrument opt : byToken.values()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getStrikePrice() != atmStrike) continue;
            if (!"PE".equals(opt.getOptionType())) continue;
            return opt.getImpliedVolatility();
        }
        return 0;
    }

    /**
     * Get total OI change (last 3 minutes) for ATM ± N strikes of a given index.
     * Positive = OI building (new positions), Negative = OI unwinding.
     * Separated by CE and PE for directional analysis.
     *
     * @return [ceOiChange, peOiChange]
     */
    public long[] getAtmOiChange(IndexType indexType, int atmStrike, int strikesAround, int minutesBack) {
        int interval = indexType.strikeInterval();
        long ceChange = 0, peChange = 0;
        for (int i = -strikesAround; i <= strikesAround; i++) {
            int strike = atmStrike + (i * interval);
            for (OptionInstrument opt : byToken.values()) {
                if (opt.getIndexType() != indexType) continue;
                if (opt.getStrikePrice() != strike) continue;
                long change = opt.getOiChangeSince(minutesBack);
                if ("CE".equals(opt.getOptionType())) ceChange += change;
                else peChange += change;
            }
        }
        return new long[]{ceChange, peChange};
    }

    /**
     * TRUE windowed variant of {@link #getAtmOiChange} (2026-07-01, FAST-OI). Measures ATM±N band
     * OI change over a genuine trailing {@code windowSec} (nearest-sample), instead of the legacy
     * method's oldest-sample reach-back that collapses to ~5 minutes with a full ring buffer.
     * Empirically 60s is the coverage knee (see {@link OptionInstrument#getOiChangeSinceSeconds}).
     *
     * @return [ceOiChange, peOiChange] over the trailing {@code windowSec}.
     */
    public long[] getAtmOiChangeSeconds(IndexType indexType, int atmStrike, int strikesAround, int windowSec) {
        int interval = indexType.strikeInterval();
        long ceChange = 0, peChange = 0;
        for (int i = -strikesAround; i <= strikesAround; i++) {
            int strike = atmStrike + (i * interval);
            for (OptionInstrument opt : byToken.values()) {
                if (opt.getIndexType() != indexType) continue;
                if (opt.getStrikePrice() != strike) continue;
                long change = opt.getOiChangeSinceSeconds(windowSec);
                if ("CE".equals(opt.getOptionType())) ceChange += change;
                else peChange += change;
            }
        }
        return new long[]{ceChange, peChange};
    }

    /**
     * True when at least one strike in the ATM ± N band has a real OI baseline older than
     * the {@code minutesBack} cutoff — i.e. {@link #getAtmOiChange} reflects an actual
     * reading rather than opening warm-up.
     *
     * <p>DATA-2 (2026-06-20): lets callers distinguish "OI available &amp; flat" (band has a
     * baseline, net change ~0) from "OI not yet available" (no baseline). {@code getAtmOiChange}
     * returns {@code [0,0]} for both; this disambiguates them. Used by the opening-window OI
     * gate and by DataHealthRecorder's OI-availability metric.</p>
     */
    public boolean isAtmOiChangeAvailable(IndexType indexType, int atmStrike, int strikesAround, int minutesBack) {
        int interval = indexType.strikeInterval();
        for (int i = -strikesAround; i <= strikesAround; i++) {
            int strike = atmStrike + (i * interval);
            for (OptionInstrument opt : byToken.values()) {
                if (opt.getIndexType() != indexType) continue;
                if (opt.getStrikePrice() != strike) continue;
                if (opt.hasOiBaseline(minutesBack)) return true;
            }
        }
        return false;
    }
}
