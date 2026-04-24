package com.algo.trade.notification;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.StrategyDecision;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class TelegramAlertService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAlertService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 500;

    private final TradingProperties properties;
    private final RestClient.Builder restClientBuilder;

    public TelegramAlertService(TradingProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        send("Application started"
                + System.lineSeparator() + "Mode: " + properties.mode()
                + System.lineSeparator() + "Live trading enabled: " + properties.liveTradingEnabled());
    }

    @EventListener(ContextClosedEvent.class)
    public void applicationShutdown() {
        send("Application shutting down"
                + System.lineSeparator() + "Mode: " + properties.mode()
                + System.lineSeparator() + "Live trading enabled: " + properties.liveTradingEnabled());
    }

    public void tradingStateChanged(String event, Object status) {
        send("Trading event: " + event + System.lineSeparator() + status);
    }

    public void systemAlert(String message) {
        send(message);
    }

    public void entryRejected(StrategyDecision decision, BigDecimal optionPremium, String stage, List<String> reasons) {
        send("Entry rejected"
                + System.lineSeparator() + "Stage: " + stage
                + System.lineSeparator() + "Signal: " + decision.signalType()
                + System.lineSeparator() + "Instrument: " + decision.selectedInstrumentKey().orElse("")
                + System.lineSeparator() + "Premium: " + optionPremium
                + System.lineSeparator() + "Score: " + decision.confidenceScore()
                + System.lineSeparator() + "Reasons: " + String.join("; ", reasons));
    }

    public void entryOrderFilled(
            StrategyDecision decision,
            BigDecimal optionPremium,
            int quantity,
            BigDecimal estimatedCost,
            OrderResponse order
    ) {
        send("Entry order filled"
                + System.lineSeparator() + "Signal: " + decision.signalType()
                + System.lineSeparator() + "Instrument: " + order.instrumentKey()
                + System.lineSeparator() + "Quantity: " + quantity
                + System.lineSeparator() + "Signal premium: " + optionPremium
                + System.lineSeparator() + "Fill price: " + order.averageFillPrice().orElse(null)
                + System.lineSeparator() + "Estimated cost: " + estimatedCost
                + System.lineSeparator() + "Broker order: " + order.brokerOrderId().orElse(""));
    }

    public void orderNotFilled(
            StrategyDecision decision,
            BigDecimal optionPremium,
            int quantity,
            OrderResponse order,
            List<String> reasons
    ) {
        send("Entry order not filled"
                + System.lineSeparator() + "Status: " + order.status()
                + System.lineSeparator() + "Signal: " + decision.signalType()
                + System.lineSeparator() + "Instrument: " + order.instrumentKey()
                + System.lineSeparator() + "Quantity: " + quantity
                + System.lineSeparator() + "Signal premium: " + optionPremium
                + System.lineSeparator() + "Reasons: " + String.join("; ", reasons));
    }

    public void tradeClosed(
            String tradeId,
            String instrumentKey,
            int quantity,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal realizedPnl,
            String reason,
            OrderResponse order
    ) {
        send("Trade closed"
                + System.lineSeparator() + "Trade: " + tradeId
                + System.lineSeparator() + "Instrument: " + instrumentKey
                + System.lineSeparator() + "Quantity: " + quantity
                + System.lineSeparator() + "Entry: " + entryPrice
                + System.lineSeparator() + "Exit: " + exitPrice
                + System.lineSeparator() + "PnL: " + realizedPnl
                + System.lineSeparator() + "Reason: " + reason
                + System.lineSeparator() + "Broker order: " + order.brokerOrderId().orElse(""));
    }

    private void send(String text) {
        TradingProperties.Telegram telegram = properties.telegram();
        if (!telegram.enabled()) {
            return;
        }
        if (isBlank(telegram.botToken()) || isBlank(telegram.chatId())) {
            log.warn("Telegram alert skipped: bot token or chat id is missing");
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClientBuilder
                        .clone()
                        .baseUrl("https://api.telegram.org")
                        .requestFactory(requestFactory(telegram.requestTimeout()))
                        .build()
                        .post()
                        .uri("/bot{token}/sendMessage", telegram.botToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new SendMessageRequest(telegram.chatId(), text))
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RestClientException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Telegram alert failed after {} attempts: {}", MAX_ATTEMPTS, ex.getMessage());
                    return;
                }
                log.warn("Telegram alert attempt failed: attempt={}, message={}", attempt, ex.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        Duration resolvedTimeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(resolvedTimeout);
        requestFactory.setReadTimeout(resolvedTimeout);
        return requestFactory;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(INITIAL_BACKOFF_MILLIS * (1L << Math.max(0, attempt - 1)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record SendMessageRequest(String chat_id, String text) {
    }
}
