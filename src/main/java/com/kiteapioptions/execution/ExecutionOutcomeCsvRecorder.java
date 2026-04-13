package com.kiteapioptions.execution;

import com.kiteapioptions.domain.OrderResponse;
import com.kiteapioptions.domain.StrategyDecision;
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
    private static final String HEADER = String.join(",",
            "timestamp",
            "stage",
            "accepted",
            "signalType",
            "underlying",
            "optionType",
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
            List<String> reasons
    ) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            if (Files.notExists(OUTPUT) || Files.size(OUTPUT) == 0) {
                Files.writeString(OUTPUT, HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(OUTPUT, row(decision, optionPremium, lotSize, stage, accepted, quantity, riskAmount,
                    estimatedCost, order, reasons), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
            List<String> reasons
    ) {
        return String.join(",",
                csv(Instant.now()),
                csv(stage),
                csv(accepted),
                csv(decision.signalType()),
                csv(decision.underlying()),
                csv(decision.optionType().map(Enum::name).orElse(null)),
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

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
