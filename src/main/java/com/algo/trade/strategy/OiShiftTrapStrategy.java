package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapDiagnostics;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapLadderManager;
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
 * Quality gates (vs. the original version):
 *   1. Minimum absolute OI threshold — filters out illiquid noise
 *   2. OI must be building (change > 0) — unwinding writers don't get squeezed
 *   3. Proximity tightened — strike must be within 1-2 strikes of spot
 *   4. Volume confirmation — underlying must show activity
 *   5. Trend alignment — price should be moving toward the trap strike
 *   6. Graduated confidence score based on signal quality
 */
@Component
public class OiShiftTrapStrategy {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapStrategy.class);
    /** Minimum OI imbalance ratio (trapped side / opposite side). */
    private static final double OI_IMBALANCE_RATIO = 1.5;

    /** Maximum distance from spot to trap strike as % of spot price. */
    private static final double PROXIMITY_PERCENT = 0.75;

    /** Minimum absolute OI on the trapped side to be meaningful. */
    private static final long MIN_ABSOLUTE_OI = 50_000;

    /** Minimum OI change (buildup) on the trapped side — writers must be adding, not unwinding. */
    private static final long MIN_OI_CHANGE = 3_000;

    /** Minimum underlying volume in the last candle to confirm market activity. */
    private static final long MIN_UNDERLYING_VOLUME = 1_000;

    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    /**
     * Optional limit-ladder manager. When present and enabled, candidates
     * produced by the legacy gate matrix are handed off to the ladder
     * (which places three discounted limit BUY orders) instead of firing
     * the legacy immediate-entry. When disabled, legacy behaviour is
     * preserved verbatim.
     */
    @Autowired(required = false)
    private OiShiftTrapLadderManager ladderManager;

    public OiShiftTrapStrategy(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService) {
        this.underlyingConfigService = underlyingConfigService;
    }

    /** Test-only: inject a ladder manager stub. */
    void setLadderManager(OiShiftTrapLadderManager mgr) {
        this.ladderManager = mgr;
    }

    public record TrapEvaluation(
            Optional<StrategyDecision> signal,
            OiShiftTrapDiagnostics diagnostics
    ) {
    }

    public TrapEvaluation evaluateWithDiagnostics(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                   StrategyConfig config, UnderlyingSymbol underlying,
                                                   List<Candle> underlyingCandles) {
        // ── Ladder poll: drain any tier fills / cancellations from prior arms ──
        OiShiftTrapDiagnostics lastLadderDiag = null;
        if (ladderManager != null && ladderManager.isEnabled()) {
            IndexType idx = IndexType.from(underlying);
            for (OiShiftTrapLadderManager.FillResult fr : ladderManager.poll(idx)) {
                if ("TIER_FILLED".equals(fr.event()) && fr.decision() != null) {
                    log.info("[OiShiftTrap] LADDER FILL tier{} side={} strike={}",
                            fr.tierIndex(), fr.diagnostics().trapSide(),
                            fr.diagnostics().signalStrike());
                    return new TrapEvaluation(Optional.of(fr.decision()), fr.diagnostics());
                }
                // Shadow-mode FILL or CANCEL — diagnostic only.
                lastLadderDiag = fr.diagnostics();
            }
        }

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
                        false, "", BigDecimal.ZERO, 0));
            }
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

        double spot = spotPrice.doubleValue();
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
                if (proximity > PROXIMITY_PERCENT) {
                    break;
                }
                bestCe = betterCandidate(bestCe, evaluateCeCandidate(level, proximity, trendDirection));
                Optional<StrategyDecision> signal = tryCeSignal(level, spotPrice, underlying, proximity, trendDirection);
                if (signal.isPresent()) {
                    OiShiftTrapDiagnostics succDiag = buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "CE", level.strike(),
                            signal.get().confidenceScore().intValue());
                    return maybeArmLadderOrEmit(signal, succDiag, underlying, OptionType.CE,
                            level.strike(), level.callLastPrice(), config);
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
                if (proximity > PROXIMITY_PERCENT) {
                    break;
                }
                bestPe = betterCandidate(bestPe, evaluatePeCandidate(level, proximity, trendDirection));
                Optional<StrategyDecision> signal = tryPeSignal(level, spotPrice, underlying, proximity, trendDirection);
                if (signal.isPresent()) {
                    OiShiftTrapDiagnostics succDiag = buildSuccessDiag(
                            underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                            snapshot.levels().size(), bestCe, bestPe, "PE", level.strike(),
                            signal.get().confidenceScore().intValue());
                    return maybeArmLadderOrEmit(signal, succDiag, underlying, OptionType.PE,
                            level.strike(), level.putLastPrice(), config);
                }
            }
        }

        // If no legacy candidate fired this tick but the ladder produced a
        // CANCEL / shadow-FILL diagnostic, prefer that — it carries real
        // information for the tuning pipeline.
        if (lastLadderDiag != null) {
            return new TrapEvaluation(Optional.empty(), lastLadderDiag);
        }
        String primaryBlocker = resolvePrimaryBlocker(trendDirection, bestCe, bestPe);
        String outcome = trendDirection == 0 ? "TREND_FLAT" : "NO_TRAP_MATCH";
        return new TrapEvaluation(Optional.empty(), new OiShiftTrapDiagnostics(
                underlying.name(), spotPrice, trendDirection, volumeMode, latestVolume,
                outcome, primaryBlocker, snapshot.levels().size(),
                bestCe, bestPe, false, "", BigDecimal.ZERO, 0));
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

    /**
     * Decide whether to emit the legacy signal immediately or hand off to
     * the ladder. The ladder REPLACES the legacy entry when enabled — the
     * legacy signal is suppressed and three discounted tiers are placed.
     * When the ladder is OFF (or rejects the arm due to op-score floor),
     * the legacy signal fires as it always has.
     */
    private TrapEvaluation maybeArmLadderOrEmit(Optional<StrategyDecision> signal,
                                                 OiShiftTrapDiagnostics succDiag,
                                                 UnderlyingSymbol underlying,
                                                 OptionType side,
                                                 BigDecimal strike,
                                                 BigDecimal ltp,
                                                 StrategyConfig config) {
        if (ladderManager == null || !ladderManager.isEnabled()) {
            return new TrapEvaluation(signal, succDiag);
        }
        double ltpDouble = ltp != null && ltp.signum() > 0 ? ltp.doubleValue() : 0.0;
        int totalLots = config != null ? Math.max(1, config.getLots()) : 3;
        boolean armed = ladderManager.arm(IndexType.from(underlying), side,
                strike.intValue(), ltpDouble, totalLots, succDiag);
        if (!armed) {
            // Ladder rejected the arm (e.g. op-score below floor). Fall back
            // to legacy fire so we don't lose the trade entirely.
            log.info("[OiShiftTrap] ladder declined to arm — falling back to legacy entry");
            return new TrapEvaluation(signal, succDiag);
        }
        // Suppress the legacy signal. The ladder will emit tier fills on
        // subsequent ticks via poll().
        OiShiftTrapDiagnostics armedDiag = succDiag.withLadderInfo(
                OiShiftTrapDiagnostics.LadderInfo.armed(
                        java.time.Instant.now(), ltpDouble, 0, 0,
                        ladderManager.config() == null ? "OFF" : ladderManager.config().getLadderMode(),
                        ltpDouble * (1 - safeTier(ladderManager.config(), 1)),
                        ltpDouble * (1 - safeTier(ladderManager.config(), 2)),
                        ltpDouble * (1 - safeTier(ladderManager.config(), 3))));
        return new TrapEvaluation(Optional.empty(), armedDiag);
    }

    private static double safeTier(com.algo.trade.strategy.oishifttrap.OiShiftTrapLadderConfig c, int idx) {
        if (c == null) return 0;
        return switch (idx) {
            case 1 -> c.getTier1Discount();
            case 2 -> c.getTier2Discount();
            case 3 -> c.getTier3Discount();
            default -> 0;
        };
    }

    private static OiShiftTrapDiagnostics buildSuccessDiag(
            String underlying, BigDecimal spot, int trend, String volumeMode, long volume,
            int chainLevels, OiShiftTrapDiagnostics.CandidateSnapshot bestCe,
            OiShiftTrapDiagnostics.CandidateSnapshot bestPe, String trapSide,
            BigDecimal strike, int score) {
        return new OiShiftTrapDiagnostics(
                underlying, spot, trend, volumeMode, volume,
                "SIGNAL", "passed", chainLevels,
                bestCe, bestPe, true, trapSide, strike, score);
    }

    private static String resolvePrimaryBlocker(int trendDirection,
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
        return best.failedGate().isBlank() ? "score_below_50" : best.failedGate();
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
            OptionChainLevel level, double proximity, int trendDirection) {
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
        if (imbalance < OI_IMBALANCE_RATIO) {
            return snap(level.strike(), ceOi, peOi, ceOiChange, imbalance, proximity, 0, "IMBALANCE");
        }
        int score = calculateScore(imbalance, ceOi, ceOiChange, proximity, trendDirection);
        String gate = score < 50 ? "SCORE" : "";
        return snap(level.strike(), ceOi, peOi, ceOiChange, imbalance, proximity, score, gate);
    }

    private OiShiftTrapDiagnostics.CandidateSnapshot evaluatePeCandidate(
            OptionChainLevel level, double proximity, int trendDirection) {
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
        if (imbalance < OI_IMBALANCE_RATIO) {
            return snap(level.strike(), peOi, ceOi, peOiChange, imbalance, proximity, 0, "IMBALANCE");
        }
        int score = calculateScore(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection));
        String gate = score < 50 ? "SCORE" : "";
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
                                                     double proximity, int trendDirection) {
        long ceOi = level.callOpenInterest();
        long peOi = level.putOpenInterest();
        long ceOiChange = level.callOpenInterestChange();
        if (ceOi < MIN_ABSOLUTE_OI || ceOiChange < MIN_OI_CHANGE) {
            return Optional.empty();
        }
        double imbalance = ceOi / (double) Math.max(peOi, 1);
        if (imbalance < OI_IMBALANCE_RATIO) {
            return Optional.empty();
        }
        int score = calculateScore(imbalance, ceOi, ceOiChange, proximity, trendDirection);
        if (score < 50) {
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
                                                     double proximity, int trendDirection) {
        long peOi = level.putOpenInterest();
        long ceOi = level.callOpenInterest();
        long peOiChange = level.putOpenInterestChange();
        if (peOi < MIN_ABSOLUTE_OI || peOiChange < MIN_OI_CHANGE) {
            return Optional.empty();
        }
        double imbalance = peOi / (double) Math.max(ceOi, 1);
        if (imbalance < OI_IMBALANCE_RATIO) {
            return Optional.empty();
        }
        int score = calculateScore(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection));
        if (score < 50) {
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

        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, SignalType.BUY_PE, spotPrice,
                Optional.ofNullable(level.putLastPrice()).filter(p -> p.signum() > 0),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.PE),
                false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                BigDecimal.valueOf(score), reasons
        ));
    }

    /**
     * Graduated confidence score based on signal quality.
     *   Base: 50
     *   +10 if imbalance > 4x (very heavy)
     *   +10 if absolute OI > 200K (institutional size)
     *   +5  if OI change > 50K (aggressive buildup)
     *   +10 if proximity < 0.15% (very close to strike)
     *   +5  if trend aligns (price moving toward trap)
     *   Max: 90
     */
    private int calculateScore(double imbalance, long absoluteOi, long oiChange,
                                double proximity, int trendStrength) {
        int score = 50;
        if (imbalance > 4.0) score += 10;
        if (absoluteOi > 200_000) score += 10;
        if (oiChange > 50_000) score += 5;
        if (proximity < 0.15) score += 10;
        if (trendStrength > 0) score += 5;
        return Math.min(90, score);
    }

    /**
     * Short-term trend from last 3 candles.
     * @return +1 bullish (higher closes), -1 bearish (lower closes), 0 flat/mixed
     */
    private int detectShortTermTrend(List<Candle> candles) {
        if (candles.size() < 3) return 0;
        List<Candle> recent = candles.subList(candles.size() - 3, candles.size());
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            int cmp = recent.get(i).close().compareTo(recent.get(i - 1).close());
            if (cmp > 0) up++;
            else if (cmp < 0) down++;
        }
        if (up >= 2) return 1;
        if (down >= 2) return -1;
        return 0;
    }
}
