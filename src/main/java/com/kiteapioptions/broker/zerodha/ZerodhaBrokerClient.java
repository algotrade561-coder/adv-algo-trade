package com.kiteapioptions.broker.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.broker.BrokerException;
import com.kiteapioptions.broker.MarketDataListener;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.BrokerName;
import com.kiteapioptions.domain.BrokerSession;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderResponse;
import com.kiteapioptions.domain.OrderSide;
import com.kiteapioptions.domain.OrderStatus;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.marketdata.KiteInstrumentCsvParser;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Zerodha Kite Connect REST adapter isolated behind the broker-neutral interface.
 */
public class ZerodhaBrokerClient implements BrokerClient {

    private static final DateTimeFormatter HISTORICAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter KITE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final Logger log = LoggerFactory.getLogger(ZerodhaBrokerClient.class);

    private final TradingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KiteInstrumentCsvParser instrumentCsvParser;
    private final KiteAccessTokenStore tokenStore;

    public ZerodhaBrokerClient(
            TradingProperties properties,
            RestClient restClient,
            ObjectMapper objectMapper,
            KiteInstrumentCsvParser instrumentCsvParser,
            KiteAccessTokenStore tokenStore
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.instrumentCsvParser = instrumentCsvParser;
        this.tokenStore = tokenStore;
    }

    @Override
    public BrokerSession session() {
        boolean authenticated = hasText(properties.broker().apiKey()) && tokenStore.authenticated();
        log.debug("Zerodha session requested: apiKeyConfigured={}, authenticated={}, userIdPresent={}",
                hasText(properties.broker().apiKey()), authenticated,
                tokenStore.userId().orElse(properties.broker().userId()) != null);
        return new BrokerSession(BrokerName.ZERODHA, tokenStore.userId().orElse(properties.broker().userId()),
                authenticated, tokenStore.updatedAt().orElse(null), null);
    }

