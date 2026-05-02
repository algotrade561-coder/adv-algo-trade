package com.algo.trade.config;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single-row JPA entity holding all runtime-tunable entry, exit, and risk parameters.
 * Seeded from {@link TradingProperties} on first startup; editable at runtime via REST API.
 */
@Entity
@Table(name = "global_config")
public class GlobalConfig {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Id
    private Long id = 1L;

    // ── Entry Fields ──────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private Timeframe timeframe = Timeframe.ONE_MINUTE;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private Timeframe trendTimeframe = Timeframe.FIVE_MINUTE;

    @Column(columnDefinition = "VARCHAR(20)")
    private String enabledOptionTypes = "CE,PE";

    @Column(columnDefinition = "VARCHAR(100)")
    private String enabledUnderlyings = "NIFTY";

    private boolean vwapFilterEnabled = true;
    private boolean trendFilterEnabled = true;

    @Column(precision = 19, scale = 4)
    private BigDecimal volumeSpikeMultiplier = BigDecimal.valueOf(1.2);

    @Column(precision = 19, scale = 4)
    private BigDecimal breakoutBufferPercent = new BigDecimal("0.05");

    private int breakoutLookback = 15;
    private int volumeLookback = 5;

    @Column(precision = 19, scale = 4)
    private BigDecimal bullishImbalanceThreshold = BigDecimal.valueOf(1.2);

    @Column(precision = 19, scale = 4)
    private BigDecimal bearishImbalanceThreshold = BigDecimal.valueOf(0.8);

    private long minLiquidityVolume = 5_000;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxIvPercent = BigDecimal.valueOf(80);

    @Column(precision = 19, scale = 4)
    private BigDecimal minSignalScorePercent = BigDecimal.valueOf(70);

    private boolean ceOiSupportRequired = false;
    private boolean peOiSupportRequired = false;
    private boolean ceOiDivergenceFilterEnabled = true;
    private boolean peOiDivergenceFilterEnabled = true;

    @Column(precision = 19, scale = 4)
    private BigDecimal oiDivergenceMultiplier = BigDecimal.valueOf(2.0);

    private long oiDivergenceMinChange = 100_000;
    private int ceBreakoutConfirmationCandles = 1;
    private int peBreakoutConfirmationCandles = 2;

    @Column(columnDefinition = "VARCHAR(5)")
    private String entryStartTime = "09:25";

    @Column(columnDefinition = "VARCHAR(5)")
    private String entryCutoffTime = "15:10";

    private boolean allowFirstMinutesEntry = false;
    private int noEntryFirstMinutes = 10;
    private boolean rsiFilterEnabled = false;
    private int rsiPeriod = 14;

    @Column(precision = 19, scale = 4)
    private BigDecimal rsiCeBuyThreshold = BigDecimal.valueOf(55);

    @Column(precision = 19, scale = 4)
    private BigDecimal rsiPeSellThreshold = BigDecimal.valueOf(45);

    // ── Exit Fields ───────────────────────────────────────────

    @Column(precision = 19, scale = 4)
    private BigDecimal stopLossPercent = BigDecimal.valueOf(12);

    @Column(precision = 19, scale = 4)
    private BigDecimal targetPercent = BigDecimal.valueOf(24);

    @Column(precision = 19, scale = 4)
    private BigDecimal trailingStopActivationPercent = BigDecimal.valueOf(10);

    @Column(precision = 19, scale = 4)
    private BigDecimal trailingGapPercent = BigDecimal.valueOf(5);

    @Column(columnDefinition = "VARCHAR(5)")
    private String forcedExitTime = "15:15";

    private boolean partialProfitBookingEnabled = false;
    private int maxHoldMinutes = 0;

    /** Exit long option positions when underlying crosses back through VWAP (thesis reversal). */
    private boolean vwapExitEnabled = false;

    /** When true, global exit config (SL, target, trailing, maxHold) overrides per-strategy exit config. */
    private boolean globalExitOverride = false;

    // ── Risk Fields ───────────────────────────────────────────

    @Column(precision = 19, scale = 4)
    private BigDecimal totalCapital = BigDecimal.valueOf(60_000);

    @Column(precision = 19, scale = 4)
    private BigDecimal maxRiskPerTradePercent = BigDecimal.valueOf(5);

    @Column(precision = 19, scale = 4)
    private BigDecimal maxDailyLossPercent = BigDecimal.valueOf(5);

