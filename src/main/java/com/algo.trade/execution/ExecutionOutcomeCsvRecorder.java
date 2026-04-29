package com.algo.trade.execution;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExecutionOutcomeCsvRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionOutcomeCsvRecorder.class);
    private static final Path OUTPUT = Path.of("reports", "entry-signals", "entry-execution-outcomes.csv");
    private static final Path EXIT_OUTPUT = Path.of("reports", "entry-signals", "exit-execution-outcomes.csv");
    private static final String EXIT_HEADER = String.join(",",
            "tradeId",
            "timestamp",
            "strategyType",
            "instrumentKey",
            "underlying",
            "optionType",
            "entryPrice",
            "exitPrice",
            "quantity",
            "realizedPnl",
            "exitReason",
            "appliedTrailingStopActivationPercent",
            "appliedTrailingGapPercent",
            "clientOrderId",
            "brokerOrderId",
            "orderStatus",
            "averageFillPrice"
    ) + System.lineSeparator();
    private static final String HEADER = String.join(",",
            "decisionKey",
            "timestamp",
            "stage",
            "accepted",
            "signalType",
            "underlying",
            "optionType",
            "marketDataMode",
            "executionMode",
            "stopLossPercent",
            "targetPercent",
            "trailingStopActivationPercent",
            "trailingGapPercent",
            "maxRiskPerTradePercent",
            "minSignalScorePercent",
            "selectedInstrumentKey",
            "selectedStrike",
            "underlyingPrice",
            "optionPremium",
            "lotSize",
            "quantity",
            "riskAmount",
            "estimatedCost",
            "clientOrderId",
            "brokerOrderId",
            "orderStatus",
            "requestedQuantity",
            "filledQuantity",
            "averageFillPrice",
            "brokerRejectionReason",
            "reasons"
    ) + System.lineSeparator();
    private final TradingProperties properties;

    public ExecutionOutcomeCsvRecorder(TradingProperties properties) {
        this.properties = properties;
    }

    public synchronized void recordEntry(
            StrategyDecision decision,
            BigDecimal optionPremium,
            int lotSize,
            String stage,
            boolean accepted,
            Integer quantity,
            BigDecimal riskAmount,
            BigDecimal estimatedCost,
            OrderResponse order,
            List<String> reasons,
            StrategyConfig strategyConfig
    ) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            if (Files.notExists(OUTPUT) || Files.size(OUTPUT) == 0) {
                Files.writeString(OUTPUT, HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(OUTPUT, row(decision, optionPremium, lotSize, stage, accepted, quantity, riskAmount,
                    estimatedCost, order, reasons, strategyConfig), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("Execution outcome CSV write failed: directory={}, message={}", OUTPUT.getParent(), ex.getMessage());
        }
    }

    private String row(
            StrategyDecision decision,
            BigDecimal optionPremium,
            int lotSize,
            String stage,
            boolean accepted,
            Integer quantity,
            BigDecimal riskAmount,
            BigDecimal estimatedCost,
            OrderResponse order,
            List<String> reasons,
            StrategyConfig strategyConfig
    ) {
        return String.join(",",
                csv(decisionKey(decision)),
                csv(IstDateTimes.formatInstant(Instant.now())),
                csv(stage),
                csv(accepted),
                csv(decision.signalType()),
                csv(decision.underlying()),
                csv(decision.optionType().map(Enum::name).orElse(null)),
                csv(properties.marketDataMode()),
                csv(properties.executionMode()),
                csv(strategyConfig != null ? strategyConfig.getStopLossPercent() : null),
                csv(strategyConfig != null ? strategyConfig.getTargetPercent() : null),
                csv(strategyConfig != null ? strategyConfig.getTrailingStopActivationPercent() : null),
                csv(strategyConfig != null ? strategyConfig.getTrailingGapPercent() : null),
                csv(properties.risk().maxRiskPerTradePercent()),
                csv(properties.entry().minSignalScorePercent()),
                csv(decision.selectedInstrumentKey().orElse(null)),
                csv(decision.selectedStrike().orElse(null)),
                csv(decision.underlyingPrice()),
                csv(optionPremium),
                csv(lotSize),
                csv(quantity),
                csv(riskAmount),
                csv(estimatedCost),
                csv(order == null ? null : order.clientOrderId()),
                csv(order == null ? null : order.brokerOrderId().orElse(null)),
                csv(order == null ? null : order.status()),
                csv(order == null ? null : order.requestedQuantity()),
                csv(order == null ? null : order.filledQuantity()),
                csv(order == null ? null : order.averageFillPrice().orElse(null)),
                csv(order == null ? null : order.rejectionReason().orElse(null)),
                csv(String.join("; ", reasons))
        ) + System.lineSeparator();
    }

    private String decisionKey(StrategyDecision decision) {
        // Must match StrategySignalCsvRecorder.decisionKey() for correlation
        // Signal recorder uses: request.timestamp() + "|" + request.underlying() + "|" + request.optionType() + "|" + request.selectedInstrumentKey()
        // OptionType enum toString = "CE"/"PE", Optional.orElse(null) toString = "CE"/"PE" or "null"
        String optType = decision.optionType().map(Enum::name).orElse(null);
        String instKey = decision.selectedInstrumentKey().orElse(null);
        String raw = decision.timestamp() + "|" + decision.underlying() + "|" + optType + "|" + instKey;
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    public synchronized void recordExit(TradeEntity trade, BigDecimal exitPrice, BigDecimal realizedPnl,
                                        String exitReason, OrderResponse order) {
        try {
            Files.createDirectories(EXIT_OUTPUT.getParent());
            if (Files.notExists(EXIT_OUTPUT) || Files.size(EXIT_OUTPUT) == 0) {
                Files.writeString(EXIT_OUTPUT, EXIT_HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String row = String.join(",",
                    csv(trade.getTradeId()),
                    csv(IstDateTimes.formatInstant(Instant.now())),
                    csv(trade.getStrategyType()),
                    csv(trade.getInstrumentKey()),
                    csv(trade.getUnderlying()),
                    csv(trade.getOptionType()),
                    csv(trade.getEntryPrice()),
                    csv(exitPrice),
                    csv(trade.getQuantity()),
                    csv(realizedPnl),
                    csv(exitReason),
                    csv(trade.getAppliedTrailingStopActivationPercent()),
                    csv(trade.getAppliedTrailingGapPercent()),
                    csv(order == null ? null : order.clientOrderId()),
                    csv(order == null ? null : order.brokerOrderId().orElse(null)),
                    csv(order == null ? null : order.status()),
                    csv(order == null ? null : order.averageFillPrice().orElse(null))
            ) + System.lineSeparator();
            Files.writeString(EXIT_OUTPUT, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("Exit outcome CSV write failed: directory={}, message={}", EXIT_OUTPUT.getParent(), ex.getMessage());
        }
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
