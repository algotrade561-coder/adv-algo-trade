package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.tuning.adapter.strategies.OiShiftTrapCaptureAdapter;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import com.algo.trade.domain.IndexType;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 7 — real implementation of OI Shift Trap capture for the unified
 * tuning pipeline. Replaces the Phase 6 no-op stub which was silently
 * dropping every reject + signal.
 *
 * <p>OI Shift Trap is intentionally NOT routed through
 * {@code TuningCaptureBridge} (it's listed in {@code INLINE_DUAL_WRITE} so
 * the bridge skips it). Instead, this class composes the same primitives
 * the bridge would: a per-blocker episode aggregator, the existing
 * {@link OiShiftTrapCaptureAdapter}, and {@link TuningEventRecorder}.</p>
 *
 * <p>Behaviour mirrors {@code OIMomentumStrategy}'s inline dual-write —
 * 60s gap window AND max-age cap so a continuously-hit blocker still
 * produces a CSV row every 60 seconds (fixes the "stuck blocker hides
 * forever" pattern).</p>
 */
@Service
public class OiShiftTrapTuneRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapTuneRecorder.class);

    @Autowired(required = false)
    private TuningEventRecorder tuningEventRecorder;

    @Autowired(required = false)
    private OiShiftTrapCaptureAdapter adapter;

    /**
     * Per-(underlying, blocker) episode aggregator. Stream key is the
     * underlying name; dedup key is the normalized blocker. Window AND
     * max-age both 60s.
     */
    private final EpisodeAggregator<String, String, OiShiftTrapDiagnostics> aggregator =
            new EpisodeAggregator<>(Duration.ofSeconds(60), Duration.ofSeconds(60));

    /** Latched once we log wiring status. */
    private volatile boolean firstCallLogged = false;

    /** Scan was blocked before evaluation even ran. Records a SCAN_CONTEXT blocked episode. */
    public void recordScanBlocked(String underlying, String outcome, String blocker, BigDecimal spot) {
        logFirstCallStatus();
        if (tuningEventRecorder == null || adapter == null) return;
        OiShiftTrapDiagnostics blockedDiag = OiShiftTrapDiagnostics.blocked(
                underlying, outcome, blocker, spot);
        feedAggregator(underlying, blocker, blockedDiag);
    }

    /** Standard per-tick evaluation outcome. Aggregator-deduplicated. */
    public void recordEvaluation(OiShiftTrapDiagnostics diag) {
        logFirstCallStatus();
        if (tuningEventRecorder == null || adapter == null || diag == null) return;
        String underlying = diag.underlying();
        String blocker = diag.primaryBlocker() == null || diag.primaryBlocker().isBlank()
                ? diag.outcome() : diag.primaryBlocker();
        feedAggregator(underlying, blocker, diag);
    }

    /** Signal fired — bypass aggregator and emit immediately as a SIGNAL event. */
    public void recordSignal(StrategyDecision decision,
                             OiShiftTrapDiagnostics diag,
                             List<Candle> underlyingCandles) {
        logFirstCallStatus();
        if (tuningEventRecorder == null || adapter == null || decision == null) return;
        try {
            String correlationKey = SignalDecisionKey.from(decision);
            tuningEventRecorder.record(adapter.buildSignalEvent(
                    decision, diag, correlationKey,
                    underlyingCandles != null ? underlyingCandles : List.of()));
        } catch (Exception ex) {
            log.warn("[OiShiftTrapTuneRecorder] signal record failed (non-fatal): {}", ex.getMessage());
        }
    }

    /**
     * Phase 5+ — emit a tuning {@code ExitEvent} when an OI Shift Trap trade closes.
     * Builds the event via {@link OiShiftTrapCaptureAdapter#buildExitEvent} and writes
     * through {@link TuningEventRecorder}. {@code trapAttrs} carries strategy-specific
     * context (e.g. OI unwind evidence: drop%, entry/current OI, minutes since entry,
     * signal-path tag derived from entry reason). NO-OP when wiring is missing or the
     * trade isn't OI_SHIFT_TRAP.
     */
    public void recordExit(TradeEntity trade,
                            BigDecimal exitPrice,
                            String exitReason,
                            MaeMfeTracker.Snapshot maeSnapshot,
                            Map<String, Object> trapAttrs) {
        logFirstCallStatus();
        if (tuningEventRecorder == null || adapter == null || trade == null) return;
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) return;
        try {
            String correlationKey = trade.getTradeId();
            IndexType index = IndexType.fromName(trade.getUnderlying());
            tuningEventRecorder.record(adapter.buildExitEvent(
                    index, trade, maeSnapshot, correlationKey,
                    exitPrice, exitReason, /* reversal= */ false, trapAttrs));
        } catch (Exception ex) {
            log.warn("[OiShiftTrapTuneRecorder] exit record failed (non-fatal): {}",
                    ex.getMessage());
        }
    }

    /**
     * Periodic flush of expired episodes so a strategy that goes quiet still
     * surfaces its last episode to disk within ~30 seconds.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flushExpired() {
        if (tuningEventRecorder == null || adapter == null) return;
        try {
            for (var row : aggregator.flushExpired(Instant.now())) {
                tuningEventRecorder.record(adapter.buildEvaluationEvent(row));
            }
        } catch (Exception ex) {
            log.debug("[OiShiftTrapTuneRecorder] flushExpired failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Push to aggregator; immediately write any flushed rows (key change / max-age). */
    private void feedAggregator(String underlying, String rawBlocker,
                                 OiShiftTrapDiagnostics diag) {
        try {
            String normalized = adapter.normalizeBlocker(rawBlocker);
            for (var row : aggregator.record(underlying, normalized, diag, Instant.now())) {
                tuningEventRecorder.record(adapter.buildEvaluationEvent(row));
            }
        } catch (Exception ex) {
            log.warn("[OiShiftTrapTuneRecorder] evaluation record failed (non-fatal): {}",
                    ex.getMessage());
        }
    }

    private void logFirstCallStatus() {
        if (firstCallLogged) return;
        firstCallLogged = true;
        log.info("[OiShiftTrapTuneRecorder] wiring status: recorder={} adapter={}",
                tuningEventRecorder != null ? "WIRED" : "NULL",
                adapter != null ? "WIRED" : "NULL");
    }

    // ── Legacy varargs shims (keep AlgoTradeExecution callsites compiling) ──

    /** AlgoTradeExecution.line 600 — varargs shape: (underlying, outcome, blocker, spot). */
    public void recordScanBlocked(Object... args) {
        if (args.length >= 4 && args[0] instanceof String u && args[1] instanceof String o
                && args[2] instanceof String b && args[3] instanceof BigDecimal s) {
            recordScanBlocked(u, o, b, s);
        }
    }

    /** AlgoTradeExecution.line 609 — varargs shape: (OiShiftTrapDiagnostics) or (diag, candles, ...). */
    public void recordEvaluation(Object... args) {
        if (args.length >= 1 && args[0] instanceof OiShiftTrapDiagnostics d) {
            recordEvaluation(d);
        }
    }

    /** AlgoTradeExecution.line 611 — varargs shape: (StrategyDecision, OiShiftTrapDiagnostics, List<Candle>). */
    @SuppressWarnings("unchecked")
    public void recordSignal(Object... args) {
        if (args.length >= 2 && args[0] instanceof StrategyDecision sd
                && args[1] instanceof OiShiftTrapDiagnostics d) {
            List<Candle> candles = args.length >= 3 && args[2] instanceof List<?> lst
                    ? (List<Candle>) lst : List.of();
            recordSignal(sd, d, candles);
        }
    }

    /** Varargs shim: (TradeEntity, BigDecimal, String, MaeMfeTracker.Snapshot, Map). */
    @SuppressWarnings("unchecked")
    public void recordExit(Object... args) {
        if (args.length >= 3
                && args[0] instanceof TradeEntity t
                && args[1] instanceof BigDecimal price
                && args[2] instanceof String reason) {
            MaeMfeTracker.Snapshot snap = args.length >= 4 && args[3] instanceof MaeMfeTracker.Snapshot s ? s : null;
            Map<String, Object> attrs = args.length >= 5 && args[4] instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            recordExit(t, price, reason, snap, attrs);
        }
    }
}
