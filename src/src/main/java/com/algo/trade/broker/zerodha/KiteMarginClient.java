package com.algo.trade.broker.zerodha;

import com.algo.trade.broker.BrokerException;
import com.algo.trade.broker.BrokerMarginClient;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.MarginSnapshot;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Kite Connect margin APIs: {@code GET /user/margins}, {@code POST /margins/basket}.
 */
@Component
public class KiteMarginClient implements BrokerMarginClient {

    private static final Logger log = LoggerFactory.getLogger(KiteMarginClient.class);

    private final TradingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KiteAccessTokenStore tokenStore;

    public KiteMarginClient(TradingProperties properties, RestClient zerodhaRestClient,
                            ObjectMapper objectMapper, KiteAccessTokenStore tokenStore) {
        this.properties = properties;
        this.restClient = zerodhaRestClient;
        this.objectMapper = objectMapper;
        this.tokenStore = tokenStore;
    }

    @Override
    public Optional<MarginSnapshot> equityMargins() {
        if (!authenticated()) {
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/user/margins")
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class);
            JsonNode equity = objectMapper.readTree(body).path("data").path("equity");
            BigDecimal available = decimal(equity.path("available").path("live_balance"));
            if (available.signum() == 0) {
                available = decimal(equity.path("available").path("cash"));
            }
            BigDecimal utilised = decimal(equity.path("utilised").path("debits"));
            BigDecimal net = decimal(equity.path("net"));
            if (net.signum() == 0) {
                net = available;
            }
            return Optional.of(new MarginSnapshot(available, utilised, net));
        } catch (Exception ex) {
            log.warn("Kite equity margins fetch failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigDecimal> basketOrderMargin(List<OrderRequest> orders, boolean considerPositions) {
        if (!authenticated() || orders == null || orders.isEmpty()) {
            return Optional.empty();
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("consider_positions", considerPositions);
            root.put("mode", "compact");
            ArrayNode orderArray = root.putArray("orders");
            for (OrderRequest order : orders) {
                String[] parts = splitInstrumentKey(order.instrumentKey());
                ObjectNode o = orderArray.addObject();
                o.put("exchange", parts[0]);
                o.put("tradingsymbol", parts[1]);
                o.put("transaction_type", order.side().name());
                o.put("variety", "regular");
                o.put("product", order.productType().name());
                o.put("order_type", order.orderType().name());
                o.put("quantity", order.quantity());
                if (order.orderType() == OrderType.LIMIT) {
                    order.limitPrice().ifPresent(p -> o.put("price", p.doubleValue()));
                }
            }
            String response = restClient.post()
                    .uri("/margins/basket")
                    .headers(this::applyAuthHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(root.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode finalNode = objectMapper.readTree(response).path("data").path("final");
            BigDecimal total = decimal(finalNode.path("total"));
            if (total.signum() == 0) {
                // Fallback: use span + exposure + additional when total is zero
                BigDecimal span = decimal(finalNode.path("span"));
                BigDecimal exposure = decimal(finalNode.path("exposure"));
                BigDecimal additional = decimal(finalNode.path("additional"));
                total = span.add(exposure).add(additional);
            }
            return Optional.of(total.max(BigDecimal.ZERO));
        } catch (Exception ex) {
            log.warn("Kite basket margin failed ({} orders): {}", orders.size(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void applyAuthHeaders(HttpHeaders headers) {
        if (!authenticated()) {
            throw new BrokerException("Kite auth required for margin API");
        }
        headers.set("X-Kite-Version", "3");
        headers.set(HttpHeaders.AUTHORIZATION,
                "token " + properties.broker().apiKey() + ":" + tokenStore.accessToken().orElseThrow());
    }

    private boolean authenticated() {
        return properties.broker().apiKey() != null && !properties.broker().apiKey().isBlank()
                && tokenStore.accessToken().isPresent();
    }

    private static String[] splitInstrumentKey(String instrumentKey) {
        String[] parts = instrumentKey.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new BrokerException("Expected instrument key in EXCHANGE:TRADINGSYMBOL format");
        }
        return parts;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText("0"));
    }
}