    private int maxTradesPerDay = 4;
    private int maxOrdersPerDay = 4;
    private int maxConsecutiveLosses = 2;
    private int maxOpenTrades = 1;

    @Column(precision = 19, scale = 4)
    private BigDecimal sameInstrumentReentryMinPriceMovePercent = BigDecimal.valueOf(3l);

    private int cooldownMinutes = 0;

    @Column(precision = 19, scale = 4)
    private BigDecimal dailyProfitTarget = BigDecimal.ZERO;

    /** Maximum lots per single trade — safety cap against stale premium quotes. Default 10. */
    private int maxLotsPerTrade = 10;

    /** Maximum trades per hour — prevents overtrading in volatile sessions. 0 = disabled. */
    private int maxTradesPerHour = 0;

    /** ML virtual trade threshold — independent of minSignalScorePercent.
     *  When ML scores above this and the system says SKIP, a virtual trade is opened. Default 45. */
    @Column(precision = 19, scale = 4)
    private BigDecimal mlVirtualTradeThreshold = BigDecimal.valueOf(45);

    /**
     * Tracks which data migrations have been applied to this row.
     * 0 = seeded from YAML defaults; incremented by each LiveConfigMigration version.
     */
    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private int configVersion = 0;

    // ── Execution Tuning Fields ───────────────────────────────

    /** Minutes to wait before auto-cancelling unfilled limit orders. Default 1. */
    private int limitOrderCancelMinutes = 1;

    /** Time (HH:mm) when FailSafe daemon force-closes all remaining positions. Default 15:20. */
    @Column(columnDefinition = "VARCHAR(5)")
    private String failSafeSquareoffTime = "15:20";

    /** Max simultaneous pending limit orders. Prevents order pile-up when broker is slow. Default 3. */
    private int maxPendingOrders = 3;

    /** IV drop % from entry that triggers exit. Default 15. */
    @Column(precision = 19, scale = 4)
    private BigDecimal ivCollapseExitThresholdPercent = BigDecimal.valueOf(15);

    /** Don't trigger IV collapse exit if profit is above this %. Default 15. */
    @Column(precision = 19, scale = 4)
    private BigDecimal ivCollapseMaxProfitPercent = BigDecimal.valueOf(15);

    /** Max entries per scan cycle per underlying. Default 1. Controls strategy stacking on same index. */
    private int maxEntriesPerScanPerUnderlying = 1;

    /** Max total entries across all underlyings per scan cycle. Default 1. Overall ceiling for per-scan entries. */
    private int maxEntriesPerScan = 1;

    // ── Constructors ──────────────────────────────────────────

    protected GlobalConfig() {}

