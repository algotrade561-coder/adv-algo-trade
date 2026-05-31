package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.tuning.adapter.TuningCaptureBridge;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Phase 6 — records strategy evaluations into the unified tuning pipeline only.
 * Replaces legacy {@code entry-signals.csv} dual-write.
 */
@Service
public class PipelineSignalCapture {

    private static final Logger log = LoggerFactory.getLogger(PipelineSignalCapture.class);

    @Autowired(required = false)
    private TuningCaptureBridge tuningCaptureBridge;

    public synchronized void recordUnified(SignalRecordContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            if (tuningCaptureBridge != null) {
                tuningCaptureBridge.record(ctx);
            }
        } catch (Exception ex) {
            log.warn("Pipeline signal capture failed for {} (non-fatal): {}",
                    ctx.strategyType(), ex.getMessage());
        }
    }

    public synchronized void record(
            StrategyEvaluationRequest request,
            StrategyDecision decision,
            OptionChainAnalysis chain,
            BigDecimal vwap,
            boolean breakoutPassed,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed,
            boolean timePassed,
            Double rsiValue,
            Double atrValue,
            Double ema9Ema21Gap,
            Double bidAskSpread,
            Double vixLevel,
            Long daysToExpiry,
            Double delta,
            Double gamma,
            Double theta,
            Double vega,
            Double realizedVol5d,
            Double ivSkew
    ) {
        recordUnified(SignalRecordContext.builder()
                .strategyType("DIRECTIONAL_BUY")
                .underlying(request.underlying())
                .decision(decision)
                .selectedInstrumentKey(request.selectedInstrumentKey())
                .selectedStrike(request.selectedStrike())
                .underlyingCandles(request.underlyingCandles())
                .trendCandles(request.trendUnderlyingCandles())
                .optionCandles(request.selectedOptionCandles())
                .vwap(vwap)
                .breakoutPassed(breakoutPassed)
                .oiPassed(oiPassed)
                .ivPassed(ivPassed)
                .liquidityPassed(liquidityPassed)
                .timePassed(timePassed)
                .rsiValue(rsiValue)
                .atrValue(atrValue)
                .ema9Ema21Gap(ema9Ema21Gap)
                .bidAskSpread(bidAskSpread)
                .vixLevel(vixLevel)
                .daysToExpiry(daysToExpiry)
                .delta(delta)
                .gamma(gamma)
                .theta(theta)
                .vega(vega)
                .realizedVol5d(realizedVol5d)
                .ivSkew(ivSkew)
                .ivRank(request.ivRank())
                .build());
    }

    public synchronized void recordAdditionalNoTrade(
            String strategyType,
            String underlying,
            BigDecimal spotPrice,
            String reason,
            List<Candle> underlyingCandles,
            StrategyDiagnostics diagnostics,
            double ivRank
    ) {
        recordAdditionalNoTrade(strategyType, underlying, spotPrice, reason,
                underlyingCandles, diagnostics, ivRank, null, null);
    }

    public synchronized void recordAdditionalNoTrade(
            String strategyType,
            String underlying,
            BigDecimal spotPrice,
            String reason,
            List<Candle> underlyingCandles,
            StrategyDiagnostics diagnostics,
            double ivRank,
            String selectedInstrumentKey,
            BigDecimal selectedStrike
    ) {
        if (diagnostics == null) {
            diagnostics = StrategyDiagnostics.NONE;
        }
        recordUnified(SignalRecordContext.builder()
                .strategyType(strategyType)
                .underlying(UnderlyingSymbol.valueOf(underlying))
                .decision(new StrategyDecision(
                        java.time.Instant.now(),
                        UnderlyingSymbol.valueOf(underlying),
                        com.algo.trade.domain.SignalType.NO_TRADE,
                        spotPrice,
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), false, java.util.Optional.empty(), false,
                        java.math.BigDecimal.ZERO, java.util.List.of(reason)))
                .underlyingCandles(underlyingCandles != null ? underlyingCandles : List.of())
                .selectedInstrumentKey(selectedInstrumentKey)
                .selectedStrike(selectedStrike)
                .firstFailedFilter(diagnostics.firstFailedFilter() != null
                        ? diagnostics.firstFailedFilter() : reason)
                .executionStage("NO_TRADE")
                .diagnostics(diagnostics)
                .ivRank(ivRank)
                .build());
    }
}
