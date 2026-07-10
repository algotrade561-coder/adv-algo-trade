package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-strategy configuration stored in DB.
 * Controls enabled/disabled state and key parameters.
 * Selling strategies are disabled by default and require explicit opt-in.
 */
@Entity
@Table(name = "strategy_configs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_sc_type_underlying", columnNames = {"strategy_type", "underlying"})
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(50)")
    private StrategyType strategyType;

    private boolean enabled = false;
    private String underlying = "NIFTY";
    private int lots = 1;
    private BigDecimal stopLossPercent = BigDecimal.valueOf(30);
    private BigDecimal targetPercent = BigDecimal.valueOf(60);
    private int maxHoldMinutes = 45;
    private int spreadStrikes = 2;
    private int otmStrikes = 2;
    private BigDecimal minCombinedPremium = BigDecimal.valueOf(80);
    private BigDecimal maxIvRankForBuying = BigDecimal.valueOf(40);
    private BigDecimal trailingStopActivationPercent = BigDecimal.valueOf(10);
    private BigDecimal trailingGapPercent = BigDecimal.valueOf(5);
    private int squareoffHour = 15;
    private int squareoffMinute = 15;

    /** Candle timeframe that triggers this strategy's evaluation.
     *  ONE_MINUTE, FIVE_MINUTE, or FIFTEEN_MINUTE. */
    private String scanTimeframe = "FIFTEEN_MINUTE";

    /** Candle resolution for the strategy's primary indicator data (e.g., underlying candles).
     *  ONE_MINUTE or FIVE_MINUTE. */
    private String candleTimeframe = "ONE_MINUTE";

    /** Candle resolution for trend EMA calculation.
     *  FIVE_MINUTE or FIFTEEN_MINUTE. */
    private String trendTimeframe = "FIVE_MINUTE";

    /** When true, signals are logged/persisted but NOT sent to ExecutionEngine for real orders. */
    private boolean paperTrading = false;

    /** ITM depth for ITM Conviction strategy (number of strikes ITM). Default 1. */
    private int itmDepth = 1;

    /** Minimum underlying price move to confirm direction (ITM Conviction). */
    private BigDecimal minimumMove = BigDecimal.valueOf(5);

    /** Minimum ATP-LTP strength gap between ITM and ATM (ITM Conviction). */
    private BigDecimal minimumStrengthGap = BigDecimal.valueOf(2);

    /** Minimum volume on the ITM option to confirm conviction (ITM Conviction). */
    private long minimumVolume = 5000;

    /**
     * Per-index OTM strikes overrides as JSON map (e.g., {"NIFTY":2,"BANKNIFTY":3}).
     * NULL falls back to the scalar {@link #otmStrikes}. Used by LONG_STRANGLE etc.
     */
    @Column(columnDefinition = "TEXT")
    private String otmStrikesByIndexJson;

    /**
     * Hard rupee cap on capital committed per trade.
     * Lots get scaled down proportionally if {@code lots * premiumPerLot} would exceed this.
     * NULL = no cap (legacy behavior).
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal maxCapitalPerTrade;

    @Transient
    private static final ObjectMapper OTM_JSON_MAPPER = new ObjectMapper();

    @Transient
    private volatile Map<String, Integer> cachedOtmByIndex;

    protected StrategyConfig() {}

    public StrategyConfig(StrategyType type) {
        this.strategyType = type;
        this.enabled = type.isDefaultEnabled();
        switch (type) {
            case DIRECTIONAL_BUY -> { stopLossPercent = BigDecimal.valueOf(30); targetPercent = BigDecimal.valueOf(60); maxHoldMinutes = 20; trailingStopActivationPercent = BigDecimal.valueOf(25); trailingGapPercent = BigDecimal.valueOf(12); minCombinedPremium = BigDecimal.valueOf(60); squareoffHour = 15; squareoffMinute = 0; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "ONE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; }
            case VOLATILITY_BREAKOUT -> { stopLossPercent = BigDecimal.valueOf(30); targetPercent = BigDecimal.valueOf(80); maxIvRankForBuying = BigDecimal.valueOf(40); trailingStopActivationPercent = BigDecimal.valueOf(30); trailingGapPercent = BigDecimal.valueOf(15); minCombinedPremium = BigDecimal.valueOf(50); squareoffHour = 15; squareoffMinute = 0; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case EVENT_DRIVEN_BUY -> { stopLossPercent = BigDecimal.valueOf(35); targetPercent = BigDecimal.valueOf(80); maxHoldMinutes = 0; maxIvRankForBuying = BigDecimal.valueOf(40); paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case SCALPING -> { stopLossPercent = BigDecimal.valueOf(20); targetPercent = BigDecimal.valueOf(40); maxHoldMinutes = 15; trailingStopActivationPercent = BigDecimal.valueOf(20); trailingGapPercent = BigDecimal.valueOf(10); minCombinedPremium = BigDecimal.valueOf(70); squareoffHour = 14; squareoffMinute = 30; scanTimeframe = "FIVE_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; }
            case BULL_CALL_SPREAD, BEAR_PUT_SPREAD -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(80); paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case LONG_STRADDLE -> { stopLossPercent = BigDecimal.valueOf(40); targetPercent = BigDecimal.valueOf(60); maxHoldMinutes = 20; maxIvRankForBuying = BigDecimal.valueOf(40); maxCapitalPerTrade = BigDecimal.valueOf(15000); scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case LONG_STRANGLE -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(80); otmStrikes = 2; otmStrikesByIndexJson = "{\"NIFTY\":2,\"BANKNIFTY\":3,\"FINNIFTY\":2,\"MIDCPNIFTY\":2}"; maxHoldMinutes = 20; maxIvRankForBuying = BigDecimal.valueOf(40); maxCapitalPerTrade = BigDecimal.valueOf(10000); scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case SHORT_STRADDLE -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(30); minCombinedPremium = BigDecimal.valueOf(150); paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case SHORT_STRANGLE -> { stopLossPercent = BigDecimal.valueOf(40); targetPercent = BigDecimal.valueOf(80); otmStrikes = 2; paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case IRON_CONDOR -> { stopLossPercent = BigDecimal.valueOf(100); targetPercent = BigDecimal.valueOf(50); otmStrikes = 2; spreadStrikes = 4; paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case BUTTERFLY -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(100); paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case CALENDAR_SPREAD -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(30); paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case DIAGONAL_SPREAD -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(40); otmStrikes = 2; paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case JADE_LIZARD -> { stopLossPercent = BigDecimal.valueOf(80); targetPercent = BigDecimal.valueOf(40); otmStrikes = 3; spreadStrikes = 5; paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case SYNTHETIC_FUTURES -> { stopLossPercent = BigDecimal.valueOf(20); targetPercent = BigDecimal.valueOf(30); maxHoldMinutes = 60; paperTrading = true; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; }
            case ITM_CONVICTION -> { stopLossPercent = BigDecimal.valueOf(12); targetPercent = BigDecimal.valueOf(25); maxHoldMinutes = 30; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "ONE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; paperTrading = true; itmDepth = 1; minimumMove = BigDecimal.valueOf(5); minimumStrengthGap = BigDecimal.valueOf(2); minimumVolume = 5000; }
            case GAP_AND_GO -> { stopLossPercent = BigDecimal.valueOf(25); targetPercent = BigDecimal.valueOf(50); maxHoldMinutes = 30; maxIvRankForBuying = BigDecimal.valueOf(50); trailingStopActivationPercent = BigDecimal.valueOf(15); trailingGapPercent = BigDecimal.valueOf(8); minCombinedPremium = BigDecimal.valueOf(30); squareoffHour = 10; squareoffMinute = 0; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; paperTrading = true; }
            case REVERSAL_BUY -> { stopLossPercent = BigDecimal.valueOf(25); targetPercent = BigDecimal.valueOf(60); maxHoldMinutes = 45; maxIvRankForBuying = BigDecimal.valueOf(50); trailingStopActivationPercent = BigDecimal.valueOf(20); trailingGapPercent = BigDecimal.valueOf(10); minCombinedPremium = BigDecimal.valueOf(50); squareoffHour = 15; squareoffMinute = 0; scanTimeframe = "FIFTEEN_MINUTE"; candleTimeframe = "FIFTEEN_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; paperTrading = true; }
            case OI_SHIFT_TRAP -> { this.enabled = false; stopLossPercent = BigDecimal.valueOf(30); targetPercent = BigDecimal.valueOf(60); maxHoldMinutes = 30; trailingStopActivationPercent = BigDecimal.valueOf(15); trailingGapPercent = BigDecimal.valueOf(8); minCombinedPremium = BigDecimal.valueOf(40); squareoffHour = 15; squareoffMinute = 0; scanTimeframe = "FIVE_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; paperTrading = true; }
            case EXPIRY_GAMMA -> { stopLossPercent = BigDecimal.valueOf(40); targetPercent = BigDecimal.valueOf(100); maxHoldMinutes = 90; trailingStopActivationPercent = BigDecimal.valueOf(40); trailingGapPercent = BigDecimal.valueOf(20); minCombinedPremium = BigDecimal.valueOf(20); squareoffHour = 15; squareoffMinute = 15; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "ONE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; paperTrading = true; }
            case EXPIRY_REVERSAL -> { stopLossPercent = BigDecimal.valueOf(25); targetPercent = BigDecimal.valueOf(50); maxHoldMinutes = 30; trailingStopActivationPercent = BigDecimal.valueOf(15); trailingGapPercent = BigDecimal.valueOf(8); minCombinedPremium = BigDecimal.valueOf(20); squareoffHour = 15; squareoffMinute = 15; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "ONE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; paperTrading = true; }
            // 3 Jun 2026: tightened after both live MOMENTUM CE trades (NIFTY 23300, SENSEX 74100)
            // peaked at +1.3% then crashed to SL. Old trailing-activation=15% never armed; SL=25%
            // gave premium room to decay −15-20% before triggering. New values:
            //   stopLoss=15 (was 25), target=30 (was 50), trailingActivation=4 (was 15), trailingGap=3 (was 8),
            //   maxHold=20 (was 30). Trade now needs only +4% peak to arm trailing — covers most
            //   pure-momentum entries that don't develop strongly. SL caps damage at half the prior.
            case MOMENTUM -> { stopLossPercent = BigDecimal.valueOf(15); targetPercent = BigDecimal.valueOf(30); maxHoldMinutes = 20; trailingStopActivationPercent = BigDecimal.valueOf(4); trailingGapPercent = BigDecimal.valueOf(3); minCombinedPremium = BigDecimal.valueOf(40); squareoffHour = 15; squareoffMinute = 0; scanTimeframe = "FIVE_MINUTE"; candleTimeframe = "FIVE_MINUTE"; trendTimeframe = "FIFTEEN_MINUTE"; paperTrading = true; }
            case OI_MOMENTUM -> { stopLossPercent = BigDecimal.valueOf(15); targetPercent = BigDecimal.valueOf(25); maxHoldMinutes = 0; trailingStopActivationPercent = BigDecimal.valueOf(12); trailingGapPercent = BigDecimal.valueOf(8); minCombinedPremium = BigDecimal.valueOf(30); squareoffHour = 15; squareoffMinute = 10; scanTimeframe = "ONE_MINUTE"; candleTimeframe = "ONE_MINUTE"; trendTimeframe = "FIVE_MINUTE"; paperTrading = true; }
        }
    }

    // Getters
    public Long getId() { return id; }
    public StrategyType getStrategyType() { return strategyType; }
    public boolean isEnabled() { return enabled; }
    public String getUnderlying() { return underlying; }
    public int getLots() { return lots; }
    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public BigDecimal getTargetPercent() { return targetPercent; }
    public int getMaxHoldMinutes() { return maxHoldMinutes; }
    public int getSpreadStrikes() { return spreadStrikes; }
    public int getOtmStrikes() { return otmStrikes; }
    public BigDecimal getMinCombinedPremium() { return minCombinedPremium; }
    public BigDecimal getMaxIvRankForBuying() { return maxIvRankForBuying; }
    public BigDecimal getTrailingStopActivationPercent() { return trailingStopActivationPercent; }
    public BigDecimal getTrailingGapPercent() { return trailingGapPercent; }
    public int getSquareoffHour() { return squareoffHour; }
    public int getSquareoffMinute() { return squareoffMinute; }
    public String getScanTimeframe() { return scanTimeframe; }
    public String getCandleTimeframe() { return candleTimeframe; }
    public String getTrendTimeframe() { return trendTimeframe; }
    public boolean isPaperTrading() { return paperTrading; }
    public int getItmDepth() { return itmDepth; }
    public BigDecimal getMinimumMove() { return minimumMove; }
    public BigDecimal getMinimumStrengthGap() { return minimumStrengthGap; }
    public long getMinimumVolume() { return minimumVolume; }

    // Setters
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setUnderlying(String underlying) { this.underlying = underlying; }
    public void setLots(int lots) { this.lots = lots; }
    public void setStopLossPercent(BigDecimal v) { this.stopLossPercent = v; }
    public void setTargetPercent(BigDecimal v) { this.targetPercent = v; }
    public void setMaxHoldMinutes(int v) { this.maxHoldMinutes = v; }
    public void setSpreadStrikes(int v) { this.spreadStrikes = v; }
    public void setOtmStrikes(int v) { this.otmStrikes = v; }
    public void setMinCombinedPremium(BigDecimal v) { this.minCombinedPremium = v; }
    public void setMaxIvRankForBuying(BigDecimal v) { this.maxIvRankForBuying = v; }
    public void setTrailingStopActivationPercent(BigDecimal v) { this.trailingStopActivationPercent = v; }
    public void setTrailingGapPercent(BigDecimal v) { this.trailingGapPercent = v; }
    public void setSquareoffHour(int v) { this.squareoffHour = v; }
    public void setSquareoffMinute(int v) { this.squareoffMinute = v; }
    public void setScanTimeframe(String v) { this.scanTimeframe = v; }
    public void setCandleTimeframe(String v) { this.candleTimeframe = v; }
    public void setTrendTimeframe(String v) { this.trendTimeframe = v; }
    public void setPaperTrading(boolean v) { this.paperTrading = v; }
    public void setItmDepth(int v) { this.itmDepth = v; }
    public void setMinimumMove(BigDecimal v) { this.minimumMove = v; }
    public void setMinimumStrengthGap(BigDecimal v) { this.minimumStrengthGap = v; }
    public void setMinimumVolume(long v) { this.minimumVolume = v; }

    public String getOtmStrikesByIndexJson() { return otmStrikesByIndexJson; }
    public void setOtmStrikesByIndexJson(String v) { this.otmStrikesByIndexJson = v; this.cachedOtmByIndex = null; }

    public BigDecimal getMaxCapitalPerTrade() { return maxCapitalPerTrade; }
    public void setMaxCapitalPerTrade(BigDecimal v) { this.maxCapitalPerTrade = v; }

    /**
     * ATR value (14-period, in underlying points) computed at signal-evaluation time.
     * Not persisted — set by StrategyExecutionPipeline before calling ExecutionEngine
     * so that RiskEngine.calculateQuantityWithATR() can use it for adaptive position sizing.
     * 0 = ATR not available; ExecutionEngine falls back to fixed stopLossPercent sizing.
     */
    @Transient
    private double atrValue = 0.0;
    public double getAtrValue() { return atrValue; }
    public void setAtrValue(double v) { this.atrValue = v; }

    /**
     * Returns the OTM strike count for the given index, falling back to the scalar
     * {@link #otmStrikes} when no per-index override is configured.
     */
    public int getOtmStrikes(IndexType indexType) {
        if (otmStrikesByIndexJson == null || otmStrikesByIndexJson.isBlank()) {
            return otmStrikes;
        }
        Map<String, Integer> map = cachedOtmByIndex;
        if (map == null) {
            try {
                map = OTM_JSON_MAPPER.readValue(otmStrikesByIndexJson,
                        new TypeReference<HashMap<String, Integer>>() {});
            } catch (Exception e) {
                map = Map.of();
            }
            cachedOtmByIndex = map;
        }
        Integer override = map.get(indexType.name());
        return override != null && override > 0 ? override : otmStrikes;
    }
}
