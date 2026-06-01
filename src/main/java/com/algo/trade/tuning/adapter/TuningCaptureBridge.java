package com.algo.trade.tuning.adapter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Phase 3 centralized dual-write hook for generic pipeline strategies. Called after
 * legacy {@code entry-signals.csv} recording; no-ops when capture is OFF, the strategy
 * has no {@link TuningPipelineCapture} adapter, or the strategy uses inline dual-write
 * (OI Momentum / OI Shift Trap).
 */
@Service
public class TuningCaptureBridge {

    private static final Logger log = LoggerFactory.getLogger(TuningCaptureBridge.class);

    private static final Set<StrategyType> INLINE_DUAL_WRITE = EnumSet.of(
            StrategyType.OI_MOMENTUM,
            StrategyType.OI_SHIFT_TRAP);

    /** Pipeline strategies that use {@link MaeMfeTracker} + exit dual-write. */
    public static final Set<StrategyType> PIPELINE_DUAL_WRITE = EnumSet.of(
            StrategyType.DIRECTIONAL_BUY,
            StrategyType.MOMENTUM,
            StrategyType.SCALPING,
            StrategyType.REVERSAL_BUY,
            StrategyType.VOLATILITY_BREAKOUT,
            StrategyType.GAP_AND_GO,
            StrategyType.EVENT_DRIVEN_BUY,
            StrategyType.EXPIRY_GAMMA,
            StrategyType.EXPIRY_REVERSAL,
            StrategyType.ITM_CONVICTION);

    /** Spread strategies tracked via combined net premium ({@link MaeMfeTracker#onSpreadEntry}). */
    public static final Set<StrategyType> SPREAD_MAE_MFE = EnumSet.copyOf(
            SpreadCaptureSupport.SPREAD_CAPTURE_STRATEGIES);

    private final TuningEventRecorder recorder;
    private final Map<StrategyType, TuningPipelineCapture> captures = new EnumMap<>(StrategyType.class);
    private final Map<StrategyType, EpisodeAggregator<IndexType, String, SignalRecordContext>> evalAggregators =
            new EnumMap<>(StrategyType.class);

    @Autowired
    public TuningCaptureBridge(TuningEventRecorder recorder,
                               List<TuningPipelineCapture> pipelineCaptures) {
        this.recorder = recorder;
        for (TuningPipelineCapture capture : pipelineCaptures) {
            StrategyType strategy = capture.strategy();
            if (captures.put(strategy, capture) != null) {
                throw new IllegalStateException(
                        "Duplicate TuningPipelineCapture for " + strategy);
            }
            evalAggregators.put(strategy,
                    new EpisodeAggregator<>(
                            java.time.Duration.ofSeconds(capture.defaultEpisodeWindowSec()),
                            java.time.Duration.ofSeconds(capture.defaultEpisodeWindowSec())));
        }
    }

    /** Test-friendly constructor with explicit captures. */
    TuningCaptureBridge(TuningEventRecorder recorder,
                        Map<StrategyType, TuningPipelineCapture> pipelineCaptures) {
        this.recorder = recorder;
        this.captures.putAll(pipelineCaptures);
        for (TuningPipelineCapture capture : pipelineCaptures.values()) {
            evalAggregators.put(capture.strategy(),
                    new EpisodeAggregator<>(
                            java.time.Duration.ofSeconds(capture.defaultEpisodeWindowSec()),
                            java.time.Duration.ofSeconds(capture.defaultEpisodeWindowSec())));
        }
    }

    /**
     * Dual-writes one evaluation to the unified tuning pipeline when a
     * {@link TuningPipelineCapture} adapter is registered for the strategy.
     */
    public void record(SignalRecordContext ctx) {
        if (ctx == null || ctx.strategyType() == null || ctx.underlying() == null) {
            countDrop("ctx_null_or_missing_fields", ctx == null ? "<null>" : ctx.strategyType());
            return;
        }
        StrategyType strategy;
        try {
            strategy = StrategyType.valueOf(ctx.strategyType());
        } catch (IllegalArgumentException ex) {
            countDrop("strategy_enum_invalid", ctx.strategyType());
            return;
        }
        if (INLINE_DUAL_WRITE.contains(strategy)) {
            return;
        }
        TuningPipelineCapture capture = captures.get(strategy);
        if (capture == null) {
            countDrop("no_pipeline_adapter_registered", strategy.name());
            return;
        }
        if (firstHitLogged.add(strategy)) {
            log.info("[TuningCaptureBridge] first invocation for {} — adapter={} captures.size={}",
                    strategy, capture.getClass().getSimpleName(), captures.size());
        }

        try {
            String correlationKey = correlationKey(ctx);
            StrategyDecision decision = ctx.decision();
            Instant eventTime = decision != null && decision.timestamp() != null
                    ? decision.timestamp()
                    : Instant.now();

            if (isFiredSignal(decision)) {
                recorder.record(capture.buildSignalEvent(ctx, correlationKey));
                return;
            }

            if (capture.cadence() == CadenceHint.LOW) {
                recorder.record(capture.buildImmediateEvaluationEvent(ctx, eventTime));
                return;
            }

            IndexType index = IndexType.from(ctx.underlying());
            String blocker = capture.normalizeBlocker(
                    ctx.firstFailedFilter() != null && !ctx.firstFailedFilter().isBlank()
                            ? ctx.firstFailedFilter()
                            : inferBlocker(decision));
            EpisodeAggregator<IndexType, String, SignalRecordContext> aggregator =
                    evalAggregators.get(strategy);
            for (EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row
                    : aggregator.record(index, blocker, ctx, eventTime)) {
                recorder.record(capture.buildEvaluationEvent(row));
            }
        } catch (Exception ex) {
            log.warn("[TuningCaptureBridge] dual-write failed for {} (non-fatal): {}",
                    strategy, ex.getMessage());
        }
    }

