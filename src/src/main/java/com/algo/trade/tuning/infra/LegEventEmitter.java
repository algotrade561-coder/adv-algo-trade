package com.algo.trade.tuning.infra;

import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.SpreadOrderExecutor;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.LegEvent;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Phase 5 — emits {@link LegEvent} rows on spread leg state transitions. */
@Component
public class LegEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(LegEventEmitter.class);

    private final TuningEventRecorder recorder;
    private final SpreadTradingProperties spreadProperties;

    @Autowired
    public LegEventEmitter(TuningEventRecorder recorder, SpreadTradingProperties spreadProperties) {
        this.recorder = recorder;
        this.spreadProperties = spreadProperties;
    }

    public void emitLeg(StrategyType strategy,
                          UnderlyingSymbol underlying,
                          String groupId,
                          int legNumber,
                          SpreadLeg leg,
                          String stage,
                          int requestedQty,
                          int filledQty,
                          BigDecimal avgFillPrice) {
        if (recorder == null || spreadProperties == null || !spreadProperties.legEventCaptureEnabled()) {
            return;
        }
        try {
            recorder.record(new LegEvent(
                    Instant.now(),
                    Instant.now(),
                    strategy,
                    IndexType.from(underlying),
                    groupId,
                    legNumber,
                    leg.side().name(),
                    leg.optionType(),
                    leg.strike(),
                    leg.instrumentKey(),
                    stage,
                    requestedQty,
                    filledQty,
                    avgFillPrice != null ? avgFillPrice : BigDecimal.ZERO,
                    Map.of()));
        } catch (Exception ex) {
            log.warn("[LegEventEmitter] dual-write failed for {} leg {} (non-fatal): {}",
                    groupId, legNumber, ex.getMessage());
        }
    }

    public void emitFromLegResults(StrategyType strategy,
                                   UnderlyingSymbol underlying,
                                   String groupId,
                                   List<SpreadOrderExecutor.LegResult> results,
                                   String successStage,
                                   String failureStage) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            SpreadOrderExecutor.LegResult result = results.get(i);
            String stage = result.success() ? successStage : failureStage;
            int filled = result.filledQuantity() > 0 ? result.filledQuantity() : result.leg().quantity();
            if (!result.success()) {
                filled = 0;
            }
            emitLeg(strategy, underlying, groupId, i + 1, result.leg(), stage,
                    result.leg().quantity(), filled, result.fillPrice().orElse(null));
        }
    }

    public void emitPaperEntryLegs(StrategyType strategy,
                                   UnderlyingSymbol underlying,
                                   String groupId,
                                   List<SpreadLeg> legs,
                                   Map<String, BigDecimal> fillPrices) {
        if (legs == null) {
            return;
        }
        for (int i = 0; i < legs.size(); i++) {
            SpreadLeg leg = legs.get(i);
            BigDecimal price = fillPrices != null ? fillPrices.get(leg.instrumentKey()) : null;
            emitLeg(strategy, underlying, groupId, i + 1, leg, "FILLED",
                    leg.quantity(), leg.quantity(), price);
        }
    }
}
