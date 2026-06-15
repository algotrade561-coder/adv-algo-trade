package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal OI Momentum Strategy — unified logic for ALL trading days.
 *
 * STANDARD DAYS: Confirmation-heavy (Momentum + OI + PCR alignment required).
 * EXPIRY DAYS: Lightweight gamma scalps (Direction + ATM OI bias).
 * EVENT SPIKE: If index moves >0.4% in <10 minutes, immediate entry.
 *
 * Implements StrategyEvaluator for candle-driven execution pipeline.
 */
@Component
public class UniversalOIMomentumStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(UniversalOIMomentumStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final PCRMaxPainTracker pcrTracker;
    private final ExpiryCalendar expiryCalendar;
    private final OperatorIntentEngine operatorIntentEngine;
    private final OperatorTrapDetector trapDetector;

    @Value("${strategy.universal-oi.enabled:true}") private boolean enabled;
    @Value("${strategy.universal-oi.spike-threshold:0.4}") private double spikeThreshold;
    @Value("${strategy.universal-oi.pcr-bullish-threshold:1.05}") private double pcrBullishThreshold;
    @Value("${strategy.universal-oi.pcr-bearish-threshold:0.95}") private double pcrBearishThreshold;

    // Per-index tracking state
    private static class IndexState {
        double openPrice = 0;
        double rangeHigh = 0;
        double rangeLow = Double.MAX_VALUE;
        boolean rangeEstablished = false;
        String lastSignalDirection = null;
        int consecutiveSignalCount = 0;
    }

    private final Map<IndexType, IndexState> states = new ConcurrentHashMap<>();

    public UniversalOIMomentumStrategy(LiveCandleBuilder candleBuilder,
                                        LiveInstrumentCache instrumentCache,
                                        MarketDataService marketDataService,
                                        PCRMaxPainTracker pcrTracker,
                                        ExpiryCalendar expiryCalendar,
                                        OperatorIntentEngine operatorIntentEngine,
                                        OperatorTrapDetector trapDetector) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.pcrTracker = pcrTracker;
        this.expiryCalendar = expiryCalendar;
        this.operatorIntentEngine = operatorIntentEngine;
        this.trapDetector = trapDetector;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            states.put(idx, new IndexState());
        }
    }

    @Override
    public String strategyName() { return "UNIVERSAL_OI_MOMENTUM"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(9, 16); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(15, 15); }

    /**
     * Evaluate entry signal for the given underlying.
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        IndexType idx = mapToIndexType(underlying);
        if (idx == null) return Optional.empty();

        IndexState state = states.computeIfAbsent(idx, k -> new IndexState());
        long token = resolveToken(idx);
        List<Candle> candles5m = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles5m.size() < 3) return Optional.empty();

        double spot = instrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return Optional.empty();

        // Capture opening range
        captureOpeningRange(idx, state, spot, now);

        // Priority: Operator trap signal
        String operatorBias = trapDetector.getDistantOISetupBias(idx);
        if (operatorBias == null) operatorBias = trapDetector.getOIAbsorptionBias(idx);
        if (operatorBias != null) {
            return buildDecision(underlying, request, operatorBias,
                    "OPERATOR_TRAP_PRIORITY: " + operatorBias, 85);
        }

        boolean isExpiry = expiryCalendar.isExpiryDay(idx);

        // Spike detection
        Candle latest = candles5m.get(candles5m.size() - 1);
        Candle reference = candles5m.size() > 2 ? candles5m.get(candles5m.size() - 3) : candles5m.get(0);
        double movePct = ((latest.close().doubleValue() - reference.open().doubleValue())
                / reference.open().doubleValue()) * 100;
        if (Math.abs(movePct) >= spikeThreshold) {
            String spikeDir = movePct > 0 ? "BULLISH" : "BEARISH";
            return buildDecision(underlying, request, spikeDir,
                    "EVENT_SPIKE: " + String.format("%.2f", movePct) + "% move", 90);
        }

        // Standard entry: Momentum + OI + PCR
        String momentumSignal = detectMomentum(idx, state, candles5m, spot);
        String oiBias = getOIBias(idx);
        String pcrBias = getPCRBias(idx);

        // Case 0: OI + PCR majority without momentum
        if (momentumSignal == null && oiBias != null && pcrBias != null && oiBias.equals(pcrBias)) {
            updateSignalStability(state, oiBias);
            if (state.consecutiveSignalCount >= 2) {
                return buildDecision(underlying, request, oiBias,
                        "OI+PCR MAJORITY (no momentum): " + oiBias, 70);
            }
            return Optional.empty();
        }

        if (momentumSignal == null) return Optional.empty();

        // Case 1: All aligned
        if (oiBias != null && momentumSignal.equals(oiBias)
                && (pcrBias == null || momentumSignal.equals(pcrBias))) {
            return buildDecision(underlying, request, momentumSignal,
                    "ALL ALIGNED: momentum=" + momentumSignal + " OI=" + oiBias + " PCR=" + pcrBias, 85);
        }

        // Case 2: Momentum + PCR, no OI
        if (oiBias == null && pcrBias != null && momentumSignal.equals(pcrBias)) {
            return buildDecision(underlying, request, momentumSignal,
                    "Momentum+PCR aligned: " + momentumSignal, 65);
        }

        // Case 3: Momentum + OI, PCR neutral
        if (oiBias != null && momentumSignal.equals(oiBias) && pcrBias == null) {
            return buildDecision(underlying, request, momentumSignal,
                    "Momentum+OI aligned: " + momentumSignal, 65);
        }

        // Case 4: OI+PCR override momentum
        if (oiBias != null && !momentumSignal.equals(oiBias) && pcrBias != null && pcrBias.equals(oiBias)) {
            return buildDecision(underlying, request, oiBias,
                    "OI+PCR MAJORITY override momentum: " + oiBias, 60);
        }

        return Optional.empty();
    }

    private String detectMomentum(IndexType idx, IndexState state, List<Candle> candles5m, double spot) {
        Candle latest = candles5m.get(candles5m.size() - 1);

        // Breakout from opening range
        if (state.rangeEstablished) {
            if (spot > state.rangeHigh) return "BULLISH";
            if (spot < state.rangeLow) return "BEARISH";
        }

        // 3 consecutive directional candles
        if (candles5m.size() >= 3) {
            Candle c1 = candles5m.get(candles5m.size() - 3);
            Candle c2 = candles5m.get(candles5m.size() - 2);
            if (c1.close().doubleValue() > c1.open().doubleValue()
                    && c2.close().doubleValue() > c2.open().doubleValue()
                    && latest.close().doubleValue() > latest.open().doubleValue()) return "BULLISH";
            if (c1.close().doubleValue() < c1.open().doubleValue()
                    && c2.close().doubleValue() < c2.open().doubleValue()
                    && latest.close().doubleValue() < latest.open().doubleValue()) return "BEARISH";
        }

        // Large single candle
        double bodyPct = Math.abs(latest.close().doubleValue() - latest.open().doubleValue())
                / latest.open().doubleValue() * 100;
        if (bodyPct > 0.15) {
            return latest.close().doubleValue() > latest.open().doubleValue() ? "BULLISH" : "BEARISH";
        }

        return null;
    }

    private String getOIBias(IndexType idx) {
        var bias = operatorIntentEngine.getBias(idx);
        if (bias.confidence() >= 30) {
            if (bias.isBullish()) return "BULLISH";
            if (bias.isBearish()) return "BEARISH";
        }
        return null;
    }

    private String getPCRBias(IndexType idx) {
        double pcr = pcrTracker.getPCR(UnderlyingSymbol.valueOf(idx.name()));
        if (pcr > pcrBullishThreshold) return "BULLISH";
        if (pcr < pcrBearishThreshold) return "BEARISH";
        return null;
    }

    private void captureOpeningRange(IndexType idx, IndexState state, double spot, LocalTime now) {
        if (state.openPrice == 0 && now.isAfter(LocalTime.of(9, 15))) {
            state.openPrice = spot;
            state.rangeHigh = spot;
            state.rangeLow = spot;
        }
        if (!state.rangeEstablished && now.isBefore(LocalTime.of(9, 35))) {
            state.rangeHigh = Math.max(state.rangeHigh, spot);
            state.rangeLow = Math.min(state.rangeLow, spot);
        } else if (!state.rangeEstablished && now.isAfter(LocalTime.of(9, 35))) {
            state.rangeEstablished = true;
        }
    }

    private void updateSignalStability(IndexState state, String direction) {
        if (direction.equals(state.lastSignalDirection)) {
            state.consecutiveSignalCount++;
        } else {
            state.lastSignalDirection = direction;
            state.consecutiveSignalCount = 1;
        }
    }

    private Optional<StrategyDecision> buildDecision(UnderlyingSymbol underlying,
                                                      StrategyEvaluationRequest request,
                                                      String direction, String reason, int confidence) {
        SignalType signalType = "BULLISH".equals(direction) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = "BULLISH".equals(direction) ? OptionType.CE : OptionType.PE;

        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying, signalType,
                request.underlyingCandles().getLast().close(), Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(), Optional.of(request.selectedLotSize()), Optional.empty(),
                Optional.of(request.selectedInstrumentKey()), Optional.of(request.selectedStrike()),
                Optional.of(optionType), true, Optional.empty(), false,
                BigDecimal.valueOf(confidence),
                List.of("UNIVERSAL_OI_MOMENTUM: " + reason)
        );
        return Optional.of(decision);
    }

    private IndexType mapToIndexType(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> IndexType.BANKNIFTY;
            case SENSEX -> IndexType.SENSEX;
            default -> IndexType.NIFTY;
        };
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }
}