    public GlobalConfig(TradingProperties props) {
        this.id = 1L;
        // Entry fields
        this.timeframe = props.entry().timeframe();
        this.trendTimeframe = props.entry().trendTimeframe();
        this.enabledOptionTypes = props.entry().enabledOptionTypes().stream()
                .map(OptionType::name)
                .collect(Collectors.joining(","));
        this.enabledUnderlyings = props.symbols().underlyings().isEmpty() ? "NIFTY"
                : props.symbols().underlyings().stream()
                        .map(UnderlyingSymbol::name)
                        .collect(Collectors.joining(","));
        this.vwapFilterEnabled = props.entry().vwapFilterEnabled();
        this.trendFilterEnabled = props.entry().trendFilterEnabled();
        this.volumeSpikeMultiplier = props.entry().volumeSpikeMultiplier();
        this.breakoutBufferPercent = props.entry().breakoutBufferPercent();
        this.breakoutLookback = props.entry().breakoutLookback();
        this.volumeLookback = props.entry().volumeLookback();
        this.bullishImbalanceThreshold = props.entry().bullishImbalanceThreshold();
        this.bearishImbalanceThreshold = props.entry().bearishImbalanceThreshold();
        this.minLiquidityVolume = props.entry().minLiquidityVolume();
        this.maxIvPercent = props.entry().maxIvPercent();
        this.minSignalScorePercent = props.entry().minSignalScorePercent();
        this.ceOiSupportRequired = props.entry().ceOiSupportRequired();
        this.peOiSupportRequired = props.entry().peOiSupportRequired();
        this.ceOiDivergenceFilterEnabled = props.entry().ceOiDivergenceFilterEnabled();
        this.peOiDivergenceFilterEnabled = props.entry().peOiDivergenceFilterEnabled();
        this.oiDivergenceMultiplier = props.entry().oiDivergenceMultiplier();
        this.oiDivergenceMinChange = props.entry().oiDivergenceMinChange();
        this.ceBreakoutConfirmationCandles = props.entry().ceBreakoutConfirmationCandles();
        this.peBreakoutConfirmationCandles = props.entry().peBreakoutConfirmationCandles();
        this.entryStartTime = props.entry().entryStartTime().format(HH_MM);
        this.entryCutoffTime = props.entry().entryCutoffTime().format(HH_MM);
        this.allowFirstMinutesEntry = props.entry().allowFirstMinutesEntry();
        this.noEntryFirstMinutes = props.entry().noEntryFirstMinutes();
        this.rsiFilterEnabled = props.entry().rsiFilterEnabled();
        this.rsiPeriod = props.entry().rsiPeriod();
        this.rsiCeBuyThreshold = props.entry().rsiCeBuyThreshold();
        this.rsiPeSellThreshold = props.entry().rsiPeSellThreshold();
        // Exit fields
        this.stopLossPercent = props.exit().stopLossPercent();
        this.targetPercent = props.exit().targetPercent();
        this.trailingStopActivationPercent = props.exit().trailingStopActivationPercent();
        this.trailingGapPercent = props.exit().trailingGapPercent();
        this.forcedExitTime = props.exit().forcedExitTime().format(HH_MM);
        this.partialProfitBookingEnabled = props.exit().partialProfitBookingEnabled();
        this.maxHoldMinutes = props.exit().maxHoldMinutes();
        // Risk fields
        this.totalCapital = props.risk().totalCapital();
        this.maxRiskPerTradePercent = props.risk().maxRiskPerTradePercent();
        this.maxDailyLossPercent = props.risk().maxDailyLossPercent();
        this.maxTradesPerDay = props.risk().maxTradesPerDay();
        this.maxOrdersPerDay = props.risk().maxOrdersPerDay();
        this.maxConsecutiveLosses = props.risk().maxConsecutiveLosses();
        this.maxOpenTrades = props.risk().maxOpenTrades();
        this.sameInstrumentReentryMinPriceMovePercent = props.risk().sameInstrumentReentryMinPriceMovePercent();
        this.cooldownMinutes = props.risk().cooldownMinutes();
        this.dailyProfitTarget = props.risk().dailyProfitTarget();
    }

    // ── Getters & Setters ─────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    // Entry getters/setters

    public Timeframe getTimeframe() { return timeframe; }
    public void setTimeframe(Timeframe timeframe) { this.timeframe = timeframe; }

    public Timeframe getTrendTimeframe() { return trendTimeframe; }
    public void setTrendTimeframe(Timeframe trendTimeframe) { this.trendTimeframe = trendTimeframe; }

    public String getEnabledOptionTypes() { return enabledOptionTypes; }
    public void setEnabledOptionTypes(String enabledOptionTypes) { this.enabledOptionTypes = enabledOptionTypes; }

