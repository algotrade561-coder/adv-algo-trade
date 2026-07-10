package com.algo.trade.controller;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Manual Order Placement API — provides strike data prepopulated with
 * current spot ± 3 strikes for all indices, and accepts order placement requests.
 */
@RestController
@RequestMapping("/api/manual-order")
public class ManualOrderController {

    private static final Logger log = LoggerFactory.getLogger(ManualOrderController.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final BrokerClient brokerClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.AppUserRepository userRepository;

    public ManualOrderController(LiveInstrumentCache liveInstrumentCache,
                                  ExpiryCalendar expiryCalendar,
                                  BrokerClient brokerClient) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.brokerClient = brokerClient;
    }

    /**
     * GET /api/manual-order/strikes
     * Returns current spot price + ATM ± 3 strikes for all active indices.
     * Prepopulated data for the manual order UI.
     */
    @GetMapping("/strikes")
    public Map<String, Object> getStrikes() {
        Map<String, Object> result = new LinkedHashMap<>();

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            if (spot <= 0) continue;

            int atm = idx.roundToATM(spot);
            int interval = idx.strikeInterval();
            LocalDate expiry = expiryCalendar.getCurrentExpiry(idx);
            boolean isExpiryDay = expiryCalendar.isExpiryDay(idx);

            // Build ±3 strikes around ATM
            List<Map<String, Object>> strikes = new ArrayList<>();
            for (int offset = -3; offset <= 3; offset++) {
                int strike = atm + (offset * interval);
                Map<String, Object> strikeData = new LinkedHashMap<>();
                strikeData.put("strike", strike);
                strikeData.put("offset", offset);
                strikeData.put("isATM", offset == 0);
                strikeData.put("label", offset == 0 ? "ATM" : (offset > 0 ? "OTM+" + offset : "ITM" + offset));

                // CE quote
                Optional<OptionInstrument> ceOpt = liveInstrumentCache.getOption(idx, strike, "CE", expiry);
                ceOpt.ifPresent(ce -> {
                    strikeData.put("ceLtp", ce.getLastPrice());
                    strikeData.put("ceOi", ce.getOpenInterest());
                    strikeData.put("ceIv", ce.getImpliedVolatility());
                    strikeData.put("ceBid", ce.getBestBid());
                    strikeData.put("ceAsk", ce.getBestAsk());
                    strikeData.put("ceToken", ce.getInstrumentToken());
                    strikeData.put("ceSymbol", ce.getTradingSymbol());
                });

                // PE quote
                Optional<OptionInstrument> peOpt = liveInstrumentCache.getOption(idx, strike, "PE", expiry);
                peOpt.ifPresent(pe -> {
                    strikeData.put("peLtp", pe.getLastPrice());
                    strikeData.put("peOi", pe.getOpenInterest());
                    strikeData.put("peIv", pe.getImpliedVolatility());
                    strikeData.put("peBid", pe.getBestBid());
                    strikeData.put("peAsk", pe.getBestAsk());
                    strikeData.put("peToken", pe.getInstrumentToken());
                    strikeData.put("peSymbol", pe.getTradingSymbol());
                });

                strikes.add(strikeData);
            }

            Map<String, Object> indexData = new LinkedHashMap<>();
            indexData.put("spot", spot);
            indexData.put("atm", atm);
            indexData.put("interval", interval);
            indexData.put("expiry", expiry.toString());
            indexData.put("isExpiryDay", isExpiryDay);
            indexData.put("lotSize", idx.lotSize());
            indexData.put("exchange", idx.exchange());
            indexData.put("strikes", strikes);

            result.put(idx.name(), indexData);
        }