    @Override
    public List<Instrument> downloadInstruments() {
        try {
            log.info("Zerodha instrument download requested");
            String csv = retryWithBackoff("downloadInstruments", () -> restClient.get()
                    .uri("/instruments")
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class));
            List<Instrument> instruments = instrumentCsvParser.parse(csv);
            log.info("Zerodha instrument download completed: instrumentCount={}", instruments.size());
            return instruments;
        } catch (RestClientException ex) {
            log.warn("Zerodha instrument download failed: {}", ex.getMessage());
            throw new BrokerException("Failed to download Zerodha instruments", ex);
        }
    }

    @Override
    public Optional<Quote> quote(String instrumentKey) {
        return Optional.ofNullable(quotes(List.of(instrumentKey)).get(instrumentKey));
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.debug("Zerodha quotes skipped: no instrument keys supplied");
            return Map.of();
        }
        log.debug("Zerodha quotes requested: count={}", instrumentKeys.size());

        URI uri = UriComponentsBuilder.fromPath("/quote")
                .queryParam("i", instrumentKeys.toArray())
                .build()
                .encode()
                .toUri();

        try {
            String body = retryWithBackoff("quotes", () -> restClient.get()
                    .uri(uri)
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class));
            JsonNode data = objectMapper.readTree(body).path("data");
            Map<String, Quote> quotes = new LinkedHashMap<>();
            data.fields().forEachRemaining(entry -> quotes.put(entry.getKey(), quoteFromJson(entry.getKey(), entry.getValue())));
            log.debug("Zerodha quotes completed: requestedCount={}, returnedCount={}", instrumentKeys.size(), quotes.size());
            return Map.copyOf(quotes);
        } catch (Exception ex) {
            log.warn("Zerodha quotes failed: requestedCount={}, message={}", instrumentKeys.size(), ex.getMessage());
            throw new BrokerException("Failed to retrieve Zerodha quotes", ex);
        }
    }

    @Override
    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        String token = tokenFromInstrumentKey(request.instrumentKey());
        ZoneId zoneId = properties.timezone();
        String from = HISTORICAL_FORMAT.format(LocalDateTime.ofInstant(request.from(), zoneId));
        String to = HISTORICAL_FORMAT.format(LocalDateTime.ofInstant(request.to(), zoneId));
        log.info("Zerodha historical candles requested: instrumentKey={}, token={}, timeframe={}, from={}, to={}, includeOpenInterest={}",
                request.instrumentKey(), token, request.timeframe(), from, to, request.includeOpenInterest());

        URI uri = UriComponentsBuilder.fromPath("/instruments/historical/{token}/{interval}")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("oi", request.includeOpenInterest() ? 1 : 0)
                .build(token, kiteInterval(request.timeframe()));

        try {
            String body = retryWithBackoff("historicalCandles", () -> restClient.get()
                    .uri(uri)
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class));
            JsonNode candles = objectMapper.readTree(body).path("data").path("candles");
            List<Candle> parsed = parseCandles(request.instrumentKey(), request.timeframe(), candles);
            log.info("Zerodha historical candles completed: instrumentKey={}, count={}", request.instrumentKey(), parsed.size());
            return parsed;
        } catch (Exception ex) {
            log.warn("Zerodha historical candles failed: instrumentKey={}, message={}", request.instrumentKey(), ex.getMessage());
            throw new BrokerException("Failed to retrieve Zerodha historical candles", ex);
        }
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("Zerodha order requested: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}, liveTradingEnabled={}",
                request.clientOrderId(), request.instrumentKey(), request.side(), request.orderType(),
                request.productType(), request.quantity(), properties.liveTradingEnabled());
        if (!properties.liveTradingEnabled()) {
            log.warn("Zerodha order rejected locally: live trading is disabled");
            return new OrderResponse(request.clientOrderId(), Optional.empty(), request.instrumentKey(), request.side(),
                    OrderStatus.REJECTED, request.quantity(), 0, Optional.empty(),
                    Optional.of("Live trading is disabled by configuration"), Instant.now());
        }

        String[] instrumentParts = splitInstrumentKey(request.instrumentKey());
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("exchange", instrumentParts[0]);
        body.add("tradingsymbol", instrumentParts[1]);
        body.add("transaction_type", request.side().name());
        body.add("quantity", String.valueOf(request.quantity()));
        body.add("product", request.productType().name());
        body.add("order_type", request.orderType().name());
        request.limitPrice().ifPresent(price -> body.add("price", price.toPlainString()));
        body.add("validity", "DAY");
        body.add("tag", request.tag());

        try {
            String responseBody = retryWithBackoff("placeOrder", () -> restClient.post()
                    .uri("/orders/regular")
                    .headers(this::applyAuthHeaders)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class));
            //JsonNode data = objectMapper.readTree(responseBody).path("data");
            //Optional<String> orderId = Optional.ofNullable(data.path("order_id").textValue());
            // Tese Execution ---------

            /*String responseBody = """
                                    {
                                    "status": "success",
                                    "data": {
                                    "order_id": "240617000123456"
                                    }
                                    }
                                    """;*/
            JsonNode data = objectMapper.readTree(responseBody).path("data");

            Optional<String> orderId = Optional.ofNullable(data.path("order_id").textValue());

            log.info("Zerodha order submitted: clientOrderId={}, brokerOrderId={}",
                    request.clientOrderId(), orderId.orElse(""));
            return new OrderResponse(request.clientOrderId(), orderId, request.instrumentKey(), request.side(),
                    OrderStatus.OPEN, request.quantity(), 0, Optional.empty(), Optional.empty(), Instant.now());
        } catch (Exception ex) {
            log.warn("Zerodha order failed: clientOrderId={}, instrument={}, message={}",
                    request.clientOrderId(), request.instrumentKey(), ex.getMessage());
            throw new BrokerException("Failed to place Zerodha order", ex);
        }
    }

    @Override
    public Optional<OrderResponse> orderStatus(String brokerOrderId) {
        log.debug("Zerodha order status requested: brokerOrderId={}", brokerOrderId);
        return orders().stream()
                .filter(order -> order.brokerOrderId().filter(brokerOrderId::equals).isPresent())
                .findFirst();
    }

    @Override
    public List<OrderResponse> orders() {
        try {
            log.debug("Zerodha orders requested");
            String body = retryWithBackoff("orders", () -> restClient.get()
                    .uri("/orders")
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class));
            JsonNode data = objectMapper.readTree(body).path("data");
            List<OrderResponse> orders = parseOrders(data);
            log.debug("Zerodha orders completed: count={}", orders.size());
            return orders;
        } catch (Exception ex) {
            log.warn("Zerodha orders failed: {}", ex.getMessage());
            throw new BrokerException("Failed to retrieve Zerodha orders", ex);
        }
    }

    @Override
    public List<Position> positions() {
        try {
            log.debug("Zerodha positions requested");
            String body = retryWithBackoff("positions", () -> restClient.get()
                    .uri("/portfolio/positions")
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class));
            JsonNode net = objectMapper.readTree(body).path("data").path("net");
            List<Position> positions = parsePositions(net);
            log.debug("Zerodha positions completed: count={}", positions.size());
            return positions;
        } catch (Exception ex) {
            log.warn("Zerodha positions failed: {}", ex.getMessage());
            throw new BrokerException("Failed to retrieve Zerodha positions", ex);
        }
    }

    @Override
    public void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener) {
        log.warn("Zerodha market data subscription requested but WebSocket streaming is not implemented: count={}",
                instrumentKeys == null ? 0 : instrumentKeys.size());
        throw new BrokerException("Zerodha WebSocket streaming will be implemented in a later phase");
    }

    private void applyAuthHeaders(HttpHeaders headers) {
        requireAuth();
        log.debug("Applying Zerodha auth headers");
        headers.set("X-Kite-Version", "3");
        headers.set(HttpHeaders.AUTHORIZATION,
                "token " + properties.broker().apiKey() + ":" + tokenStore.accessToken().orElseThrow());
    }

    private void requireAuth() {
        if (!hasText(properties.broker().apiKey()) || tokenStore.accessToken().isEmpty()) {
            log.warn("Zerodha auth missing: apiKeyConfigured={}, accessTokenPresent={}",
                    hasText(properties.broker().apiKey()), tokenStore.accessToken().isPresent());
            throw new BrokerException("Zerodha api-key and access-token are required for live broker calls. "
                    + "Set KITE_ACCESS_TOKEN or call /auth/kite/session and complete the Kite login callback first.");
        }
    }

    private <T> T retryWithBackoff(String operationName, Callable<T> operation) {
        int retries = properties.safety().brokerRetryCount();
        long backoffMs = properties.safety().brokerRetryBackoff().toMillis();
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return operation.call();
            } catch (Exception ex) {
                if (!(ex instanceof RestClientException)) {
                    throw new BrokerException("Zerodha operation failed: " + operationName, ex);
                }
                if (attempt >= retries) {
                    throw (RestClientException) ex;
                }
                long sleepMs = backoffMs * (1L << attempt);
                log.warn("Zerodha operation failed; retrying: operation={}, attempt={}, maxRetries={}, backoffMs={}, message={}",
                        operationName, attempt + 1, retries, sleepMs, ex.getMessage());
                sleep(sleepMs);
            }
        }
        throw new IllegalStateException("Retry loop exited unexpectedly for operation: " + operationName);
    }

    private void sleep(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerException("Interrupted during Zerodha retry backoff", ex);
        }
    }

    private Quote quoteFromJson(String instrumentKey, JsonNode node) {
        return new Quote(instrumentKey, Instant.now(), decimal(node, "last_price"), node.path("volume").asLong(0),
                node.path("oi").asLong(0), Optional.ofNullable(nullableDecimal(node, "implied_volatility")),
                Optional.empty(), Optional.empty());
    }

    private List<Candle> parseCandles(String instrumentKey, Timeframe timeframe, JsonNode candles) {
        return java.util.stream.StreamSupport.stream(candles.spliterator(), false)
                .map(row -> new Candle(instrumentKey, parseKiteInstant(row.get(0).asText()), timeframe,
                        rowDecimal(row, 1), rowDecimal(row, 2), rowDecimal(row, 3), rowDecimal(row, 4),
                        row.get(5).asLong(), row.size() > 6 ? row.get(6).asLong() : 0L))
                .toList();
    }

    private List<OrderResponse> parseOrders(JsonNode data) {
        return java.util.stream.StreamSupport.stream(data.spliterator(), false)
                .map(node -> new OrderResponse(
                        node.path("tag").asText(node.path("order_id").asText()),
                        Optional.ofNullable(node.path("order_id").textValue()),
                        node.path("exchange").asText() + ":" + node.path("tradingsymbol").asText(),
                        parseSide(node.path("transaction_type").asText()),
                        parseStatus(node.path("status").asText()),
                        node.path("quantity").asInt(),
                        node.path("filled_quantity").asInt(0),
                        Optional.ofNullable(nullableDecimal(node, "average_price")),
                        Optional.ofNullable(node.path("status_message").textValue()),
                        Instant.now()))
                .toList();
    }

    private List<Position> parsePositions(JsonNode net) {
        return java.util.stream.StreamSupport.stream(net.spliterator(), false)
                .map(node -> new Position(
                        node.path("exchange").asText() + ":" + node.path("tradingsymbol").asText(),
                        node.path("quantity").asInt(),
                        decimal(node, "average_price"),
                        decimal(node, "last_price"),
                        decimal(node, "pnl")))
                .toList();
    }

    private String tokenFromInstrumentKey(String instrumentKey) {
        if (instrumentKey.chars().allMatch(Character::isDigit)) {
            return instrumentKey;
        }
        throw new BrokerException("Zerodha historical candles require an instrument token as instrumentKey");
    }

    private String[] splitInstrumentKey(String instrumentKey) {
        String[] parts = instrumentKey.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new BrokerException("Expected instrument key in EXCHANGE:TRADINGSYMBOL format");
        }
        return parts;
    }

    private String kiteInterval(Timeframe timeframe) {
        return switch (timeframe) {
            case ONE_MINUTE -> "minute";
            case FIVE_MINUTE -> "5minute";
            case FIFTEEN_MINUTE -> "15minute";
        };
    }

    private OrderSide parseSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? OrderSide.SELL : OrderSide.BUY;
    }

    private OrderStatus parseStatus(String status) {
        return switch (status.toUpperCase()) {
            case "COMPLETE" -> OrderStatus.COMPLETE;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "OPEN", "TRIGGER PENDING" -> OrderStatus.OPEN;
            default -> OrderStatus.NEW;
        };
    }

    private BigDecimal rowDecimal(JsonNode row, int index) {
        return new BigDecimal(row.get(index).asText());
    }

    private Instant parseKiteInstant(String value) {
        return OffsetDateTime.parse(value, KITE_TIMESTAMP_FORMAT).toInstant();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = nullableDecimal(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nullableDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
