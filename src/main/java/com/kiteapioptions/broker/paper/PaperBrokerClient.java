package com.kiteapioptions.broker.paper;

import com.kiteapioptions.broker.BrokerClient;
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
import com.kiteapioptions.marketdata.MockMarketDataGenerator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory paper broker with simulated market fills and virtual positions.
 */
public class PaperBrokerClient implements BrokerClient {

    private static final MathContext MONEY_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(PaperBrokerClient.class);

    private final TradingProperties properties;
    private final MockMarketDataGenerator marketDataGenerator;
    private final Map<String, OrderResponse> ordersByClientId = new ConcurrentHashMap<>();
    private final Map<String, PositionState> positions = new ConcurrentHashMap<>();

    public PaperBrokerClient(TradingProperties properties, MockMarketDataGenerator marketDataGenerator) {
        this.properties = properties;
        this.marketDataGenerator = marketDataGenerator;
    }

    @Override
    public BrokerSession session() {
        log.debug("Paper broker session requested");
        return new BrokerSession(BrokerName.MOCK, "paper", true, Instant.now(), null);
    }

    @Override
    public List<Instrument> downloadInstruments() {
        log.info("Paper broker instrument download requested");
        List<Instrument> instruments = marketDataGenerator.optionInstruments(LocalDate.now().plusDays(7));
        log.info("Paper broker instrument download completed: instrumentCount={}", instruments.size());
        return instruments;
    }

    @Override
    public Optional<Quote> quote(String instrumentKey) {
        log.debug("Paper quote requested: instrumentKey={}", instrumentKey);
        return Optional.of(marketDataGenerator.quote(instrumentKey));
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        log.debug("Paper quotes requested: count={}", instrumentKeys == null ? 0 : instrumentKeys.size());
        Map<String, Quote> quotes = instrumentKeys.stream().distinct()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(key -> key, marketDataGenerator::quote));
        log.debug("Paper quotes completed: returnedCount={}", quotes.size());
        return quotes;
    }

    @Override
    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        log.info("Paper historical candles requested: instrumentKey={}, timeframe={}, from={}, to={}",
                request.instrumentKey(), request.timeframe(), request.from(), request.to());
        long candleCount = Math.max(1, request.timeframe().duration().toSeconds() == 0
                ? 1
                : (request.to().getEpochSecond() - request.from().getEpochSecond()) / request.timeframe().duration().toSeconds());
        int boundedCount = (int) Math.min(candleCount, 2_000);
        List<Candle> candles = marketDataGenerator.candles(request.instrumentKey(), request.from(), request.timeframe(), boundedCount);
        log.info("Paper historical candles generated: instrumentKey={}, count={}", request.instrumentKey(), candles.size());
        return candles;
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("Paper order requested: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}",
                request.clientOrderId(), request.instrumentKey(), request.side(), request.orderType(),
                request.productType(), request.quantity());
        if (ordersByClientId.containsKey(request.clientOrderId())) {
            log.warn("Paper order rejected as duplicate: clientOrderId={}", request.clientOrderId());
            return new OrderResponse(request.clientOrderId(), Optional.empty(), request.instrumentKey(), request.side(),
                    OrderStatus.REJECTED, request.quantity(), 0, Optional.empty(),
                    Optional.of("Duplicate paper order clientOrderId"), Instant.now());
        }

        Quote quote = quote(request.instrumentKey()).orElseThrow();
        BigDecimal fillPrice = applySlippage(quote.lastPrice(), request.side());
        PositionState updatedPosition = positions.compute(request.instrumentKey(),
                (key, current) -> PositionState.apply(current, request.side(), request.quantity(), fillPrice));

        OrderResponse response = new OrderResponse(request.clientOrderId(), Optional.of("PAPER-" + request.clientOrderId()),
                request.instrumentKey(), request.side(), OrderStatus.COMPLETE, request.quantity(), request.quantity(),
                Optional.of(fillPrice), Optional.empty(), Instant.now());
        ordersByClientId.put(request.clientOrderId(), response);
        if (updatedPosition.quantity == 0) {
            positions.remove(request.instrumentKey());
        }
        log.info("Paper order filled: clientOrderId={}, instrument={}, fillPrice={}, filledQuantity={}, resultingPositionQuantity={}",
                request.clientOrderId(), request.instrumentKey(), fillPrice, request.quantity(), updatedPosition.quantity);
        return response;
    }

    @Override
    public Optional<OrderResponse> orderStatus(String brokerOrderId) {
        log.debug("Paper order status requested: brokerOrderId={}", brokerOrderId);
        return ordersByClientId.values().stream()
                .filter(order -> order.brokerOrderId().filter(brokerOrderId::equals).isPresent())
                .findFirst();
    }

    @Override
    public List<OrderResponse> orders() {
        log.debug("Paper orders requested: count={}", ordersByClientId.size());
        return List.copyOf(ordersByClientId.values());
    }

    @Override
    public List<Position> positions() {
        log.debug("Paper positions requested: count={}", positions.size());
        return positions.entrySet().stream()
                .map(entry -> entry.getValue().toPosition(entry.getKey(), marketDataGenerator.quote(entry.getKey()).lastPrice()))
                .toList();
    }

    @Override
    public void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener) {
        log.info("Paper market data subscription requested: count={}", instrumentKeys == null ? 0 : instrumentKeys.size());
        quotes(instrumentKeys).values().forEach(listener::onQuote);
    }

    private BigDecimal applySlippage(BigDecimal price, OrderSide side) {
        BigDecimal slippageFactor = properties.paper().slippagePercent()
                .divide(BigDecimal.valueOf(100), MONEY_CONTEXT);
        BigDecimal adjustment = price.multiply(slippageFactor, MONEY_CONTEXT);
        return side == OrderSide.BUY ? price.add(adjustment) : price.subtract(adjustment).max(BigDecimal.ONE);
    }

    private record PositionState(int quantity, BigDecimal averagePrice) {

        static PositionState apply(PositionState current, OrderSide side, int orderQuantity, BigDecimal fillPrice) {
            if (current == null) {
                return side == OrderSide.BUY
                        ? new PositionState(orderQuantity, fillPrice)
                        : new PositionState(-orderQuantity, fillPrice);
            }

            int signedQuantity = side == OrderSide.BUY ? orderQuantity : -orderQuantity;
            int newQuantity = current.quantity + signedQuantity;
            if (newQuantity == 0) {
                return new PositionState(0, BigDecimal.ZERO);
            }
            if (Integer.signum(current.quantity) != Integer.signum(signedQuantity)) {
                return new PositionState(newQuantity, current.averagePrice);
            }

            BigDecimal currentValue = current.averagePrice.multiply(BigDecimal.valueOf(Math.abs(current.quantity)));
            BigDecimal newValue = fillPrice.multiply(BigDecimal.valueOf(orderQuantity));
            BigDecimal average = currentValue.add(newValue)
                    .divide(BigDecimal.valueOf(Math.abs(newQuantity)), MONEY_CONTEXT);
            return new PositionState(newQuantity, average);
        }

        Position toPosition(String instrumentKey, BigDecimal lastPrice) {
            BigDecimal pnl = lastPrice.subtract(averagePrice).multiply(BigDecimal.valueOf(quantity), MONEY_CONTEXT);
            return new Position(instrumentKey, quantity, averagePrice, lastPrice, pnl);
        }
    }
}
