package com.algo.trade.marketdata;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Timeframe Market Context (2026-07-01).
 *
 * <p>Gives the intraday trader the higher-timeframe awareness it previously lacked — the OI-momentum
 * loop only ever saw ≤60 min of price (range30m / drift60m / VWAP). This layer answers "which way is
 * the day / week / month leaning, where are we in the range, and what regime is this?" so entries can be
 * biased toward the bigger move (not fought), sat out in chop, and RIDDEN when aligned (vs scalped).</p>
 *
 * <p><b>Two parts:</b>
 * <ul>
 *   <li><b>Intraday (live, primary):</b> today's open/high/low + current spot → intraday trend + range
 *       position. Cheap, computed on demand, always available. This is the context used most heavily.</li>
 *   <li><b>Higher-timeframe (refreshed hourly):</b> ONE_HOUR candles aggregated into DAILY bars →
 *       short (3-day) / week (5-day) / month (20-day) trend + key levels (recent daily highs/lows).</li>
 * </ul>
 * Degrades to NEUTRAL if candle data is unavailable — never throws into the trading path.</p>
 *
 * <p>Config-gated ({@code mtf.enabled}, default true). Read-only: this service computes and exposes
 * context; callers decide how to use it (bias bonus, regime gate, ride-vs-scalp). Captured into the
 * OI-momentum tuning events so the day/week alignment edge can be validated on real outcomes.</p>
 */
@Service
public class MultiTimeframeContextService {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeContextService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MarketDataService marketDataService;
    private final LiveInstrumentCache liveInstrumentCache;
    private final TradingProperties properties;

    @Value("${mtf.enabled:true}")
    private boolean enabled;
    /** % move over the lookback to call a timeframe "trending" (not flat). */
    @Value("${mtf.short-trend-threshold-pct:0.30}")
    private double shortTrendThresholdPct;
    @Value("${mtf.week-trend-threshold-pct:0.80}")
    private double weekTrendThresholdPct;
    @Value("${mtf.month-trend-threshold-pct:2.0}")
    private double monthTrendThresholdPct;
    /** Spot within this % of a recent daily high/low counts as NEAR that level. */
    @Value("${mtf.level-proximity-pct:0.25}")
    private double levelProximityPct;

    /** Cached higher-timeframe snapshot per index, refreshed hourly + at startup. */
    private final ConcurrentHashMap<IndexType, Htf> htfByIndex = new ConcurrentHashMap<>();

