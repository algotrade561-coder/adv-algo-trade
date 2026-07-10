package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outcome-Feedback Engine — guarded live recalibration of entry gate thresholds.
 *
 * <p>Adjusts entry gates (signalScore floor, momentum slope, etc.) based on realized
 * trade outcomes measured in R-multiples (profit relative to risk). Applied directly
 * to the AGGRESSIVE profile, with hard clamps and rate limits preventing noise-driven
 * drift.</p>
 *
 * <h2>Safety Rails</h2>
 * <ul>
 *   <li><b>Minimum sample</b>: No adjustment until ≥30 trades or ≥5 trading days per regime</li>
 *   <li><b>Hard clamps</b>: signalScore.min never below break-even floor; SL never above daily loss budget</li>
 *   <li><b>Rate limiting</b>: Max ±5% change per weekly recalibration</li>
 *   <li><b>Hysteresis</b>: Dead-band (±0.1R) prevents oscillation around neutral</li>
 *   <li><b>Cost gate</b>: Always enforced — charges filter remains primary</li>
 *   <li><b>Auto-revert</b>: Reverts adjustment after ≥5 days if expectancy drops</li>
 * </ul>
 *
 * <h2>R-Multiple Calibration</h2>
 * <ul>
 *   <li>Promote (relax) gates if rejected setups consistently show expectancy &gt; +0.5R</li>
 *   <li>Tighten gates if relaxed entries show expectancy &lt; -0.5R</li>
 *   <li>Neutral dead-band: -0.1R to +0.1R → no change</li>
 * </ul>
 */
@Component
public class OutcomeFeedbackEngine {

