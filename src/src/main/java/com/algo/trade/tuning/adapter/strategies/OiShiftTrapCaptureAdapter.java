package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapDiagnostics;
import com.algo.trade.strategy.oishifttrap.ShiftTrapConfirmationEvaluator;
import com.algo.trade.strategy.oishifttrap.ShiftTrapVelocityCalculator;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.ShadowGateEvent;
import com.algo.trade.tuning.SignalEvent;
import java.util.ArrayList;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.adapter.ShadowGate;
import com.algo.trade.tuning.adapter.TuningCaptureAdapter;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Week 2 adapter for {@link StrategyType#OI_SHIFT_TRAP}.
 */
@Component
public class OiShiftTrapCaptureAdapter implements TuningCaptureAdapter {

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_SHIFT_TRAP;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.HIGH;
    }

    @Override
    public int defaultEpisodeWindowSec() {
        return 60;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("score", "score",
                        List.of(50.0, 60.0, 70.0, 80.0, 90.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("imbalance", "imbalance",
                        List.of(1.5, 2.0, 3.0, 4.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("proximity", "proximityPct",
                        List.of(0.25, 0.5, 0.75, 1.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("trapSide", "trapSide", null, BandStyle.CATEGORICAL)
        );
    }

    @Override
    public List<ShadowGate> shadowGates() {
        return List.of(
                ShadowGate.rule("confirm_momentumDecelerating", "spot acceleration negative"),
                ShadowGate.rule("confirm_spotStalled", "underlying range < 0.05% over last 2 candles"),
                ShadowGate.rule("confirm_oiStillBuilding", "trapped side OI still increasing"),
                ShadowGate.rule("confirm_oppositeOiFlushing", "opposite side OI flushing"),
                ShadowGate.rule("confirm_priceRetraced", "price retraced toward trap"),
                ShadowGate.rule("confirm_proximityTightening", "proximity tightening (shadow)"),
                ShadowGate.rule("confirm_volumeSpike", "underlying volume spike"),
                ShadowGate.rule("confirm_pcrAligned", "PCR aligned with trap side")
        );
    }

    @Override
    public String normalizeBlocker(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return switch (raw) {
            case "MIN_OI" -> "gate:MIN_OI";
            case "MIN_OI_CHANGE" -> "gate:MIN_OI_CHANGE";
            case "IMBALANCE" -> "gate:IMBALANCE";
            case "SCORE" -> "gate:SCORE";
            // Phase 2+3+4 hard blockers — already lowercase snake-case in diag.primaryBlocker.
            case "fake_breakout" -> "gate:FAKE_BREAKOUT";
            case "oi_absorption" -> "gate:OI_ABSORPTION";
            case "pcr_misaligned" -> "gate:PCR_MISALIGNED";
            case "cross_index_disagreement" -> "gate:CROSS_INDEX_DISAGREEMENT";
            case "underlying_volume" -> "gate:UNDERLYING_VOLUME";
            // A1/E1/E4 imbalance-only guards (2 Jun 2026)
            case "imbalance_pe_against_bullish_thrust" -> "gate:IMBALANCE_PE_BULLISH_THRUST";
            case "imbalance_ce_against_bearish_thrust" -> "gate:IMBALANCE_CE_BEARISH_THRUST";
            case "expiry_imbalance_after_1130" -> "gate:EXPIRY_AFTER_1130";
            case "expiry_near_strike_after_1400" -> "gate:EXPIRY_NEAR_STRIKE_AFTER_1400";
            case "reversal_risk_against_trap" -> "gate:REVERSAL_RISK_AGAINST_TRAP";
            // 4 Jun 2026 — structural strike-position gate
            case "imbalance_pe_strike_below_spot" -> "gate:IMBALANCE_PE_STRIKE_BELOW_SPOT";
            case "imbalance_ce_strike_above_spot" -> "gate:IMBALANCE_CE_STRIKE_ABOVE_SPOT";
            // 4 Jun 2026 PM — opening auction + warmup gates
            case "opening_auction_window" -> "gate:OPENING_AUCTION_WINDOW";
            case "insufficient_warmup_candles" -> "gate:INSUFFICIENT_WARMUP_CANDLES";
            default -> raw;
        };
    }

    public SignalEvent buildSignalEvent(
            StrategyDecision decision,
            OiShiftTrapDiagnostics diag,
            String correlationKey,
            List<Candle> underlyingCandles) {
        IndexType index = IndexType.fromName(decision.underlying().name());
        OiShiftTrapDiagnostics.CandidateSnapshot snap = "CE".equals(diag.trapSide())
                ? diag.bestCe() : diag.bestPe();
        int strike = decision.selectedStrike().map(BigDecimal::intValue).orElse(0);
        OptionType optionType = "PE".equals(diag.trapSide()) ? OptionType.PE : OptionType.CE;
        BigDecimal premium = decision.optionPrice().orElse(BigDecimal.ZERO);
        ShiftTrapVelocityCalculator.Velocity velocity =
                ShiftTrapVelocityCalculator.compute(underlyingCandles, decision.underlyingPrice());

        return new SignalEvent(
                decision.timestamp() != null ? decision.timestamp() : Instant.now(),
                Instant.now(),
                StrategyType.OI_SHIFT_TRAP,
                index,
                correlationKey,
                decision.selectedInstrumentKey().orElse(""),
                strike,
                optionType,
                premium,
                signalAttributes(diag, snap, velocity, decision));
    }

    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<String, String, OiShiftTrapDiagnostics> row) {
        OiShiftTrapDiagnostics diag = row.firstPayload();
        Map<String, Object> attrs = signalAttributes(diag,
                pickCandidate(diag), ShiftTrapVelocityCalculator.Velocity.zero(), null);
        attrs.put("episodeFirstAt", row.firstAt().toString());
        attrs.put("episodeLastAt", row.lastAt().toString());
        attrs.put("rawBlocker", row.dedupKey());
        attrs.put("outcome", diag.outcome());
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (row.streamKey() + ":" + row.dedupKey() + ":" + row.firstAt().toEpochMilli()).hashCode(), 16);
        EvaluationOutcome outcome = mapOutcome(diag);
        // A7 (2026-06-02): EvaluationEvent's invariant requires blocker == null
        // when outcome != BLOCKED. Today an unknown number of SIGNAL/SKIPPED
        // rows were silently dropped at the validator because this adapter
        // always passed a non-null blocker (the dedup key). Now we null-out
        // the blocker for non-BLOCKED rows.
        String blockerForEvent = (outcome == EvaluationOutcome.BLOCKED)
                ? normalizeBlocker(row.dedupKey()) : null;
        return new EvaluationEvent(
                row.firstAt(),
                Instant.now(),
                StrategyType.OI_SHIFT_TRAP,
                IndexType.fromName(diag.underlying()),
                episodeId,
                outcome,
                blockerForEvent,
                row.tickCount(),
                attrs);
    }

    /** One evaluation tick with no episode folding (legacy dedup-off mode). */
    public EvaluationEvent buildImmediateEvaluationEvent(OiShiftTrapDiagnostics diag, Instant eventTime) {
        String underlying = diag.underlying();
        String blocker = normalizeBlocker(diag.primaryBlocker());
        var row = new EpisodeAggregator.EpisodeRow<>(
                underlying, blocker, eventTime, eventTime, 1, diag);
        return buildEvaluationEvent(row);
    }

    private static EvaluationOutcome mapOutcome(OiShiftTrapDiagnostics diag) {
        if (diag.signalGenerated()) {
            return EvaluationOutcome.FIRED;
        }
        String outcome = diag.outcome();
        if (outcome != null && (outcome.contains("SKIP") || "NO_SETUP".equals(outcome))) {
            return EvaluationOutcome.SKIPPED;
        }
        return EvaluationOutcome.BLOCKED;
    }

    /**
     * Builds an {@link ExitEvent} from a closed Shift Trap trade. {@code trapContext}
     * Optional trap metadata (score, imbalance, etc.) may be passed in {@code trapAttrs}.
     */
    public ExitEvent buildExitEvent(IndexType index,
                                     TradeEntity trade,
                                     MaeMfeTracker.Snapshot snapshot,
                                     String correlationKey,
                                     BigDecimal exitPrice,
                                     String exitReason,
                                     boolean reversal,
                                     Map<String, Object> trapAttrs) {
        BigDecimal entryPrice = trade.getEntryPrice() != null
                ? trade.getEntryPrice() : BigDecimal.ZERO;
        double realizedPnlPct = 0.0;
        if (entryPrice.signum() > 0 && exitPrice != null) {
            realizedPnlPct = exitPrice.subtract(entryPrice)
                    .divide(entryPrice, java.math.MathContext.DECIMAL64)
                    .doubleValue() * 100.0;
        }
        long holdSec = 0;
        if (trade.getEntryTime() != null) {
            holdSec = Duration.between(trade.getEntryTime(), Instant.now()).getSeconds();
        }

        double maePct = snapshot != null ? snapshot.maePct() : 0.0;
        double mfePct = snapshot != null ? snapshot.mfePct() : 0.0;
        long timeToMaeSec = snapshot != null ? snapshot.timeToMaeSec() : 0L;
        long timeToMfeSec = snapshot != null ? snapshot.timeToMfeSec() : 0L;

        String key = correlationKey;
        if ((key == null || key.isBlank()) && snapshot != null) {
            key = snapshot.entry().correlationKey();
        }
        if (key == null || key.isBlank()) {
            key = trade.getTradeId();
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("instrumentKey", trade.getInstrumentKey());
        attrs.put("quantity", trade.getQuantity());
        attrs.put("optionType", trade.getOptionType());
        if (trapAttrs != null) {
            attrs.putAll(trapAttrs);
        }
        if (snapshot != null) {
            attrs.put("tickCount", snapshot.tickCount());
            if (snapshot.spotAtMae() > 0) {
                attrs.put("spotAtMae", snapshot.spotAtMae());
            }
            if (snapshot.spotAtMfe() > 0) {
                attrs.put("spotAtMfe", snapshot.spotAtMfe());
            }
        }

        return new ExitEvent(
                Instant.now(),
                Instant.now(),
                StrategyType.OI_SHIFT_TRAP,
                index,
                key,
                trade.getTradeId(),
                exitReason != null ? exitReason : "UNKNOWN",
                entryPrice,
                exitPrice != null ? exitPrice : BigDecimal.ZERO,
                realizedPnlPct,
                holdSec,
                maePct,
                mfePct,
                timeToMaeSec,
                timeToMfeSec,
                reversal,
                attrs);
    }

    /**
     * Emits one {@link ShadowGateEvent} per declared confirmation gate for a fired signal.
     * Gate names match {@link #shadowGates()} and legacy {@code oi-shift-trap-confirmations.csv}
     * column headers.
     */
    public List<ShadowGateEvent> buildShadowGateEvents(
            String correlationKey,
            Instant signalAt,
            IndexType index,
            ShiftTrapConfirmationEvaluator.Confirmations confirmations,
            ShiftTrapVelocityCalculator.Velocity velocity,
            String trapSide,
            int trapStrike) {
        if (confirmations == null) {
            return List.of();
        }
        Map<String, Object> shared = new LinkedHashMap<>();
        putIfPresent(shared, "trapSide", trapSide);
        shared.put("trapStrike", trapStrike);
        shared.put("confirmationsPassedCount", confirmations.passedCount());
        if (velocity != null) {
            shared.put("spotVelocity1m", velocity.spotVelocity1m());
            shared.put("spotVelocity3m", velocity.spotVelocity3m());
            shared.put("spotAcceleration", velocity.spotAcceleration());
        }

        List<ShadowGateEvent> events = new ArrayList<>(8);
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_momentumDecelerating",
                confirmations.momentumDecelerating(),
                velocity != null ? velocity.spotAcceleration() : null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_spotStalled",
                confirmations.spotStalled(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_oiStillBuilding",
                confirmations.oiStillBuilding(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_oppositeOiFlushing",
                confirmations.oppositeOiFlushing(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_priceRetraced",
                confirmations.priceRetraced(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_proximityTightening",
                confirmations.proximityTightening(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_volumeSpike",
                confirmations.volumeSpike(), null, shared));
        events.add(shadowGate(correlationKey, signalAt, index, "confirm_pcrAligned",
                confirmations.pcrAligned(), null, shared));
        return events;
    }

    private ShadowGateEvent shadowGate(
            String correlationKey,
            Instant signalAt,
            IndexType index,
            String gateName,
            boolean passed,
            Double bandValue,
            Map<String, Object> sharedAttrs) {
        return new ShadowGateEvent(
                signalAt != null ? signalAt : Instant.now(),
                Instant.now(),
                StrategyType.OI_SHIFT_TRAP,
                index,
                correlationKey,
                gateName,
                passed,
                bandValue,
                Map.copyOf(sharedAttrs));
    }

    public Map<String, Object> signalAttributes(
            OiShiftTrapDiagnostics diag,
            OiShiftTrapDiagnostics.CandidateSnapshot snap,
            ShiftTrapVelocityCalculator.Velocity velocity,
            StrategyDecision decision) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (diag == null) {
            return a;
        }
        a.put("score", diag.signalScore());
        putIfPresent(a, "trapSide", diag.trapSide());
        a.put("trendDirection", diag.trendDirection());
        putIfPresent(a, "volumeMode", diag.volumeMode());
        a.put("latestVolume", diag.latestVolume());
        putIfPresent(a, "outcome", diag.outcome());
        putIfPresent(a, "primaryBlocker", diag.primaryBlocker());
        a.put("chainLevels", diag.chainLevels());
        // Phase 4 fields 13, 14 — only emit when populated to keep tuning bucket cardinality tight.
        if (diag.daysToExpiry() > 0) {
            a.put("daysToExpiry", diag.daysToExpiry());
        }
        if (diag.relativeVolumeRatio() > 0.0) {
            a.put("relativeVolumeRatio", diag.relativeVolumeRatio());
        }
        if (snap != null && snap.present()) {
            a.put("imbalance", snap.imbalance());
            a.put("proximityPct", snap.proximityPct());
            a.put("trappedOi", snap.trappedOi());
            a.put("oiChange", snap.oiChange());
            a.put("failedGate", snap.failedGate());
        }
        if (velocity != null) {
            a.put("spotVelocity1m", velocity.spotVelocity1m());
            a.put("spotVelocity3m", velocity.spotVelocity3m());
            a.put("spotAcceleration", velocity.spotAcceleration());
        }
        if (decision != null) {
            a.put("signalType", decision.signalType().name());
            a.put("reasons", String.join("; ", decision.reasons()));
            // Phase 1-4 signal-path categorical attribute. Lets tuning split metrics by
            // entry path (standard / imbalance-only / distant-OI / pending-resolved).
            a.put("signalPath", resolveSignalPath(decision.reasons()));
        }
        return a;
    }

    /**
     * Resolve a coarse signal-path label from the first reason string. Defaults to
     * {@code "standard"} when no special-path marker is found.
     */
    private static String resolveSignalPath(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "standard";
        }
        String first = reasons.get(0);
        if (first == null) {
            return "standard";
        }
        if (first.contains("[imbalance-only]")) {
            return "imbalance_only";
        }
        if (first.contains("[distant-OI]")) {
            return "distant_oi";
        }
        if (first.contains("[pending-resolved]")) {
            return "pending_resolved";
        }
        return "standard";
    }

    private static OiShiftTrapDiagnostics.CandidateSnapshot pickCandidate(OiShiftTrapDiagnostics diag) {
        if (diag == null) {
            return OiShiftTrapDiagnostics.CandidateSnapshot.empty();
        }
        return "CE".equals(diag.trapSide()) ? diag.bestCe() : diag.bestPe();
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
