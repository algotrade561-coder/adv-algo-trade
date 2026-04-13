package com.kiteapioptions.reporting;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.PnlSnapshot;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.persistence.OrderEntity;
import com.kiteapioptions.persistence.OrderRepository;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.StrategyDecisionRepository;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.persistence.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-side reporting facade for REST endpoints and journals.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final BrokerClient brokerClient;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;

    public ReportingService(BrokerClient brokerClient, TradeRepository tradeRepository, OrderRepository orderRepository,
                            StrategyDecisionRepository decisionRepository) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
    }

    public List<Position> positions() {
        log.debug("Reporting positions requested");
        List<Position> positions = brokerClient.positions();
        log.debug("Reporting positions completed: count={}", positions.size());
        return positions;
    }

    public List<OrderEntity> orders() {
        List<OrderEntity> orders = orderRepository.findAll();
        log.debug("Reporting orders completed: count={}", orders.size());
        return orders;
    }

    public List<TradeEntity> trades() {
        List<TradeEntity> trades = tradeRepository.findAll();
        log.debug("Reporting trades completed: count={}", trades.size());
        return trades;
    }

    public PnlSnapshot pnl() {
        log.debug("Reporting PnL calculation started");
        BigDecimal realized = tradeRepository.findAll().stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = positions().stream()
                .map(Position::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        PnlSnapshot snapshot = new PnlSnapshot(Instant.now(), realized, unrealized, realized.add(unrealized));
        log.debug("Reporting PnL calculation completed: realized={}, unrealized={}, total={}",
                snapshot.realizedPnl(), snapshot.unrealizedPnl(), snapshot.totalPnl());
        return snapshot;
    }

    public Optional<StrategyDecisionEntity> latestDecision() {
        Optional<StrategyDecisionEntity> decision = decisionRepository.findTopByOrderByTimestampDesc();
        log.debug("Reporting latest decision completed: present={}", decision.isPresent());
        return decision;
    }

    public List<StrategyDecisionEntity> recentDecisions() {
        List<StrategyDecisionEntity> decisions = decisionRepository.findTop20ByOrderByTimestampDesc();
        log.debug("Reporting recent decisions completed: count={}", decisions.size());
        return decisions;
    }

    public String tradeJournalCsv() {
        log.debug("Reporting trade journal CSV generation started");
        StringBuilder csv = new StringBuilder("tradeId,instrumentKey,underlying,optionType,status,quantity,entryPrice,exitPrice,entryTime,exitTime,realizedPnl,entryReason,exitReason\n");
        for (TradeEntity trade : tradeRepository.findAll()) {
            csv.append(trade.getTradeId()).append(',')
                    .append(trade.getInstrumentKey()).append(',')
                    .append(trade.getUnderlying()).append(',')
                    .append(trade.getOptionType()).append(',')
                    .append(trade.getStatus()).append(',')
                    .append(trade.getQuantity()).append(',')
                    .append(trade.getEntryPrice()).append(',')
                    .append(trade.getExitPrice()).append(',')
                    .append(trade.getEntryTime()).append(',')
                    .append(trade.getExitTime()).append(',')
                    .append(trade.getRealizedPnl()).append(',')
                    .append(escape(trade.getEntryReason())).append(',')
                    .append(escape(trade.getExitReason())).append('\n');
        }
        log.debug("Reporting trade journal CSV generation completed: bytes={}", csv.length());
        return csv.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
