package com.algo.trade.risk;

import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.StrategyType;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prevents excessive same-direction exposure across correlated indices.
 * NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY are highly correlated — opening
 * the same directional spread on all four simultaneously multiplies risk.
 *
 * <p>Gate: max N same-direction open spreads across the correlated bucket.</p>
 */
@Component
public class CorrelationGate {

    private static final Logger log = LoggerFactory.getLogger(CorrelationGate.class);

    /** Correlated bucket — all NSE index options move together on macro events. */
    private static final Set<UnderlyingSymbol> CORRELATED_BUCKET = Set.of(
            UnderlyingSymbol.NIFTY,
            UnderlyingSymbol.BANKNIFTY,
            UnderlyingSymbol.FINNIFTY,
            UnderlyingSymbol.MIDCPNIFTY
    );

    /** Max same-direction open spreads across the correlated bucket. */
    private static final int MAX_SAME_DIRECTION_CORRELATED = 2;

    private final PositionGroupRepository positionGroupRepository;

    public CorrelationGate(PositionGroupRepository positionGroupRepository) {
        this.positionGroupRepository = positionGroupRepository;
    }

    /**
     * Check if a new spread entry is allowed given existing correlated positions.
     *
     * @param underlying the underlying for the new entry
     * @param strategyType the strategy type for the new entry
     * @return empty if allowed, or a rejection reason string
     */
    public java.util.Optional<String> check(UnderlyingSymbol underlying, StrategyType strategyType) {
        if (!CORRELATED_BUCKET.contains(underlying)) {
            return java.util.Optional.empty(); // SENSEX is independent
        }

        boolean isSellingStrategy = strategyType.isSellingStrategy();
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();

        long sameDirectionCount = openGroups.stream()
                .filter(g -> g.getStatus() == PositionGroupStatus.OPEN)
                .filter(g -> CORRELATED_BUCKET.contains(g.getUnderlying()))
                .filter(g -> g.getStrategyType().isSellingStrategy() == isSellingStrategy)
                .count();

        if (sameDirectionCount >= MAX_SAME_DIRECTION_CORRELATED) {
            String direction = isSellingStrategy ? "short-premium" : "long-premium";
            String reason = String.format(
                    "Correlation gate: %d %s spreads already open across correlated indices (max %d)",
                    sameDirectionCount, direction, MAX_SAME_DIRECTION_CORRELATED);
            log.info("[CorrelationGate] BLOCKED: {} for {} — {}", strategyType, underlying, reason);
            return java.util.Optional.of(reason);
        }

        return java.util.Optional.empty();
    }
}
