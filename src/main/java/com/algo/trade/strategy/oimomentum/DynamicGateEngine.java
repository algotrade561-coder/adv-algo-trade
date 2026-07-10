package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Gate Engine — volatility-conditioned threshold adjustment (LIVE).
 *
 * <p>Recomputes per-index entry/exit/sizing values every 30 minutes from the live market regime
 * (VIX + realised range). Values are read by the OI Momentum strategy and applied LIVE:
 * the entry bias-floor is nudged by the regime, and lot size is scaled by the conviction multiplier.</p>
 *
 * <h2>Safety (all enforced here)</h2>
 * <ul>
 *   <li><b>Hard clamps</b> — every value is bounded to an absolute [min,max] rail.</li>
 *   <li><b>Rate limiting</b> — per-parameter max step per recalculation (no full-range jumps).</li>
 *   <li><b>Trading-session guard</b> — recompute only Mon–Fri 09:15–15:30 IST (never weekends/off-hours,
 *       so a stale VIX can't set the regime when the market is closed).</li>
 *   <li><b>Staleness guard</b> — if a recalc hasn't landed recently, callers get NEUTRAL (= static config),
 *       so a stalled scheduler can't serve stale values forever.</li>
 *   <li><b>Neutral fallback</b> — disabled / no-data / stale ⇒ {@link DynamicValues#neutral()} which equals
 *       the static config, so the engine is behaviour-neutral until it has a real regime read.</li>
 *   <li><b>Master switch</b> — {@code oi-momentum.dynamic-gates.enabled} (default false).</li>
 * </ul>
 *
 * <p>The entry bias-floor is applied as a <b>delta from {@link #NEUTRAL_SIGNAL_SCORE}</b> (not an absolute),
 * so in the NORMAL regime the floor is unchanged; LOW_VOL tightens it, HIGH_VOL/EXTREME loosen it. This keeps
 * the engine's score scale decoupled from the strategy's actual bias-floor scale.</p>
 */
@Component
public class DynamicGateEngine {

    private static final Logger log = LoggerFactory.getLogger(DynamicGateEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Indices the engine conditions (VIX is market-wide; range is per-index). */
    private static final List<IndexType> TRADED = List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX);

    /** The NORMAL-regime signalScore baseline. The entry floor is adjusted by (value − this) so NORMAL = no change. */
    public static final double NEUTRAL_SIGNAL_SCORE = 55.0;

    // ── Hard clamps (absolute safety rails) ──
    private static final double MIN_SIGNAL_SCORE = 35.0, MAX_SIGNAL_SCORE = 75.0;
    // MAX_SL lowered 20→10 (2026-06-29): the engine had widened the stop to 15% in a low-ATR regime while the
    // scalp target floored at 3% — a 1:5 risk/reward that needs ~83% win-rate to break even (actual 56% → bled
    // -3.7%/trade). Capping the dynamic stop at 10% halves the worst-case loss; deeper stop/target coupling to be
    // validated against the now-clean tuning loop before tightening further.
    private static final double MIN_SL = 5.0,  MAX_SL = 10.0;
    private static final double MIN_TARGET = 10.0, MAX_TARGET = 60.0;
    private static final double MIN_TRAIL_ACT = 3.0, MAX_TRAIL_ACT = 15.0;
    private static final double MIN_TRAIL_GAP = 1.5, MAX_TRAIL_GAP = 8.0;
    private static final double MIN_LOT_MULT = 0.5, MAX_LOT_MULT = 1.5;
    private static final double MAX_RATE_CHANGE_PCT = 5.0; // max ±5% per recalc

    /** If no recalc has landed within this window, serve NEUTRAL (stalled-scheduler guard). */
    private static final Duration STALE_AFTER = Duration.ofMinutes(40);

    private final MarketGuard marketGuard;
    private final LiveInstrumentCache liveInstrumentCache;
    private final TickMomentumDetector momentumDetector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutcomeFeedbackEngine outcomeFeedbackEngine;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FeatureNormalizer featureNormalizer;

    @org.springframework.beans.factory.annotation.Value("${oi-momentum.dynamic-gates.enabled:true}")
    private boolean enabled;

    private final Map<IndexType, DynamicValues> currentByIndex = new ConcurrentHashMap<>();
    /** Serializes read-modify-write of {@link #currentByIndex} across recalculate + the relax crons,
     *  which run on separate scheduler threads. Without it a relaxation can be overwritten or stacked. */
    private final Object gateLock = new Object();
    private volatile Instant lastRecalcTime = null;

    /** Snapshot of all dynamically-derived gate values for one index. */
    public record DynamicValues(
            double signalScoreMin,
            double stopLossPercent,
            double targetPercent,
            double trailingActivationPercent,
            double trailingGapPercent,
            double lotSizeMultiplier, // 0.5 .. 1.5
            String regime             // LOW_VOL | NORMAL | HIGH_VOL | EXTREME | NEUTRAL
    ) {
        /** Neutral defaults (equivalent to static config — engine is behaviour-neutral here). */
        public static DynamicValues neutral() {
            return new DynamicValues(NEUTRAL_SIGNAL_SCORE, 12, 25, 12, 3, 1.0, "NEUTRAL");
        }
    }

    public DynamicGateEngine(MarketGuard marketGuard,
                             LiveInstrumentCache liveInstrumentCache,
                             TickMomentumDetector momentumDetector) {
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.momentumDetector = momentumDetector;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Current dynamic values for an index. Returns NEUTRAL when disabled, stale, or not yet computed. */
    public DynamicValues getValues(IndexType index) {
        if (!isActive()) return DynamicValues.neutral();
        DynamicValues v = currentByIndex.get(index);
        return v != null ? v : DynamicValues.neutral();
    }

    /** Back-compat: NIFTY values. */
    public DynamicValues getValues() {
        return getValues(IndexType.NIFTY);
    }

    /** Active = enabled AND a recalc landed recently (not stale). Callers gate live application on this. */
    public boolean isActive() {
        if (!enabled) return false;
        Instant t = lastRecalcTime;
        return t != null && Duration.between(t, Instant.now()).compareTo(STALE_AFTER) <= 0;
    }

    // ── Recalculation (every 30 min, trading session only) ──────────────────

    @Scheduled(fixedRate = 1_800_000, initialDelay = 300_000) // 30 min, 5 min initial delay
    public void recalculate() {
        if (!enabled) return;
        if (!isTradingSession()) return; // never compute on weekends / off-hours (stale VIX guard)

        try {
            double vix = marketGuard.getCurrentVix();
            if (vix <= 0) return; // no live VIX yet — keep previous values

            for (IndexType ix : TRADED) {
                double range = computeRange(ix);
                String regime = classifyRegime(vix, range);

                // Feed observations into the normalizer for z-score computation
                if (featureNormalizer != null) {
                    featureNormalizer.observe(regime, "vix", vix);
                    featureNormalizer.observe(regime, "range30m", range);
                }

                DynamicValues target = computeForRegime(regime);

                // Layer outcome-feedback adjustments (guarded, live)
                if (outcomeFeedbackEngine != null && outcomeFeedbackEngine.isEnabled()) {
                    var adj = outcomeFeedbackEngine.getAdjustment(regime);
                    if (adj.signalScoreDelta() != 0 || adj.lotMultiplier() != 1.0) {
                        target = new DynamicValues(
                                clamp(target.signalScoreMin() + adj.signalScoreDelta(), MIN_SIGNAL_SCORE, MAX_SIGNAL_SCORE),
                                target.stopLossPercent(),
                                target.targetPercent(),
                                target.trailingActivationPercent(),
                                target.trailingGapPercent(),
                                clamp(target.lotSizeMultiplier() * adj.lotMultiplier(), MIN_LOT_MULT, MAX_LOT_MULT),
                                regime);
                    }
                }

                DynamicValues prev;
                DynamicValues next;
                synchronized (gateLock) {
                    prev = currentByIndex.getOrDefault(ix, DynamicValues.neutral());
                    next = applyRateLimiting(prev, target);
                    currentByIndex.put(ix, next);
                }
                if (!next.regime().equals(prev.regime())) {
                    log.info("[DynamicGates][{}] regime {} → {} (VIX={}, range={}%) floorBase={} SL={}% trail={}/{}% lotMult={}",
                            ix, prev.regime(), regime, String.format("%.1f", vix), String.format("%.2f", range),
                            String.format("%.0f", next.signalScoreMin()), String.format("%.1f", next.stopLossPercent()),
                            String.format("%.1f", next.trailingActivationPercent()), String.format("%.1f", next.trailingGapPercent()),
                            String.format("%.2f", next.lotSizeMultiplier()));
                }
            }
            lastRecalcTime = Instant.now();
        } catch (Exception e) {
            log.debug("[DynamicGates] recalculation failed (keeping previous values): {}", e.getMessage());
        }
    }

    private boolean isTradingSession() {
        LocalTime t = LocalTime.now(IST);
        DayOfWeek d = LocalDate.now(IST).getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    // ── Regime classification + value derivation ────────────────────────────

    private String classifyRegime(double vix, double range30m) {
        if (vix > 20 || range30m > 0.8) return "EXTREME";
        if (vix > 16 || range30m > 0.5) return "HIGH_VOL";
        if (vix > 12 || range30m > 0.2) return "NORMAL";
        return "LOW_VOL";
    }

    private DynamicValues computeForRegime(String regime) {
        return switch (regime) {
            //                              score  SL  tgt  trAct trGap lotMult
            case "LOW_VOL"  -> new DynamicValues(55, 8,  15,  6,   2.0, 0.8, regime);
            case "HIGH_VOL" -> new DynamicValues(42, 12, 30, 12,   3.5, 1.3, regime);
            case "EXTREME"  -> new DynamicValues(38, 15, 45, 15,   4.5, 1.0, regime);
            default          -> new DynamicValues(48, 10, 22, 10,   2.5, 1.0, "NORMAL");
        };
    }

    // ── Rate limiting (per-parameter floor — fixes the small-range bypass) ───

    private DynamicValues applyRateLimiting(DynamicValues old, DynamicValues target) {
        return new DynamicValues(
                rl(old.signalScoreMin(),             target.signalScoreMin(),             MIN_SIGNAL_SCORE, MAX_SIGNAL_SCORE, 1.0),
                rl(old.stopLossPercent(),            target.stopLossPercent(),            MIN_SL,           MAX_SL,           0.5),
                rl(old.targetPercent(),              target.targetPercent(),              MIN_TARGET,       MAX_TARGET,       1.0),
                rl(old.trailingActivationPercent(),  target.trailingActivationPercent(),  MIN_TRAIL_ACT,    MAX_TRAIL_ACT,    0.5),
                rl(old.trailingGapPercent(),         target.trailingGapPercent(),         MIN_TRAIL_GAP,    MAX_TRAIL_GAP,    0.25),
                rl(old.lotSizeMultiplier(),          target.lotSizeMultiplier(),          MIN_LOT_MULT,     MAX_LOT_MULT,     0.05),
                target.regime());
    }

    /** Move {@code current} toward {@code target} by at most max(±5%, minStep), then clamp to [min,max]. */
    private double rl(double current, double target, double min, double max, double minStep) {
        double maxDelta = Math.max(Math.abs(current) * MAX_RATE_CHANGE_PCT / 100.0, minStep);
        double clamped = Math.max(current - maxDelta, Math.min(current + maxDelta, target));
        return Math.max(min, Math.min(max, clamped));
    }

    /** Simple clamp to [min, max]. */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double computeRange(IndexType ix) {
        double high = momentumDetector.getRolling30MinHigh(ix);
        double low = momentumDetector.getRolling30MinLow(ix);
        double spot = liveInstrumentCache.getFuturesPrice(ix);
        if (high > 0 && low > 0 && spot > 0) {
            return (high - low) / spot * 100.0;
        }
        return 0.2; // unknown ⇒ treat as NORMAL-ish range
    }

    // ── Intraday Activity Monitor ───────────────────────────────────────────
    // If no trades have been taken by midday (12:00), progressively relax gates.
    // Particularly important on Mondays and low-activity sessions where the bot
    // might otherwise sit idle despite tradeable conditions.

    /** Tracks intraday trade count per index (updated externally by the strategy). */
    private final Map<IndexType, java.util.concurrent.atomic.AtomicInteger> intradayTradeCount = new ConcurrentHashMap<>();

    /** Called by OIMomentumStrategy after each trade to update activity tracking. */
    public void recordTrade(IndexType indexType) {
        intradayTradeCount.computeIfAbsent(indexType, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }

    /** Reset at day start. */
    public void resetDay() {
        intradayTradeCount.values().forEach(c -> c.set(0));
        activityRelaxApplied = false;
    }

    private volatile boolean activityRelaxApplied = false;

    /**
     * Intraday activity check: runs every 30 min (piggybacks on recalculate schedule).
     * If total trades across all indices < 3 by 12:00, apply floor relaxation.
     * If total trades < 5 by 14:00, apply stronger relaxation.
     */
    @Scheduled(cron = "0 0 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void middayActivityCheck() {
        if (!enabled) return;
        int totalTrades = intradayTradeCount.values().stream()
                .mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        if (totalTrades < 3 && !activityRelaxApplied) {
            activityRelaxApplied = true;
            // Relax all current values by lowering signal score floor
            for (IndexType ix : TRADED) {
                synchronized (gateLock) {
                    DynamicValues current = currentByIndex.getOrDefault(ix, DynamicValues.neutral());
                    DynamicValues relaxed = new DynamicValues(
                            clamp(current.signalScoreMin() - 5, MIN_SIGNAL_SCORE, MAX_SIGNAL_SCORE),
                            current.stopLossPercent(),
                            current.targetPercent(),
                            current.trailingActivationPercent(),
                            current.trailingGapPercent(),
                            clamp(current.lotSizeMultiplier() * 1.1, MIN_LOT_MULT, MAX_LOT_MULT),
                            current.regime());
                    currentByIndex.put(ix, relaxed);
                }
            }
            log.info("[DynamicGates] MIDDAY_ACTIVITY_RELAX: only {} trades by 12:00 — floor lowered by 5, lots +10%",
                    totalTrades);
        }
    }

    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void afternoonActivityCheck() {
        if (!enabled) return;
        int totalTrades = intradayTradeCount.values().stream()
                .mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        if (totalTrades < 5) {
            for (IndexType ix : TRADED) {
                synchronized (gateLock) {
                    DynamicValues current = currentByIndex.getOrDefault(ix, DynamicValues.neutral());
                    DynamicValues relaxed = new DynamicValues(
                            clamp(current.signalScoreMin() - 3, MIN_SIGNAL_SCORE, MAX_SIGNAL_SCORE),
                            current.stopLossPercent(),
                            current.targetPercent(),
                            current.trailingActivationPercent(),
                            clamp(current.trailingGapPercent() - 0.5, MIN_TRAIL_GAP, MAX_TRAIL_GAP),
                            clamp(current.lotSizeMultiplier() * 1.1, MIN_LOT_MULT, MAX_LOT_MULT),
                            current.regime());
                    currentByIndex.put(ix, relaxed);
                }
            }
            log.info("[DynamicGates] AFTERNOON_ACTIVITY_RELAX: only {} trades by 14:00 — further relaxation applied",
                    totalTrades);
        }
    }

    // ── Intraday Mini-Log (every 2 hours) ───────────────────────────────────

    @Scheduled(cron = "0 15 11,13,15 * * MON-FRI", zone = "Asia/Kolkata")
    public void intradayMiniLog() {
        if (!enabled || !isActive()) return;
        int totalTrades = intradayTradeCount.values().stream()
                .mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[DynamicGates] INTRADAY_LOG trades=%d |", totalTrades));
        for (IndexType ix : TRADED) {
            DynamicValues v = currentByIndex.getOrDefault(ix, DynamicValues.neutral());
            sb.append(String.format(" %s[%s floor=%.0f SL=%.1f trail=%.1f/%.1f lot=%.2f]",
                    ix.name(), v.regime(), v.signalScoreMin(), v.stopLossPercent(),
                    v.trailingActivationPercent(), v.trailingGapPercent(), v.lotSizeMultiplier()));
        }
        if (outcomeFeedbackEngine != null && outcomeFeedbackEngine.isEnabled()) {
            var snap = outcomeFeedbackEngine.snapshot();
            snap.forEach((regime, adj) -> {
                if (adj.signalScoreDelta() != 0) {
                    sb.append(String.format(" | OF[%s Δscore=%.1f lotM=%.2f E[R]=%.2f n=%d]",
                            regime, adj.signalScoreDelta(), adj.lotMultiplier(), adj.expectancyR(), adj.sampleSize()));
                }
            });
        }
        log.info("{}", sb);
    }
}
