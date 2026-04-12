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
import org.springframework.stereotype.Service;

/**
 * Read-side reporting facade for REST endpoints and journals.
 */
@Service
public class ReportingService {

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
        return brokerClient.positions();
    }

    public List<OrderEntity> orders() {
        return orderRepository.findAll();
    }

    public List<TradeEntity> trades() {
        return tradeRepository.findAll();
    }

    public PnlSnapshot pnl() {
        BigDecimal realized = tradeRepository.findAll().stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = positions().stream()
                .map(Position::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PnlSnapshot(Instant.now(), realized, unrealized, realized.add(unrealized));
    }

    public Optional<StrategyDecisionEntity> latestDecision() {
        return decisionRepository.findTopByOrderByTimestampDesc();
    }

    public String tradeJournalCsv() {
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
        return csv.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
