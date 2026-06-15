package com.algo.trade.multiuser;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Utility service that filters trade/order queries by the current user's ID.
 *
 * In multi-user mode, each user sees only their own trades and orders.
 * This service reads from UserContext.getUserId() and queries the repository
 * using the userId field added to TradeEntity and OrderEntity.
 *
 * For backward compatibility with existing trades (userId = null),
 * trades without a userId are considered to belong to the DEFAULT_USER_ID (1).
 */
@Service
public class UserTradeFilter {

    private static final Logger log = LoggerFactory.getLogger(UserTradeFilter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;

    public UserTradeFilter(TradeRepository tradeRepository, OrderRepository orderRepository) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Get all trades for the current user.
     */
    public List<TradeEntity> getMyTrades() {
        Long userId = UserContext.getUserId();
        return tradeRepository.findByUserId(userId);
    }

    /**
     * Get open trades for the current user.
     */
    public List<TradeEntity> getMyOpenTrades() {
        Long userId = UserContext.getUserId();
        return tradeRepository.findByUserIdAndStatus(userId, TradeStatus.OPEN);
    }

    /**
     * Get all orders for the current user.
     */
    public List<OrderEntity> getMyOrders() {
        Long userId = UserContext.getUserId();
        return orderRepository.findByUserId(userId);
    }

    /**
     * Get today's orders for the current user.
     */
    public List<OrderEntity> getMyTodayOrders() {
        Long userId = UserContext.getUserId();
        Instant startOfDay = todayStart();
        Instant endOfDay = todayEnd();
        return orderRepository.findByUserIdAndUpdatedAtBetween(userId, startOfDay, endOfDay);
    }

    /**
     * Get daily realized P&L for the current user.
     * Sums realizedPnl of all trades entered today (closed or open with partial P&L).
     */
    public BigDecimal getMyDailyPnl() {
        Long userId = UserContext.getUserId();
        Instant startOfDay = todayStart();
        Instant endOfDay = todayEnd();
        List<TradeEntity> todayTrades = tradeRepository.findByUserIdAndEntryTimeBetween(userId, startOfDay, endOfDay);
        return todayTrades.stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get open trade count for the current user.
     */
    public int getMyOpenTradeCount() {
        return getMyOpenTrades().size();
    }

    /**
     * Get today's trade count for the current user.
     */
    public int getMyTodayTradeCount() {
        Long userId = UserContext.getUserId();
        Instant startOfDay = todayStart();
        Instant endOfDay = todayEnd();
        return tradeRepository.findByUserIdAndEntryTimeBetween(userId, startOfDay, endOfDay).size();
    }

    /**
     * Get trades for a specific user (admin use).
     */
    public List<TradeEntity> getTradesForUser(Long userId) {
        return tradeRepository.findByUserId(userId);
    }

    /**
     * Get today's P&L for a specific user (admin use).
     */
    public BigDecimal getDailyPnlForUser(Long userId) {
        Instant startOfDay = todayStart();
        Instant endOfDay = todayEnd();
        List<TradeEntity> todayTrades = tradeRepository.findByUserIdAndEntryTimeBetween(userId, startOfDay, endOfDay);
        return todayTrades.stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Instant todayStart() {
        return ZonedDateTime.now(IST).toLocalDate().atStartOfDay(IST).toInstant();
    }

    private Instant todayEnd() {
        return ZonedDateTime.now(IST).toLocalDate().plusDays(1).atStartOfDay(IST).toInstant();
    }
}