    public MultiTimeframeContextService(MarketDataService marketDataService,
                                        LiveInstrumentCache liveInstrumentCache,
                                        TradingProperties properties) {
        this.marketDataService = marketDataService;
        this.liveInstrumentCache = liveInstrumentCache;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Higher-timeframe cached values (daily-bar derived). */
    private record Htf(int shortTrend, int weekTrend, int monthTrend,
                       double dayOpen, double dayHigh, double dayLow,
                       double nearestResistance, double nearestSupport,
                       double atrPct5d, Instant computedAt) {
        static Htf neutral() { return new Htf(0, 0, 0, 0, 0, 0, 0, 0, 0, Instant.EPOCH); }
    }

    /**
     * The composite context for a decision. All directional fields use +1 = bullish (favours CE),
     * -1 = bearish (favours PE), 0 = neutral.
     */
    public record MtfContext(
            IndexType index,
            double spot,
            // intraday (live)
            int intradayBias,          // spot vs day open
            double intradayTrendPct,   // (spot - dayOpen)/dayOpen*100
            double dayRangePosition,   // 0=at day low, 1=at day high
            // higher timeframe
            int shortTrend, int weekTrend, int monthTrend,
            int htfBias,               // combined short+week directional lean
            String regime,             // TRENDING | RANGING | VOLATILE | NEUTRAL
            String levelZone,          // NEAR_RESISTANCE | NEAR_SUPPORT | MID_RANGE
            boolean available          // false = degraded/neutral (no data yet)
    ) {
        /** True if a CE(+1)/PE(-1) entry direction agrees with the combined day+week lean. */
        public boolean alignsWith(int direction) {
            return htfBias != 0 && direction != 0 && Integer.signum(htfBias) == Integer.signum(direction);
        }
        public boolean isCounterTrend(int direction) {
            return htfBias != 0 && direction != 0 && Integer.signum(htfBias) != Integer.signum(direction);
        }
        public boolean isRanging() { return "RANGING".equals(regime); }
        public boolean isTrending() { return "TRENDING".equals(regime); }
        /** Buying a CALL into resistance / a PUT into support = poor entry location. */
        public boolean atUnfavourableLevel(int direction) {
            return (direction > 0 && "NEAR_RESISTANCE".equals(levelZone))
                    || (direction < 0 && "NEAR_SUPPORT".equals(levelZone));
        }
        public String summary() {
            return String.format("MTF[%s] bias=%+d regime=%s zone=%s | intraday=%+d(%.2f%%) range=%.2f "
                            + "| short=%+d week=%+d month=%+d avail=%b",
                    index, htfBias, regime, levelZone, intradayBias, intradayTrendPct, dayRangePosition,
                    shortTrend, weekTrend, monthTrend, available);
        }
    }

    /** Compute the live composite context for an index (intraday is live; HTF from the cached snapshot). */
    public MtfContext getContext(IndexType index) {
        if (!enabled) {
            return new MtfContext(index, 0, 0, 0, 0.5, 0, 0, 0, 0, "NEUTRAL", "MID_RANGE", false);
        }
        Htf htf = htfByIndex.getOrDefault(index, Htf.neutral());
        double spot = liveInstrumentCache.getFuturesPrice(index);
        boolean avail = htf.computedAt().getEpochSecond() > 0 && spot > 0;

        // ── intraday (live) ──
        int intradayBias = 0;
        double intradayTrendPct = 0;
        double rangePos = 0.5;
        if (spot > 0 && htf.dayOpen() > 0) {
            intradayTrendPct = (spot - htf.dayOpen()) / htf.dayOpen() * 100.0;
            if (intradayTrendPct > 0.10) intradayBias = 1;
            else if (intradayTrendPct < -0.10) intradayBias = -1;
        }
        double hi = Math.max(htf.dayHigh(), spot), lo = htf.dayLow() > 0 ? Math.min(htf.dayLow(), spot) : spot;
        if (spot > 0 && hi > lo) rangePos = (spot - lo) / (hi - lo);

        // ── combined bias: day+week lean, nudged by the live intraday direction ──
        int combined = htf.shortTrend() + htf.weekTrend() + intradayBias;
        int htfBias = combined > 0 ? 1 : combined < 0 ? -1 : 0;

        // ── regime ──
        String regime = classifyRegime(htf);

        // ── level zone ──
        String zone = "MID_RANGE";
        if (spot > 0) {
            if (htf.nearestResistance() > 0
                    && (htf.nearestResistance() - spot) / spot * 100.0 <= levelProximityPct
                    && htf.nearestResistance() >= spot) {
                zone = "NEAR_RESISTANCE";
            } else if (htf.nearestSupport() > 0
                    && (spot - htf.nearestSupport()) / spot * 100.0 <= levelProximityPct
                    && htf.nearestSupport() <= spot) {
                zone = "NEAR_SUPPORT";
            }
        }

        return new MtfContext(index, spot, intradayBias, intradayTrendPct, rangePos,
                htf.shortTrend(), htf.weekTrend(), htf.monthTrend(), htfBias, regime, zone, avail);
    }

    private String classifyRegime(Htf htf) {
        int aligned = htf.shortTrend() + htf.weekTrend() + htf.monthTrend();
        boolean strongAligned = Math.abs(aligned) >= 2
                && (htf.weekTrend() == 0 || htf.monthTrend() == 0 || Integer.signum(htf.weekTrend()) == Integer.signum(htf.monthTrend()));
        if (htf.atrPct5d() >= 1.5) return "VOLATILE";
        if (strongAligned) return "TRENDING";
        if (Math.abs(aligned) == 0 && htf.atrPct5d() > 0 && htf.atrPct5d() < 0.6) return "RANGING";
        return "NEUTRAL";
    }

    // ── Higher-timeframe refresh (hourly + startup) ──────────────────────────────

    @jakarta.annotation.PostConstruct
    void warmup() {
        // Defer to the scheduled run; a failed fetch at boot must not block startup.
        try { refresh(); } catch (Exception e) { log.debug("[MTF] warmup deferred: {}", e.getMessage()); }
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L) // hourly, first ~2 min after boot
    public void refresh() {
        if (!enabled) return;
        for (IndexType index : IndexType.values()) {
            try {
                Htf htf = computeHtf(index);
                if (htf != null) {
                    htfByIndex.put(index, htf);
                    log.info("[MTF] {} refreshed: short={} week={} month={} dayOpen={} R={} S={} atr5d={}%",
                            index, htf.shortTrend(), htf.weekTrend(), htf.monthTrend(),
                            String.format("%.0f", htf.dayOpen()), String.format("%.0f", htf.nearestResistance()),
                            String.format("%.0f", htf.nearestSupport()), String.format("%.2f", htf.atrPct5d()));
                }
            } catch (Exception ex) {
                log.debug("[MTF] {} refresh failed (keeping prior/neutral): {}", index, ex.getMessage());
            }
        }
    }

    private Htf computeHtf(IndexType index) {
        String spotKey = properties.symbols().spotHistoricalKeys()
                .get(UnderlyingSymbol.valueOf(index.name()));
        if (spotKey == null || spotKey.isBlank()) return null;

        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(40)); // ~28 sessions of hourly bars
        List<Candle> hourly = marketDataService.historicalCandles(
                new HistoricalDataRequest(spotKey, from, now, Timeframe.ONE_HOUR, false));
        if (hourly == null || hourly.size() < 8) return null;

        // Aggregate hourly → daily bars (open=first, high=max, low=min, close=last), chronological.
        TreeMap<LocalDate, double[]> byDate = new TreeMap<>(); // [open,high,low,close]
        for (Candle c : hourly) {
            LocalDate d = c.timestamp().atZone(IST).toLocalDate();
            double o = c.open().doubleValue(), h = c.high().doubleValue(),
                   l = c.low().doubleValue(), cl = c.close().doubleValue();
            byDate.compute(d, (k, v) -> v == null
                    ? new double[]{o, h, l, cl}
                    : new double[]{v[0], Math.max(v[1], h), Math.min(v[2], l), cl});
        }
        List<Map.Entry<LocalDate, double[]>> days = new ArrayList<>(byDate.entrySet());
        int n = days.size();
        if (n < 4) return null;

        List<Double> closes = new ArrayList<>();
        for (var e : days) closes.add(e.getValue()[3]);

        int shortTrend = trend(closes, 3, shortTrendThresholdPct);
        int weekTrend = trend(closes, 5, weekTrendThresholdPct);
        int monthTrend = trend(closes, Math.min(20, n - 1), monthTrendThresholdPct);

        // today's bar (latest date)
        double[] today = days.get(n - 1).getValue();
        double dayOpen = today[0], dayHigh = today[1], dayLow = today[2];

        // key levels: nearest recent daily high above / low below the latest close (last ~10 sessions)
        double lastClose = closes.get(n - 1);
        double nearestRes = Double.MAX_VALUE, nearestSup = 0;
        int from10 = Math.max(0, n - 11);
        for (int i = from10; i < n - 1; i++) {
            double hi = days.get(i).getValue()[1], lo = days.get(i).getValue()[2];
            if (hi > lastClose && hi < nearestRes) nearestRes = hi;
            if (lo < lastClose && lo > nearestSup) nearestSup = lo;
        }
        if (nearestRes == Double.MAX_VALUE) nearestRes = 0;

        // 5-day ATR% (daily true range approx = high-low) as regime volatility proxy
        double atrSum = 0; int cnt = 0;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            double[] b = days.get(i).getValue();
            if (b[3] > 0) { atrSum += (b[1] - b[2]) / b[3] * 100.0; cnt++; }
        }
        double atrPct5d = cnt > 0 ? atrSum / cnt : 0;

        return new Htf(shortTrend, weekTrend, monthTrend, dayOpen, dayHigh, dayLow,
                nearestRes, nearestSup, atrPct5d, Instant.now());
    }

    /** +1/-1/0 trend from the % change over the last {@code lookback} daily closes vs {@code thresholdPct}. */
    private int trend(List<Double> closes, int lookback, double thresholdPct) {
        int n = closes.size();
        if (n < lookback + 1 || lookback < 1) return 0;
        double now = closes.get(n - 1), past = closes.get(n - 1 - lookback);
        if (past <= 0) return 0;
        double pct = (now - past) / past * 100.0;
        if (pct >= thresholdPct) return 1;
        if (pct <= -thresholdPct) return -1;
        return 0;
    }
}