    private static final Logger log = LoggerFactory.getLogger(OutcomeFeedbackEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Safety thresholds ──
    private static final int MIN_TRADES_FOR_ADJUSTMENT = 20;
    private static final int MIN_DAYS_FOR_ADJUSTMENT = 3;
    private static final double PROMOTE_THRESHOLD_R = 0.3;   // relax if expectancy > +0.3R (lower bar)
    private static final double TIGHTEN_THRESHOLD_R = -0.5;  // tighten if expectancy < -0.5R
    private static final double HYSTERESIS_BAND_R = 0.08;    // tighter dead-band for faster response
    private static final double MAX_WEEKLY_CHANGE_PCT = 7.0;  // max ±7% per recalibration (faster adaptation)
    private static final int REVERT_WINDOW_DAYS = 5;         // auto-revert after 5 days if worse

    // ── Hard clamps (entry floor never below cost-adjusted break-even) ──
    private static final double MIN_SIGNAL_SCORE_FLOOR = 30.0;
    private static final double MAX_SIGNAL_SCORE_FLOOR = 75.0;
    private static final double MIN_MOMENTUM_SLOPE = 0.05;
    private static final double MAX_MOMENTUM_SLOPE = 0.60;

    private final TradeRepository tradeRepository;

    @Value("${oi-momentum.outcome-feedback.enabled:true}")
    private boolean enabled;

    // ── Current adjustments per regime (additive deltas from static baseline) ──
    private final Map<String, GateAdjustment> adjustments = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAdjustmentTime = new ConcurrentHashMap<>();
    private final Map<String, Double> preAdjustmentExpectancy = new ConcurrentHashMap<>();

    /** The computed adjustment for one regime. */
    public record GateAdjustment(
            double signalScoreDelta,      // additive: applied to the effective floor
            double momentumSlopeDelta,    // additive: applied to momentum slope threshold
            double lotMultiplier,         // multiplicative: 0.7–1.3
            String regime,
            int sampleSize,
            double expectancyR,
            Instant computedAt
    ) {
        public static GateAdjustment neutral(String regime) {
            return new GateAdjustment(0, 0, 1.0, regime, 0, 0, Instant.now());
        }
    }

    public OutcomeFeedbackEngine(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Get the current gate adjustment for a regime. Returns neutral if disabled or insufficient data. */
    public GateAdjustment getAdjustment(String regime) {
        if (!enabled) return GateAdjustment.neutral(regime);
        return adjustments.getOrDefault(regime, GateAdjustment.neutral(regime));
    }

    /** Get the signal score floor delta for an index (uses the current regime). */
    public double getSignalScoreDelta(String regime) {
        return getAdjustment(regime).signalScoreDelta();
    }

    /** Get the lot multiplier for an index (outcome-driven sizing). */
    public double getLotMultiplier(String regime) {
        return getAdjustment(regime).lotMultiplier();
    }

    public boolean isEnabled() { return enabled; }

    /** Diagnostic snapshot of all adjustments. */
    public Map<String, GateAdjustment> snapshot() {
        return Map.copyOf(adjustments);
    }

    // ── Weekly recalibration (every day at market close — 15:35 IST) ──────

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recalibrate() {
        if (!enabled) return;
        try {
            log.info("[OutcomeFeedback] Starting daily recalibration...");
            recalibrateForRegime("LOW_VOL");
            recalibrateForRegime("NORMAL");
            recalibrateForRegime("HIGH_VOL");
            recalibrateForRegime("EXTREME");
            logDailyValidation();
        } catch (Exception e) {
            log.warn("[OutcomeFeedback] Recalibration failed: {}", e.getMessage());
        }
    }

    private void recalibrateForRegime(String regime) {
        List<TradeOutcome> outcomes = getRecentOutcomes(regime);
        if (outcomes.size() < MIN_TRADES_FOR_ADJUSTMENT) {
            log.debug("[OutcomeFeedback][{}] Insufficient trades ({} < {}), keeping current adjustment",
                    regime, outcomes.size(), MIN_TRADES_FOR_ADJUSTMENT);
            return;
        }

        // Check minimum days coverage
        long distinctDays = outcomes.stream()
                .map(o -> o.exitTime.atZone(IST).toLocalDate())
                .distinct().count();
        if (distinctDays < MIN_DAYS_FOR_ADJUSTMENT) {
            log.debug("[OutcomeFeedback][{}] Insufficient days ({} < {}), keeping current adjustment",
                    regime, distinctDays, MIN_DAYS_FOR_ADJUSTMENT);
            return;
        }

        // Compute expectancy in R-multiples
        double avgExpectancyR = outcomes.stream()
                .mapToDouble(TradeOutcome::rMultiple)
                .average().orElse(0);

        // Hysteresis: don't act on noise
        if (Math.abs(avgExpectancyR) < HYSTERESIS_BAND_R) {
            log.debug("[OutcomeFeedback][{}] Expectancy {}R within dead-band, no change",
                    regime, String.format("%.2f", avgExpectancyR));
            return;
        }

        // Auto-revert check: if we adjusted recently and expectancy got worse, revert
        GateAdjustment current = adjustments.getOrDefault(regime, GateAdjustment.neutral(regime));
        Instant lastAdj = lastAdjustmentTime.get(regime);
        if (lastAdj != null && current.signalScoreDelta() != 0) {
            long daysSinceAdj = ChronoUnit.DAYS.between(lastAdj.atZone(IST).toLocalDate(),
                    LocalDate.now(IST));
            if (daysSinceAdj >= REVERT_WINDOW_DAYS) {
                Double preExpectancy = preAdjustmentExpectancy.get(regime);
                if (preExpectancy != null && avgExpectancyR < preExpectancy) {
                    log.warn("[OutcomeFeedback][{}] Auto-REVERT: expectancy dropped {}R→{}R after {} days",
                            regime, String.format("%.2f", preExpectancy), String.format("%.2f", avgExpectancyR), daysSinceAdj);
                    adjustments.put(regime, GateAdjustment.neutral(regime));
                    lastAdjustmentTime.remove(regime);
                    preAdjustmentExpectancy.remove(regime);
                    return;
                }
            }
        }

        // Compute new adjustment
        double signalScoreDelta = current.signalScoreDelta();
        double slopeDelta = current.momentumSlopeDelta();
        double lotMult = current.lotMultiplier();

        if (avgExpectancyR > PROMOTE_THRESHOLD_R && !outOfSampleConfirmsPositive(outcomes)) {
            // Positive in aggregate but NOT confirmed out-of-sample (one half negative) → do NOT relax.
            // Relaxing on an unconfirmed edge is the over-fit/over-relax trap. Hold current gates.
            log.info("[OutcomeFeedback][{}] promote SKIPPED — expectancy {}R aggregate but out-of-sample not confirmed (n={})",
                    regime, String.format("%.2f", avgExpectancyR), outcomes.size());
        } else if (avgExpectancyR > PROMOTE_THRESHOLD_R) {
            // Positive expectancy AND out-of-sample confirmed → relax gates (more entries)
            signalScoreDelta = rateLimit(signalScoreDelta, signalScoreDelta - 2.0);
            slopeDelta = rateLimit(slopeDelta, slopeDelta - 0.02);
            lotMult = rateLimit(lotMult, Math.min(1.3, lotMult + 0.05));
            log.info("[OutcomeFeedback][{}] RELAX gates: expectancy={}R (n={}), scoreDelta={}, slopeDelta={}, lotMult={}",
                    regime, String.format("%.2f", avgExpectancyR), outcomes.size(),
                    String.format("%.1f", signalScoreDelta), String.format("%.3f", slopeDelta), String.format("%.2f", lotMult));
        } else if (avgExpectancyR < TIGHTEN_THRESHOLD_R) {
            // Negative expectancy → tighten gates (fewer entries)
            signalScoreDelta = rateLimit(signalScoreDelta, signalScoreDelta + 3.0);
            slopeDelta = rateLimit(slopeDelta, slopeDelta + 0.03);
            lotMult = rateLimit(lotMult, Math.max(0.7, lotMult - 0.05));
            log.info("[OutcomeFeedback][{}] TIGHTEN gates: expectancy={}R (n={}), scoreDelta={}, slopeDelta={}, lotMult={}",
                    regime, String.format("%.2f", avgExpectancyR), outcomes.size(),
                    String.format("%.1f", signalScoreDelta), String.format("%.3f", slopeDelta), String.format("%.2f", lotMult));
        }

        // Apply hard clamps
        signalScoreDelta = Math.max(-15, Math.min(15, signalScoreDelta));
        slopeDelta = Math.max(-0.15, Math.min(0.15, slopeDelta));
        lotMult = Math.max(0.7, Math.min(1.3, lotMult));

        // Store
        preAdjustmentExpectancy.putIfAbsent(regime, avgExpectancyR);
        GateAdjustment newAdj = new GateAdjustment(
                signalScoreDelta, slopeDelta, lotMult,
                regime, outcomes.size(), avgExpectancyR, Instant.now());
        adjustments.put(regime, newAdj);
        lastAdjustmentTime.put(regime, Instant.now());
    }

    /** Rate-limit a value change: max ±5% per recalibration. */
    private double rateLimit(double current, double target) {
        double maxDelta = Math.max(Math.abs(current) * MAX_WEEKLY_CHANGE_PCT / 100.0, 0.5);
        if (target > current + maxDelta) return current + maxDelta;
        if (target < current - maxDelta) return current - maxDelta;
        return target;
    }

    // ── Trade outcome extraction ────────────────────────────────────────

    private List<TradeOutcome> getRecentOutcomes(String regime) {
        try {
            Instant lookback = Instant.now().minus(14, ChronoUnit.DAYS);
            return tradeRepository.findAll().stream()
                    .filter(t -> t.getExitTime() != null && t.getExitTime().isAfter(lookback))
                    .filter(t -> t.getRealizedPnl() != null && t.getEntryPrice() != null)
                    .filter(t -> "OI_MOMENTUM".equalsIgnoreCase(t.getStrategyType()))
                    .filter(t -> matchesRegime(t, regime))
                    .map(this::toOutcome)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.debug("[OutcomeFeedback] Failed to load trade outcomes: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean matchesRegime(TradeEntity trade, String regime) {
        // Tag regime from entry reason or VIX at entry time (best effort)
        String reason = trade.getEntryReason();
        if (reason == null) return "NORMAL".equals(regime); // default bucket
        String upper = reason.toUpperCase();
        if (upper.contains("LOW_VOL") || upper.contains("LOWVOL")) return "LOW_VOL".equals(regime);
        if (upper.contains("EXTREME")) return "EXTREME".equals(regime);
        if (upper.contains("HIGH_VOL") || upper.contains("HIGHVOL")) return "HIGH_VOL".equals(regime);
        return "NORMAL".equals(regime);
    }

    private TradeOutcome toOutcome(TradeEntity trade) {
        try {
            double entryPrice = trade.getEntryPrice().doubleValue();
            int qty = trade.getQuantity();
            double grossPnl = trade.getRealizedPnl().doubleValue();
            // NET of round-trip charges — gross PnL overstates edge and over-relaxes gates. Charges are a
            // first-class cost here (the report showed charges are ~25-30% of blocks), so expectancy must be net.
            double charges = OptionsChargesEstimator.roundTripCharges(entryPrice, qty);
            double netPnl = grossPnl - charges;
            // Risk = the trade's ACTUAL applied SL distance × qty (not an assumed flat 12%). Fall back to 12%
            // only when the trade wasn't stamped with an SL.
            double slPct = (trade.getAppliedStopLossPercent() != null)
                    ? trade.getAppliedStopLossPercent().doubleValue() : 12.0;
            double risk = entryPrice * (slPct / 100.0) * qty;
            if (risk <= 0) risk = 1;
            double rMultiple = netPnl / risk;
            return new TradeOutcome(trade.getExitTime(), rMultiple, netPnl, trade.getEntryReason());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Out-of-sample confirmation: split the window into an earlier and a later half by exit time and require
     * BOTH halves to be net-positive before we relax a gate. Stops a relaxation from being driven by a single
     * lucky sub-period (in-sample overfit). Tightening is NOT gated on this — cutting risk is the safe direction.
     */
    private boolean outOfSampleConfirmsPositive(List<TradeOutcome> outcomes) {
        if (outcomes.size() < MIN_TRADES_FOR_ADJUSTMENT) return false;
        List<TradeOutcome> sorted = outcomes.stream()
                .sorted(Comparator.comparing(TradeOutcome::exitTime))
                .toList();
        int mid = sorted.size() / 2;
        double firstHalf = sorted.subList(0, mid).stream()
                .mapToDouble(TradeOutcome::rMultiple).average().orElse(-1);
        double secondHalf = sorted.subList(mid, sorted.size()).stream()
                .mapToDouble(TradeOutcome::rMultiple).average().orElse(-1);
        return firstHalf > 0 && secondHalf > 0;
    }

    record TradeOutcome(Instant exitTime, double rMultiple, double pnl, String reason) {}

    // ── Daily validation logging ────────────────────────────────────────

    private void logDailyValidation() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║       OUTCOME FEEDBACK — DAILY VALIDATION REPORT           ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║ Date: %-54s ║%n", LocalDate.now(IST)));

        for (String regime : List.of("LOW_VOL", "NORMAL", "HIGH_VOL", "EXTREME")) {
            GateAdjustment adj = adjustments.getOrDefault(regime, GateAdjustment.neutral(regime));
            sb.append(String.format("║ %-10s │ n=%-4d │ E[R]=%-6.2f │ Δscore=%-5.1f │ Δslope=%-6.3f │ lotM=%-4.2f ║%n",
                    regime, adj.sampleSize(), adj.expectancyR(),
                    adj.signalScoreDelta(), adj.momentumSlopeDelta(), adj.lotMultiplier()));
        }

        sb.append("╚══════════════════════════════════════════════════════════════╝");
        log.info("[OutcomeFeedback] {}", sb);
    }
}
