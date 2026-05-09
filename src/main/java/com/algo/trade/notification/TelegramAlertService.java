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
    private static final long DEDUP_WINDOW_MILLIS = 5 * 60 * 1000; // 5 minutes

    private final TradingProperties properties;
    private final RestClient.Builder restClientBuilder;

    /** Dedup key → last sent timestamp. Prevents flooding identical alerts. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recentAlerts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Keywords that bypass deduplication — always sent immediately. */
    private static final java.util.Set<String> ALWAYS_SEND_KEYWORDS = java.util.Set.of(
            "SL Hit", "STOP_LOSS", "TARGET", "Target Hit", "Trailing Stop",
            "Trade closed", "PAPER Trade Closed", "order filled", "Entry order filled",
            "URGENT", "DANGER", "FailSafe", "Graceful shutdown",
            "Application started", "Application shutting down",
            "Kill switch", "HALTED", "Regime change"
    );

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

        // Smart deduplication: suppress repeated identical alerts within 5-min window
        // but always send critical alerts (SL, target, trade closed, etc.)
        if (!isAlwaysSend(text)) {
            String dedupKey = extractDedupKey(text);
            long now = System.currentTimeMillis();
            Long lastSent = recentAlerts.get(dedupKey);
            if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MILLIS) {
                log.debug("Telegram alert deduplicated (sent {}s ago): {}", (now - lastSent) / 1000, dedupKey);
                return;
            }
            recentAlerts.put(dedupKey, now);
            // Cleanup stale entries periodically
            if (recentAlerts.size() > 200) {
                recentAlerts.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_WINDOW_MILLIS * 2);
            }
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

    /** Check if this alert should always be sent (bypass dedup). */
    private boolean isAlwaysSend(String text) {
        String upper = text.toUpperCase();
        return ALWAYS_SEND_KEYWORDS.stream().anyMatch(kw -> upper.contains(kw.toUpperCase()));
    }

    /**
     * Extract a dedup key from the alert text.
     * Groups by: first line (alert type) + instrument key if present.
     * This means "Entry rejected for NIFTY26APR24500CE" deduplicates separately
     * from "Entry rejected for NIFTY26APR24600CE".
     */
    private String extractDedupKey(String text) {
        // Use first line as the base key
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text.trim();
        // Extract instrument key if present (NFO:NIFTY...)
        String instrument = "";
        if (text.contains("NFO:") || text.contains("NIFTY") || text.contains("BANKNIFTY")) {
            for (String word : text.split("[\\s,;]+")) {
                if (word.contains("NIFTY") && word.length() > 8) {
                    instrument = word;
                    break;
                }
            }
        }
        return firstLine + "|" + instrument;
    }

    private record SendMessageRequest(String chat_id, String text) {
    }
}