    public List<OptionType> getEnabledOptionTypesAsList() {
        if (enabledOptionTypes == null || enabledOptionTypes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(enabledOptionTypes.split(","))
                .map(String::trim)
                .map(OptionType::valueOf)
                .toList();
    }

    public void setEnabledOptionTypes(List<OptionType> types) {
        this.enabledOptionTypes = types.stream()
                .map(OptionType::name)
                .collect(Collectors.joining(","));
    }

    public String getEnabledUnderlyings() { return enabledUnderlyings; }
    public void setEnabledUnderlyings(String enabledUnderlyings) { this.enabledUnderlyings = enabledUnderlyings; }

    public List<UnderlyingSymbol> getEnabledUnderlyingsAsList() {
        if (enabledUnderlyings == null || enabledUnderlyings.isBlank()) {
            return List.of(UnderlyingSymbol.NIFTY);
        }
        return Arrays.stream(enabledUnderlyings.split(","))
                .map(String::trim)
                .map(UnderlyingSymbol::valueOf)
                .toList();
    }

    public void setEnabledUnderlyings(List<UnderlyingSymbol> underlyings) {
        this.enabledUnderlyings = underlyings.stream()
                .map(UnderlyingSymbol::name)
                .collect(Collectors.joining(","));
    }

    public boolean isVwapFilterEnabled() { return vwapFilterEnabled; }
    public void setVwapFilterEnabled(boolean vwapFilterEnabled) { this.vwapFilterEnabled = vwapFilterEnabled; }

    public boolean isTrendFilterEnabled() { return trendFilterEnabled; }
    public void setTrendFilterEnabled(boolean trendFilterEnabled) { this.trendFilterEnabled = trendFilterEnabled; }

    public BigDecimal getVolumeSpikeMultiplier() { return volumeSpikeMultiplier; }
    public void setVolumeSpikeMultiplier(BigDecimal volumeSpikeMultiplier) { this.volumeSpikeMultiplier = volumeSpikeMultiplier; }

    public BigDecimal getBreakoutBufferPercent() { return breakoutBufferPercent; }
    public void setBreakoutBufferPercent(BigDecimal breakoutBufferPercent) { this.breakoutBufferPercent = breakoutBufferPercent; }

    public int getBreakoutLookback() { return breakoutLookback; }
    public void setBreakoutLookback(int breakoutLookback) { this.breakoutLookback = breakoutLookback; }

    public int getVolumeLookback() { return volumeLookback; }
    public void setVolumeLookback(int volumeLookback) { this.volumeLookback = volumeLookback; }

    public BigDecimal getBullishImbalanceThreshold() { return bullishImbalanceThreshold; }
    public void setBullishImbalanceThreshold(BigDecimal bullishImbalanceThreshold) { this.bullishImbalanceThreshold = bullishImbalanceThreshold; }

    public BigDecimal getBearishImbalanceThreshold() { return bearishImbalanceThreshold; }
    public void setBearishImbalanceThreshold(BigDecimal bearishImbalanceThreshold) { this.bearishImbalanceThreshold = bearishImbalanceThreshold; }

    public long getMinLiquidityVolume() { return minLiquidityVolume; }
    public void setMinLiquidityVolume(long minLiquidityVolume) { this.minLiquidityVolume = minLiquidityVolume; }

    public BigDecimal getMaxIvPercent() { return maxIvPercent; }
    public void setMaxIvPercent(BigDecimal maxIvPercent) { this.maxIvPercent = maxIvPercent; }

    public BigDecimal getMinSignalScorePercent() { return minSignalScorePercent; }
    public void setMinSignalScorePercent(BigDecimal minSignalScorePercent) { this.minSignalScorePercent = minSignalScorePercent; }

    public boolean isCeOiSupportRequired() { return ceOiSupportRequired; }
    public void setCeOiSupportRequired(boolean ceOiSupportRequired) { this.ceOiSupportRequired = ceOiSupportRequired; }

    public boolean isPeOiSupportRequired() { return peOiSupportRequired; }
    public void setPeOiSupportRequired(boolean peOiSupportRequired) { this.peOiSupportRequired = peOiSupportRequired; }

    public boolean isCeOiDivergenceFilterEnabled() { return ceOiDivergenceFilterEnabled; }
    public void setCeOiDivergenceFilterEnabled(boolean ceOiDivergenceFilterEnabled) { this.ceOiDivergenceFilterEnabled = ceOiDivergenceFilterEnabled; }

    public boolean isPeOiDivergenceFilterEnabled() { return peOiDivergenceFilterEnabled; }
    public void setPeOiDivergenceFilterEnabled(boolean peOiDivergenceFilterEnabled) { this.peOiDivergenceFilterEnabled = peOiDivergenceFilterEnabled; }

    public BigDecimal getOiDivergenceMultiplier() { return oiDivergenceMultiplier; }
    public void setOiDivergenceMultiplier(BigDecimal oiDivergenceMultiplier) { this.oiDivergenceMultiplier = oiDivergenceMultiplier; }

    public long getOiDivergenceMinChange() { return oiDivergenceMinChange; }
    public void setOiDivergenceMinChange(long oiDivergenceMinChange) { this.oiDivergenceMinChange = oiDivergenceMinChange; }

    public int getCeBreakoutConfirmationCandles() { return ceBreakoutConfirmationCandles; }
    public void setCeBreakoutConfirmationCandles(int ceBreakoutConfirmationCandles) { this.ceBreakoutConfirmationCandles = ceBreakoutConfirmationCandles; }

    public int getPeBreakoutConfirmationCandles() { return peBreakoutConfirmationCandles; }
    public void setPeBreakoutConfirmationCandles(int peBreakoutConfirmationCandles) { this.peBreakoutConfirmationCandles = peBreakoutConfirmationCandles; }

    // LocalTime entry time getters/setters

    public String getEntryStartTime() { return entryStartTime; }
    public void setEntryStartTime(String entryStartTime) { this.entryStartTime = entryStartTime; }
    public LocalTime getEntryStartTimeAsLocalTime() { return LocalTime.parse(entryStartTime, HH_MM); }
    public void setEntryStartTime(LocalTime entryStartTime) { this.entryStartTime = entryStartTime.format(HH_MM); }

    public String getEntryCutoffTime() { return entryCutoffTime; }
    public void setEntryCutoffTime(String entryCutoffTime) { this.entryCutoffTime = entryCutoffTime; }
    public LocalTime getEntryCutoffTimeAsLocalTime() { return LocalTime.parse(entryCutoffTime, HH_MM); }
    public void setEntryCutoffTime(LocalTime entryCutoffTime) { this.entryCutoffTime = entryCutoffTime.format(HH_MM); }

    public boolean isAllowFirstMinutesEntry() { return allowFirstMinutesEntry; }
    public void setAllowFirstMinutesEntry(boolean allowFirstMinutesEntry) { this.allowFirstMinutesEntry = allowFirstMinutesEntry; }

    public int getNoEntryFirstMinutes() { return noEntryFirstMinutes; }
    public void setNoEntryFirstMinutes(int noEntryFirstMinutes) { this.noEntryFirstMinutes = noEntryFirstMinutes; }

    public boolean isRsiFilterEnabled() { return rsiFilterEnabled; }
    public void setRsiFilterEnabled(boolean rsiFilterEnabled) { this.rsiFilterEnabled = rsiFilterEnabled; }

    public int getRsiPeriod() { return rsiPeriod; }
    public void setRsiPeriod(int rsiPeriod) { this.rsiPeriod = rsiPeriod; }

    public BigDecimal getRsiCeBuyThreshold() { return rsiCeBuyThreshold; }
    public void setRsiCeBuyThreshold(BigDecimal rsiCeBuyThreshold) { this.rsiCeBuyThreshold = rsiCeBuyThreshold; }

    public BigDecimal getRsiPeSellThreshold() { return rsiPeSellThreshold; }
    public void setRsiPeSellThreshold(BigDecimal rsiPeSellThreshold) { this.rsiPeSellThreshold = rsiPeSellThreshold; }

    // Exit getters/setters

    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public void setStopLossPercent(BigDecimal stopLossPercent) { this.stopLossPercent = stopLossPercent; }

    public BigDecimal getTargetPercent() { return targetPercent; }
    public void setTargetPercent(BigDecimal targetPercent) { this.targetPercent = targetPercent; }

    public BigDecimal getTrailingStopActivationPercent() { return trailingStopActivationPercent; }
    public void setTrailingStopActivationPercent(BigDecimal trailingStopActivationPercent) { this.trailingStopActivationPercent = trailingStopActivationPercent; }

    public BigDecimal getTrailingGapPercent() { return trailingGapPercent; }
    public void setTrailingGapPercent(BigDecimal trailingGapPercent) { this.trailingGapPercent = trailingGapPercent; }

    public String getForcedExitTime() { return forcedExitTime; }
    public void setForcedExitTime(String forcedExitTime) { this.forcedExitTime = forcedExitTime; }
    public LocalTime getForcedExitTimeAsLocalTime() { return LocalTime.parse(forcedExitTime, HH_MM); }
    public void setForcedExitTime(LocalTime forcedExitTime) { this.forcedExitTime = forcedExitTime.format(HH_MM); }

    public boolean isPartialProfitBookingEnabled() { return partialProfitBookingEnabled; }
    public void setPartialProfitBookingEnabled(boolean partialProfitBookingEnabled) { this.partialProfitBookingEnabled = partialProfitBookingEnabled; }

    public int getMaxHoldMinutes() { return maxHoldMinutes; }
    public void setMaxHoldMinutes(int maxHoldMinutes) { this.maxHoldMinutes = maxHoldMinutes; }

    public boolean isVwapExitEnabled() { return vwapExitEnabled; }
    public void setVwapExitEnabled(boolean vwapExitEnabled) { this.vwapExitEnabled = vwapExitEnabled; }

    public boolean isGlobalExitOverride() { return globalExitOverride; }
    public void setGlobalExitOverride(boolean globalExitOverride) { this.globalExitOverride = globalExitOverride; }

    // Risk getters/setters

    public BigDecimal getTotalCapital() { return totalCapital; }
    public void setTotalCapital(BigDecimal totalCapital) { this.totalCapital = totalCapital; }

    public BigDecimal getMaxRiskPerTradePercent() { return maxRiskPerTradePercent; }
    public void setMaxRiskPerTradePercent(BigDecimal maxRiskPerTradePercent) { this.maxRiskPerTradePercent = maxRiskPerTradePercent; }

    public BigDecimal getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public void setMaxDailyLossPercent(BigDecimal maxDailyLossPercent) { this.maxDailyLossPercent = maxDailyLossPercent; }

    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int maxTradesPerDay) { this.maxTradesPerDay = maxTradesPerDay; }

