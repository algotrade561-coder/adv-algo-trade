package com.algo.trade.controller;

import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.execution.ExecutionResult;
import com.algo.trade.execution.LotSizeValidator;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.multiuser.UserContext;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.reporting.ReportingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual close of an open position from the monitoring "Positions" tab.
 *
 * <p>Any authenticated user can close <b>their own</b> open positions. A SUPERUSER/ADMIN viewing another
 * user (via the monitoring user dropdown → {@code userId}) closes <b>that user's</b> position. EVERY path
 * (bot-trade close, close-preview, direct-broker fallback) resolves its single target owner through the
 * privilege-checked {@link ReportingService#resolveCloseTargetUser} — a regular user can never act on
 * someone else's position no matter what {@code userId} they post.</p>
 *
 * <p>The exit itself goes through {@link ExecutionEngine#closeTrade}, which wraps the broker call in
 * {@code runAsTradeOwner(tradeId, …)} → {@code UserContext.runAs(ownerId)}. So the order is always placed
 * on the <b>owning user's</b> broker account / key, regardless of who clicked Close.</p>
 *
 * <p><b>Close preview</b> ({@code GET /positions/close-preview}) returns the target user's authoritative
 * closable state (live broker qty, bot-trade qty, lot size, max closable) so the UI modal prepopulates
 * from the SERVER, never from a possibly-stale table row.</p>
 *
 * <p><b>Partial close</b>: {@code quantity} in the POST body is optional. Absent → full close (unchanged
 * legacy behavior). Present → validated (positive, lot multiple, ≤ open qty) and consumed FIFO against the
 * owner's bot trades: whole trades close via the engine; a residual smaller than one trade is sold directly
 * on the owner's account and that trade's open quantity is reduced to match the broker.</p>
 */
@RestController
public class PositionCloseController {

    private static final Logger log = LoggerFactory.getLogger(PositionCloseController.class);

    private final ReportingService reportingService;
    private final ExecutionEngine executionEngine;
    private final MarketDataService marketDataService;
    private final com.algo.trade.broker.BrokerClient brokerClient;
    private final LotSizeValidator lotSizeValidator;
    private final TradeRepository tradeRepository;

    /** Optional — email lookup for the preview dialog header. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.AppUserRepository appUserRepository;

    public PositionCloseController(ReportingService reportingService,
                                    ExecutionEngine executionEngine,
                                    MarketDataService marketDataService,
                                    com.algo.trade.broker.BrokerClient brokerClient,
                                    LotSizeValidator lotSizeValidator,
                                    TradeRepository tradeRepository) {
        this.reportingService = reportingService;
        this.executionEngine = executionEngine;
        this.marketDataService = marketDataService;
        this.brokerClient = brokerClient;
        this.lotSizeValidator = lotSizeValidator;
        this.tradeRepository = tradeRepository;
    }

    /** Request body: instrumentKey required; userId honored only for SUPERUSER/ADMIN; quantity optional (absent = full close). */
    public record CloseRequest(String instrumentKey, Long userId, Integer quantity) {}

    // ─────────────────────────────────────── close preview ───────────────────────────────────────

    /**
     * Authoritative "what would be closed" for the modal: the TARGET user's live broker position +
     * bot open trades for this instrument, fetched fresh. The UI must prepopulate from this, never
     * from the rendered table row (which can be stale or mid-user-switch).
     */
    @GetMapping("/positions/close-preview")
    public ResponseEntity<Map<String, Object>> closePreview(
            @RequestParam("instrumentKey") String instrumentKey,
            @RequestParam(value = "userId", required = false) Long userId) {
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "instrumentKey is required"));
        }
        String inst = instrumentKey.trim();
        Long targetUser = reportingService.resolveCloseTargetUser(userId);

        // Live + paper position rows for the TARGET user (privilege-checked inside positions()).
        List<com.algo.trade.domain.Position> posRows;
        try {
            posRows = reportingService.positions(userId).stream()
                    .filter(p -> inst.equals(p.instrumentKey()))
                    .filter(p -> p.quantity() != 0)
                    .toList();
        } catch (Exception e) {
            posRows = List.of();
        }
        int brokerQty = posRows.stream().mapToInt(com.algo.trade.domain.Position::quantity).sum();
        BigDecimal avgPrice = posRows.isEmpty() ? null : posRows.get(0).averagePrice();
        BigDecimal lastPrice = posRows.stream().map(com.algo.trade.domain.Position::lastPrice)
                .filter(p -> p != null && p.signum() > 0).findFirst()
                .orElseGet(() -> currentPrice(inst));
        BigDecimal unrealized = posRows.stream().map(com.algo.trade.domain.Position::unrealizedPnl)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TradeEntity> botTrades = reportingService.closableOpenTrades(inst, userId);
        int botQty = botTrades.stream().mapToInt(TradeEntity::getQuantity).sum();
        boolean anyPaper = botTrades.stream().anyMatch(TradeEntity::isPaperTrade);

        int lotSize = lotSizeValidator.lotSizeFor(inst);
        // Broker is the truth when it has the instrument; bot trades cover paper (broker knows nothing of paper).
        int maxClosable = brokerQty != 0 ? Math.abs(brokerQty) : botQty;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("instrumentKey", inst);
        out.put("ownerUserId", targetUser);
        out.put("ownerEmail", ownerEmail(targetUser));
        out.put("brokerQty", brokerQty);
        out.put("botOpenQty", botQty);
        out.put("botTradeCount", botTrades.size());
        out.put("hasPaperTrades", anyPaper);
        out.put("avgPrice", avgPrice);
        out.put("lastPrice", lastPrice);
        out.put("unrealizedPnl", posRows.isEmpty() ? null : unrealized);
        out.put("lotSize", lotSize);
        out.put("maxClosableQty", maxClosable);
        out.put("maxClosableLots", lotSize > 0 ? maxClosable / lotSize : null);
        out.put("side", brokerQty < 0 ? "BUY" : "SELL"); // order side that flattens
        return ResponseEntity.ok(out);
    }

    private String ownerEmail(Long userId) {
        try {
            if (appUserRepository == null || userId == null) return null;
            return appUserRepository.findById(userId).map(com.algo.trade.auth.AppUser::getEmail).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────── close ───────────────────────────────────────────

    @PostMapping("/positions/close")
    public ResponseEntity<Map<String, Object>> closePosition(@RequestBody CloseRequest req) {
        if (req == null || req.instrumentKey() == null || req.instrumentKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "instrumentKey is required"));
        }
        String instrumentKey = req.instrumentKey().trim();
        Long targetUser = reportingService.resolveCloseTargetUser(req.userId());

        // Role-scoped to a single owner (own, or — for superuser/admin — the selected user).
        List<TradeEntity> targets = reportingService.closableOpenTrades(instrumentKey, req.userId());

        if (req.quantity() != null) {
            return closePartial(instrumentKey, targetUser, targets, req.quantity());
        }

        if (targets.isEmpty()) {
            // Fallback: no bot-managed trade found — try direct broker close for manual positions.
            // Target is privilege-checked above (never the raw client-supplied userId).
            return closeDirectBrokerPosition(instrumentKey, targetUser, null);
        }
        return closeFullBotTrades(instrumentKey, targets);
    }

    /** Legacy full close: every open bot trade on the instrument via the engine (owner-routed). */
    private ResponseEntity<Map<String, Object>> closeFullBotTrades(String instrumentKey, List<TradeEntity> targets) {
        String actor = UserContext.getUserEmail();
        String reason = "MANUAL_CLOSE[" + (actor != null ? actor : "user#" + UserContext.getUserId()) + "]";

        int closed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (TradeEntity trade : targets) {
            Map<String, Object> r = closeOneTrade(trade, instrumentKey, reason, actor);
            if (Boolean.TRUE.equals(r.get("accepted"))) closed++;
            results.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", closed > 0);
        out.put("closed", closed);
        out.put("attempted", targets.size());
        out.put("instrumentKey", instrumentKey);
        out.put("results", results);
        if (closed == 0) out.put("message", "Exit order was not accepted — see results / logs.");
        return ResponseEntity.ok(out);
    }

    /** One engine-routed full-trade close; returns the per-trade result map. */
    private Map<String, Object> closeOneTrade(TradeEntity trade, String instrumentKey, String reason, String actor) {
        String tradeId = trade.getTradeId();
        BigDecimal price = currentPrice(instrumentKey);
        if ((price == null || price.signum() <= 0) && trade.isPaperTrade()) {
            price = trade.getEntryPrice(); // paper close computes P&L from price — never pass null
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tradeId", tradeId);
        r.put("ownerUserId", trade.getUserId());
        r.put("quantity", trade.getQuantity());
        try {
            // closeTrade → runAsTradeOwner(tradeId): exit fires on the OWNER's broker key.
            ExecutionResult res = executionEngine.closeTrade(tradeId, price, reason);
            r.put("accepted", res.accepted());
            r.put("reasons", res.reasons());
            log.warn("[PositionsClose] {} closed tradeId={} owner={} instrument={} accepted={}",
                    actor, tradeId, trade.getUserId(), instrumentKey, res.accepted());
        } catch (Exception ex) {
            r.put("accepted", false);
            r.put("reasons", List.of(ex.getMessage()));
            log.error("[PositionsClose] close failed tradeId={} instrument={}: {}",
                    tradeId, instrumentKey, ex.getMessage(), ex);
        }
        return r;
    }

    /**
     * Partial (or explicitly-sized) close. Validates the requested quantity against the target
     * user's REAL open quantity + lot size, then consumes bot trades FIFO:
     * whole trades → engine close; a residual smaller than one live trade → direct sized exit on
     * the owner's account + {@link TradeEntity#reduceQuantity} so the bot matches the broker.
     */
    private ResponseEntity<Map<String, Object>> closePartial(String instrumentKey, Long targetUser,
                                                             List<TradeEntity> targets, int qty) {
        String actor = UserContext.getUserEmail();
        String reason = "MANUAL_CLOSE[" + (actor != null ? actor : "user#" + UserContext.getUserId()) + "]";

        int lotSize = lotSizeValidator.lotSizeFor(instrumentKey);
        if (qty <= 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "quantity must be positive"));
        }
        if (lotSize > 0 && qty % lotSize != 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "quantity " + qty + " is not a multiple of lot size " + lotSize));
        }

        Integer liveQty = liveBrokerQty(instrumentKey, targetUser);
        int botQty = targets.stream().mapToInt(TradeEntity::getQuantity).sum();
        int maxClosable = liveQty != null && liveQty != 0 ? Math.abs(liveQty) : botQty;
        if (maxClosable <= 0) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "closed", 0,
                    "message", "No open position found for " + instrumentKey + " (userId=" + targetUser + ")"));
        }
        if (qty > maxClosable) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "quantity " + qty + " exceeds open quantity " + maxClosable));
        }

        // Pure manual/broker position (no bot trades): sized direct close.
        if (targets.isEmpty()) {
            return closeDirectBrokerPosition(instrumentKey, targetUser, qty);
        }

        // FIFO across the owner's bot trades (oldest first — matches broker-side FIFO economics).
        List<TradeEntity> fifo = targets.stream()
                .sorted(Comparator.comparing(TradeEntity::getEntryTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int remaining = qty;
        int closedQty = 0;
        boolean aborted = false; // a rejection stops everything — never keep selling past a failure
        List<Map<String, Object>> results = new ArrayList<>();
        for (TradeEntity trade : fifo) {
            if (remaining <= 0) break;
            if (trade.getQuantity() <= remaining) {
                Map<String, Object> r = closeOneTrade(trade, instrumentKey, reason, actor);
                results.add(r);
                if (Boolean.TRUE.equals(r.get("accepted"))) {
                    remaining -= trade.getQuantity();
                    closedQty += trade.getQuantity();
                } else {
                    aborted = true; // don't keep firing exits after a rejection — report honestly
                    break;
                }
            } else {
                // Residual split of a single trade. Paper trades have no broker leg to split — refuse
                // (close them whole) rather than inventing a partial-paper bookkeeping path.
                if (trade.isPaperTrade()) {
                    results.add(Map.of("tradeId", trade.getTradeId(), "accepted", false,
                            "reasons", List.of("Partial close of a PAPER trade is not supported — "
                                    + "closest lot boundary is the whole trade (" + trade.getQuantity() + ")")));
                    aborted = true;
                    break;
                }
                Map<String, Object> r = placeSizedExit(instrumentKey, targetUser, remaining,
                        liveQty != null && liveQty < 0);
                results.add(r);
                if (Boolean.TRUE.equals(r.get("accepted"))) {
                    final int sold = remaining;
                    trade.reduceQuantity(sold);
                    tradeRepository.save(trade);
                    log.warn("[PositionsClose] PARTIAL: {} sold {} of tradeId={} (owner={}) — open qty now {}",
                            actor, sold, trade.getTradeId(), trade.getUserId(), trade.getQuantity());
                    closedQty += sold;
                    remaining = 0;
                } else {
                    aborted = true;
                }
                break; // split is always the terminal step
            }
        }

        // MIXED position: the broker holds MORE than the bot records (a manual add on the same
        // instrument). After all bot trades are consumed, flatten the manual remainder directly on
        // the owner's account — "Close ALL" must mean the broker position, not just the bot's slice.
        if (!aborted && remaining > 0) {
            Map<String, Object> r = placeSizedExit(instrumentKey, targetUser, remaining,
                    liveQty != null && liveQty < 0);
            r.put("note", "manual (non-bot) remainder");
            results.add(r);
            if (Boolean.TRUE.equals(r.get("accepted"))) {
                closedQty += remaining;
                remaining = 0;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", closedQty > 0);
        out.put("closed", closedQty > 0 ? 1 : 0);           // legacy flag consumed by the UI
        out.put("closedQty", closedQty);
        out.put("requestedQty", qty);
        out.put("instrumentKey", instrumentKey);
        out.put("ownerUserId", targetUser);
        out.put("results", results);
        if (closedQty < qty) {
            out.put("message", closedQty == 0
                    ? "Exit order was not accepted — see results / logs."
                    : "Partially done: " + closedQty + "/" + qty + " closed — see results.");
        }
        return ResponseEntity.ok(out);
    }

    /** The target user's live signed broker qty for the instrument, or null when unavailable. */
    private Integer liveBrokerQty(String instrumentKey, Long targetUser) {
        try {
            final java.util.concurrent.atomic.AtomicReference<Integer> qtyRef = new java.util.concurrent.atomic.AtomicReference<>(null);
            UserContext.runAs(targetUser, () -> {
                try {
                    brokerClient.positions().stream()
                            .filter(p -> instrumentKey.equals(p.instrumentKey()))
                            .filter(p -> p.quantity() != 0)
                            .findFirst().ifPresent(p -> qtyRef.set(p.quantity()));
                } catch (Exception e) {
                    log.warn("[PositionsClose] live qty fetch failed for userId={}: {}", targetUser, e.getMessage());
                }
            });
            return qtyRef.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** Place one sized MARKET exit on the target user's account. */
    private Map<String, Object> placeSizedExit(String instrumentKey, Long targetUser, int qty, boolean shortPosition) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", "direct_broker_exit");
        r.put("quantity", qty);
        r.put("ownerUserId", targetUser);
        try {
            com.algo.trade.domain.OrderSide exitSide = shortPosition
                    ? com.algo.trade.domain.OrderSide.BUY
                    : com.algo.trade.domain.OrderSide.SELL;
            final java.util.concurrent.atomic.AtomicReference<com.algo.trade.domain.OrderResponse> orderRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            UserContext.runAs(targetUser, () -> {
                var orderRequest = new com.algo.trade.domain.OrderRequest(
                        "MANUAL-CLOSE-" + System.currentTimeMillis(),
                        instrumentKey, exitSide,
                        com.algo.trade.domain.OrderType.MARKET,
                        com.algo.trade.domain.ProductType.MIS,
                        qty, java.util.Optional.empty(),
                        "manual-close-ui");
                orderRef.set(brokerClient.placeOrder(orderRequest));
            });
            var order = orderRef.get();
            log.warn("[PositionsClose] Sized exit: {} {} qty={} for userId={} by={} → {}",
                    exitSide, instrumentKey, qty, targetUser, UserContext.getUserEmail(),
                    order != null ? order.status() : "null");
            r.put("accepted", true);
            r.put("side", exitSide.name());
            r.put("orderId", order != null ? order.brokerOrderId().orElse("") : "");
        } catch (Exception e) {
            log.error("[PositionsClose] Sized exit failed {} qty={} userId={}: {}",
                    instrumentKey, qty, targetUser, e.getMessage(), e);
            r.put("accepted", false);
            r.put("reasons", List.of(e.getMessage()));
        }
        return r;
    }

    /** Best-effort live LTP; null → engine places a MARKET exit. */
    private BigDecimal currentPrice(String instrumentKey) {
        try {
            return marketDataService.quote(instrumentKey)
                    .map(q -> q.lastPrice())
                    .filter(p -> p != null && p.signum() > 0)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fallback: close a manual/broker position directly (no bot TradeEntity exists).
     * Queries the broker for the live position qty and places a SELL/BUY order to flatten it —
     * or, when {@code qtyOverride} is given, exactly that many (already validated by the caller).
     * {@code targetUser} is ALWAYS the privilege-checked owner from resolveCloseTargetUser.
     */
    private ResponseEntity<Map<String, Object>> closeDirectBrokerPosition(String instrumentKey, Long targetUser,
                                                                          Integer qtyOverride) {
        String actor = UserContext.getUserEmail();
        log.info("[PositionsClose] No bot trade found for {} — attempting direct broker close for userId={} qty={}",
                instrumentKey, targetUser, qtyOverride != null ? qtyOverride : "ALL");
        try {
            Integer liveQty = liveBrokerQty(instrumentKey, targetUser);
            if (liveQty == null || liveQty == 0) {
                return ResponseEntity.status(404).body(Map.of("ok", false, "closed", 0,
                        "message", "No open position found at broker for " + instrumentKey));
            }
            int fullQty = Math.abs(liveQty);
            int qty = qtyOverride != null ? Math.min(qtyOverride, fullQty) : fullQty;

            Map<String, Object> r = placeSizedExit(instrumentKey, targetUser, qty, liveQty < 0);
            if (!Boolean.TRUE.equals(r.get("accepted"))) {
                return ResponseEntity.internalServerError().body(Map.of("ok", false,
                        "message", "Direct close failed: " + r.getOrDefault("reasons", "see logs")));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("closed", 1);
            out.put("closedQty", qty);
            out.put("instrumentKey", instrumentKey);
            out.put("quantity", qty);
            out.put("side", r.get("side"));
            out.put("method", "direct_broker_close");
            out.put("orderId", r.getOrDefault("orderId", ""));
            log.warn("[PositionsClose] Direct broker close: {} qty={}/{} for userId={} by={}",
                    instrumentKey, qty, fullQty, targetUser, actor);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("[PositionsClose] Direct broker close failed for {}: {}", instrumentKey, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "message", "Direct close failed: " + e.getMessage()));
        }
    }
}
