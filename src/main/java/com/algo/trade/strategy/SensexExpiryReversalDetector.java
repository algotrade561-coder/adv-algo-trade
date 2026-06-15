package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.execution.DailyResettable;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the Sensex expiry-day operator-driven reversal pattern:
 *
 * <p><b>Pattern (observed May–June 2026)</b>:
 * Sensex opens weak, drifts down 300–1000 pts until ~11–12 AM, then
 * sharp operator-driven buying every hour drives it back up 800–1100 pts by close.</p>
 *
 * <p><b>Detection Logic</b>:
 * <ol>
 *   <li>Sensex expiry day (Thursday)</li>
 *   <li>Index drops ≥300 pts from open before 12:30 PM</li>
 *   <li>At least one 5-min candle shows reversal (bullish body ≥ 40% of candle range)</li>
 * </ol>
 *
 * <p><b>Signal Output</b>:
 * <ul>
 *   <li>REVERSAL_FORMING: Conditions met, rally expected. Enable gamma scalps.</li>
 *   <li>REVERSAL_CONFIRMED: Price has recovered &gt;50% of the drop. Ride momentum.</li>
 *   <li>NONE: No pattern detected.</li>
 * </ul>
 *
 * <p>Runs every 5 minutes during market hours on Sensex expiry days.</p>
 */