    public int getMaxOrdersPerDay() { return maxOrdersPerDay; }
    public void setMaxOrdersPerDay(int maxOrdersPerDay) { this.maxOrdersPerDay = maxOrdersPerDay; }

    public int getMaxConsecutiveLosses() { return maxConsecutiveLosses; }
    public void setMaxConsecutiveLosses(int maxConsecutiveLosses) { this.maxConsecutiveLosses = maxConsecutiveLosses; }

    public int getMaxOpenTrades() { return maxOpenTrades; }
    public void setMaxOpenTrades(int maxOpenTrades) { this.maxOpenTrades = maxOpenTrades; }

    public BigDecimal getSameInstrumentReentryMinPriceMovePercent() { return sameInstrumentReentryMinPriceMovePercent; }
    public void setSameInstrumentReentryMinPriceMovePercent(BigDecimal v) { this.sameInstrumentReentryMinPriceMovePercent = v; }

    public int getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(int cooldownMinutes) { this.cooldownMinutes = cooldownMinutes; }

    public BigDecimal getDailyProfitTarget() { return dailyProfitTarget; }
    public void setDailyProfitTarget(BigDecimal dailyProfitTarget) { this.dailyProfitTarget = dailyProfitTarget; }

    public int getMaxLotsPerTrade() { return maxLotsPerTrade; }
    public void setMaxLotsPerTrade(int maxLotsPerTrade) { this.maxLotsPerTrade = maxLotsPerTrade; }

