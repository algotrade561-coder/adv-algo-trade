package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapConfig;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapDiagnostics;
import com.algo.trade.strategy.oishifttrap.ShiftTrapConvergenceTracker;
import com.algo.trade.strategy.oishifttrap.ShiftTrapCrossIndexValidator;
import com.algo.trade.strategy.oishifttrap.ShiftTrapFakeBreakoutDetector;
import com.algo.trade.strategy.oishifttrap.ShiftTrapLiquidityHuntDetector;
import com.algo.trade.strategy.oishifttrap.ShiftTrapOiAbsorptionDetector;
import com.algo.trade.strategy.oishifttrap.ShiftTrapPendingSignalRegistry;
import com.algo.trade.strategy.oishifttrap.ShiftTrapHighImbalanceAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects "trapped" option writers at strikes near the current price.
 *
 * Thesis: When a strike near ATM has disproportionately heavy call/put OI AND
 * that OI is still building (not unwinding), writers are exposed. If price
 * approaches that strike, a short-cover squeeze can produce a sharp directional move.
 *
 * <p><b>2026-06-01 — Phase 1+2+3+4 enhancements</b> (all gated by
 * {@link OiShiftTrapConfig#isEnhancementsEnabled()}). When master toggle is OFF,
 * behavior is byte-identical to the legacy strategy.</p>
 */
@Component
public class OiShiftTrapStrategy {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapStrategy.class);
    /** Minimum OI imbalance ratio (trapped side / opposite side). Legacy default. */
    private static final double OI_IMBALANCE_RATIO = 1.5;
    /** Maximum distance from spot to trap strike as % of spot price. Legacy default. */
    private static final double PROXIMITY_PERCENT = 0.75;
    private static final long MIN_ABSOLUTE_OI = 50_000;
    private static final long MIN_OI_CHANGE = 3_000;
    private static final long MIN_UNDERLYING_VOLUME = 1_000;
    private static final int LEGACY_SCORE_BASE = 50;
    private static final int LEGACY_MIN_CONFIDENCE = 50;
    private static final int IMBALANCE_ONLY_MIN_SCORE = 70;
    private static final int LIQUIDITY_HUNT_BONUS = 10;
    private static final int DISTANT_OI_MIN_SCORE = 65;
    private static final int PENDING_RESOLUTION_MIN_SCORE = 65;

    /** Hard-block reason strings (kept aligned with OiShiftTrapCaptureAdapter.normalizeBlocker). */
    public static final String BLOCKER_PCR_MISALIGNED = "pcr_misaligned";
    public static final String BLOCKER_CROSS_INDEX_DISAGREEMENT = "cross_index_disagreement";

    // 2 Jun 2026 — A1/E1/E4 directional + expiry guards on imbalance-only path.
    public static final String BLOCKER_IMBALANCE_PE_AGAINST_BULLISH_THRUST = "imbalance_pe_against_bullish_thrust";
    public static final String BLOCKER_IMBALANCE_CE_AGAINST_BEARISH_THRUST = "imbalance_ce_against_bearish_thrust";
    public static final String BLOCKER_EXPIRY_LATE_MORNING_BLOCK = "expiry_imbalance_after_1130";
    public static final String BLOCKER_EXPIRY_NEAR_STRIKE_LAST_HOUR = "expiry_near_strike_after_1400";

    // 2 Jun 2026 — R1-R4 composite reversal-risk veto. Fires when the chain is
    // rotating against the trap direction (max-pain drift + skew + walls + OI flow).
    public static final String BLOCKER_REVERSAL_RISK_AGAINST_TRAP = "reversal_risk_against_trap";

    // 4 Jun 2026 — Structural strike-position gate on imbalance-only.
    // Heavy PE OI with spot ABOVE strike = put WRITING (bullish floor), bad PE BUY.
    // Heavy CE OI with spot BELOW strike = call WRITING (bearish cap), bad CE BUY.
    // Validated against today's 2 losing trades:
    //   NIFTY 23300 PE: spot 23339 was +0.17% ABOVE strike → would have blocked
    //   SENSEX 73900 PE: spot 74107 was +0.28% ABOVE strike → would have blocked
    public static final String BLOCKER_IMBALANCE_PE_STRIKE_BELOW_SPOT = "imbalance_pe_strike_below_spot";
    public static final String BLOCKER_IMBALANCE_CE_STRIKE_ABOVE_SPOT = "imbalance_ce_strike_above_spot";

    // 4 Jun 2026 (PM) — Opening-auction window block + min-warmup gate.
    // The 09:20 losses today both fell in the first 5 min of the session, where
    // OI imbalance is dominated by yesterday's positional carryover and the
    // velocity calculator doesn't yet have enough candle history (only 1-2
    // candles) to detect the bullish thrust that ate both PE BUYs.
    public static final String BLOCKER_OPENING_AUCTION_WINDOW = "opening_auction_window";
    public static final String BLOCKER_INSUFFICIENT_WARMUP_CANDLES = "insufficient_warmup_candles";

    /** Strike-vs-spot offset (% of strike) above which imbalance-only is rejected.
     *  0.05% = 12 pts on NIFTY @ 23300. Tight enough to allow at-money traps,
     *  strict enough to block the OTM-writer-floor false positives. */
    private static final double STRIKE_OFFSET_PCT_MAX = 0.05;

    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;
    private final OiShiftTrapConfig shiftTrapConfig;
    private final ExpiryCalendar expiryCalendar;
    private final LiveInstrumentCache liveInstrumentCache;
    private final ShiftTrapFakeBreakoutDetector fakeBreakoutDetector;
    private final ShiftTrapLiquidityHuntDetector liquidityHuntDetector;
    private final ShiftTrapConvergenceTracker convergenceTracker;
    private final ShiftTrapOiAbsorptionDetector oiAbsorptionDetector;
    private final ShiftTrapCrossIndexValidator crossIndexValidator;
    private final ShiftTrapPendingSignalRegistry pendingSignalRegistry;
    private final ShiftTrapHighImbalanceAlertService highImbalanceAlertService;

    /**
     * A4 (2026-06-02): OIST signal-path heartbeat tick. The eval-path heartbeat
     * fires from AlgoTradeExecution before evaluateWithDiagnostics; we also tick
     * the heartbeat from the imbalance-only signal emission so a stalled eval
     * path (today's 12:10 IST silent stop) can still be detected if the signal
     * path is alive. Optional injection so unit tests don't need to wire it.
     */
    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.OiShiftTrapHeartbeatService oiShiftTrapHeartbeat;

    /**
     * R1-R4 (2026-06-02): composite reversal-risk tracker. Reads max-pain
     * drift, IV skew, wall migration, per-strike OI flow inversion from the
     * shared market context, scores 0-100 against an OIST candidate. Veto
     * when the chain is rotating against the trap.
     */
    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ReversalRiskTracker reversalRiskTracker;

    /** Score threshold above which the imbalance-only path is vetoed. Configurable per-deploy. */
    private static final int REVERSAL_RISK_VETO_THRESHOLD = 50;

    public OiShiftTrapStrategy(
            @Autowired(required = false) com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService,
            @Autowired(required = false) OiShiftTrapConfig shiftTrapConfig,
            @Autowired(required = false) ExpiryCalendar expiryCalendar,
            @Autowired(required = false) LiveInstrumentCache liveInstrumentCache,
            @Autowired(required = false) ShiftTrapFakeBreakoutDetector fakeBreakoutDetector,
            @Autowired(required = false) ShiftTrapLiquidityHuntDetector liquidityHuntDetector,
            @Autowired(required = false) ShiftTrapConvergenceTracker convergenceTracker,
            @Autowired(required = false) ShiftTrapOiAbsorptionDetector oiAbsorptionDetector,
            @Autowired(required = false) ShiftTrapCrossIndexValidator crossIndexValidator,
            @Autowired(required = false) ShiftTrapPendingSignalRegistry pendingSignalRegistry,
            @Autowired(required = false) ShiftTrapHighImbalanceAlertService highImbalanceAlertService) {
        this.underlyingConfigService = underlyingConfigService;
        this.shiftTrapConfig = shiftTrapConfig;
        this.expiryCalendar = expiryCalendar;
        this.liveInstrumentCache = liveInstrumentCache;
        this.fakeBreakoutDetector = fakeBreakoutDetector;
        this.liquidityHuntDetector = liquidityHuntDetector;
        this.convergenceTracker = convergenceTracker;
        this.oiAbsorptionDetector = oiAbsorptionDetector;
        this.crossIndexValidator = crossIndexValidator;
        this.pendingSignalRegistry = pendingSignalRegistry;
        this.highImbalanceAlertService = highImbalanceAlertService;
    }

    public record TrapEvaluation(
            Optional<StrategyDecision> signal,
            OiShiftTrapDiagnostics diagnostics
    ) {
    }

    public TrapEvaluation evaluateWithDiagnostics(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                   StrategyConfig config, UnderlyingSymbol underlying,
                                                   List<Candle> underlyingCandles) {
        if (snapshot == null || snapshot.levels().isEmpty()) {
            return new TrapEvaluation(Optional.empty(),
                    OiShiftTrapDiagnostics.blocked(underlying.name(), "NO_CHAIN", "empty_snapshot", spotPrice));
        }
        if (spotPrice == null || spotPrice.signum() <= 0) {
            return new TrapEvaluation(Optional.empty(),
                    OiShiftTrapDiagnostics.blocked(underlying.name(), "NO_SPOT", "invalid_spot", spotPrice));
        }
        if (underlyingCandles == null || underlyingCandles.isEmpty()) {
            return new TrapEvaluation(Optional.empty(),
                    OiShiftTrapDiagnostics.blocked(underlying.name(), "NO_CANDLES", "no_underlying_candles", spotPrice));
        }

        // Phase 2: record tick state for stateful detectors (NO-OP when toggle OFF or beans missing).
        if (enhancementsOn()) {
            if (convergenceTracker != null) convergenceTracker.recordSpot(underlying, spotPrice);
            if (oiAbsorptionDetector != null) oiAbsorptionDetector.recordSnapshot(underlying, snapshot);
        }

        // Phase 4: compute DTE + relative volume once per evaluation. Must precede every
        // diagnostic-construction site that uses them.
        int dte = effectiveDte(underlying);
        double relVol = relativeVolume(underlyingCandles);

        String volumeMode = underlyingConfigService != null
                ? underlyingConfigService.getVolumeSpikeMode(underlying) : "NORMAL";
        long latestVolume = underlyingCandles.getLast().volume();
        if (!"OI_PROXY".equals(volumeMode) && !"DISABLED".equals(volumeMode)) {
            if (latestVolume < MIN_UNDERLYING_VOLUME) {
                log.debug("[OiShiftTrap] Skipped: low underlying volume {} < {}", latestVolume, MIN_UNDERLYING_VOLUME);
                return new TrapEvaluation(Optional.empty(), new OiShiftTrapDiagnostics(
                        underlying.name(), spotPrice, 0, volumeMode, latestVolume,
                        "LOW_VOLUME", "underlying_volume", snapshot.levels().size(),
                        OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                        OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                        false, "", BigDecimal.ZERO, 0,
                        dte, relVol));
            }
        }

        // Phase 2 feature 7: block on fake-breakout pattern (runs BEFORE pending revival so
        // a thesis-broken bar can't slip through via OI-first path).
        if (fakeBreakoutBlocks(underlyingCandles)) {
            log.debug("[OiShiftTrap] Skipped: fake-breakout pattern on {}", underlying);
            return new TrapEvaluation(Optional.empty(), new OiShiftTrapDiagnostics(
                    underlying.name(), spotPrice, 0, volumeMode, latestVolume,
                    "FAKE_BREAKOUT", "fake_breakout", snapshot.levels().size(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    false, "", BigDecimal.ZERO, 0,
                    dte, relVol));
        }

        int trendDirection = detectShortTermTrend(underlyingCandles);
        List<OptionChainLevel> sorted = snapshot.levels().stream()
                .sorted(Comparator.comparing(OptionChainLevel::strike))
                .toList();

        OptionChainLevel atm = sorted.stream()
                .min(Comparator.comparing(l -> l.strike().subtract(spotPrice).abs()))
                .orElse(null);
        if (atm == null) {
            return new TrapEvaluation(Optional.empty(),
                    OiShiftTrapDiagnostics.blocked(underlying.name(), "NO_ATM", "no_atm_strike", spotPrice));
        }

        // Phase 2 feature 8: block when writers are absorbing a break-through.
        if (oiAbsorptionBlocks(underlying, snapshot, spotPrice, trendDirection)) {
            log.debug("[OiShiftTrap] Skipped: OI absorption on {}", underlying);
            return new TrapEvaluation(Optional.empty(), new OiShiftTrapDiagnostics(
                    underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                    "OI_ABSORPTION", "oi_absorption", snapshot.levels().size(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    false, "", BigDecimal.ZERO, 0,
                    dte, relVol));
        }

        // Phase 3 feature 9: revive a pending OI-first candidate when proximity has caught up.
        // Runs AFTER volume gate + fake-breakout block + OI-absorption block so a pending
        // entry cannot bypass ANY safety blocker. Hard-blocker reasons from PCR/cross-index
        // are propagated via the holder so tuning sees them in primaryBlocker.
        String[] hardBlocker = {""};
        Optional<StrategyDecision> pending = tryPendingResolution(snapshot, spotPrice, underlying, hardBlocker);
        if (pending.isPresent()) {
            String side = pending.get().signalType() == SignalType.BUY_CE ? "CE" : "PE";
            BigDecimal strk = pending.get().selectedStrike().orElse(BigDecimal.ZERO);
            return new TrapEvaluation(pending, buildSuccessDiag(
                    underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                    snapshot.levels().size(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                    side, strk, pending.get().confidenceScore().intValue(), dte, relVol));
        }

        double spot = spotPrice.doubleValue();
        double effectiveProximity = effectiveProximityPercent(underlying);
        double effectiveImbalance = effectiveImbalanceRatio(underlying);
        OiShiftTrapDiagnostics.CandidateSnapshot bestCe = OiShiftTrapDiagnostics.CandidateSnapshot.empty();
        OiShiftTrapDiagnostics.CandidateSnapshot bestPe = OiShiftTrapDiagnostics.CandidateSnapshot.empty();

        if (trendDirection >= 0) {
            for (OptionChainLevel level : sorted) {
                if (level.strike().compareTo(atm.strike()) <= 0) {
                    continue;
                }
                if (level.callOpenInterest() == 0) {
                    continue;
                }
                double proximity = (level.strike().doubleValue() - spot) / spot * 100;
                if (proximity > effectiveProximity) {
                    break;
                }
                bestCe = betterCandidate(bestCe, evaluateCeCandidate(level, proximity, trendDirection, effectiveImbalance));
                // Phase 5 — high-imbalance Telegram watch alert (no-trade FYI)
                maybeAlertHighImbalance(underlying, "CE", level, spotPrice);
                Optional<StrategyDecision> imbOnly = tryImbalanceOnlySignal(
                        level, spotPrice, underlying, proximity, trendDirection, "CE", underlyingCandles, hardBlocker);
                if (imbOnly.isPresent()) {
                    return new TrapEvaluation(imbOnly, buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "CE", level.strike(),
                            imbOnly.get().confidenceScore().intValue(), dte, relVol));
                }
                Optional<StrategyDecision> signal = tryCeSignal(level, spotPrice, underlying, proximity,
                        trendDirection, underlyingCandles, hardBlocker);
                if (signal.isPresent()) {
                    return new TrapEvaluation(signal, buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "CE", level.strike(),
                            signal.get().confidenceScore().intValue(), dte, relVol));
                }
            }
        }

        if (trendDirection <= 0) {
            List<OptionChainLevel> belowAtm = sorted.stream()
                    .filter(l -> l.strike().compareTo(atm.strike()) < 0)
                    .sorted(Comparator.comparing(OptionChainLevel::strike, Comparator.reverseOrder()))
                    .toList();

            for (OptionChainLevel level : belowAtm) {
                if (level.putOpenInterest() == 0) {
                    continue;
                }
                double proximity = (spot - level.strike().doubleValue()) / spot * 100;
                if (proximity > effectiveProximity) {
                    break;
                }
                bestPe = betterCandidate(bestPe, evaluatePeCandidate(level, proximity, trendDirection, effectiveImbalance));
                // Phase 5 — high-imbalance Telegram watch alert (no-trade FYI)
                maybeAlertHighImbalance(underlying, "PE", level, spotPrice);
                Optional<StrategyDecision> imbOnly = tryImbalanceOnlySignal(
                        level, spotPrice, underlying, proximity, trendDirection, "PE", underlyingCandles, hardBlocker);
                if (imbOnly.isPresent()) {
                    return new TrapEvaluation(imbOnly, buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "PE", level.strike(),
                            imbOnly.get().confidenceScore().intValue(), dte, relVol));
                }
                Optional<StrategyDecision> signal = tryPeSignal(level, spotPrice, underlying, proximity,
                        trendDirection, underlyingCandles, hardBlocker);
                if (signal.isPresent()) {
                    return new TrapEvaluation(signal, buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "PE", level.strike(),
                            signal.get().confidenceScore().intValue(), dte, relVol));
                }
            }
        }

        // Phase 2 feature 6: distant-OI convergence scan beyond the standard proximity gate.
        // Side-effect: registers OI-strong-but-non-converging candidates with the pending registry.
        Optional<StrategyDecision> distant = scanDistantStrikes(
                snapshot, sorted, atm, spotPrice, underlying, trendDirection, effectiveImbalance,
                effectiveProximity, underlyingCandles, hardBlocker);
        if (distant.isPresent()) {
            String side = distant.get().signalType() == SignalType.BUY_CE ? "CE" : "PE";
            BigDecimal strk = distant.get().selectedStrike().orElse(BigDecimal.ZERO);
            return new TrapEvaluation(distant, buildSuccessDiag(
                    underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                    snapshot.levels().size(), bestCe, bestPe, side, strk,
                    distant.get().confidenceScore().intValue(), dte, relVol));
        }

        // Final no-signal diagnostic — prefer hard-block reason (PCR / cross-index) over
        // candidate-failedGate when both are present, so tuning sees the right blocker.
        String candidateBlocker = resolvePrimaryBlocker(trendDirection, bestCe, bestPe);
        String primaryBlocker = hardBlocker[0].isEmpty() ? candidateBlocker : hardBlocker[0];
        String outcome = trendDirection == 0 ? "TREND_FLAT" : "NO_TRAP_MATCH";

        // 2026-06-01 — per-evaluation DEBUG summary so the operator can see what the
        // tick produced even when nothing fires. Cheap; gated on isDebugEnabled.
        if (log.isDebugEnabled()) {
            log.debug("[OiShiftTrap] {} eval no-signal: trend={} blocker={} bestCe={} bestPe={} dte={} relVol={}",
                    underlying.name(), trendDirection, primaryBlocker,
                    candidateOneLine(bestCe), candidateOneLine(bestPe), dte,
                    String.format("%.2f", relVol));
        }

        return new TrapEvaluation(Optional.empty(), new OiShiftTrapDiagnostics(
                underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                outcome, primaryBlocker, snapshot.levels().size(),
                bestCe, bestPe, false, "", BigDecimal.ZERO, 0,
                dte, relVol));
    }

    /** Compact one-line representation of a candidate snapshot for log lines. */
    private static String candidateOneLine(OiShiftTrapDiagnostics.CandidateSnapshot c) {
        if (c == null || !c.present()) return "none";
        return String.format("strike=%s imb=%.1fx prox=%.2f%% trappedOi=%d score=%d gate=%s",
                c.strike(), c.imbalance(), c.proximityPct(), c.trappedOi(), c.score(),
                c.failedGate().isBlank() ? "-" : c.failedGate());
    }

    public Optional<StrategyDecision> evaluate(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                StrategyConfig config, UnderlyingSymbol underlying,
                                                List<Candle> underlyingCandles) {
        return evaluateWithDiagnostics(snapshot, spotPrice, config, underlying, underlyingCandles).signal();
    }

    /** Backward-compatible overload for callers that don't pass candles. */
    public Optional<StrategyDecision> evaluate(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluate(snapshot, spotPrice, config, underlying, null);
    }

    private static OiShiftTrapDiagnostics buildSuccessDiag(
            String underlying, BigDecimal spot, int trend, String volumeMode, long volume,
            int chainLevels, OiShiftTrapDiagnostics.CandidateSnapshot bestCe,
            OiShiftTrapDiagnostics.CandidateSnapshot bestPe, String trapSide,
            BigDecimal strike, int score, int dte, double relVol) {
        return new OiShiftTrapDiagnostics(
                underlying, spot, trend, volumeMode, volume,
                "SIGNAL", "passed", chainLevels,
                bestCe, bestPe, true, trapSide, strike, score,
                dte, relVol);
    }

    private String resolvePrimaryBlocker(int trendDirection,
                                          OiShiftTrapDiagnostics.CandidateSnapshot bestCe,
                                          OiShiftTrapDiagnostics.CandidateSnapshot bestPe) {
        if (trendDirection > 0 && !bestCe.present() && !bestPe.present()) {
            return "trend_blocks_pe_no_ce_candidate";
        }
        if (trendDirection < 0 && !bestPe.present() && !bestCe.present()) {
            return "trend_blocks_ce_no_pe_candidate";
        }
        if (trendDirection == 0) {
            return "trend_flat";
        }
        OiShiftTrapDiagnostics.CandidateSnapshot best = bestCe.score() >= bestPe.score() ? bestCe : bestPe;
        if (!best.present()) {
            return "no_strike_in_proximity";
        }
        if (!best.failedGate().isBlank()) {
            return best.failedGate();
        }
        return "score_below_" + effectiveMinConfidenceScore();
    }

    private static OiShiftTrapDiagnostics.CandidateSnapshot betterCandidate(
            OiShiftTrapDiagnostics.CandidateSnapshot current,
            OiShiftTrapDiagnostics.CandidateSnapshot candidate) {
        if (!candidate.present()) {
            return current;
        }
        if (!current.present() || candidate.score() > current.score()) {
            return candidate;
        }
        return current;
    }

    private OiShiftTrapDiagnostics.CandidateSnapshot evaluateCeCandidate(
            OptionChainLevel level, double proximity, int trendDirection, double imbalanceFloor) {
        long ceOi = level.callOpenInterest();
        long peOi = level.putOpenInterest();
        long ceOiChange = level.callOpenInterestChange();
        if (ceOi < MIN_ABSOLUTE_OI) {
            return snap(level.strike(), ceOi, peOi, ceOiChange, 0, proximity, 0, "MIN_OI");
        }
        if (ceOiChange < MIN_OI_CHANGE) {
            double imb = ceOi / (double) Math.max(peOi, 1);
            return snap(level.strike(), ceOi, peOi, ceOiChange, imb, proximity, 0, "MIN_OI_CHANGE");
        }
        double imbalance = ceOi / (double) Math.max(peOi, 1);
        if (imbalance < imbalanceFloor) {
            return snap(level.strike(), ceOi, peOi, ceOiChange, imbalance, proximity, 0, "IMBALANCE");
        }
        int score = calculateScore(imbalance, ceOi, ceOiChange, proximity, trendDirection);
        String gate = score < effectiveMinConfidenceScore() ? "SCORE" : "";
        return snap(level.strike(), ceOi, peOi, ceOiChange, imbalance, proximity, score, gate);
    }

    private OiShiftTrapDiagnostics.CandidateSnapshot evaluatePeCandidate(
            OptionChainLevel level, double proximity, int trendDirection, double imbalanceFloor) {
        long peOi = level.putOpenInterest();
        long ceOi = level.callOpenInterest();
        long peOiChange = level.putOpenInterestChange();
        if (peOi < MIN_ABSOLUTE_OI) {
            return snap(level.strike(), peOi, ceOi, peOiChange, 0, proximity, 0, "MIN_OI");
        }
        if (peOiChange < MIN_OI_CHANGE) {
            double imb = peOi / (double) Math.max(ceOi, 1);
            return snap(level.strike(), peOi, ceOi, peOiChange, imb, proximity, 0, "MIN_OI_CHANGE");
        }
        double imbalance = peOi / (double) Math.max(ceOi, 1);
        if (imbalance < imbalanceFloor) {
            return snap(level.strike(), peOi, ceOi, peOiChange, imbalance, proximity, 0, "IMBALANCE");
        }
        int score = calculateScore(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection));
        String gate = score < effectiveMinConfidenceScore() ? "SCORE" : "";
        return snap(level.strike(), peOi, ceOi, peOiChange, imbalance, proximity, score, gate);
    }

    private static OiShiftTrapDiagnostics.CandidateSnapshot snap(
            BigDecimal strike, long trappedOi, long oppositeOi, long oiChange,
            double imbalance, double proximityPct, int score, String failedGate) {
        return new OiShiftTrapDiagnostics.CandidateSnapshot(
                strike, trappedOi, oppositeOi, oiChange, imbalance, proximityPct, score, failedGate);
    }

    private Optional<StrategyDecision> tryCeSignal(OptionChainLevel level, BigDecimal spotPrice,
                                                     UnderlyingSymbol underlying,
                                                     double proximity, int trendDirection,
                                                     List<Candle> candles, String[] hardBlocker) {
        long ceOi = level.callOpenInterest();
        long peOi = level.putOpenInterest();
        long ceOiChange = level.callOpenInterestChange();
        if (ceOi < MIN_ABSOLUTE_OI || ceOiChange < MIN_OI_CHANGE) {
            return Optional.empty();
        }
        double imbalance = ceOi / (double) Math.max(peOi, 1);
        if (imbalance < effectiveImbalanceRatio(underlying)) {
            return Optional.empty();
        }
        int rawScore = calculateScore(imbalance, ceOi, ceOiChange, proximity, trendDirection);
        int huntBonus = liquidityHuntBonus(candles, level.strike(), spotPrice);
        int score = Math.min(90, rawScore + huntBonus);
        if (score < effectiveMinConfidenceScore()) {
            // 2026-06-01 — log near-miss for live tuning visibility
            if (score >= effectiveMinConfidenceScore() - 10) {
                log.info("[OiShiftTrap] CE NEAR-MISS strike={} score={} need={} | {}",
                        level.strike(), score, effectiveMinConfidenceScore(),
                        scoreBreakdown(imbalance, ceOi, ceOiChange, proximity, trendDirection, huntBonus));
            }
            return Optional.empty();
        }
        if (!pcrPassesIfRequired(underlying, "CE")) {
            log.info("[OiShiftTrap] CE blocked by PCR gate: strike={}", level.strike());
            hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
            return Optional.empty();
        }
        if (!crossIndexPasses(underlying, "CE", score)) {
            hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("OI Shift Trap: call-heavy strike=%s OI=%,d change=+%,d imbalance=%.1fx",
                level.strike(), ceOi, ceOiChange, imbalance));
        reasons.add(String.format("Spot=%s proximity=%.2f%% trend=%s — call writers exposed",
                spotPrice, proximity, trendDirection > 0 ? "BULLISH" : "FLAT"));

        log.info("[OiShiftTrap] CE signal: strike={} OI={} change=+{} imbalance={}x score={} proximity={}%",
                level.strike(), ceOi, ceOiChange, String.format("%.1f", imbalance), score,
                String.format("%.2f", proximity));
        log.info("[OiShiftTrap] CE score breakdown: {}",
                scoreBreakdown(imbalance, ceOi, ceOiChange, proximity, trendDirection, huntBonus));

        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, SignalType.BUY_CE, spotPrice,
                Optional.ofNullable(level.callLastPrice()).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.CE),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        ));
    }

    private Optional<StrategyDecision> tryPeSignal(OptionChainLevel level, BigDecimal spotPrice,
                                                     UnderlyingSymbol underlying,
                                                     double proximity, int trendDirection,
                                                     List<Candle> candles, String[] hardBlocker) {
        long peOi = level.putOpenInterest();
        long ceOi = level.callOpenInterest();
        long peOiChange = level.putOpenInterestChange();
        if (peOi < MIN_ABSOLUTE_OI || peOiChange < MIN_OI_CHANGE) {
            return Optional.empty();
        }
        double imbalance = peOi / (double) Math.max(ceOi, 1);
        if (imbalance < effectiveImbalanceRatio(underlying)) {
            return Optional.empty();
        }
        int rawScore = calculateScore(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection));
        int huntBonus = liquidityHuntBonus(candles, level.strike(), spotPrice);
        int score = Math.min(90, rawScore + huntBonus);
        if (score < effectiveMinConfidenceScore()) {
            // 2026-06-01 — log near-miss for live tuning visibility
            if (score >= effectiveMinConfidenceScore() - 10) {
                log.info("[OiShiftTrap] PE NEAR-MISS strike={} score={} need={} | {}",
                        level.strike(), score, effectiveMinConfidenceScore(),
                        scoreBreakdown(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection), huntBonus));
            }
            return Optional.empty();
        }
        if (!pcrPassesIfRequired(underlying, "PE")) {
            log.info("[OiShiftTrap] PE blocked by PCR gate: strike={}", level.strike());
            hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
            return Optional.empty();
        }
        if (!crossIndexPasses(underlying, "PE", score)) {
            hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("OI Shift Trap: put-heavy strike=%s OI=%,d change=+%,d imbalance=%.1fx",
                level.strike(), peOi, peOiChange, imbalance));
        reasons.add(String.format("Spot=%s proximity=%.2f%% trend=%s — put writers exposed",
                spotPrice, proximity, trendDirection < 0 ? "BEARISH" : "FLAT"));

        log.info("[OiShiftTrap] PE signal: strike={} OI={} change=+{} imbalance={}x score={} proximity={}%",
                level.strike(), peOi, peOiChange, String.format("%.1f", imbalance), score,
                String.format("%.2f", proximity));
        log.info("[OiShiftTrap] PE score breakdown: {}",
                scoreBreakdown(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection), huntBonus));

        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, SignalType.BUY_PE, spotPrice,
                Optional.ofNullable(level.putLastPrice()).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.PE),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        ));
    }

    /** Feature 5 — imbalance-only high-conviction shortcut. */
    private Optional<StrategyDecision> tryImbalanceOnlySignal(OptionChainLevel level, BigDecimal spotPrice,
                                                                UnderlyingSymbol underlying,
                                                                double proximity, int trendDirection,
                                                                String trapSide, List<Candle> candles,
                                                                String[] hardBlocker) {
        if (!enhancementsOn()) {
            return Optional.empty();
        }

        // ── 4 Jun 2026 PM: opening-auction window block ──────────────────
        // Block first 10 min of session (09:15-09:25 IST). OI during this
        // window is dominated by yesterday's positional carryover, not
        // today's intent. Both 09:20 losses today fell in this window.
        java.time.LocalTime nowIstEarly = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIstEarly.isBefore(java.time.LocalTime.of(9, 25))) {
            hardBlocker[0] = BLOCKER_OPENING_AUCTION_WINDOW;
            log.info("[OiShiftTrap] {} imbalance BLOCKED — opening auction window (now={} < 09:25)",
                    trapSide, nowIstEarly);
            return Optional.empty();
        }

        // ── 4 Jun 2026 PM: min-warmup gate ──────────────────────────────
        // Velocity-dependent gates need ≥ 3 trend candles to be reliable.
        // At 09:20:01 today we had only 1-2 candles so the A1 thrust gate
        // returned near-zero velocity and didn't catch the bullish move.
        if (candles == null || candles.size() < 3) {
            hardBlocker[0] = BLOCKER_INSUFFICIENT_WARMUP_CANDLES;
            log.info("[OiShiftTrap] {} imbalance BLOCKED — insufficient warmup ({} candles, need 3)",
                    trapSide, candles == null ? 0 : candles.size());
            return Optional.empty();
        }
        double threshold = shiftTrapConfig.getImbalanceOnlyEntryRatio();
        long trappedOi;
        long oppositeOi;
        long trappedChange;
        if ("CE".equals(trapSide)) {
            trappedOi = level.callOpenInterest();
            oppositeOi = level.putOpenInterest();
            trappedChange = level.callOpenInterestChange();
        } else {
            trappedOi = level.putOpenInterest();
            oppositeOi = level.callOpenInterest();
            trappedChange = level.putOpenInterestChange();
        }
        if (trappedOi < MIN_ABSOLUTE_OI) {
            return Optional.empty();
        }
        double imbalance = trappedOi / (double) Math.max(oppositeOi, 1);
        if (imbalance < threshold) {
            return Optional.empty();
        }
        if (!pcrPassesIfRequired(underlying, trapSide)) {
            hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
            return Optional.empty();
        }

        // ── A1 (2026-06-02): directional kill-switch on imbalance-only path ──
        // Today's −54% trade was a PE buy at 12:35 IST while spot was rocketing
        // up. Block PE when spot velocity (1m or 3m) is positive — and CE
        // symmetric. Validation against today's 67 signals: blocks 28/67 (42%)
        // while preserving the morning range-bound winners (where both
        // velocities were clearly negative).
        com.algo.trade.strategy.oishifttrap.ShiftTrapVelocityCalculator.Velocity velocity =
                com.algo.trade.strategy.oishifttrap.ShiftTrapVelocityCalculator.compute(candles, spotPrice);
        if ("PE".equals(trapSide) && (velocity.spotVelocity1m() > 0 || velocity.spotVelocity3m() > 0)) {
            hardBlocker[0] = BLOCKER_IMBALANCE_PE_AGAINST_BULLISH_THRUST;
            if (log.isInfoEnabled()) {
                log.info("[OiShiftTrap] PE imbalance BLOCKED — bullish thrust v1m={} v3m={} strike={}",
                        String.format("%+.3f", velocity.spotVelocity1m()),
                        String.format("%+.3f", velocity.spotVelocity3m()),
                        level.strike());
            }
            return Optional.empty();
        }
        if ("CE".equals(trapSide) && (velocity.spotVelocity1m() < 0 || velocity.spotVelocity3m() < 0)) {
            hardBlocker[0] = BLOCKER_IMBALANCE_CE_AGAINST_BEARISH_THRUST;
            if (log.isInfoEnabled()) {
                log.info("[OiShiftTrap] CE imbalance BLOCKED — bearish thrust v1m={} v3m={} strike={}",
                        String.format("%+.3f", velocity.spotVelocity1m()),
                        String.format("%+.3f", velocity.spotVelocity3m()),
                        level.strike());
            }
            return Optional.empty();
        }

        // ── 4 Jun 2026: structural strike-position gate ──
        // The velocity check above catches "spot is moving wrong way RIGHT now".
        // This gate catches the structural setup: "spot is on the wrong SIDE of
        // the trap strike for this to be a buyer-panic". Heavy PE OI well ABOVE
        // spot means put WRITERS defending a floor (bullish for spot — DON'T
        // BUY PE). Symmetric for CE. Today's 2 losing trades both had spot
        // 0.17%-0.28% above the PE strike at entry. Velocity check missed them
        // because v1m/v3m were not yet conclusively positive 15 sec after open.
        BigDecimal spotMinusStrike = spotPrice.subtract(level.strike());
        double strikeOffsetPct = spotMinusStrike.doubleValue() / level.strike().doubleValue() * 100.0;
        if ("PE".equals(trapSide) && strikeOffsetPct > STRIKE_OFFSET_PCT_MAX) {
            hardBlocker[0] = BLOCKER_IMBALANCE_PE_STRIKE_BELOW_SPOT;
            log.info("[OiShiftTrap] PE imbalance BLOCKED — spot {} is {}% ABOVE strike {} "
                    + "(writers' floor, not buyers' panic; threshold={}%)",
                    spotPrice, String.format("%+.3f", strikeOffsetPct),
                    level.strike(), STRIKE_OFFSET_PCT_MAX);
            return Optional.empty();
        }
        if ("CE".equals(trapSide) && strikeOffsetPct < -STRIKE_OFFSET_PCT_MAX) {
            hardBlocker[0] = BLOCKER_IMBALANCE_CE_STRIKE_ABOVE_SPOT;
            log.info("[OiShiftTrap] CE imbalance BLOCKED — spot {} is {}% BELOW strike {} "
                    + "(writers' cap, not buyers' panic; threshold={}%)",
                    spotPrice, String.format("%+.3f", strikeOffsetPct),
                    level.strike(), STRIKE_OFFSET_PCT_MAX);
            return Optional.empty();
        }

        // ── R1-R4 (2026-06-02): composite reversal-risk veto ──
        // Reads the max-pain drift + IV skew + wall migration + per-strike OI
        // flow inversion via ReversalRiskTracker (sampled by V3ContextFeeder).
        // Score 0-100; >= 50 = block new entries opposite to drift. Today the
        // 12:45 IST PE BUY at 23250 would have scored 100/100 against the trap.
        if (reversalRiskTracker != null) {
            try {
                com.algo.trade.domain.IndexType ix = com.algo.trade.domain.IndexType.from(underlying);
                com.algo.trade.strategy.oishifttrap.ReversalRiskTracker.Score risk =
                        reversalRiskTracker.score(ix, trapSide, level.strike().intValue());
                if (risk.total() >= REVERSAL_RISK_VETO_THRESHOLD) {
                    hardBlocker[0] = BLOCKER_REVERSAL_RISK_AGAINST_TRAP;
                    log.info("[OiShiftTrap] {} imbalance BLOCKED — reversal risk {}/100 against trap "
                            + "(strike={} components={})",
                            trapSide, risk.total(), level.strike(), risk.detail());
                    return Optional.empty();
                }
            } catch (Exception ex) {
                log.debug("[OiShiftTrap] reversal-risk score failed (non-fatal): {}", ex.getMessage());
            }
        }

        // ── E1 (2026-06-02): on expiry day, block imbalance-only after 11:30 IST ──
        // Empirical pattern today: morning OIST signals (09:55) worked; afternoon
        // signals (12:35+) all lost. Theta dominates from late morning on expiry.
        java.time.LocalTime nowIst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean expiryToday = isExpiryDay(underlying);
        if (expiryToday && nowIst.isAfter(java.time.LocalTime.of(11, 30))) {
            hardBlocker[0] = BLOCKER_EXPIRY_LATE_MORNING_BLOCK;
            log.info("[OiShiftTrap] Imbalance-only BLOCKED — expiry-day after 11:30 IST ({} strike={})",
                    trapSide, level.strike());
            return Optional.empty();
        }

        // ── E4 (2026-06-02): expiry-day last-hour near-strike PE block ──
        // After 14:00 IST on expiry, spot pins to max-pain. PE buy at strikes
        // within 0.3% of spot is buying into a pin against you.
        if (expiryToday && "PE".equals(trapSide)
                && nowIst.isAfter(java.time.LocalTime.of(14, 0))
                && proximity < 0.3) {
            hardBlocker[0] = BLOCKER_EXPIRY_NEAR_STRIKE_LAST_HOUR;
            log.info("[OiShiftTrap] PE imbalance BLOCKED — expiry last-hour near-strike pin (proximity={}%, strike={})",
                    String.format("%.2f", proximity), level.strike());
            return Optional.empty();
        }

        int effTrend = "CE".equals(trapSide) ? trendDirection : Math.abs(trendDirection);
        int rawScore = calculateScore(imbalance, trappedOi, trappedChange, proximity, effTrend);
        int huntBonus = liquidityHuntBonus(candles, level.strike(), spotPrice);
        int score = Math.min(90, Math.max(IMBALANCE_ONLY_MIN_SCORE, rawScore + huntBonus));
        if (!crossIndexPasses(underlying, trapSide, score)) {
            hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
            return Optional.empty();
        }

        // 2 Jun 2026 — surface the actual OI shift quantum so the UI / logs / tune CSV
        // show "how much OI moved" not just "current OI". For the trapped side, the
        // since-open net OI delta represents fresh writer activity at that strike.
        long trapSideChange = "CE".equals(trapSide)
                ? level.callOpenInterestChange()
                : level.putOpenInterestChange();
        long oiBeforeToday = Math.max(1L, trappedOi - trapSideChange);
        double freshWritersPct = (double) trapSideChange / oiBeforeToday * 100.0;

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("OI Shift Trap [imbalance-only]: %s-heavy strike=%s OI=%,d imbalance=%.1fx (>= %.1fx)",
                trapSide, level.strike(), trappedOi, imbalance, threshold));
        reasons.add(String.format("OI shift today: %+,d %s contracts at strike (= %+.1f%% of pre-open OI = %,d → %,d)",
                trapSideChange, trapSide, freshWritersPct, oiBeforeToday, trappedOi));
        reasons.add(String.format("Spot=%s proximity=%.2f%% — high-conviction shortcut path",
                spotPrice, proximity));

        log.info("[OiShiftTrap] {} imbalance-only signal: strike={} OI={} oiShiftToday={} ({}% of pre-open) imbalance={}x score={} proximity={}%",
                trapSide, level.strike(), trappedOi,
                String.format("%+,d", trapSideChange),
                String.format("%+.1f", freshWritersPct),
                String.format("%.1f", imbalance), score,
                String.format("%.2f", proximity));
        log.info("[OiShiftTrap] {} imbalance-only score breakdown: {}",
                trapSide, scoreBreakdown(imbalance, trappedOi, trappedChange, proximity, effTrend, huntBonus));

        SignalType signalType = "CE".equals(trapSide) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = "CE".equals(trapSide) ? OptionType.CE : OptionType.PE;
        BigDecimal lastPrice = "CE".equals(trapSide) ? level.callLastPrice() : level.putLastPrice();
        // A4 (2026-06-02): tick the OIST heartbeat from the signal path too, so
        // a stalled evaluation path doesn't appear silently dead when the
        // imbalance-only shortcut keeps emitting signals.
        if (oiShiftTrapHeartbeat != null) {
            try {
                oiShiftTrapHeartbeat.recordTick(IndexType.from(underlying));
            } catch (Exception ex) {
                log.debug("[OiShiftTrap] heartbeat signal-tick failed (non-fatal): {}", ex.getMessage());
            }
        }
        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.ofNullable(lastPrice).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(optionType),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        ));
    }

    /**
     * Feature 6 — distant-OI convergence scan. Also registers OI-strong-but-non-converging
     * candidates with the pending registry (feature 9 producer side).
     */
    private Optional<StrategyDecision> scanDistantStrikes(OptionChainSnapshot snapshot,
                                                            List<OptionChainLevel> sorted,
                                                            OptionChainLevel atm,
                                                            BigDecimal spotPrice,
                                                            UnderlyingSymbol underlying,
                                                            int trendDirection,
                                                            double effectiveImbalance,
                                                            double effectiveProximity,
                                                            List<Candle> candles,
                                                            String[] hardBlocker) {
        if (!enhancementsOn()) {
            return Optional.empty();
        }
        int maxStrikes = shiftTrapConfig.getDistantOiMaxStrikes();
        if (maxStrikes <= 0) {
            return Optional.empty();
        }
        int atmIndex = sorted.indexOf(atm);
        if (atmIndex < 0) {
            return Optional.empty();
        }

        if (trendDirection >= 0) {
            int upper = Math.min(sorted.size(), atmIndex + 1 + maxStrikes);
            double spotForDistant = spotPrice.doubleValue();
            for (int i = atmIndex + 1; i < upper; i++) {
                OptionChainLevel level = sorted.get(i);
                // 2026-06-01: skip strikes the main loop already evaluated. Distant scan
                // only inspects strikes BEYOND the standard proximity window.
                double proximityFromSpot = (level.strike().doubleValue() - spotForDistant) / spotForDistant * 100.0;
                if (proximityFromSpot <= effectiveProximity) continue;
                if (level.callOpenInterest() < MIN_ABSOLUTE_OI) continue;
                if (level.callOpenInterestChange() < MIN_OI_CHANGE) continue;
                double imb = level.callOpenInterest() / (double) Math.max(level.putOpenInterest(), 1);
                if (imb < effectiveImbalance) continue;
                // Phase 5 — high-imbalance Telegram watch alert (distant strike)
                maybeAlertHighImbalance(underlying, "CE", level, spotPrice);
                if (convergenceTracker == null || !convergenceTracker.isConverging(underlying, level.strike())) {
                    registerPendingCandidate(underlying, "CE", level.strike(),
                            imb, level.callOpenInterest(), effectiveProximity);
                    continue;
                }
                if (!pcrPassesIfRequired(underlying, "CE")) {
                    hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
                    continue;
                }
                int score = Math.min(90, DISTANT_OI_MIN_SCORE
                        + liquidityHuntBonus(candles, level.strike(), spotPrice));
                if (!crossIndexPasses(underlying, "CE", score)) {
                    hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
                    continue;
                }
                return Optional.of(buildDistantDecision(level, spotPrice, underlying, "CE", imb, score));
            }
        }

        if (trendDirection <= 0) {
            int lower = Math.max(0, atmIndex - maxStrikes);
            double spotForDistantPe = spotPrice.doubleValue();
            for (int i = atmIndex - 1; i >= lower; i--) {
                OptionChainLevel level = sorted.get(i);
                // 2026-06-01: skip strikes the main loop already evaluated.
                double proximityFromSpot = (spotForDistantPe - level.strike().doubleValue()) / spotForDistantPe * 100.0;
                if (proximityFromSpot <= effectiveProximity) continue;
                if (level.putOpenInterest() < MIN_ABSOLUTE_OI) continue;
                if (level.putOpenInterestChange() < MIN_OI_CHANGE) continue;
                double imb = level.putOpenInterest() / (double) Math.max(level.callOpenInterest(), 1);
                if (imb < effectiveImbalance) continue;
                // Phase 5 — high-imbalance Telegram watch alert (distant strike)
                maybeAlertHighImbalance(underlying, "PE", level, spotPrice);
                if (convergenceTracker == null || !convergenceTracker.isConverging(underlying, level.strike())) {
                    registerPendingCandidate(underlying, "PE", level.strike(),
                            imb, level.putOpenInterest(), effectiveProximity);
                    continue;
                }
                if (!pcrPassesIfRequired(underlying, "PE")) {
                    hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
                    continue;
                }
                int score = Math.min(90, DISTANT_OI_MIN_SCORE
                        + liquidityHuntBonus(candles, level.strike(), spotPrice));
                if (!crossIndexPasses(underlying, "PE", score)) {
                    hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
                    continue;
                }
                return Optional.of(buildDistantDecision(level, spotPrice, underlying, "PE", imb, score));
            }
        }
        return Optional.empty();
    }

    private StrategyDecision buildDistantDecision(OptionChainLevel level, BigDecimal spotPrice,
                                                    UnderlyingSymbol underlying, String trapSide,
                                                    double imbalance, int score) {
        SignalType signalType = "CE".equals(trapSide) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = "CE".equals(trapSide) ? OptionType.CE : OptionType.PE;
        BigDecimal lastPrice = "CE".equals(trapSide) ? level.callLastPrice() : level.putLastPrice();
        long trappedOi = "CE".equals(trapSide) ? level.callOpenInterest() : level.putOpenInterest();

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("OI Shift Trap [distant-OI]: %s-heavy distant strike=%s OI=%,d imbalance=%.1fx",
                trapSide, level.strike(), trappedOi, imbalance));
        reasons.add(String.format("Spot=%s converging toward distant strike — pre-trap setup",
                spotPrice));

        log.info("[OiShiftTrap] {} distant-OI signal: strike={} OI={} imbalance={}x score={}",
                trapSide, level.strike(), trappedOi, String.format("%.1f", imbalance), score);

        return new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.ofNullable(lastPrice).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(optionType),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        );
    }

    /**
     * Feature 9 — pending signal resolution. Runs AFTER volume + fake-breakout gates so a
     * pending entry cannot bypass safety blockers. PCR and cross-index still apply.
     */
    private Optional<StrategyDecision> tryPendingResolution(OptionChainSnapshot snapshot,
                                                              BigDecimal spotPrice,
                                                              UnderlyingSymbol underlying,
                                                              String[] hardBlocker) {
        if (!enhancementsOn() || pendingSignalRegistry == null) {
            return Optional.empty();
        }
        int ttl = shiftTrapConfig.getPendingSignalTtlMinutes();
        for (String side : new String[]{"CE", "PE"}) {
            Optional<ShiftTrapPendingSignalRegistry.PendingEntry> readyOpt =
                    pendingSignalRegistry.checkReady(underlying, side, spotPrice, ttl);
            if (readyOpt.isEmpty()) {
                continue;
            }
            ShiftTrapPendingSignalRegistry.PendingEntry ready = readyOpt.get();
            OptionChainLevel level = snapshot.levels().stream()
                    .filter(l -> l.strike().compareTo(ready.targetStrike()) == 0)
                    .findFirst().orElse(null);
            if (level == null) {
                continue;
            }
            // 2026-06-01: re-verify the OI thesis still holds at the current tick.
            // Pending may have been registered minutes ago — writers may have unwound
            // since then. If imbalance / OI / buildup no longer pass, drop silently.
            long trappedOiNow = "CE".equals(side) ? level.callOpenInterest() : level.putOpenInterest();
            long oppositeOiNow = "CE".equals(side) ? level.putOpenInterest() : level.callOpenInterest();
            long trappedChangeNow = "CE".equals(side) ? level.callOpenInterestChange() : level.putOpenInterestChange();
            if (trappedOiNow < MIN_ABSOLUTE_OI || trappedChangeNow < MIN_OI_CHANGE) {
                continue;
            }
            double imbalanceNow = trappedOiNow / (double) Math.max(oppositeOiNow, 1);
            if (imbalanceNow < effectiveImbalanceRatio(underlying)) {
                continue;
            }
            if (!pcrPassesIfRequired(underlying, side)) {
                hardBlocker[0] = BLOCKER_PCR_MISALIGNED;
                continue;
            }
            int score = Math.min(90, PENDING_RESOLUTION_MIN_SCORE);
            if (!crossIndexPasses(underlying, side, score)) {
                hardBlocker[0] = BLOCKER_CROSS_INDEX_DISAGREEMENT;
                continue;
            }
            log.info("[OiShiftTrap] {} pending RESOLVED: {} strike={} imbalance={}x", underlying, side,
                    ready.targetStrike(), String.format("%.1f", ready.imbalance()));
            return Optional.of(buildPendingDecision(level, spotPrice, underlying, side, ready.imbalance(), score));
        }
        return Optional.empty();
    }

    private StrategyDecision buildPendingDecision(OptionChainLevel level, BigDecimal spotPrice,
                                                    UnderlyingSymbol underlying, String trapSide,
                                                    double imbalance, int score) {
        SignalType signalType = "CE".equals(trapSide) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = "CE".equals(trapSide) ? OptionType.CE : OptionType.PE;
        BigDecimal lastPrice = "CE".equals(trapSide) ? level.callLastPrice() : level.putLastPrice();
        long trappedOi = "CE".equals(trapSide) ? level.callOpenInterest() : level.putOpenInterest();

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("OI Shift Trap [pending-resolved]: %s-heavy strike=%s OI=%,d imbalance=%.1fx",
                trapSide, level.strike(), trappedOi, imbalance));
        reasons.add(String.format("Spot=%s reached pending trap strike — OI-first setup confirmed",
                spotPrice));

        return new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.ofNullable(lastPrice).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(optionType),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        );
    }

    private void registerPendingCandidate(UnderlyingSymbol underlying, String trapSide,
                                            BigDecimal strike, double imbalance, long trappedOi,
                                            double proximityPercent) {
        if (pendingSignalRegistry == null) {
            return;
        }
        try {
            pendingSignalRegistry.registerPending(underlying, trapSide, strike,
                    imbalance, trappedOi, proximityPercent);
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] pending registration failed: {}", ex.toString());
        }
    }

    /** Graduated confidence score based on signal quality. */
    private int calculateScore(double imbalance, long absoluteOi, long oiChange,
                                double proximity, int trendStrength) {
        int score = effectiveScoreBase();
        if (imbalance > 4.0) score += 10;
        if (absoluteOi > 200_000) score += 10;
        if (oiChange > 50_000) score += 5;
        if (proximity < 0.15) score += 10;
        if (trendStrength > 0) score += 5;
        return Math.min(90, score);
    }

    /**
     * 2026-06-01 — return a human-readable breakdown of which bonuses contributed.
     * Used by signal-fire INFO logs and near-miss INFO logs so the operator can see
     * exactly which condition pushed (or failed to push) the score over the threshold.
     */
    private String scoreBreakdown(double imbalance, long absoluteOi, long oiChange,
                                   double proximity, int trendStrength, int huntBonus) {
        return String.format(
                "base=%d imb%.1fx=+%d absOi=%d=+%d oiChg=%d=+%d prox=%.2f%%=+%d trend=%d=+%d hunt=+%d",
                effectiveScoreBase(),
                imbalance, imbalance > 4.0 ? 10 : 0,
                absoluteOi, absoluteOi > 200_000 ? 10 : 0,
                oiChange, oiChange > 50_000 ? 5 : 0,
                proximity, proximity < 0.15 ? 10 : 0,
                trendStrength, trendStrength > 0 ? 5 : 0,
                huntBonus);
    }

    /** Short-term trend from the most recent N candles. */
    private int detectShortTermTrend(List<Candle> candles) {
        int lookback = effectiveTrendLookback();
        if (candles.size() < lookback) return 0;
        List<Candle> recent = candles.subList(candles.size() - lookback, candles.size());
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            int cmp = recent.get(i).close().compareTo(recent.get(i - 1).close());
            if (cmp > 0) up++;
            else if (cmp < 0) down++;
        }
        int transitions = recent.size() - 1;
        int majority = transitions == 2 ? 2 : (transitions / 2) + 1;
        if (up >= majority) return 1;
        if (down >= majority) return -1;
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1+2+3+4 helper methods — gated by the master toggle.
    // ─────────────────────────────────────────────────────────────────────────

    private boolean enhancementsOn() {
        return shiftTrapConfig != null && shiftTrapConfig.isEnhancementsEnabled();
    }

    private boolean isExpiryDay(UnderlyingSymbol underlying) {
        if (expiryCalendar == null || underlying == null) {
            return false;
        }
        try {
            return expiryCalendar.isExpiryDay(IndexType.from(underlying));
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] expiry-day lookup failed for {}: {}", underlying, ex.toString());
            return false;
        }
    }

    private double effectiveImbalanceRatio(UnderlyingSymbol underlying) {
        if (!enhancementsOn()) {
            return OI_IMBALANCE_RATIO;
        }
        return isExpiryDay(underlying)
                ? shiftTrapConfig.getExpiryDayImbalanceRatio()
                : shiftTrapConfig.getNonExpiryImbalanceRatio();
    }

    private double effectiveProximityPercent(UnderlyingSymbol underlying) {
        if (!enhancementsOn()) {
            return PROXIMITY_PERCENT;
        }
        return isExpiryDay(underlying)
                ? shiftTrapConfig.getExpiryDayProximityPercent()
                : shiftTrapConfig.getNonExpiryProximityPercent();
    }

    private int effectiveMinConfidenceScore() {
        return enhancementsOn() ? shiftTrapConfig.getMinConfidenceScore() : LEGACY_MIN_CONFIDENCE;
    }

    private int effectiveScoreBase() {
        return enhancementsOn() ? shiftTrapConfig.getScoreBase() : LEGACY_SCORE_BASE;
    }

    private int effectiveTrendLookback() {
        return enhancementsOn() ? shiftTrapConfig.getTrendLookbackCandles() : 3;
    }

    private boolean pcrPassesIfRequired(UnderlyingSymbol underlying, String trapSide) {
        if (!enhancementsOn() || liveInstrumentCache == null || underlying == null) {
            return true;
        }
        try {
            double pcr = liveInstrumentCache.getRealtimePcr(IndexType.from(underlying));
            if (pcr <= 0) {
                return true;
            }
            return "PE".equals(trapSide) ? pcr > 1.0 : pcr < 1.0;
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] PCR lookup failed for {}: {}", underlying, ex.toString());
            return true;
        }
    }

    private boolean fakeBreakoutBlocks(List<Candle> candles) {
        if (!enhancementsOn() || fakeBreakoutDetector == null) {
            return false;
        }
        try {
            return fakeBreakoutDetector.isFakeBreakout(candles,
                    shiftTrapConfig.getFakeBreakoutVolumeMultiplier(),
                    shiftTrapConfig.getFakeBreakoutReversalPercent());
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] fake-breakout detector failed: {}", ex.toString());
            return false;
        }
    }

    private boolean oiAbsorptionBlocks(UnderlyingSymbol underlying, OptionChainSnapshot snapshot,
                                        BigDecimal spotPrice, int trendDirection) {
        if (!enhancementsOn() || oiAbsorptionDetector == null) {
            return false;
        }
        try {
            return oiAbsorptionDetector.check(underlying, snapshot, spotPrice, trendDirection,
                    shiftTrapConfig.getOiAbsorptionWallOiThreshold())
                    == ShiftTrapOiAbsorptionDetector.Decision.OVERRIDE_NO_ENTRY;
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] OI absorption detector failed: {}", ex.toString());
            return false;
        }
    }

    private int liquidityHuntBonus(List<Candle> candles, BigDecimal trapStrike, BigDecimal spotPrice) {
        if (!enhancementsOn() || liquidityHuntDetector == null) {
            return 0;
        }
        try {
            boolean hunt = liquidityHuntDetector.isHuntPattern(candles, trapStrike, spotPrice,
                    shiftTrapConfig.getLiquidityHuntProximityPercent());
            return hunt ? LIQUIDITY_HUNT_BONUS : 0;
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] liquidity-hunt detector failed: {}", ex.toString());
            return 0;
        }
    }

    /**
     * Feature 10 — cross-index agreement gate. When {@code crossIndexMinAgreement <= 1}
     * the validator becomes a no-op (still records the candidate for other indices to see).
     * This makes single-index deployments safe.
     */
    private boolean crossIndexPasses(UnderlyingSymbol underlying, String trapSide, int score) {
        if (!enhancementsOn() || crossIndexValidator == null) {
            return true;
        }
        try {
            int min = shiftTrapConfig.getCrossIndexMinAgreement();
            crossIndexValidator.registerCandidate(underlying, trapSide, score);
            if (min <= 1) {
                return true; // single-index-safe: own registration counts; no peer needed.
            }
            boolean ok = crossIndexValidator.hasMinimumAgreement(underlying, trapSide, min);
            if (!ok) {
                log.info("[OiShiftTrap] cross-index disagreement: {} side={} min={}",
                        underlying, trapSide, min);
            }
            return ok;
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] cross-index validator failed: {}", ex.toString());
            return true;
        }
    }

    /** Phase 4 — days-to-expiry helper. Returns 0 when no calendar bean. */
    private int effectiveDte(UnderlyingSymbol underlying) {
        if (expiryCalendar == null || underlying == null) {
            return 0;
        }
        try {
            return (int) expiryCalendar.daysToExpiry(IndexType.from(underlying));
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * Phase 4 — relative volume helper. Returns {@code latestVolume / avg(prior 5 bars)}.
     * Returns 0.0 when there aren't enough candles to form the average.
     */
    private double relativeVolume(List<Candle> candles) {
        if (candles == null || candles.size() < 6) {
            return 0.0;
        }
        long sum = 0;
        for (int i = candles.size() - 6; i < candles.size() - 1; i++) {
            sum += candles.get(i).volume();
        }
        double avg = sum / 5.0;
        if (avg <= 0) {
            return 0.0;
        }
        return candles.get(candles.size() - 1).volume() / avg;
    }

    /**
     * Phase 5 — emit a Telegram watch alert when a candidate strike's OI imbalance crosses
     * {@code imbalanceAlertRatio}, regardless of whether the signal fires. Cooldown +
     * threshold logic lives in {@link ShiftTrapHighImbalanceAlertService}. No-op when the
     * service is missing, enhancements are OFF, or the strike's OI is below the minimum.
     */
    private void maybeAlertHighImbalance(UnderlyingSymbol underlying, String trapSide,
                                          OptionChainLevel level, BigDecimal spotPrice) {
        if (highImbalanceAlertService == null || level == null) {
            return;
        }
        try {
            long trappedOi = "CE".equals(trapSide) ? level.callOpenInterest() : level.putOpenInterest();
            long oppositeOi = "CE".equals(trapSide) ? level.putOpenInterest() : level.callOpenInterest();
            if (trappedOi < MIN_ABSOLUTE_OI) {
                return;
            }
            double imbalance = trappedOi / (double) Math.max(oppositeOi, 1);
            highImbalanceAlertService.maybeAlert(underlying, trapSide, level.strike(),
                    imbalance, trappedOi, spotPrice);
        } catch (Exception ex) {
            log.debug("[OiShiftTrap] high-imbalance alert failed: {}", ex.toString());
        }
    }
}