    /**
     * Dual-writes an {@link ExitEvent} when a pipeline-strategy trade closes and
     * {@link MaeMfeTracker#onExit} has already been called by the exit monitor.
     */
    public void recordExit(TradeEntity trade,
                           MaeMfeTracker.Snapshot snapshot,
                           BigDecimal exitPrice,
                           String exitReason,
                           boolean reversal) {
        if (trade == null || trade.getStrategyType() == null || trade.getStrategyType().isBlank()) {
            return;
        }
        StrategyType strategy;
        try {
            strategy = StrategyType.valueOf(trade.getStrategyType());
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (INLINE_DUAL_WRITE.contains(strategy) || !captures.containsKey(strategy)) {
            return;
        }
        try {
            IndexType index = IndexType.fromName(trade.getUnderlying());
            String correlationKey = snapshot != null
                    && snapshot.entry().correlationKey() != null
                    && !snapshot.entry().correlationKey().isBlank()
                    ? snapshot.entry().correlationKey()
                    : trade.getTradeId();
            ExitEvent event = PipelineCaptureExitSupport.buildExitEvent(
                    strategy, index, trade, snapshot, correlationKey, exitPrice, exitReason, reversal);
            recorder.record(event);
        } catch (Exception ex) {
            log.warn("[TuningCaptureBridge] exit dual-write failed for {} (non-fatal): {}",
                    strategy, ex.getMessage());
        }
    }

    /**
     * Dual-writes a spread {@link ExitEvent} when a position group closes and
     * {@link MaeMfeTracker#onSpreadExit} has already been called.
     */
    public void recordSpreadExit(PositionGroup group,
                                 MaeMfeTracker.Snapshot snapshot,
                                 java.util.Map<String, BigDecimal> exitPrices,
                                 String exitReason) {
        if (group == null || group.strategyType() == null) {
            return;
        }
        StrategyType strategy = group.strategyType();
        if (!captures.containsKey(strategy)) {
            return;
        }
        try {
            IndexType index = IndexType.from(group.underlying());
            String correlationKey = group.groupId();
            ExitEvent event = PipelineCaptureExitSupport.buildSpreadExitEvent(
                    strategy, index, group, snapshot, correlationKey, exitPrices, exitReason);
            recorder.record(event);
        } catch (Exception ex) {
            log.warn("[TuningCaptureBridge] spread exit dual-write failed for {} (non-fatal): {}",
                    strategy, ex.getMessage());
        }
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 45_000)
    public void flushExpiredEpisodes() {
        Instant now = Instant.now();
        for (var entry : evalAggregators.entrySet()) {
            TuningPipelineCapture capture = captures.get(entry.getKey());
            if (capture == null || capture.cadence() == CadenceHint.LOW) {
                continue;
            }
            try {
                for (EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row
                        : entry.getValue().flushExpired(now)) {
                    recorder.record(capture.buildEvaluationEvent(row));
                }
            } catch (Exception ex) {
                log.warn("[TuningCaptureBridge] episode flush failed for {} (non-fatal): {}",
                        entry.getKey(), ex.getMessage());
            }
        }
    }

    static String correlationKey(SignalRecordContext ctx) {
        if (ctx.decision() != null) {
            String key = SignalDecisionKey.from(ctx.decision());
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        String raw = ctx.strategyType() + "|" + ctx.underlying() + "|" + Instant.now();
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    public static boolean isFiredSignal(StrategyDecision decision) {
        if (decision == null) {
            return false;
        }
        String name = decision.signalType().name();
        return name.startsWith("BUY_") || name.startsWith("SELL_");
    }

    private static String inferBlocker(StrategyDecision decision) {
        if (decision == null || decision.reasons() == null || decision.reasons().isEmpty()) {
            return "unknown";
        }
        return com.algo.trade.reporting.SignalTuningFilterUtils.inferFailedFilter(
                String.join("; ", decision.reasons()));
    }

    // ── Drop diagnostics ─────────────────────────────────────────────────

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            droppedByReason = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            lastLoggedMs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<StrategyType> firstHitLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long DROP_LOG_THROTTLE_MS = 30_000L;

    /** Throttled WARN per (reason, strategy) every 30s. */
    private void countDrop(String reason, String strategy) {
        String key = reason + "|" + (strategy == null ? "<null>" : strategy);
        droppedByReason.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicLong last =
                lastLoggedMs.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong(0));
        long prev = last.get();
        if (now - prev >= DROP_LOG_THROTTLE_MS && last.compareAndSet(prev, now)) {
            long count = droppedByReason.get(key).get();
            log.warn("[TuningCaptureBridge] DROPPED reason={} strategy={} count_so_far={} captures.size={}",
                    reason, strategy, count, captures.size());
        }
    }

    public java.util.Map<String, Long> droppedSnapshot() {
        java.util.Map<String, Long> snap = new java.util.LinkedHashMap<>();
        droppedByReason.forEach((k, v) -> snap.put(k, v.get()));
        return snap;
    }
}
