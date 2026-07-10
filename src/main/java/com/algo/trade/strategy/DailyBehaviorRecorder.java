package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily Behaviour Recorder — captures today's actual market behaviour and
 * appends it to the daily-behavior-log.yml at end of day.
 *
 * <p>This creates the feedback loop:</p>
 * <ol>
 *   <li>During the day: tracks open/high/low/close, PCR peak, OI direction, behaviour type</li>
 *   <li>At 15:35 IST: writes today's row into the YAML log file</li>
 *   <li>Next boot: DailyBehaviorLogLoader reads the updated file → recalculates tuning rules</li>
 *   <li>Strategies adapt their thresholds based on fresh data</li>
 * </ol>
 *
 * <p>The bot becomes self-improving: each day's trading adds to the dataset,
 * and the pattern confidence scores shift as more evidence accumulates.</p>
 */
@Component
public class DailyBehaviorRecorder implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(DailyBehaviorRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final PcrCalculator pcrCalculator;
    private final MarketGuard marketGuard;
    private final ExpiryBehaviorTuner expiryBehaviorTuner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OptionLeadsIndexDetector optionLeadsDetector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PcrMomentumReversalStrategy pcrReversalStrategy;

    @Value("${trading.behavior-log.path:./data/daily-behavior-log.yml}")
    private String logFilePath;

    @Value("${trading.behavior-recorder.enabled:true}")
    private boolean enabled;

    // ── Per-index intraday tracking ───────────────────────────────────────

    private final Map<IndexType, DayRecord> todayRecords = new ConcurrentHashMap<>();
    private volatile boolean recordedToday = false;

    public DailyBehaviorRecorder(LiveInstrumentCache liveInstrumentCache,
                                  ExpiryCalendar expiryCalendar,
                                  PcrCalculator pcrCalculator,
                                  MarketGuard marketGuard,
                                  // @Lazy breaks the circular reference: ExpiryBehaviorTuner field-injects
                                  // this recorder, while this constructor needs the tuner. Spring Boot 3.x
                                  // prohibits circular references by default — the proxy defers resolution.
                                  @org.springframework.context.annotation.Lazy ExpiryBehaviorTuner expiryBehaviorTuner) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.pcrCalculator = pcrCalculator;
        this.marketGuard = marketGuard;
        this.expiryBehaviorTuner = expiryBehaviorTuner;
    }

    // ── Live Price Updates (called from tick feed) ────────────────────────

    /**
     * Called on every spot price tick to track OHLC for the day.
     */
    public void recordTick(IndexType indexType, double price) {
        if (!enabled || price <= 0) return;

        DayRecord rec = todayRecords.computeIfAbsent(indexType, k -> new DayRecord());
        if (rec.open == 0) rec.open = price;
        if (price > rec.high) rec.high = price;
        if (rec.low == 0 || price < rec.low) rec.low = price;
        rec.close = price;
    }

    /**
     * Called periodically (from PCR calculator or tick loop) to track PCR extremes.
     */
    public void recordPcr(IndexType indexType, double pcr) {
        if (!enabled || pcr <= 0) return;
        DayRecord rec = todayRecords.computeIfAbsent(indexType, k -> new DayRecord());
        if (pcr > rec.pcrHigh) rec.pcrHigh = pcr;
        if (rec.pcrLow == 0 || pcr < rec.pcrLow) rec.pcrLow = pcr;
        rec.pcrLatest = pcr;
    }

    // ── End-of-Day Recording (15:35 IST) ──────────────────────────────────

    /**
     * Runs at 15:35 IST — after market close. Captures the day's behaviour
     * and appends it to the YAML log file.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordEndOfDay() {
        if (!enabled || recordedToday) return;

        LocalDate today = LocalDate.now(IST);
        int written = 0;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            DayRecord rec = todayRecords.get(idx);
            if (rec == null || rec.open == 0) continue;

            // Classify today's behaviour
            String behaviour = classifyBehaviour(idx, rec);
            String operatorSignals = classifyOperatorSignals(idx, rec);
            String botAction = classifyBotAction(idx, rec);
            double changePct = rec.open > 0 ? ((rec.close - rec.open) / rec.open) * 100 : 0;

            // Write to file
            appendToLog(today, idx, rec, changePct, behaviour, operatorSignals, botAction);
            written++;
        }

        if (written > 0) {
            recordedToday = true;
            log.info("[BehaviorRecorder] Recorded {} index entries for {} into daily log.", written, today);
        }
    }

    /**
     * Reset at start of day (called by the daily reset scheduler).
     */
    public void resetDaily() {
        todayRecords.clear();
        recordedToday = false;
    }

    // ── Behaviour Classification ──────────────────────────────────────────

    private String classifyBehaviour(IndexType idx, DayRecord rec) {
        double changePct = rec.open > 0 ? ((rec.close - rec.open) / rec.open) * 100 : 0;
        boolean isExpiryDay = expiryCalendar.isExpiryDay(idx);

        // Sharp decline
        if (changePct <= -1.5) return "Sharp decline (>" + String.format("%.1f", changePct) + "%)";
        if (changePct <= -0.8) return "Decline";

        // Strong rally
        if (changePct >= 1.0) return "Strong rally";
        if (changePct >= 0.5) return "Mild rally";

        // Expiry-specific patterns
        if (isExpiryDay) {
            double range = rec.high - rec.low;
            double rangePercent = rec.open > 0 ? (range / rec.open) * 100 : 0;

            // Check for reversal pattern (big range but closed flat or opposite to open direction)
            if (rangePercent > 1.0 && Math.abs(changePct) < 0.3) {
                return "Expiry reversal (wide range, closed flat)";
            }
            if (rangePercent < 0.5) {
                return "Expiry pinning";
            }
            return "Expiry volatility";
        }

        // Range-bound
        double range = rec.open > 0 ? ((rec.high - rec.low) / rec.open) * 100 : 0;
        if (range < 0.5) return "Flat/range-bound";
        if (Math.abs(changePct) < 0.3) return "Sideways";

        return changePct > 0 ? "Mild rally" : "Mild decline";
    }

    private String classifyOperatorSignals(IndexType idx, DayRecord rec) {
        StringBuilder signals = new StringBuilder();

        // PCR analysis
        if (rec.pcrHigh >= 1.5) {
            double pcrDrop = rec.pcrHigh - rec.pcrLatest;
            if (pcrDrop >= 0.15) {
                signals.append("PCR unwind ").append(String.format("%.1f→%.1f", rec.pcrHigh, rec.pcrLatest));
            } else {
                signals.append("PCR high ").append(String.format("%.1f", rec.pcrHigh));
            }
        } else if (rec.pcrLatest > 0) {
            signals.append("PCR ").append(String.format("%.2f", rec.pcrLatest));
        }

        // Option leads detection
        if (optionLeadsDetector != null) {
            int dir = optionLeadsDetector.getSignalDirection(idx);
            if (dir != 0) {
                if (signals.length() > 0) signals.append(", ");
                signals.append("Option breakout ").append(dir > 0 ? "CE" : "PE");
            }
        }

        // Expiry-specific
        if (expiryCalendar.isExpiryDay(idx)) {
            if (signals.length() > 0) signals.append(", ");
            signals.append("Expiry day");
        }

        return signals.length() > 0 ? signals.toString() : "Neutral";
    }

    private String classifyBotAction(IndexType idx, DayRecord rec) {
        ExpiryBehaviorTuner.ExpiryMode mode = expiryBehaviorTuner.currentMode(
                mapToUnderlying(idx));

        return switch (mode) {
            case CRASH_HALT -> "Auto-halt triggered";
            case EXPIRY_REVERSAL -> "Gamma scalps after noon";
            case EXPIRY_PINNING -> "Straddle bias";
            case MOMENTUM -> "Directional longs";
            case NORMAL -> {
                double changePct = rec.open > 0 ? ((rec.close - rec.open) / rec.open) * 100 : 0;
                if (Math.abs(changePct) < 0.3) yield "No trade";
                yield changePct > 0 ? "Long CE" : "Long PE";
            }
        };
    }

    // ── File Writing ──────────────────────────────────────────────────────

    /** Sibling append-safe sequence file for auto-recorded entries (keeps the curated map file intact). */
    private File autoLogFile() {
        File curated = new File(logFilePath);
        File dir = curated.getParentFile();
        return new File(dir != null ? dir : new File("."), "daily-behavior-autolog.yml");
    }

    private void appendToLog(LocalDate date, IndexType idx, DayRecord rec,
                             double changePct, String behaviour, String operatorSignals, String botAction) {
        try {
            // Write to a SEPARATE, append-safe sequence file — not the curated map-rooted
            // daily-behavior-log.yml. Appending "  - { ... }" items to the map file used to
            // corrupt its structure (root then parsed as a List → "ArrayList cannot be cast
            // to Map" in DailyBehaviorLogLoader, silently disabling all tuning). A root-level
            // YAML sequence file can be appended to indefinitely and always stays valid.
            File file = autoLogFile();
            if (file.getParentFile() != null) file.getParentFile().mkdirs();

            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                // Root-level sequence item (column 0) — keeps the autolog a valid YAML list.
                String entry = String.format(
                        "- { date: \"%s\", index: %s, open: %d, high: %d, low: %d, close: %d, "
                                + "changePct: %.2f, behaviour: \"%s\", operatorSignals: \"%s\", botAction: \"%s\" }",
                        date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        idx.name(),
                        (int) rec.open, (int) rec.high, (int) rec.low, (int) rec.close,
                        changePct,
                        behaviour.replace("\"", "'"),
                        operatorSignals.replace("\"", "'"),
                        botAction.replace("\"", "'"));

                pw.println("# Auto-recorded " + date);
                pw.println(entry);
            }

            log.debug("[BehaviorRecorder] Appended {} {} to {}", date, idx, logFilePath);
        } catch (Exception e) {
            log.warn("[BehaviorRecorder] Failed to write to {}: {}", logFilePath, e.getMessage());
        }
    }

    private com.algo.trade.domain.UnderlyingSymbol mapToUnderlying(IndexType idx) {
        return switch (idx) {
            case NIFTY -> com.algo.trade.domain.UnderlyingSymbol.NIFTY;
            case BANKNIFTY -> com.algo.trade.domain.UnderlyingSymbol.BANKNIFTY;
            case SENSEX -> com.algo.trade.domain.UnderlyingSymbol.SENSEX;
            case FINNIFTY -> com.algo.trade.domain.UnderlyingSymbol.FINNIFTY;
            case MIDCPNIFTY -> com.algo.trade.domain.UnderlyingSymbol.MIDCPNIFTY;
        };
    }

    // ── State ─────────────────────────────────────────────────────────────

    static class DayRecord {
        volatile double open;
        volatile double high;
        volatile double low;
        volatile double close;
        volatile double pcrHigh;
        volatile double pcrLow;
        volatile double pcrLatest;
    }
}
