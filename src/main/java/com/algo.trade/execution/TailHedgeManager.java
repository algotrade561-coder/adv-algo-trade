package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors open BUY positions and automatically places cheap far-OTM hedge orders
 * on the opposite side to cap catastrophic losses.
 */
@Component
public class TailHedgeManager {

    private static final Logger log = LoggerFactory.getLogger(TailHedgeManager.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 20);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 20);
    private static final BigDecimal MIN_POSITION_VALUE = new BigDecimal("5000");
    private static final int OTM_STRIKES = 5;
    private static final BigDecimal MAX_HEDGE_COST_PERCENT = new BigDecimal("0.10");
    private static final BigDecimal PRICE_BUFFER = new BigDecimal("1.05");
    private static final BigDecimal TICK_SIZE = new BigDecimal("0.05");

    private final TradeRepository tradeRepository;
    private final BrokerClient brokerClient;
    private final InstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketDataService marketDataService;
    private final TelegramAlertService telegramAlertService;

    /** Tracks which positions have been hedged today (tradeId → true). */
    private final ConcurrentHashMap<String, Boolean> hedgedPositions = new ConcurrentHashMap<>();

    public TailHedgeManager(TradeRepository tradeRepository,
                            BrokerClient brokerClient,
                            InstrumentCache instrumentCache,
                            ExpiryCalendar expiryCalendar,
                            MarketDataService marketDataService,
                            TelegramAlertService telegramAlertService) {
        this.tradeRepository = tradeRepository;
        this.brokerClient = brokerClient;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketDataService = marketDataService;
        this.telegramAlertService = telegramAlertService;
    }

    /**
     * Runs every 60 seconds. Checks open BUY positions and places hedge orders
     * for qualifying positions that haven't been hedged today.
     */
    @Scheduled(fixedDelay = 60_000)
    public void checkAndHedge() {
        // Guard: skip if outside market hours (09:20–15:20 IST)
        LocalTime now = ZonedDateTime.now(IST).toLocalTime();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.debug("TailHedgeManager: outside market hours — skipping");
            return;
        }

        try {
            // Fetch open trades from TradeRepository
            List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);

            for (TradeEntity trade : openTrades) {
                try {
                    processTradeForHedge(trade);
                } catch (Exception ex) {
                    log.debug("TailHedgeManager: error processing trade {}: {}",
                            trade.getTradeId(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.debug("TailHedgeManager: error fetching open trades: {}", ex.getMessage());
        }
    }

    private void processTradeForHedge(TradeEntity trade) {
        // Filter: only BUY positions (qty > 0)
        if (trade.getQuantity() <= 0) return;

        // Filter: value >= ₹5000
        BigDecimal positionValue = trade.getEntryPrice()
                .multiply(BigDecimal.valueOf(trade.getQuantity()), MC);
        if (positionValue.compareTo(MIN_POSITION_VALUE) < 0) return;

        // Filter: not already hedged today
        if (hedgedPositions.containsKey(trade.getTradeId())) return;

        // Determine option type from trade
        String optionTypeStr = trade.getOptionType();
        if (optionTypeStr == null || optionTypeStr.isBlank()) return;

        boolean isCE = "CE".equalsIgnoreCase(optionTypeStr);
        boolean isPE = "PE".equalsIgnoreCase(optionTypeStr);
        if (!isCE && !isPE) return;

        // Hedge type is opposite: CE position → PE hedge, PE position → CE hedge
        OptionType hedgeOptionType = isCE ? OptionType.PE : OptionType.CE;

        // Determine underlying and index type
        String underlyingStr = trade.getUnderlying();
        UnderlyingSymbol underlying;
        IndexType indexType;
        try {
            underlying = UnderlyingSymbol.valueOf(underlyingStr);
            indexType = IndexType.from(underlying);
        } catch (Exception ex) {
            log.debug("TailHedgeManager: unknown underlying {} for trade {}", underlyingStr, trade.getTradeId());
            return;
        }

        // Get current spot price for ATM computation
        String spotKey = underlying == UnderlyingSymbol.NIFTY ? "NSE:NIFTY 50" : "NSE:NIFTY BANK";
        Optional<Quote> spotQuoteOpt = marketDataService.quote(spotKey);
        if (spotQuoteOpt.isEmpty()) {
            log.debug("TailHedgeManager: could not fetch spot quote for {}", spotKey);
            return;
        }
        double spotPrice = spotQuoteOpt.get().lastPrice().doubleValue();
        int currentATM = indexType.roundToATM(spotPrice);
        int strikeInterval = indexType.strikeInterval();

        // Compute hedge strike: 5 strikes OTM from ATM
        int hedgeStrike;
        if (hedgeOptionType == OptionType.PE) {
            // PE hedge (protecting CE position) → lower strikes
            hedgeStrike = currentATM - OTM_STRIKES * strikeInterval;
        } else {
            // CE hedge (protecting PE position) → higher strikes
            hedgeStrike = currentATM + OTM_STRIKES * strikeInterval;
        }

        // Look up hedge instrument
        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
        Optional<Instrument> hedgeInstrumentOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(hedgeStrike), hedgeOptionType);

        if (hedgeInstrumentOpt.isEmpty()) {
            log.debug("TailHedgeManager: hedge instrument not found — {} {} at strike {}",
                    underlying, hedgeOptionType, hedgeStrike);
            return;
        }

        Instrument hedgeInstrument = hedgeInstrumentOpt.get();
        String hedgeKey = hedgeInstrument.instrumentKey();

        // Get hedge option LTP
        Optional<Quote> hedgeQuoteOpt = marketDataService.quote(hedgeKey);
        if (hedgeQuoteOpt.isEmpty()) {
            log.debug("TailHedgeManager: could not fetch quote for hedge instrument {}", hedgeKey);
            return;
        }

        BigDecimal hedgePrice = hedgeQuoteOpt.get().lastPrice();
        BigDecimal mainPrice = trade.getEntryPrice();

        // Skip if hedge price > 10% of main position price
        if (hedgePrice.compareTo(mainPrice.multiply(MAX_HEDGE_COST_PERCENT, MC)) > 0) {
            log.debug("TailHedgeManager: hedge price {} > 10% of main price {} — skipping",
                    hedgePrice, mainPrice);
            return;
        }

        // Place LIMIT BUY at LTP × 1.05, rounded to nearest 0.05 tick
        BigDecimal limitPrice = roundToTick(hedgePrice.multiply(PRICE_BUFFER, MC));
        int lotSize = indexType.lotSize();

        try {
            String clientOrderId = "HEDGE-" + UUID.randomUUID().toString().substring(0, 8);
            OrderRequest orderRequest = new OrderRequest(
                    clientOrderId, hedgeKey, OrderSide.BUY, OrderType.LIMIT,
                    ProductType.MIS, lotSize, Optional.of(limitPrice), "tail-hedge");
            brokerClient.placeOrder(orderRequest);

            // Mark as hedged
            hedgedPositions.put(trade.getTradeId(), Boolean.TRUE);

            // Send Telegram alert
            String alertMessage = "Tail hedge placed"
                    + System.lineSeparator() + "Hedge: " + hedgeInstrument.tradingSymbol()
                    + System.lineSeparator() + "Qty: " + lotSize
                    + System.lineSeparator() + "Price: " + limitPrice
                    + System.lineSeparator() + "Protecting: " + trade.getInstrumentKey();
            telegramAlertService.systemAlert(alertMessage);

            log.info("TailHedgeManager: hedge placed — {} qty={} price={} protecting {}",
                    hedgeKey, lotSize, limitPrice, trade.getInstrumentKey());
        } catch (Exception ex) {
            log.debug("TailHedgeManager: failed to place hedge order for {}: {}",
                    hedgeKey, ex.getMessage());
            // Do NOT mark as hedged — retry next cycle
        }
    }

    /**
     * Round price to nearest 0.05 tick with minimum of 0.05.
     */
    static BigDecimal roundToTick(BigDecimal price) {
        BigDecimal rounded = price.divide(TICK_SIZE, 0, RoundingMode.HALF_UP).multiply(TICK_SIZE);
        return rounded.compareTo(TICK_SIZE) < 0 ? TICK_SIZE : rounded;
    }

    /**
     * Resets the hedged-positions map at midnight each day.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDaily() {
        int count = hedgedPositions.size();
        hedgedPositions.clear();
        log.info("TailHedgeManager: daily reset — cleared {} hedged position entries", count);
    }
}