        return result;
    }

    /**
     * POST /api/manual-order/place
     * Place a manual order — explicitly pinned to the LOGGED-IN user's broker session.
     *
     * <p>UserContextFilter normally sets the user context for HTTP threads, but manual
     * orders must NEVER fall back to the primary account's credentials if the context
     * is missing (that would route a secondary user's order into the primary user's
     * Zerodha account). We therefore resolve the user from the Authentication and wrap
     * the placement in {@code UserContext.runAs(userId, ...)}; combined with the
     * fail-fast in ZerodhaBrokerClient.applyAuthHeaders, the order either uses THIS
     * user's token or fails with an actionable "Kite login required" message.</p>
     */
    @PostMapping("/place")
    public Map<String, Object> placeOrder(@RequestBody ManualOrderRequest request,
                                           org.springframework.security.core.Authentication auth) {
        Long userId = resolveUserId(auth);
        final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.algo.trade.multiuser.UserContext.runAs(userId, () -> result.set(placeOrderInternal(request)));
        return result.get();
    }

    /** Resolve the acting user: explicit context first, then the Spring Security principal. */
    private Long resolveUserId(org.springframework.security.core.Authentication auth) {
        if (com.algo.trade.multiuser.UserContext.isSet()) {
            return com.algo.trade.multiuser.UserContext.getUserId();
        }
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof org.springframework.security.oauth2.core.user.OAuth2User oAuth2User) {
            String email = oAuth2User.getAttribute("email");
            if (email != null && userRepository != null) {
                return userRepository.findByEmail(email.toLowerCase())
                        .map(com.algo.trade.auth.AppUser::getId)
                        .orElse(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID);
            }
        }
        return com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
    }

    private Map<String, Object> placeOrderInternal(ManualOrderRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            IndexType idx = IndexType.fromName(request.index);
            int lotSize = idx.lotSize();
            int quantity = request.lots * lotSize;

            OrderSide side = "BUY".equalsIgnoreCase(request.side) ? OrderSide.BUY : OrderSide.SELL;
            OrderType orderType = "LIMIT".equalsIgnoreCase(request.orderType)
                    ? OrderType.LIMIT : OrderType.MARKET;
            ProductType productType = "NRML".equalsIgnoreCase(request.productType)
                    ? ProductType.NRML : ProductType.MIS;
            OrderVariety variety = OrderVariety.fromString(request.variety);

            String clientOrderId = "MANUAL-" + System.currentTimeMillis();
            Optional<java.math.BigDecimal> limitPrice = orderType == OrderType.LIMIT && request.limitPrice > 0
                    ? Optional.of(java.math.BigDecimal.valueOf(request.limitPrice))
                    : Optional.empty();

            OrderRequest orderRequest = new OrderRequest(
                    clientOrderId, request.instrumentKey,
                    side, orderType, productType, quantity, limitPrice,
                    Optional.empty(), variety, "manual-order");

            OrderResponse result = brokerClient.placeOrder(orderRequest);

            response.put("success", true);
            response.put("orderId", result.brokerOrderId().orElse(clientOrderId));
            response.put("status", result.status().name());
            response.put("quantity", quantity);
            response.put("instrument", request.tradingSymbol);
            response.put("side", side.name());
            response.put("variety", variety.name());

            log.info("[ManualOrder] Placed for userId={}: {} {} {} x{} @ {} variety={} → {}",
                    com.algo.trade.multiuser.UserContext.getUserId(),
                    side, request.tradingSymbol, request.index, quantity,
                    request.limitPrice > 0 ? "₹" + request.limitPrice : "MARKET",
                    variety, result.status());

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            log.error("[ManualOrder] Failed: {}", e.getMessage());
        }

        return response;
    }

    // ── Request DTO ───────────────────────────────────────────────────────

    public static class ManualOrderRequest {
        public String index;             // NIFTY, BANKNIFTY, SENSEX
        public String side;              // BUY, SELL
        public String optionType;        // CE, PE
        public int strike;
        public int lots;                 // number of lots
        public String orderType;         // MARKET, LIMIT
        public double limitPrice;        // 0 for MARKET
        public String productType;       // MIS, NRML
        public String variety;           // REGULAR, AMO (null defaults to REGULAR)
        public String instrumentKey;     // e.g. "NFO:NIFTY26JUN24000CE"
        public String tradingSymbol;     // e.g. "NIFTY26JUN24000CE"
    }
}
