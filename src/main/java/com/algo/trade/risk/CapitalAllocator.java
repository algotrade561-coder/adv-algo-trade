package com.algo.trade.risk;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capital Allocator — determines how much capital to deploy per trade.
 *
 * Two modes:
 *   RISK_BASED       → allocate 2–3% of capital per trade (conservative)
 *   FULL_UTILIZATION → allocate 100% of available capital across N concurrent trades
 *
 * Volatility regime adjusts usable capital:
 *   NORMAL  → 100% usable
 *   HIGH    → 70% usable
 *   EXTREME → 50% usable
 *
 * Integration:
 *   Called by RiskEngine.calculateQuantity() as an additional lot ceiling.
 */
@Component
public class CapitalAllocator {

    private static final Logger log = LoggerFactory.getLogger(CapitalAllocator.class);

    private final GlobalConfigService globalConfigService;
    private final MarketGuard marketGuard;

    /** Optional — provides per-user capital when multi-user mode is active. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.UserBrokerConfigRepository userBrokerConfigRepository;

    @Value("${trading.capital-allocator.mode:FULL_UTILIZATION}")
    private String capitalModeStr;

    @Value("${trading.capital-allocator.base-capital:90000}")
    private double baseCapital;

    @Value("${trading.capital-allocator.risk-per-trade-percent:3.0}")
    private double riskPerTradePercent;

    @Value("${trading.capital-allocator.max-concurrent-trades:6}")
    private int maxConcurrentTrades;

    @Value("${trading.capital-allocator.high-vol-multiplier:0.70}")
    private double highVolMultiplier;

    @Value("${trading.capital-allocator.extreme-vol-multiplier:0.50}")
    private double extremeVolMultiplier;

    public enum CapitalMode { RISK_BASED, FULL_UTILIZATION }
    public enum VolatilityRegime { NORMAL, HIGH, EXTREME }

    public CapitalAllocator(GlobalConfigService globalConfigService, MarketGuard marketGuard) {
        this.globalConfigService = globalConfigService;
        this.marketGuard = marketGuard;
    }

    /**
     * Get max lots affordable for a single trade given current capital and premium.
     *
     * @param indexType the index being traded
     * @param premium option premium per unit
     * @param currentOpenTrades number of currently open trades
     * @return maximum lots affordable for this trade
     */
    public int getMaxLotsForTrade(IndexType indexType, BigDecimal premium, int currentOpenTrades) {
        if (premium == null || premium.signum() <= 0) return 1;

        int lotSize = indexType.lotSize();
        double costPerLot = premium.doubleValue() * lotSize;
        if (costPerLot <= 0) return 1;

        double availableCapital = getAvailableCapital();
        VolatilityRegime regime = detectVolatilityRegime();
        double usableCapital = applyVolatilityAdjustment(availableCapital, regime);
        CapitalMode mode = getCapitalMode();

        int effectiveConcurrent = Math.max(1, maxConcurrentTrades - currentOpenTrades);

        double capitalPerTrade;
        if (mode == CapitalMode.FULL_UTILIZATION) {
            capitalPerTrade = usableCapital / effectiveConcurrent;
        } else {
            capitalPerTrade = usableCapital * (riskPerTradePercent / 100.0);
        }

        int maxLots = (int) Math.floor(capitalPerTrade / costPerLot);
        return Math.max(1, maxLots);
    }

    /**
     * Get available capital — per-user in multi-user mode, global otherwise.
     * In multi-user mode, uses UserBrokerConfig.totalCapital for the current user.
     * Falls back to GlobalConfigService's total capital if not set or in single-user mode.
     */
    public double getAvailableCapital() {
        // Multi-user: use per-user capital if UserContext is set and config exists
        if (userBrokerConfigRepository != null && com.algo.trade.multiuser.UserContext.isSet()) {
            Long userId = com.algo.trade.multiuser.UserContext.getUserId();
            if (userId != null && !userId.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                return userBrokerConfigRepository.findByUserId(userId)
                        .map(config -> config.getTotalCapital() > 0 ? config.getTotalCapital() : baseCapital)
                        .orElse(baseCapital);
            }
        }
        // Single-user or default: use global config
        BigDecimal configCapital = globalConfigService.getTotalCapital();
        if (configCapital != null && configCapital.doubleValue() > 0) {
            return configCapital.doubleValue();
        }
        return baseCapital;
    }

    /**
     * Detect current volatility regime from VIX.
     */
    public VolatilityRegime detectVolatilityRegime() {
        double vix = marketGuard.getCurrentVix();
        if (vix > 25) return VolatilityRegime.EXTREME;
        if (vix > 18) return VolatilityRegime.HIGH;
        return VolatilityRegime.NORMAL;
    }

    private double applyVolatilityAdjustment(double capital, VolatilityRegime regime) {
        return switch (regime) {
            case EXTREME -> capital * extremeVolMultiplier;
            case HIGH -> capital * highVolMultiplier;
            case NORMAL -> capital;
        };
    }

    private CapitalMode getCapitalMode() {
        try {
            return CapitalMode.valueOf(capitalModeStr.toUpperCase());
        } catch (Exception e) {
            return CapitalMode.FULL_UTILIZATION;
        }
    }

    public Map<String, Object> getStatus() {
        double available = getAvailableCapital();
        VolatilityRegime regime = detectVolatilityRegime();
        double usable = applyVolatilityAdjustment(available, regime);
        CapitalMode mode = getCapitalMode();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", mode.name());
        status.put("regime", regime.name());
        status.put("availableCapital", Math.round(available));
        status.put("usableCapital", Math.round(usable));
        status.put("maxConcurrentTrades", maxConcurrentTrades);
        status.put("capitalPerTrade", Math.round(usable / maxConcurrentTrades));
        status.put("riskPerTradePercent", riskPerTradePercent);
        return status;
    }
}
