package com.algo.trade.controller;

import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.strategy.spread.SpreadStrategyRegistry;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for spread position introspection and manual control.
 */
@RestController
@RequestMapping("/spreads")
public class SpreadController {

    private static final Logger log = LoggerFactory.getLogger(SpreadController.class);

    private final PositionGroupRepository positionGroupRepository;
    private final SpreadStrategyRegistry strategyRegistry;
    private final MarketDataService marketDataService;

    public SpreadController(PositionGroupRepository positionGroupRepository,
                            SpreadStrategyRegistry strategyRegistry,
                            MarketDataService marketDataService) {
        this.positionGroupRepository = positionGroupRepository;
        this.strategyRegistry = strategyRegistry;
        this.marketDataService = marketDataService;
    }

    /**
     * List all open spread position groups with leg details and current P&L.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listOpenSpreads() {
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        List<Map<String, Object>> result = openGroups.stream()
                .filter(g -> g.getStatus() == PositionGroupStatus.OPEN)
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Get full details for a specific spread group.
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> getSpreadDetail(@PathVariable String groupId) {
        return positionGroupRepository.findByGroupId(groupId)
                .map(entity -> ResponseEntity.ok(toDetail(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Force-flatten all open spread positions immediately.
     */
    @PostMapping("/flatten")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public ResponseEntity<Map<String, Object>> flattenAll() {
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        int flattened = 0;
        int failed = 0;

        for (PositionGroupEntity entity : openGroups) {
            if (entity.getStatus() != PositionGroupStatus.OPEN) continue;
            try {
                PositionGroup group = entity.toDomain();
                Map<String, BigDecimal> marks = new HashMap<>();
                entity.getLegs().stream()
                        .filter(SpreadLegEntity::isActive)
                        .forEach(leg -> marks.put(leg.getInstrumentKey(), leg.getEntryPrice()));

                // Try to get live prices
                List<String> keys = entity.getLegs().stream()
                        .filter(SpreadLegEntity::isActive)
                        .map(SpreadLegEntity::getInstrumentKey).toList();
                marketDataService.quotes(keys).forEach((k, q) -> marks.put(k, q.lastPrice()));

                boolean closed = strategyRegistry.get(entity.getStrategyType())
                        .map(strategy -> strategy.forceExit(group, marks, "MANUAL_FLATTEN_ALL"))
                        .orElse(false);
                if (closed) flattened++;
                else failed++;
            } catch (Exception ex) {
                log.error("Flatten failed for group {}: {}", entity.getGroupId(), ex.getMessage());
                failed++;
            }
        }

        Map<String, Object> response = Map.of(
                "flattened", flattened,
                "failed", failed,
                "total", openGroups.size()
        );
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toSummary(PositionGroupEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("groupId", entity.getGroupId());
        map.put("strategyType", entity.getStrategyType().name());
        map.put("underlying", entity.getUnderlying().name());
        map.put("entryTime", entity.getEntryTime().toString());
        map.put("status", entity.getStatus().name());
        map.put("legs", entity.getLegs().stream()
                .filter(SpreadLegEntity::isActive)
                .map(l -> Map.of(
                        "instrument", l.getInstrumentKey(),
                        "strike", l.getStrike(),
                        "side", l.getOrderSide().name(),
                        "qty", l.getQuantity(),
                        "entryPrice", l.getEntryPrice()
                )).toList());
        return map;
    }

    private Map<String, Object> toDetail(PositionGroupEntity entity) {
        Map<String, Object> map = new HashMap<>(toSummary(entity));
        map.put("effectiveSL", entity.getEffectiveStopLossPercent());
        map.put("effectiveTarget", entity.getEffectiveTargetPercent());
        map.put("peakProfit", entity.getPeakProfitPercent());
        map.put("partialExitLayers", entity.getPartialExitLayers());
        map.put("pnl", entity.getPnl());
        map.put("exitTime", entity.getExitTime());
        map.put("exitReason", entity.getExitReason());
        map.put("pnlSource", entity.getPnlSource());
        return map;
    }
}
