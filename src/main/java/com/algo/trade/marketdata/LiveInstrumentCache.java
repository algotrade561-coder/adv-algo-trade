package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.GreeksCalculator;
import com.algo.trade.indicator.IVRankTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    // IndexType → current futures/spot price
    private final Map<IndexType, Double> futuresPriceCache = new ConcurrentHashMap<>();
    // IndexType → previous day's closing price (seeded at startup from REST historical API)
    private final Map<IndexType, Double> previousDayClose = new ConcurrentHashMap<>();

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
        inst.setLastTickTimeMs(System.currentTimeMillis());
        if (volume > 0) inst.setVolume(volume);
        if (oi > 0) {
            if (inst.getOpenInterest() > 0) inst.setPrevOpenInterest(inst.getOpenInterest());
            inst.setOpenInterest(oi);
            inst.sampleOiIfDue(); // Feed OI ring buffer for time-series lookback
        }
        if (bestBid > 0) inst.setBestBid(bestBid);
        if (bestAsk > 0) inst.setBestAsk(bestAsk);
        if (bidQty > 0) inst.setBestBidQty(bidQty);
        if (askQty > 0) inst.setBestAskQty(askQty);

        // Recalculate Greeks on every price update
        double underlying = futuresPriceCache.getOrDefault(inst.getIndexType(), 0.0);
        if (underlying > 0) {
            greeksCalculator.calculateAndUpdate(inst, underlying);
        }
    }

    /** Update futures/spot price — called when index spot tick arrives. */
    public void updateFuturesPrice(IndexType indexType, double price) {
        futuresPriceCache.put(indexType, price);
        // Record ATM IV for IV rank tracking
        try {
            LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
            int atm = indexType.roundToATM(price);
            getOption(indexType, atm, "CE", expiry).ifPresent(ce -> {
                if (ce.getImpliedVolatility() > 0) {
                    ivRankTracker.recordIV(indexType, ce.getImpliedVolatility());
                }
            });
        } catch (Exception ignored) {}
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
}
