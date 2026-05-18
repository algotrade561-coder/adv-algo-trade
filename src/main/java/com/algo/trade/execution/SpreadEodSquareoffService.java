package com.algo.trade.execution;

import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.Quote;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.spread.SpreadStrategyRegistry;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forces exit of all open spread position groups (EOD fail-safe and shutdown).
 */
@Service
public class SpreadEodSquareoffService {

    private static final Logger log = LoggerFactory.getLogger(SpreadEodSquareoffService.class);

    private final PositionGroupRepository positionGroupRepository;
    private final SpreadStrategyRegistry registry;
    private final MarketDataService marketDataService;
    private final TelegramAlertService telegramAlertService;

    public SpreadEodSquareoffService(PositionGroupRepository positionGroupRepository,
                                     SpreadStrategyRegistry registry,
                                     MarketDataService marketDataService,
                                     TelegramAlertService telegramAlertService) {
        this.positionGroupRepository = positionGroupRepository;
        this.registry = registry;
        this.marketDataService = marketDataService;
        this.telegramAlertService = telegramAlertService;
    }

    public int squareOffAllOpenGroups(String reason) {
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        int attempted = 0;
        int closed = 0;
        List<String> failures = new java.util.ArrayList<>();

        for (PositionGroupEntity entity : openGroups) {
            if (entity.getStatus() != PositionGroupStatus.OPEN) {
                continue;
            }
            attempted++;
            boolean ok = registry.get(entity.getStrategyType())
                    .map(strategy -> {
                        Map<String, BigDecimal> prices = fetchPrices(entity);
                        return strategy.forceExit(entity.toDomain(), prices, reason);
                    })
                    .orElse(false);
            if (ok) {
                closed++;
            } else {
                failures.add(entity.getGroupId() + " (" + entity.getStrategyType() + ")");
            }
        }

        if (attempted > 0) {
            log.warn("[SpreadEod] {} open spread group(s): closed={}, failed={}, reason={}",
                    attempted, closed, failures.size(), reason);
            if (!failures.isEmpty()) {
                telegramAlertService.systemAlert(String.format(
                        "🚨 Spread EOD square-off partial (%s)\nClosed: %d / %d\nStill OPEN:\n%s",
                        reason, closed, attempted, String.join("\n", failures)));
            }
        }
        return closed;
    }

    private Map<String, BigDecimal> fetchPrices(PositionGroupEntity entity) {
        List<String> keys = entity.getLegs().stream()
                .map(com.algo.trade.persistence.SpreadLegEntity::getInstrumentKey)
                .toList();
        Map<String, Quote> quotes = marketDataService.quotes(keys);
        Map<String, BigDecimal> prices = new HashMap<>();
        quotes.forEach((k, q) -> prices.put(k, q.lastPrice()));
        return prices;
    }
}
