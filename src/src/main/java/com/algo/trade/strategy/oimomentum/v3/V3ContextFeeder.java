package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 OPERATOR — Context feeder.
 *
 * <p>Closes P1a from the audit review: the {@link MarketContextService} and
 * {@link RegimeClassifier} expose setters but none had callers. This component runs
 * once a minute during market hours, pulls data from the existing indicators / caches,
 * and feeds the context.</p>
 *
 * <p>Specifically:</p>
 * <ul>
 *   <li>VWAP per index — computed by {@link VwapIndicator#calculateSessionAnchored}.</li>
 *   <li>PCR per index — sourced from {@link LiveInstrumentCache#getRealtimePcr}.</li>
 *   <li>VIX — sourced from {@link MarketGuard#getCurrentVix}.</li>
 *   <li>Session-open spot per index — captured at the first sample of the trading day.</li>
 *   <li>Previous close per index — set from yesterday's last sample.</li>
 *   <li>Daily ATR % per index — computed from 30-day daily candles.</li>
 * </ul>
 */
@Component
public class V3ContextFeeder {

    private static final Logger log = LoggerFactory.getLogger(V3ContextFeeder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MarketContextService marketContext;
    private final RegimeClassifier regimeClassifier;
    private final LiveInstrumentCache liveInstrumentCache;
    private final LiveCandleBuilder liveCandleBuilder;
    private final VwapIndicator vwapIndicator;
    private final AtrIndicator atrIndicator;
    private final MarketGuard marketGuard;

    /** Optional API client for FII/DII fetch — replaces the manual properties file. */
    @Autowired(required = false)
    private FiiDiiApiClient fiiDiiApiClient;

    /**
     * R1-R4 (2026-06-02): reversal-risk tracker — sampled once per minute
     * from the latest chain snapshot. Optional injection so legacy bringup
     * paths and unit tests work without it.
     */
    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ReversalRiskTracker reversalRiskTracker;

    /** Indices to feed — uses NIFTY/BANKNIFTY/SENSEX by default. */
    private static final IndexType[] FEED_INDICES =
            new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX};

    /** Per-index latest-day spot — used to derive session-open at boundary roll. */
    private final Map<IndexType, Double> lastSampleSpot = new ConcurrentHashMap<>();
    private volatile LocalDate currentSessionDay = null;

    /**
     * Path to an optional FII/DII properties file. Reviewed once at 09:00 IST each day.
     * Expected content (key=value):
     *   fii.net.crore=1234.5
     *   dii.net.crore=-567.8
     * Lines with #, empty file, or missing file → no-op (recordFiiDii skipped).
     */
    @Value("${oi-momentum.v3.fii-dii-file:data/v3-fii-dii.properties}")
    private String fiiDiiFile;

    /** Tracks the date we last successfully loaded FII/DII so we don't re-read same file. */
    private volatile LocalDate fiiDiiLastLoadedDay = null;

    @Autowired
    public V3ContextFeeder(MarketContextService marketContext,
                            RegimeClassifier regimeClassifier,
                            LiveInstrumentCache liveInstrumentCache,
                            LiveCandleBuilder liveCandleBuilder,
                            VwapIndicator vwapIndicator,
                            AtrIndicator atrIndicator,
                            MarketGuard marketGuard) {
        this.marketContext = marketContext;
        this.regimeClassifier = regimeClassifier;
        this.liveInstrumentCache = liveInstrumentCache;
        this.liveCandleBuilder = liveCandleBuilder;
        this.vwapIndicator = vwapIndicator;
        this.atrIndicator = atrIndicator;
        this.marketGuard = marketGuard;
    }

    @PostConstruct
    void announce() {
        log.info("[V3ContextFeeder] active — feeding MarketContextService + RegimeClassifier "
                + "once per minute during market hours.");
        // Best-effort FII/DII fetch on startup. Failures are silent here (no telegram)
        // because (a) the app must boot whether the endpoint is up or not, and (b) the
        // scheduled daily refresh and the file-fallback both compensate.
        try {
            tryFetchFiiDii(LocalDate.now(IST));
        } catch (Exception ex) {
            log.debug("[V3ContextFeeder] startup FII/DII fetch failed: {}", ex.getMessage());
        }
    }

    /**
     * Daily FII/DII refresh at 09:05 IST — published right after open, when the prior
     * day's net activity becomes available on NSE.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshFiiDiiDaily() {
        try {
            tryFetchFiiDii(LocalDate.now(IST));
        } catch (Exception ex) {
            log.warn("[V3ContextFeeder] daily FII/DII fetch failed: {}", ex.getMessage());
        }
    }

    /**
     * Try the API first; on failure fall back to the file feeder (which is a no-op when
     * no file exists). Idempotent within a trading day — once fiiDiiLastLoadedDay equals
     * today, subsequent calls skip the file path.
     */
    private void tryFetchFiiDii(LocalDate today) {
        if (fiiDiiApiClient != null) {
            var data = fiiDiiApiClient.fetchLatest();
            if (data.isPresent() && data.get().isMeaningful()) {
                marketContext.recordFiiDii(data.get().fiiNetCrore(), data.get().diiNetCrore());
                fiiDiiLastLoadedDay = today;
                log.info("[V3ContextFeeder] API loaded FII={}cr DII={}cr (source date={})",
                        data.get().fiiNetCrore(), data.get().diiNetCrore(),
                        data.get().sourceDate());
                return;
            }
            log.info("[V3ContextFeeder] FII/DII API returned no usable data — "
                    + "falling back to {}", fiiDiiFile);
        }
        // File path (existing behaviour, kept as fallback)
        if (fiiDiiLastLoadedDay == null || !fiiDiiLastLoadedDay.equals(today)) {
            loadFiiDii(today);
        }
    }

    /**
     * Fire every 60 seconds during market hours. Pulls per-index spot/VWAP/PCR/ATR/etc.
     * and pushes them into the context services.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void feed() {
        try {
            LocalTime now = LocalTime.now(IST);
            if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(15, 35))) {
                return;
            }
            LocalDate today = LocalDate.now(IST);
            boolean newDay = currentSessionDay == null || !currentSessionDay.equals(today);

            // VIX (single global value)
            double vix = marketGuard.getCurrentVix();
            if (vix > 0) marketContext.recordVix(vix);

            // FII/DII — try API first; fall back to file. Idempotent per trading day.
            if (fiiDiiLastLoadedDay == null || !fiiDiiLastLoadedDay.equals(today)) {
                tryFetchFiiDii(today);
            }

            for (IndexType ix : FEED_INDICES) {
                feedOne(ix, now, today, newDay);
            }

            if (newDay) currentSessionDay = today;
        } catch (Exception ex) {
            log.warn("[V3ContextFeeder] feed cycle failed: {}", ex.getMessage());
        }
    }

    private void feedOne(IndexType ix, LocalTime now, LocalDate today, boolean newDay) {
        try {
            // ── Spot + session boundary handling ──
            double spot = liveInstrumentCache.getFuturesPrice(ix);
            if (spot <= 0) return;

            // On the first sample of a new trading day, capture session-open + previous-close.
            if (newDay) {
                regimeClassifier.setSessionOpen(ix, spot);
                Double prevSpot = lastSampleSpot.get(ix);
                if (prevSpot != null && prevSpot > 0) {
                    regimeClassifier.setPreviousClose(ix, prevSpot);
                }
            }
            // If session-open is still unset (e.g. fed mid-day on startup), set it now to spot.
            if (regimeClassifier.getSessionOpen(ix) <= 0) {
                regimeClassifier.setSessionOpen(ix, spot);
            }
            lastSampleSpot.put(ix, spot);

            // ── VWAP ──
            List<Candle> oneMinCandles = liveCandleBuilder.getHistory(
                    ix.spotToken(), Timeframe.ONE_MINUTE);
            if (oneMinCandles != null && !oneMinCandles.isEmpty()) {
                BigDecimal vwap = vwapIndicator.calculateSessionAnchored(oneMinCandles, IST);
                if (vwap != null && vwap.signum() > 0) {
                    marketContext.recordVwap(ix, vwap.doubleValue());
                }
            }

            // ── PCR (realtime) ──
            double pcr = liveInstrumentCache.getRealtimePcr(ix);
            if (pcr > 0) marketContext.recordPcr(ix, pcr);

            // ── R1-R4 (2026-06-02): reversal-risk tracker sampling ──
            // Reads the latest chain snapshot once a minute and updates per-index
            // history of max-pain, IV skew, both walls, per-strike OI. Powers the
            // OIST imbalance-only veto when the chain is rotating against the trap.
            if (reversalRiskTracker != null) {
                com.algo.trade.data.ChainSnapshot latest = marketContext.getLatestSnapshot(ix);
                if (latest != null) {
                    try {
                        reversalRiskTracker.recordSnapshot(ix, latest);
                    } catch (Exception ex) {
                        log.debug("[V3ContextFeeder] reversalRiskTracker tick failed for {}: {}",
                                ix, ex.getMessage());
                    }
                }
            }

            // ── ATR % approximation from 15-min candles ──
            // Timeframe enum lacks ONE_DAY; approximate daily ATR via 15-min candles
            // over the last ~3.75 hours (15 candles × 15 min). Backtest-tuned multiplier
            // 2.5 scales rolling-15m ATR up to a daily-ish equivalent. Acceptable for
            // regime classification (LOW_VOL/NORMAL/HIGH_VOL bucketing).
            if (oneMinCandles != null && oneMinCandles.size() >= 30) {
                List<Candle> fifteenMin = liveCandleBuilder.getHistory(
                        ix.spotToken(), Timeframe.FIFTEEN_MINUTE);
                if (fifteenMin != null && fifteenMin.size() >= 15) {
                    double atrPct = atrIndicator.calculateATRPercent(fifteenMin, 14);
                    double dailyEquiv = atrPct * 2.5;  // intraday→daily scale
                    if (dailyEquiv > 0) regimeClassifier.setDailyAtrPct(ix, dailyEquiv);
                }
            }
        } catch (Exception ex) {
            log.debug("[V3ContextFeeder] feedOne failed for {}: {}", ix, ex.getMessage());
        }
    }

    /**
     * Review nit #55 — load FII/DII from a daily properties file. Operator updates the
     * file each morning (or via a separate cron / API integration). Missing file or
     * malformed values silently no-op so we never crash on missing input.
     */
    private void loadFiiDii(LocalDate today) {
        try {
            if (fiiDiiFile == null || fiiDiiFile.isBlank()) return;
            Path p = Path.of(fiiDiiFile);
            if (!Files.exists(p)) {
                fiiDiiLastLoadedDay = today;     // Mark loaded so we don't retry every minute
                return;
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(p)) {
                props.load(in);
            }
            double fii = parseDoubleOr(props.getProperty("fii.net.crore"), Double.NaN);
            double dii = parseDoubleOr(props.getProperty("dii.net.crore"), Double.NaN);
            if (!Double.isNaN(fii) || !Double.isNaN(dii)) {
                marketContext.recordFiiDii(
                        Double.isNaN(fii) ? marketContext.getFiiNetCrore() : fii,
                        Double.isNaN(dii) ? marketContext.getDiiNetCrore() : dii);
                log.info("[V3ContextFeeder] loaded FII={}cr DII={}cr from {} for {}",
                        fii, dii, fiiDiiFile, today);
            }
            fiiDiiLastLoadedDay = today;
        } catch (Exception ex) {
            log.warn("[V3ContextFeeder] failed to load FII/DII from {}: {}",
                    fiiDiiFile, ex.getMessage());
            fiiDiiLastLoadedDay = today;     // Mark loaded to suppress retry storm
        }
    }

    private static double parseDoubleOr(String v, double fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }
}
