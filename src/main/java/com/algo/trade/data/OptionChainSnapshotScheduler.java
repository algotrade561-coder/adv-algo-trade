package com.algo.trade.data;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.monitoring.SchedulerRegistry;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.oimomentum.OperatorFrameworkService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures option chain snapshots every 5 minutes during market hours.
 * Reads live data from LiveInstrumentCache (no external API calls).
 *
 * Snapshot includes ATM ± N strikes with full Greeks, bid/ask, OI, and 5-min high/low.
 */
@Component
public class OptionChainSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(OptionChainSnapshotScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final String TASK_NAME = "chainSnapshotCapture";

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketGuard marketGuard;
    private final SnapshotFileWriter snapshotFileWriter;
    private final SchedulerRegistry schedulerRegistry;
    private final OperatorFrameworkService operatorFrameworkService;

    @Value("${snapshot.enabled:true}")
    private boolean enabled;

    @Value("${snapshot.strikes-each-side:10}")
    private int strikesEachSide;

    /**
     * Per-strike OI from the previous snapshot cycle, keyed by "INDEX|strike|CE_or_PE".
     * Used to compute a true 5-minute OI delta in buildStrikeData() instead of relying
     * on OptionInstrument.prevOpenInterest which is a transient WS field and almost always
     * stale at snapshot time (analysis showed 99.6% zero OI change before this fix).
     *
     * Cleared on each new trading day — prevents Friday OI leaking into Monday's first
     * snapshot and producing bogus 5-min deltas for the Operator Framework.
     */
    private final Map<String, Long> prevSnapshotOiCache = new ConcurrentHashMap<>();

    /**
     * Per-strike LTP from the previous snapshot cycle, keyed by "INDEX|strike|CE_or_PE".
     * Used to derive 5-minute option price high/low between consecutive snapshots,
     * fixing the "always zero high5m/low5m" bug caused by the clock-reset coinciding
     * with the snapshot fire time.
     *
     * Cleared on each new trading day alongside prevSnapshotOiCache.
     */
    private final Map<String, Double> prevSnapshotLtpCache = new ConcurrentHashMap<>();

    /** Trading date of the last successful snapshot cycle — used to detect day rollover. */
    private volatile LocalDate lastCaptureDay = null;

    @Value("${snapshot.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private List<String> enabledUnderlyings;

    public OptionChainSnapshotScheduler(LiveInstrumentCache liveInstrumentCache,
                                         ExpiryCalendar expiryCalendar,
                                         MarketGuard marketGuard,
                                         SnapshotFileWriter snapshotFileWriter,
                                         SchedulerRegistry schedulerRegistry,
                                         OperatorFrameworkService operatorFrameworkService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
        this.snapshotFileWriter = snapshotFileWriter;
        this.schedulerRegistry = schedulerRegistry;
        this.operatorFrameworkService = operatorFrameworkService;
    }

    @PostConstruct
    void register() {
        schedulerRegistry.register(TASK_NAME,
                "Option chain snapshot capture (ATM±" + strikesEachSide + " strikes, every 5 min)",
                300_000, this::captureSnapshots);
    }

    /**
     * Captures option chain snapshot for all enabled underlyings.
     * Runs every 5 minutes. Skips if outside market hours or disabled.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void captureSnapshots() {
        if (!enabled || !schedulerRegistry.isEnabled(TASK_NAME)) return;
        if (!isMarketHours()) return;
        if (!liveInstrumentCache.isReady()) {
            log.debug("[ChainSnapshot] LiveInstrumentCache not ready, skipping capture");
            return;
        }

        // Day-boundary clear — prevents Friday OI/LTP leaking into Monday's first snapshot delta
        LocalDate today = LocalDate.now(IST);
        if (lastCaptureDay != null && !today.equals(lastCaptureDay)) {
            prevSnapshotOiCache.clear();
            prevSnapshotLtpCache.clear();
            log.info("[ChainSnapshot] New trading day {} — cleared cross-day OI/LTP snapshot caches", today);
        }
        lastCaptureDay = today;

        int captured = 0;
        for (String underlyingName : enabledUnderlyings) {
            try {
                IndexType indexType = IndexType.fromName(underlyingName);
                Optional<ChainSnapshot> snapshot = captureForUnderlying(indexType);
                if (snapshot.isPresent()) {
                    snapshotFileWriter.write(snapshot.get());
                    captured++;
                    log.info("[ChainSnapshot] Captured: {} spot={} strikes={}",
                            indexType, snapshot.get().spot(), snapshot.get().strikes().size());
                    // Feed into Operator Framework for institutional accumulation analysis
                    operatorFrameworkService.onChainSnapshot(indexType, snapshot.get());
                }
            } catch (Exception e) {
                log.error("[ChainSnapshot] Capture failed for {}: {}", underlyingName, e.getMessage());
            }
        }

        // Always record run, even if no snapshots were captured
        // This ensures health monitoring shows accurate "Last Run" status
        schedulerRegistry.recordRun(TASK_NAME);
    }

    /**
     * Capture snapshot for a single underlying. Exposed for manual/test invocation.
     */
    public Optional<ChainSnapshot> captureForUnderlying(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) {
            log.warn("[ChainSnapshot] No spot price for {} — snapshot skipped. "
                    + "Check WS subscription includes the {} spot token.", indexType, indexType);
            return Optional.empty();
        }

        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
        List<OptionInstrument> fullChain = liveInstrumentCache.getStrikeChain(indexType, expiry);
        if (fullChain.isEmpty()) {
            log.warn("[ChainSnapshot] Empty instrument chain for {} expiry={} — snapshot skipped. "
                    + "Instruments may not be loaded or subscription is missing.", indexType, expiry);
            return Optional.empty();
        }

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        double vix = marketGuard.getCurrentVix();

        List<ChainSnapshot.StrikeData> strikes = new ArrayList<>();
        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int targetStrike = atm + (i * interval);

            OptionInstrument ce = findInChain(fullChain, targetStrike, "CE");
            OptionInstrument pe = findInChain(fullChain, targetStrike, "PE");

            if (ce != null && pe != null) {
                strikes.add(buildStrikeData(targetStrike, ce, pe));
            }
        }

        if (strikes.isEmpty()) {
            log.warn("[ChainSnapshot] No valid strikes found for {} ATM={}", indexType, atm);
            return Optional.empty();
        }

        ChainSnapshot snapshot = new ChainSnapshot(
                Instant.now(),
                indexType.name(),
                spot,
                vix,
                expiry.toString(),
                atm,
                strikes
        );

        return Optional.of(snapshot);
    }

    /**
     * Check if current time is within market hours (09:15-15:30 IST, weekdays).
     */
    boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Build a StrikeData record using cross-snapshot deltas for OI and price range.
     *
     * OI change — computed as current OI minus the OI from the PREVIOUS snapshot, not
     * OptionInstrument.prevOpenInterest (a transient WS field that is stale at capture time).
     * This gives a true 5-minute OI delta that the Operator Framework can use for
     * accumulation/distribution analysis.
     *
     * 5m high/low — derived from min/max of {current LTP, previous snapshot LTP}.
     * This replaces OptionInstrument.high5m/low5m which were always zero because the
     * clock-aligned window reset coincided with the snapshot cadence.
     *
     * Both caches are updated at the end of each call so the next snapshot has a baseline.
     */
    private ChainSnapshot.StrikeData buildStrikeData(int strike, OptionInstrument ce, OptionInstrument pe) {
        String idxName = ce.getIndexType().name();
        String ceKey = idxName + "|" + strike + "|CE";
        String peKey = idxName + "|" + strike + "|PE";

        // ── 5-min OI delta (snapshot-to-snapshot) ────────────────────────────
        long currentCeOi = ce.getOpenInterest();
        long currentPeOi = pe.getOpenInterest();
        Long prevCeOi = prevSnapshotOiCache.get(ceKey);
        Long prevPeOi = prevSnapshotOiCache.get(peKey);
        long ceOiChange = (prevCeOi != null && prevCeOi > 0 && currentCeOi > 0)
                ? currentCeOi - prevCeOi : 0;
        long peOiChange = (prevPeOi != null && prevPeOi > 0 && currentPeOi > 0)
                ? currentPeOi - prevPeOi : 0;

        // ── 5-min price range (snapshot-to-snapshot) ─────────────────────────
        double currentCeLtp = ce.getLastPrice();
        double currentPeLtp = pe.getLastPrice();
        Double prevCeLtp = prevSnapshotLtpCache.get(ceKey);
        Double prevPeLtp = prevSnapshotLtpCache.get(peKey);
        // High = max of current and previous LTP; Low = min. Falls back to live
        // OptionInstrument high5m/low5m if no prior snapshot exists yet.
        double ceHigh5m, ceLow5m, peHigh5m, peLow5m;
        if (prevCeLtp != null && prevCeLtp > 0 && currentCeLtp > 0) {
            ceHigh5m = Math.max(currentCeLtp, prevCeLtp);
            ceLow5m  = Math.min(currentCeLtp, prevCeLtp);
        } else {
            ceHigh5m = ce.getHigh5m();
            ceLow5m  = ce.getLow5m();
        }
        if (prevPeLtp != null && prevPeLtp > 0 && currentPeLtp > 0) {
            peHigh5m = Math.max(currentPeLtp, prevPeLtp);
            peLow5m  = Math.min(currentPeLtp, prevPeLtp);
        } else {
            peHigh5m = pe.getHigh5m();
            peLow5m  = pe.getLow5m();
        }

        // ── Update caches for next snapshot ──────────────────────────────────
        if (currentCeOi > 0)  prevSnapshotOiCache.put(ceKey, currentCeOi);
        if (currentPeOi > 0)  prevSnapshotOiCache.put(peKey, currentPeOi);
        if (currentCeLtp > 0) prevSnapshotLtpCache.put(ceKey, currentCeLtp);
        if (currentPeLtp > 0) prevSnapshotLtpCache.put(peKey, currentPeLtp);

        return new ChainSnapshot.StrikeData(
                strike,
                // CE
                currentCeLtp,
                currentCeOi,
                ce.getVolume(),
                ce.getImpliedVolatility(),
                ce.getDelta(),
                ce.getGamma(),
                ce.getTheta(),
                ce.getVega(),
                ce.getBestBid(),
                ce.getBestAsk(),
                ceOiChange,
                ceHigh5m,
                ceLow5m,
                // PE
                currentPeLtp,
                currentPeOi,
                pe.getVolume(),
                pe.getImpliedVolatility(),
                pe.getDelta(),
                pe.getGamma(),
                pe.getTheta(),
                pe.getVega(),
                pe.getBestBid(),
                pe.getBestAsk(),
                peOiChange,
                peHigh5m,
                peLow5m
        );
    }

    private OptionInstrument findInChain(List<OptionInstrument> chain, int strike, String optionType) {
        for (OptionInstrument inst : chain) {
            if (inst.getStrikePrice() == strike && optionType.equals(inst.getOptionType())) {
                return inst;
            }
        }
        return null;
    }
}
