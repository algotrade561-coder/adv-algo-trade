package com.algo.trade.broker;

import com.algo.trade.domain.BrokerSession;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.Position;
import com.algo.trade.domain.Quote;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.execution.TradingStateService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoutingBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingBrokerClient.class);
    private static final MathContext MONEY_CONTEXT = MathContext.DECIMAL64;

    private final TradingProperties properties;
    private final BrokerClient zerodhaClient;
    private final BrokerClient paperClient;
    private final TradingStateService tradingStateService;
    private final Map<String, OrderResponse> paperOrdersByClientId = new ConcurrentHashMap<>();
    private final Map<String, PositionState> paperPositions = new ConcurrentHashMap<>();

    public RoutingBrokerClient(
            TradingProperties properties,
            BrokerClient zerodhaClient,
            BrokerClient paperClient,
            TradingStateService tradingStateService
    ) {
        this.properties = properties;
        this.zerodhaClient = zerodhaClient;
        this.paperClient = paperClient;
        this.tradingStateService = tradingStateService;
    }

    @Override
    public BrokerSession session() {
        return tradingStateService.executionMode() == ExecutionMode.ZERODHA
                ? zerodhaClient.session()
                : paperClient.session();
    }

    @Override
    public List<Instrument> downloadInstruments() {
        return marketDataClient().downloadInstruments();
    }

    @Override
    public Optional<Quote> quote(String instrumentKey) {
        return marketDataClient().quote(instrumentKey);
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        return marketDataClient().quotes(instrumentKeys);
    }

    @Override
    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        return marketDataClient().historicalCandles(request);
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("Routing order placement: executionMode={}, instrument={}, side={}, quantity={}",
                tradingStateService.executionMode(), request.instrumentKey(), request.side(), request.quantity());
        if (tradingStateService.executionMode() == ExecutionMode.ZERODHA) {
            return zerodhaClient.placeOrder(request);
        }
        return placePaperOrder(request);
    }

    @Override
    public Optional<OrderResponse> orderStatus(String brokerOrderId) {
        if (tradingStateService.executionMode() == ExecutionMode.ZERODHA) {
            return zerodhaClient.orderStatus(brokerOrderId);
        }
        return paperOrdersByClientId.values().stream()
                .filter(order -> order.brokerOrderId().filter(brokerOrderId::equals).isPresent())
                .findFirst();
    }

    @Override
    public List<OrderResponse> orders() {
        if (tradingStateService.executionMode() == ExecutionMode.ZERODHA) {
            return zerodhaClient.orders();
        }
        return List.copyOf(paperOrdersByClientId.values());
    }

    @Override
    public List<Position> positions() {
        if (tradingStateService.executionMode() == ExecutionMode.ZERODHA) {
            return zerodhaClient.positions();
        }
        return paperPositions.entrySet().stream()
                .map(entry -> entry.getValue().toPosition(entry.getKey(), lastPrice(entry.getKey())))
                .toList();
    }

    @Override
    public void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener) {
        marketDataClient().subscribeMarketData(instrumentKeys, listener);
    }

    private BrokerClient marketDataClient() {
        MarketDataMode mode = tradingStateService.marketDataMode();
        return mode == MarketDataMode.ZERODHA ? zerodhaClient : paperClient;
    }

    private OrderResponse placePaperOrder(OrderRequest request) {
        if (paperOrdersByClientId.containsKey(request.clientOrderId())) {
            log.warn("Routed paper order rejected as duplicate: clientOrderId={}", request.clientOrderId());
            return new OrderResponse(request.clientOrderId(), Optional.empty(), request.instrumentKey(), request.side(),
                    OrderStatus.REJECTED, request.quantity(), 0, Optional.empty(),
                    Optional.of("Duplicate paper order clientOrderId"), Instant.now());
        }

        BigDecimal fillPrice = applySlippage(lastPrice(request.instrumentKey()), request.side());
        PositionState updatedPosition = paperPositions.compute(request.instrumentKey(),
                (key, current) -> PositionState.apply(current, request.side(), request.quantity(), fillPrice));
        OrderResponse response = new OrderResponse(request.clientOrderId(), Optional.of("PAPER-" + request.clientOrderId()),
                request.instrumentKey(), request.side(), OrderStatus.COMPLETE, request.quantity(), request.quantity(),
                Optional.of(fillPrice), Optional.empty(), Instant.now());
        paperOrdersByClientId.put(request.clientOrderId(), response);
        if (updatedPosition.quantity() == 0) {
            paperPositions.remove(request.instrumentKey());
        }
        log.info("Routed paper order filled using marketDataMode={}: clientOrderId={}, instrument={}, fillPrice={}, quantity={}, resultingPositionQuantity={}",
                tradingStateService.marketDataMode(), request.clientOrderId(), request.instrumentKey(), fillPrice,
                request.quantity(), updatedPosition.quantity());
        return response;
    }

    private BigDecimal lastPrice(String instrumentKey) {
        return marketDataClient().quote(instrumentKey)
                .map(Quote::lastPrice)
                .orElseThrow(() -> new BrokerException("No market data quote available for paper fill: " + instrumentKey));
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
