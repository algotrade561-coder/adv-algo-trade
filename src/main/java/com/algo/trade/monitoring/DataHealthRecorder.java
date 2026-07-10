package com.algo.trade.monitoring;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Data-health monitoring with mid-day and end-of-day checks.
 *
 * <ul>
 *   <li><b>12:00 IST</b> — mid-day health check: alerts if spot/candles/PCR are missing (catches
 *       WS disconnects early, not at 15:31 when it's too late).</li>
 *   <li><b>15:31 IST</b> — end-of-day CSV write: persists full-day health snapshot for analysis.</li>
 * </ul>
 *
 * Only monitors actively subscribed underlyings (from {@code trading.symbols.underlyings}).
 * FINNIFTY/MIDCPNIFTY are excluded unless explicitly subscribed — they have no PCR data by design.
 */
@Component
public class DataHealthRecorder {

    private static final Logger log = LoggerFactory.getLogger(DataHealthRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Expected min 5-min candles by midday (9:15–12:00 = 165 min / 5 = 33 candles). */
    private static final int MIN_CANDLES_5M_BY_MIDDAY = 30;
    /** Expected min PCR samples by midday (~90 min of 2-min cycles = ~45 samples). */
    private static final int MIN_PCR_SAMPLES_BY_MIDDAY = 35;
    /** Expected min PCR samples by end of day (~185 for a full session). */
    private static final int MIN_PCR_SAMPLES_EOD = 170;

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache liveInstrumentCache;
    private final PcrCalculator pcrCalculator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ErrorEventService errorEventService;

    /** Only record health for actively subscribed underlyings (from YAML config). */
    @org.springframework.beans.factory.annotation.Value("${trading.symbols.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private List<String> activeUnderlyings;

    public DataHealthRecorder(LiveCandleBuilder candleBuilder,
                              LiveInstrumentCache liveInstrumentCache,
                              PcrCalculator pcrCalculator) {
        this.candleBuilder = candleBuilder;
        this.liveInstrumentCache = liveInstrumentCache;
        this.pcrCalculator = pcrCalculator;
    }

    /**
     * Mid-day health check (12:00 IST) — proactive alert if data feeds are degraded.
     * Catches WS disconnects, missing chain subscriptions, and candle gaps early.
     */
    @Scheduled(cron = "0 0 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void midDayHealthCheck() {
        StringBuilder issues = new StringBuilder();
        for (String name : activeUnderlyings) {
            IndexType idx = resolveIndex(name);
            if (idx == null) continue;

            double spot = liveInstrumentCache.getFuturesPrice(idx);
            int c5 = safeCount(idx, Timeframe.FIVE_MINUTE);
            int pcrN = pcrCalculator.getIntradaySeries(idx).size();

            if (spot <= 0) {
                issues.append(String.format("\n• %s: NO SPOT DATA (feed dead)", idx));
            }
            if (c5 < MIN_CANDLES_5M_BY_MIDDAY) {
                issues.append(String.format("\n• %s: only %d 5-min candles (expected ≥%d by noon)", idx, c5, MIN_CANDLES_5M_BY_MIDDAY));
            }
            if (isPcrTracked(idx) && pcrN < MIN_PCR_SAMPLES_BY_MIDDAY) {
                issues.append(String.format("\n• %s: only %d PCR samples (expected ≥%d by noon)", idx, pcrN, MIN_PCR_SAMPLES_BY_MIDDAY));
            }
        }

        if (!issues.isEmpty()) {
            String msg = "⚠️ [DataHealth] Mid-day check — data gaps detected:" + issues;
            log.warn(msg);
            if (telegramAlertService != null) telegramAlertService.systemAlert(msg);
            if (errorEventService != null) errorEventService.medium("DataHealth", "Mid-day data gaps:" + issues);
        } else {
            log.info("[DataHealth] Mid-day check passed: all indices have spot, candles, and PCR data.");
        }
    }

    /** End-of-day CSV write (15:31 IST). */
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void writeDailyHealthRow() {
        try {
            Path dir = Path.of("data", "health");
            Files.createDirectories(dir);
            Path file = dir.resolve("data-health-" + LocalDate.now(IST) + ".csv");
            boolean newFile = !Files.exists(file);
            int rowsWritten = 0;
            StringBuilder alerts = new StringBuilder();
            try (FileWriter w = new FileWriter(file.toFile(), true)) {
                if (newFile) w.write("date,index,spot,candles1m,candles5m,pcrSamples,ivQualityPct,oiAvailPct\n");
                for (String name : activeUnderlyings) {
                    IndexType idx = resolveIndex(name);
                    if (idx == null) continue;

                    double spot = liveInstrumentCache.getFuturesPrice(idx);
                    int c1 = safeCount(idx, Timeframe.ONE_MINUTE);
                    int c5 = safeCount(idx, Timeframe.FIVE_MINUTE);
                    int pcrN = pcrCalculator.getIntradaySeries(idx).size();
                    double ivQuality = computeIvQualityPercent(idx, spot);
                    double oiAvail = computeOiAvailabilityPercent(idx, spot);
                    w.write(String.format("%s,%s,%.2f,%d,%d,%d,%.1f,%.1f%n",
                            LocalDate.now(IST), idx, spot, c1, c5, pcrN, ivQuality, oiAvail));
                    rowsWritten++;

                    // Spot/candle missing
                    if (spot <= 0 || c1 == 0 || c5 == 0) {
                        String msg = String.format("%s MISSING data: spot=%.0f c1m=%d c5m=%d", idx, spot, c1, c5);
                        log.warn("[DataHealth] {}", msg);
                        alerts.append("\n• ").append(msg);
                    }
                    // PCR sample deviation (only for tracked indices)
                    if (isPcrTracked(idx) && pcrN < MIN_PCR_SAMPLES_EOD) {
                        String msg = String.format("%s PCR_INCOMPLETE: %d samples (expected ≥%d)", idx, pcrN, MIN_PCR_SAMPLES_EOD);
                        log.warn("[DataHealth] {}", msg);
                        alerts.append("\n• ").append(msg);
                    }
                    // IV quality degraded
                    if (ivQuality >= 0 && ivQuality < 20.0) {
                        String msg = String.format("%s IV_CORRUPT: only %.0f%% plausible IV", idx, ivQuality);
                        log.warn("[DataHealth] {}", msg);
                        alerts.append("\n• ").append(msg);
                    }
                    // OI-change baseline coverage degraded (DATA-2/3, 2026-06-20). By EOD
                    // every traded strike should have an OI baseline; a low figure means the
                    // OI feed was starved for much of the session (the signals read 0-change
                    // as "flat" when it was really "unavailable").
                    if (oiAvail >= 0 && oiAvail < 70.0) {
                        String msg = String.format("%s OI_SPARSE: only %.0f%% of strikes had an OI baseline", idx, oiAvail);
                        log.warn("[DataHealth] {}", msg);
                        alerts.append("\n• ").append(msg);
                    }
                }
            }
            if (rowsWritten > 0) {
                log.info("[DataHealth] EOD written: {} indices, file={}", rowsWritten, file);
            } else {
                log.warn("[DataHealth] No rows written — check trading.symbols.underlyings config.");
            }
            // Send consolidated alert if any issues found
            if (!alerts.isEmpty() && telegramAlertService != null) {
                telegramAlertService.systemAlert("📊 [DataHealth] EOD issues:" + alerts);
            }
        } catch (Exception e) {
            log.warn("[DataHealth] write failed: {}", e.getMessage(), e);
        }
    }

    /** Check if an index is in the PcrCalculator's tracked list. */
    private boolean isPcrTracked(IndexType idx) {
        return PcrCalculator.TRACKED_INDICES.contains(idx);
    }

    private IndexType resolveIndex(String name) {
        try { return IndexType.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            log.debug("[DataHealth] Skipping unknown underlying: {}", name);
            return null;
        }
    }

    /**
     * Compute the % of live option strikes (for this index) with plausible IV (3%–60%).
     */
    /** ATM band (in strikes) the metrics are restricted to — the strikes the bot actually trades. */
    private static final int HEALTH_BAND_STRIKES = 7;

    private double computeIvQualityPercent(IndexType idx, double spot) {
        try {
            var allOptions = liveInstrumentCache.allOptions();
            if (allOptions == null || allOptions.isEmpty() || spot <= 0) return -1;
            // FIX (2026-06-24): restrict to the ATM±band. Scanning ALL strikes counted degenerate
            // deep ITM/OTM wings (IV floors to the solver seed / blows up there), which made the metric
            // read ~2% and fire a daily false IV_CORRUPT alarm even though ATM-band IV is clean.
            int atm = idx.roundToATM(spot);
            int bandPts = HEALTH_BAND_STRIKES * idx.strikeInterval();
            long total = 0, valid = 0;
            for (var opt : allOptions) {
                if (opt.getIndexType() != idx) continue;
                if (Math.abs(opt.getStrikePrice() - atm) > bandPts) continue;
                double iv = opt.getImpliedVolatility();
                total++;
                if (iv > 3.0 && iv < 60.0) valid++;
            }
            return total == 0 ? -1 : ((double) valid / total) * 100.0;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Compute the % of live option strikes (for this index) that have a real OI baseline
     * (3-min lookback) — i.e. OI-change readings reflect actual flow, not warm-up. Surfaces
     * the opening-window OI gap (Finding 2/3): on a healthy day this is ~100% by EOD; a low
     * value flags a session where OI-change was largely unavailable.
     */
    private double computeOiAvailabilityPercent(IndexType idx, double spot) {
        try {
            var allOptions = liveInstrumentCache.allOptions();
            if (allOptions == null || allOptions.isEmpty() || spot <= 0) return -1;
            // FIX (2026-06-24): restrict to the ATM±band — deep wings rarely carry an OI baseline, which
            // dragged this metric to ~2% and fired a daily false OI_SPARSE alarm. The tradeable band is
            // what matters for OI-confirmed entries.
            int atm = idx.roundToATM(spot);
            int bandPts = HEALTH_BAND_STRIKES * idx.strikeInterval();
            long total = 0, withBaseline = 0;
            for (var opt : allOptions) {
                if (opt.getIndexType() != idx) continue;
                if (Math.abs(opt.getStrikePrice() - atm) > bandPts) continue;
                total++;
                if (opt.hasOiBaseline(3)) withBaseline++;
            }
            return total == 0 ? -1 : ((double) withBaseline / total) * 100.0;
        } catch (Exception e) {
            return -1;
        }
    }

    private int safeCount(IndexType idx, Timeframe tf) {
        try { return candleBuilder.getHistory(idx.spotToken(), tf).size(); }
        catch (Exception e) { return -1; }
    }
}