@Component
public class SensexExpiryReversalDetector implements DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(SensexExpiryReversalDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long SENSEX_SPOT_TOKEN = IndexType.SENSEX.spotToken();

    public enum ReversalPhase {
        NONE,
        DOWNTREND,         // Price falling, pattern not yet confirmed
        REVERSAL_FORMING,  // Drop threshold met + first reversal candle detected
        REVERSAL_CONFIRMED // Recovery > 50% of drop — strong operator buying
    }

    private final ExpiryCalendar expiryCalendar;
    private final LiveCandleBuilder candleBuilder;

    @Value("${trading.sensex-reversal.min-drop-points:300}")
    private double minDropPoints;

    @Value("${trading.sensex-reversal.detection-cutoff:12:30}")
    private String detectionCutoff;

    @Value("${trading.sensex-reversal.confirmation-recovery-pct:50}")
    private double confirmationRecoveryPct;

    // State
    private volatile ReversalPhase currentPhase = ReversalPhase.NONE;
    private volatile double dropFromOpen = 0;
    private volatile double recoveryFromLow = 0;
    private volatile int reversalCandleCount = 0;
    private volatile double intradayLow = Double.MAX_VALUE;
    private volatile double openPrice = 0;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public SensexExpiryReversalDetector(ExpiryCalendar expiryCalendar, LiveCandleBuilder candleBuilder) {
        this.expiryCalendar = expiryCalendar;
        this.candleBuilder = candleBuilder;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public ReversalPhase getPhase() { return currentPhase; }
    public double getDropFromOpen() { return dropFromOpen; }
    public double getRecoveryFromLow() { return recoveryFromLow; }
    public int getReversalCandleCount() { return reversalCandleCount; }

    public boolean isReversalActive() {
        return currentPhase == ReversalPhase.REVERSAL_FORMING
                || currentPhase == ReversalPhase.REVERSAL_CONFIRMED;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("phase", currentPhase.name());
        status.put("dropFromOpen", String.format("%.0f", dropFromOpen));
        status.put("recoveryFromLow", String.format("%.0f", recoveryFromLow));
        status.put("reversalCandleCount", reversalCandleCount);
        status.put("isSensexExpiryDay", expiryCalendar.isExpiryDay(IndexType.SENSEX));
        return status;
    }

    // ── Scheduled Detection ───────────────────────────────────────────────

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("sensexReversalDetector",
                    "Sensex expiry reversal pattern detection (5min)", 300_000, this::detect);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void detect() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("sensexReversalDetector")) return;
        if (!isMarketHours()) return;
        if (!expiryCalendar.isExpiryDay(IndexType.SENSEX)) {
            resetIfNeeded();
            return;
        }

        List<Candle> candles5m = candleBuilder.getHistory(SENSEX_SPOT_TOKEN, Timeframe.FIVE_MINUTE);
        if (candles5m == null || candles5m.isEmpty()) return;

        // History is seeded with prior session + today after a market-hours restart —
        // keep only TODAY's candles so open/low are not corrupted by yesterday's session.
        LocalDate today = LocalDate.now(IST);
        List<Candle> todayCandles = new ArrayList<>();
        for (Candle c : candles5m) {
            if (LocalDate.ofInstant(c.timestamp(), IST).equals(today)) {
                todayCandles.add(c);
            }
        }
        if (todayCandles.size() < 3) return;

        Candle firstCandle = todayCandles.get(0);
        Candle latestCandle = todayCandles.get(todayCandles.size() - 1);

        openPrice = firstCandle.open().doubleValue();
        double currentPrice = latestCandle.close().doubleValue();
        if (openPrice <= 0) return;

        // Recompute intraday low fresh from today's candles each run — no stale
        // carry-over between expiry days.
        double lowToday = Double.MAX_VALUE;
        for (Candle c : todayCandles) {
            double low = c.low().doubleValue();
            if (low < lowToday) lowToday = low;
        }
        intradayLow = lowToday;

        dropFromOpen = openPrice - intradayLow;
        recoveryFromLow = currentPrice - intradayLow;

        LocalTime now = LocalTime.now(IST);
        LocalTime cutoff = LocalTime.parse(detectionCutoff);

        // Phase transitions
        switch (currentPhase) {
            case NONE:
                if (dropFromOpen >= minDropPoints && now.isBefore(cutoff)) {
                    currentPhase = ReversalPhase.DOWNTREND;
                    log.info("[SensexReversalDetector] DOWNTREND: Sensex dropped {} pts from open before noon.",
                            String.format("%.0f", dropFromOpen));
                }
                break;

            case DOWNTREND:
                // Look for first reversal candle (bullish 5-min candle after the drop)
                if (isReversalCandle(latestCandle)) {
                    reversalCandleCount++;
                    currentPhase = ReversalPhase.REVERSAL_FORMING;
                    log.warn("[SensexReversalDetector] REVERSAL_FORMING: First bullish reversal candle detected. "
                            + "Drop={} pts, recovery={} pts. Gamma scalps enabled.",
                            String.format("%.0f", dropFromOpen), String.format("%.0f", recoveryFromLow));
                }
                break;

            case REVERSAL_FORMING:
                // Count additional reversal candles
                if (isReversalCandle(latestCandle)) {
                    reversalCandleCount++;
                }
                // Check for confirmation: recovery > X% of the drop
                double recoveryPct = (dropFromOpen > 0) ? (recoveryFromLow / dropFromOpen) * 100.0 : 0;
                if (recoveryPct >= confirmationRecoveryPct) {
                    currentPhase = ReversalPhase.REVERSAL_CONFIRMED;
                    log.warn("[SensexReversalDetector] REVERSAL_CONFIRMED: Sensex recovered {}% of {}-pt drop. "
                            + "Operator buying confirmed. {} reversal candles.",
                            String.format("%.0f", recoveryPct), String.format("%.0f", dropFromOpen), reversalCandleCount);
                }
                break;

            case REVERSAL_CONFIRMED:
                // Stay confirmed for the rest of the day
                if (isReversalCandle(latestCandle)) reversalCandleCount++;
                break;
        }
    }

    /**
     * Reset state at start of day (invoked by DailyResetService at midnight).
     */
    @Override
    public void resetDaily() {
        currentPhase = ReversalPhase.NONE;
        dropFromOpen = 0;
        recoveryFromLow = 0;
        reversalCandleCount = 0;
        intradayLow = Double.MAX_VALUE;
        openPrice = 0;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private boolean isReversalCandle(Candle candle) {
        double open = candle.open().doubleValue();
        double close = candle.close().doubleValue();
        double high = candle.high().doubleValue();
        double low = candle.low().doubleValue();

        double range = high - low;
        if (range <= 0) return false;

        // Bullish candle with body ≥ 40% of range
        double body = close - open;
        return body > 0 && (body / range) >= 0.40;
    }

    private void resetIfNeeded() {
        if (currentPhase != ReversalPhase.NONE) {
            resetDaily();
        }
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 35));
    }
}
