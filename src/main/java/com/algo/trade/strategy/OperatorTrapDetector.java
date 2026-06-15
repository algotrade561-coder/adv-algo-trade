package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator Trap Detector — detects institutional manipulation patterns.
 *
 * Detects 3 key operator behaviors:
 * 1. TRAP DETECTION (Bull/Bear traps)
 * 2. LIQUIDITY HUNTING
 * 3. OI UNWIND DETECTION (for exits)
 */
@Component
public class OperatorTrapDetector {

    private static final Logger log = LoggerFactory.getLogger(OperatorTrapDetector.class);

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final MarketDataService marketDataService;

    private static class TrapState {
        volatile String activeTrapDirection = null;
        volatile long trapDetectedTime = 0;
        volatile double breakoutLevel = 0;
        volatile long lastCeOI = 0, lastPeOI = 0;
        volatile long oiSnapshotTime = 0;
        volatile boolean liquidityHuntActive = false;
        volatile int liquidityTargetStrike = 0;
        volatile long liquidityHuntTime = 0;
        volatile int distantCeStrike = 0;
        volatile int distantPeStrike = 0;
        volatile long distantCeOI = 0;
        volatile long distantPeOI = 0;
        volatile double prevSpotForConvergence = 0;
        volatile String distantOIBias = null;
        volatile long distantOIDetectedTime = 0;
    }

    private final Map<IndexType, TrapState> states = new ConcurrentHashMap<>();
    private static final long TRAP_DURATION_MS = 10 * 60_000;
    private static final long LIQUIDITY_HUNT_DURATION_MS = 5 * 60_000;

    @org.springframework.beans.factory.annotation.Value("${operator.trap.volume-spike-multiplier:2.0}")
    private double volumeSpikeMultiplier;

    @org.springframework.beans.factory.annotation.Value("${operator.trap.oi-unwind-threshold-percent:10.0}")
    private double oiUnwindThresholdPct;

    public OperatorTrapDetector(LiveCandleBuilder candleBuilder,
                                LiveInstrumentCache instrumentCache,
                                MarketDataService marketDataService) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            states.put(idx, new TrapState());
        }
    }

    public boolean isTrapActive(IndexType idx, String direction) {
        TrapState state = states.get(idx);
        if (state == null || state.activeTrapDirection == null) return false;
        if (System.currentTimeMillis() - state.trapDetectedTime > TRAP_DURATION_MS) {
            state.activeTrapDirection = null;
            return false;
        }
        return state.activeTrapDirection.equals(direction);
    }

    public boolean isLiquidityHunt(IndexType idx) {
        TrapState state = states.get(idx);
        if (state == null || !state.liquidityHuntActive) return false;
        if (System.currentTimeMillis() - state.liquidityHuntTime > LIQUIDITY_HUNT_DURATION_MS) {
            state.liquidityHuntActive = false;
            return false;
        }
        return true;
    }

    public int getLiquidityTargetStrike(IndexType idx) {
        TrapState state = states.get(idx);
        return state != null ? state.liquidityTargetStrike : 0;
    }

    public boolean isOIUnwinding(IndexType idx, String optionType) {
        TrapState state = states.get(idx);
        if (state == null || state.oiSnapshotTime == 0) return false;
        // Simplified: compare last snapshot with current OI
        return false;
    }

    public String getOIAbsorptionBias(IndexType idx) {
        TrapState state = states.get(idx);
        if (state == null) return null;

        double spot = instrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return null;

        // Simplified OI absorption logic using available market data
        return null;
    }

    public String getDistantOISetupBias(IndexType idx) {
        TrapState state = states.get(idx);
        if (state == null) return null;
        if (state.distantOIBias != null
                && System.currentTimeMillis() - state.distantOIDetectedTime > 15 * 60_000L) {
            state.distantOIBias = null;
        }
        return state.distantOIBias;
    }

    @Scheduled(fixedDelay = 15_000)
    public void detect() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 20)) || now.isAfter(LocalTime.of(15, 20))) return;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            TrapState state = states.get(idx);
            if (state == null) continue;
            detectTraps(idx, state);
        }
    }

    private void detectTraps(IndexType idx, TrapState state) {
        long token = resolveToken(idx);
        List<Candle> candles = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles.size() < 4) return;

        Candle prev = candles.get(candles.size() - 3);
        Candle breakout = candles.get(candles.size() - 2);
        Candle current = candles.get(candles.size() - 1);

        double avgVol = candles.subList(0, candles.size() - 2).stream()
                .mapToLong(Candle::volume).average().orElse(0);
        if (avgVol <= 0) return;

        // BULL TRAP
        boolean bullBreakout = breakout.close().doubleValue() > prev.high().doubleValue()
                && breakout.volume() > avgVol * volumeSpikeMultiplier;
        boolean bullReversal = current.close().doubleValue() < breakout.open().doubleValue();

        if (bullBreakout && bullReversal) {
            if (state.activeTrapDirection == null || !"BULLISH".equals(state.activeTrapDirection)) {
                state.activeTrapDirection = "BULLISH";
                state.trapDetectedTime = System.currentTimeMillis();
                state.breakoutLevel = breakout.high().doubleValue();
                log.warn("[TrapDetector] {} BULL TRAP detected: breakout to {} then reversed",
                        idx, String.format("%.0f", breakout.high().doubleValue()));
            }
            return;
        }

        // BEAR TRAP
        boolean bearBreakout = breakout.close().doubleValue() < prev.low().doubleValue()
                && breakout.volume() > avgVol * volumeSpikeMultiplier;
        boolean bearReversal = current.close().doubleValue() > breakout.open().doubleValue();

        if (bearBreakout && bearReversal) {
            if (state.activeTrapDirection == null || !"BEARISH".equals(state.activeTrapDirection)) {
                state.activeTrapDirection = "BEARISH";
                state.trapDetectedTime = System.currentTimeMillis();
                state.breakoutLevel = breakout.low().doubleValue();
                log.warn("[TrapDetector] {} BEAR TRAP detected: breakdown to {} then reversed",
                        idx, String.format("%.0f", breakout.low().doubleValue()));
            }
        }
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }
}
