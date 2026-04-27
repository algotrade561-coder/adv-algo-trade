package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks open trades every minute and closes any that have exceeded their
 * strategy's maxHoldMinutes. Mirrors the time-exit logic from DirectionalBuyStrategy
 * in the reference project but works against DB-persisted TradeEntity records.
 */
@Component
public class MaxHoldExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(MaxHoldExitMonitor.class);

    private final TradeRepository tradeRepository;
    private final ExecutionEngine executionEngine;
    private final StrategyConfigService strategyConfigService;
    private final MarketDataService marketDataService;
    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final PositionSyncProperties positionSyncProperties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public MaxHoldExitMonitor(TradeRepository tradeRepository,
                               ExecutionEngine executionEngine,
                               StrategyConfigService strategyConfigService,
                               MarketDataService marketDataService,
                               TradingProperties properties,
                               GlobalConfigService globalConfigService,
                               PositionSyncProperties positionSyncProperties) {
        this(tradeRepository, executionEngine, strategyConfigService, marketDataService, properties, globalConfigService, positionSyncProperties, Clock.systemUTC());
    }

    MaxHoldExitMonitor(TradeRepository tradeRepository,
                       ExecutionEngine executionEngine,
                       StrategyConfigService strategyConfigService,
                       MarketDataService marketDataService,
                       TradingProperties properties,
                       GlobalConfigService globalConfigService,
                       PositionSyncProperties positionSyncProperties,
                       Clock clock) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.strategyConfigService = strategyConfigService;
        this.marketDataService = marketDataService;
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.positionSyncProperties = positionSyncProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void check() {
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;

        Instant now = clock.instant();

        for (TradeEntity trade : openTrades) {
            if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            int maxHold = resolveMaxHoldMinutes(trade);
            if (maxHold <= 0) continue;

            long heldMinutes = Duration.between(trade.getEntryTime(), now).toMinutes();
            if (heldMinutes < maxHold) continue;

            log.info("[MaxHoldExit] Time limit reached: tradeId={} instrument={} heldMinutes={} maxHoldMinutes={}",
                    trade.getTradeId(), trade.getInstrumentKey(), heldMinutes, maxHold);

            BigDecimal exitPrice = marketDataService.quote(trade.getInstrumentKey())
                    .map(q -> q.lastPrice())
                    .filter(p -> p != null && p.signum() > 0)
                    .orElse(trade.getEntryPrice());

            String reason = "MAX_HOLD_EXIT_" + heldMinutes + "min";
            try {
                executionEngine.closeTrade(trade.getTradeId(), exitPrice, reason);
            } catch (Exception e) {
                log.error("[MaxHoldExit] Failed to close trade: tradeId={} reason={}", trade.getTradeId(), e.getMessage());
            }
        }
    }

    private int resolveMaxHoldMinutes(TradeEntity trade) {
        // Prefer the explicit strategyType field (set at entry time)
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                StrategyType type = StrategyType.valueOf(trade.getStrategyType());
                StrategyConfig config = strategyConfigService.getAll().stream()
                        .filter(c -> c.getStrategyType() == type)
                        .findFirst().orElse(null);
                if (config != null && config.getMaxHoldMinutes() > 0) {
                    return config.getMaxHoldMinutes();
                }
            } catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        // Fallback: parse from entryReason text
        String entryReason = trade.getEntryReason() != null ? trade.getEntryReason().toUpperCase() : "";
        for (StrategyConfig config : strategyConfigService.getAll()) {
            if (entryReason.contains(config.getStrategyType().name()) && config.getMaxHoldMinutes() > 0) {
                return config.getMaxHoldMinutes();
            }
        }
        // Final fallback
        int fallback = strategyConfigService.getDirectionalBuyConfig().getMaxHoldMinutes();
        return fallback > 0 ? fallback : globalConfigService.getMaxHoldMinutes();
    }
}
