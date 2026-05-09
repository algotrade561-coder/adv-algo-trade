package com.algo.trade.data;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.monitoring.SchedulerRegistry;
import com.algo.trade.risk.MarketGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    @Value("${snapshot.enabled:true}")
    private boolean enabled;

    @Value("${snapshot.strikes-each-side:10}")
    private int strikesEachSide;

    @Value("${snapshot.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private List<String> enabledUnderlyings;

    public OptionChainSnapshotScheduler(LiveInstrumentCache liveInstrumentCache,
                                         ExpiryCalendar expiryCalendar,
                                         MarketGuard marketGuard,
                                         SnapshotFileWriter snapshotFileWriter,
                                         SchedulerRegistry schedulerRegistry) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
        this.snapshotFileWriter = snapshotFileWriter;
        this.schedulerRegistry = schedulerRegistry;
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
            log.debug("[ChainSnapshot] No spot price for {}, skipping", indexType);
            return Optional.empty();
        }

        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
        List<OptionInstrument> fullChain = liveInstrumentCache.getStrikeChain(indexType, expiry);
        if (fullChain.isEmpty()) {
            log.debug("[ChainSnapshot] Empty chain for {} expiry={}", indexType, expiry);
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

    private ChainSnapshot.StrikeData buildStrikeData(int strike, OptionInstrument ce, OptionInstrument pe) {
        long ceOiChange = ce.getOpenInterest() - ce.getPrevOpenInterest();
        long peOiChange = pe.getOpenInterest() - pe.getPrevOpenInterest();

        return new ChainSnapshot.StrikeData(
                strike,
                // CE
                ce.getLastPrice(),
                ce.getOpenInterest(),
                ce.getVolume(),
                ce.getImpliedVolatility(),
                ce.getDelta(),
                ce.getGamma(),
                ce.getTheta(),
                ce.getVega(),
                ce.getBestBid(),
                ce.getBestAsk(),
                ceOiChange,
                ce.getHigh5m(),
                ce.getLow5m(),
                // PE
                pe.getLastPrice(),
                pe.getOpenInterest(),
                pe.getVolume(),
                pe.getImpliedVolatility(),
                pe.getDelta(),
                pe.getGamma(),
                pe.getTheta(),
                pe.getVega(),
                pe.getBestBid(),
                pe.getBestAsk(),
                peOiChange,
                pe.getHigh5m(),
                pe.getLow5m()
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