    public int getMaxTradesPerHour() { return maxTradesPerHour; }
    public void setMaxTradesPerHour(int maxTradesPerHour) { this.maxTradesPerHour = maxTradesPerHour; }

    public BigDecimal getMlVirtualTradeThreshold() { return mlVirtualTradeThreshold; }
    public void setMlVirtualTradeThreshold(BigDecimal mlVirtualTradeThreshold) { this.mlVirtualTradeThreshold = mlVirtualTradeThreshold; }

    public int getConfigVersion() { return configVersion; }
    public void setConfigVersion(int configVersion) { this.configVersion = configVersion; }

    public int getLimitOrderCancelMinutes() { return limitOrderCancelMinutes; }
    public void setLimitOrderCancelMinutes(int v) { this.limitOrderCancelMinutes = v; }

    public String getFailSafeSquareoffTime() { return failSafeSquareoffTime; }
    public void setFailSafeSquareoffTime(String v) { this.failSafeSquareoffTime = v; }
    public LocalTime getFailSafeSquareoffTimeAsLocalTime() { return LocalTime.parse(failSafeSquareoffTime, HH_MM); }
    public void setFailSafeSquareoffTime(LocalTime v) { this.failSafeSquareoffTime = v.format(HH_MM); }

    public int getMaxPendingOrders() { return maxPendingOrders; }
    public void setMaxPendingOrders(int v) { this.maxPendingOrders = v; }

    public BigDecimal getIvCollapseExitThresholdPercent() { return ivCollapseExitThresholdPercent; }
    public void setIvCollapseExitThresholdPercent(BigDecimal v) { this.ivCollapseExitThresholdPercent = v; }

    public BigDecimal getIvCollapseMaxProfitPercent() { return ivCollapseMaxProfitPercent; }
    public void setIvCollapseMaxProfitPercent(BigDecimal v) { this.ivCollapseMaxProfitPercent = v; }

    public int getMaxEntriesPerScanPerUnderlying() { return maxEntriesPerScanPerUnderlying; }
    public void setMaxEntriesPerScanPerUnderlying(int v) { this.maxEntriesPerScanPerUnderlying = v; }

    public int getMaxEntriesPerScan() { return maxEntriesPerScan; }
    public void setMaxEntriesPerScan(int v) { this.maxEntriesPerScan = v; }
}
