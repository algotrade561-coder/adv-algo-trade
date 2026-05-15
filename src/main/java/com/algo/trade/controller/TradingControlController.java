package com.algo.trade.controller;

import com.algo.trade.config.ConfigDocumentation;
import com.algo.trade.config.ConfigDocumentation.ConfigResponse;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.broker.BrokerClient;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.reporting.ReportingService;
import com.algo.trade.risk.MarketGuard;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradingControlController {

    private static final Logger log = LoggerFactory.getLogger(TradingControlController.class);

    private final TradingProperties tradingProperties;
    private final TradingStateService tradingStateService;
    private final TelegramAlertService telegramAlertService;
    private final InstrumentCache instrumentCache;
    private final KiteAccessTokenStore tokenStore;
    private final KiteWebSocketClient webSocketClient;
    private final BrokerClient brokerClient;
    private final TradeRepository tradeRepository;
    private final ReportingService reportingService;
    private final MarketGuard marketGuard;
    private final com.algo.trade.config.GlobalConfigService globalConfigService;
    private final com.algo.trade.risk.RiskEngine riskEngine;
    private final com.algo.trade.persistence.OrderRepository orderRepository;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final com.algo.trade.execution.ExecutionEngine executionEngine;
    private final com.algo.trade.persistence.PositionGroupRepository positionGroupRepository;

    public TradingControlController(
            TradingProperties tradingProperties,
            TradingStateService tradingStateService,
            TelegramAlertService telegramAlertService,
            InstrumentCache instrumentCache,
            KiteAccessTokenStore tokenStore,
            KiteWebSocketClient webSocketClient,
            BrokerClient brokerClient,
            TradeRepository tradeRepository,
            ReportingService reportingService,
            MarketGuard marketGuard,
            com.algo.trade.config.GlobalConfigService globalConfigService,
            com.algo.trade.risk.RiskEngine riskEngine,
            com.algo.trade.persistence.OrderRepository orderRepository,
            com.algo.trade.marketdata.MarketDataService marketDataService,
            com.algo.trade.execution.ExecutionEngine executionEngine,
            com.algo.trade.persistence.PositionGroupRepository positionGroupRepository
    ) {
        this.tradingProperties = tradingProperties;
        this.tradingStateService = tradingStateService;
        this.telegramAlertService = telegramAlertService;
        this.instrumentCache = instrumentCache;
        this.tokenStore = tokenStore;
        this.webSocketClient = webSocketClient;
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.reportingService = reportingService;
        this.marketGuard = marketGuard;
        this.globalConfigService = globalConfigService;
        this.riskEngine = riskEngine;
        this.orderRepository = orderRepository;
        this.marketDataService = marketDataService;
        this.executionEngine = executionEngine;
        this.positionGroupRepository = positionGroupRepository;
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return ConfigDocumentation.from(tradingProperties, status());
    }

    /**
     * Returns all current entry blocking reasons from every layer:
     * scanner state, halt/approval, risk limits, and market guard.
     * Empty blockingReasons = all clear.
     */
    @GetMapping("/trading/status")
    public Map<String, Object> tradingStatus() {
        var pnl = reportingService.pnl();
        var allOpenTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        int openLiveTrades = (int) allOpenTrades.stream().filter(t -> !t.isPaperTrade()).count();
        int openPaperTrades = (int) allOpenTrades.stream().filter(t -> t.isPaperTrade()).count();
        // Include pending orders in the open count
        int pendingOrders = orderRepository.findByStatusIn(
                java.util.List.of(com.algo.trade.domain.OrderStatus.OPEN, com.algo.trade.domain.OrderStatus.NEW)).size();

        java.time.Instant todayStart = java.time.LocalDate.now(tradingProperties.timezone())
                .atStartOfDay(tradingProperties.timezone()).toInstant();
        java.time.Instant todayEnd = java.time.LocalDate.now(tradingProperties.timezone())
                .plusDays(1).atStartOfDay(tradingProperties.timezone()).toInstant();
        var todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, todayEnd);

        int liveTradesToday = (int) todayTrades.stream()
                .filter(t -> !t.isPaperTrade())
                .count();
        int paperTradesToday = (int) todayTrades.stream()
                .filter(t -> t.isPaperTrade())
                .count();

        // Consecutive losses (exclude paper trades) — use today's trades only
        var closedTodayLive = todayTrades.stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.CLOSED)
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .toList();
        int consecutiveLosses = 0;
        for (var t : closedTodayLive) {
            if (t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0) consecutiveLosses++;
            else break;
        }

        java.util.List<String> blockingReasons = new java.util.ArrayList<>(
                tradingStateService.entryBlockReasons(
                        pnl.realizedPnl(), openLiveTrades + pendingOrders, liveTradesToday, consecutiveLosses,
                        webSocketClient.isConnected(),
                        globalConfigService.getMaxOpenTrades(),
                        globalConfigService.getMaxTradesPerDay(),
                        globalConfigService.getMaxConsecutiveLosses(),
                        riskEngine.effectiveDailyLossLimit()));

        // Layer 4 — MarketGuard
        String marketBlock = marketGuard.longPremiumBlockReason();
        if (marketBlock != null) blockingReasons.add(marketBlock);

        // Layer 6 — Rolling win-rate auto-pause
        double rollingWinRate = tradingStateService.rollingWinRate();
        int rollingTotal = liveTradesToday; // approximate — uses today's trade count
        if (rollingTotal >= 5 && rollingWinRate < 25.0) {
            blockingReasons.add(String.format("Rolling win rate too low (%.0f%% on %d trades) — auto-paused",
                    rollingWinRate, rollingTotal));
        }

        // Layer 7 — Entry time window
        java.time.LocalTime marketTime = java.time.LocalTime.now(tradingProperties.timezone());
        java.time.LocalTime entryStart = globalConfigService.getEntryStartTime();
        java.time.LocalTime entryCutoff = globalConfigService.getForcedExitTime();
        if (marketTime.isBefore(entryStart)) {
            blockingReasons.add("Before entry window (opens at " + entryStart + ")");
        } else if (marketTime.isAfter(entryCutoff)) {
            blockingReasons.add("After entry cutoff (" + entryCutoff + ")");
        }

        // Layer 8 — Global exit override indicator (not a block, but useful context)
        boolean globalExitOverride = globalConfigService.isGlobalExitOverride();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryAllowed", blockingReasons.isEmpty());
        result.put("blockingReasons", blockingReasons);
        result.put("openTrades", openLiveTrades + pendingOrders);
        result.put("openPaperTrades", openPaperTrades);
        result.put("pendingOrders", pendingOrders);
        result.put("tradesToday", liveTradesToday);
        result.put("paperTradesToday", paperTradesToday);
        result.put("consecutiveLosses", consecutiveLosses);
        result.put("dailyPnl", pnl.realizedPnl());
        result.put("tradesThisHour", tradingStateService.tradesInLastHour());
        result.put("rollingWinRate", rollingWinRate);
        result.put("entryWindowOpen", !marketTime.isBefore(entryStart) && !marketTime.isAfter(entryCutoff));
        result.put("globalExitOverride", globalExitOverride);
        // Paper P&L — today's closed + unrealized from open
        BigDecimal paperClosedPnl = todayTrades.stream()
                .filter(t -> t.isPaperTrade() && t.getStatus() == com.algo.trade.domain.TradeStatus.CLOSED)
                .map(t -> t.getRealizedPnl())
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paperUnrealizedPnl = allOpenTrades.stream()
                .filter(t -> t.isPaperTrade())
                .map(t -> {
                    var quote = marketDataService.quote(t.getInstrumentKey());
                    if (quote.isEmpty() || quote.get().lastPrice().signum() <= 0) return BigDecimal.ZERO;
                    boolean isShort = isShortTrade(t);
                    BigDecimal unrealizedPerUnit = isShort
                            ? t.getEntryPrice().subtract(quote.get().lastPrice())
                            : quote.get().lastPrice().subtract(t.getEntryPrice());
                    return unrealizedPerUnit.multiply(BigDecimal.valueOf(t.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.put("paperPnl", paperClosedPnl.add(paperUnrealizedPnl));
        result.put("paperClosedPnl", paperClosedPnl);
        result.put("paperUnrealizedPnl", paperUnrealizedPnl);

        // Add spread strategy paper P&L (from PositionGroupEntity — separate tracking)
        BigDecimal spreadPaperPnl = positionGroupRepository.findAll().stream()
                .filter(pg -> !pg.isOpen()) // closed spread positions
                .filter(pg -> pg.getExitTime() != null
                        && pg.getExitTime().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()
                                .equals(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
                .map(pg -> pg.getPnl() != null ? pg.getPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Combine single-leg paper + spread paper
        BigDecimal totalPaperPnl = paperClosedPnl.add(paperUnrealizedPnl).add(spreadPaperPnl);
        result.put("paperPnl", totalPaperPnl);
        result.put("spreadPaperPnl", spreadPaperPnl);

        // Estimated trading charges (Zerodha F&O Options fee structure)
        BigDecimal liveCharges = BigDecimal.ZERO;
        BigDecimal paperCharges = BigDecimal.ZERO;
        for (var t : todayTrades) {
            if (t.getStatus() == com.algo.trade.domain.TradeStatus.CLOSED
                    && t.getEntryPrice() != null && t.getExitPrice() != null && t.getQuantity() > 0) {
                BigDecimal charges = com.algo.trade.util.ZerodhaChargesCalculator.estimateCharges(
                        t.getEntryPrice(), t.getExitPrice(), t.getQuantity());
                if (t.isPaperTrade()) paperCharges = paperCharges.add(charges);
                else liveCharges = liveCharges.add(charges);
            }
        }
        // Also estimate charges for open trades (using entry price as proxy for exit)
        for (var t : allOpenTrades) {
            if (t.getEntryPrice() != null && t.getQuantity() > 0) {
                BigDecimal charges = com.algo.trade.util.ZerodhaChargesCalculator.estimateCharges(
                        t.getEntryPrice(), t.getEntryPrice(), t.getQuantity());
                if (t.isPaperTrade()) paperCharges = paperCharges.add(charges);
                else liveCharges = liveCharges.add(charges);
            }
        }
        result.put("liveEstimatedCharges", liveCharges);
        result.put("paperEstimatedCharges", paperCharges);
        result.put("liveNetPnl", pnl.realizedPnl().subtract(liveCharges));
        result.put("paperNetPnl", paperClosedPnl.add(paperUnrealizedPnl).subtract(paperCharges));
        // Signal counts today
        long entrySignals = reportingService.countEntrySignalsSince(todayStart);
        long rejectedSignals = reportingService.countRejectedSignalsSince(todayStart);
        result.put("entrySignals", entrySignals);
        result.put("rejectedSignals", rejectedSignals);
        result.put("totalEvaluations", tradingStateService.getTotalEvaluations());
        result.put("totalBlocked", tradingStateService.getTotalBlocked());
        result.put("lastScanAt", tradingStateService.lastScanAt());
        result.put("effectiveDailyLossLimit", tradingStateService.dailyLossExtension() > 0
                ? globalConfigService.getTotalCapital().doubleValue()
                    * globalConfigService.getMaxDailyLossPercent().doubleValue() / 100.0
                    + tradingStateService.dailyLossExtension()
                : globalConfigService.getTotalCapital().doubleValue()
                    * globalConfigService.getMaxDailyLossPercent().doubleValue() / 100.0);
        return result;
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> mode(@RequestBody ModeRequest request) {
        if (request.mode() == TradingMode.LIVE && !tradingProperties.liveTradingEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "LIVE mode requires trading.live-trading-enabled=true"
            ));
        }
        tradingStateService.setRequestedMode(request.mode());
        telegramAlertService.tradingStateChanged("Mode changed to " + request.mode(), status());
        return ResponseEntity.ok(status());
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        tradingStateService.start();
        telegramAlertService.tradingStateChanged("Scanner started", status());
        return status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        tradingStateService.stop();
        telegramAlertService.tradingStateChanged("Scanner stopped", status());
        return status();
    }

    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody KillSwitchRequest request) {
        if (request.enabled()) {
            tradingStateService.enableKillSwitch();
        } else {
            tradingStateService.clearKillSwitch();
        }
        telegramAlertService.tradingStateChanged("Kill switch set to " + request.enabled(), status());
        return status();
    }

    // ── Halt mode ─────────────────────────────────────────────────────────────

    @PostMapping("/halt/soft")
    public Map<String, Object> softHalt(@RequestBody HaltRequest request) {
        String reason = request.reason() != null ? request.reason() : "Manual soft halt";
        tradingStateService.softHalt(reason);
        telegramAlertService.tradingStateChanged("Soft halt: " + reason, status());
        return status();
    }

    @PostMapping("/halt/hard")
    public Map<String, Object> hardHalt(@RequestBody HaltRequest request) {
        String reason = request.reason() != null ? request.reason() : "Emergency stop — all activity halted";
        tradingStateService.hardHalt(reason);
        telegramAlertService.tradingStateChanged("🚨 EMERGENCY STOP: " + reason, status());
        return status();
    }

    @PostMapping("/halt/resume")
    public Map<String, Object> resumeFromHalt() {
        tradingStateService.resumeFromHalt();
        executionEngine.resetRejectionCounter();
        telegramAlertService.tradingStateChanged("Halt cleared — trading resumed", status());
        return status();
    }

    // ── Daily approval gate ───────────────────────────────────────────────────

    @PostMapping("/daily/approve")
    public Map<String, Object> approveToday() {
        tradingStateService.approveToday();
        telegramAlertService.tradingStateChanged("Daily trading approved", status());
        return status();
    }

    @PostMapping("/daily/revoke")
    public Map<String, Object> revokeApproval() {
        tradingStateService.revokeApproval();
        telegramAlertService.tradingStateChanged("Daily approval revoked", status());
        return status();
    }

    // ── Daily loss limit extension ────────────────────────────────────────────

    @PostMapping("/daily/extend-limit")
    public Map<String, Object> extendDailyLimit() {
        String msg = tradingStateService.extendDailyLimit();
        telegramAlertService.tradingStateChanged(msg, status());
        Map<String, Object> resp = new LinkedHashMap<>(status());
        resp.put("message", msg);
        return resp;
    }

    // ── Scan underlyings ──────────────────────────────────────────────────────

    @GetMapping("/scan/underlyings")
    public Map<String, Object> scanUnderlyings() {
        return status();
    }

    @PostMapping("/scan/underlyings/{underlying}")
    public Map<String, Object> scanUnderlying(
            @PathVariable UnderlyingSymbol underlying,
            @RequestBody ScanUnderlyingRequest request
    ) {
        tradingStateService.setUnderlyingScanEnabled(underlying, request.enabled());
        telegramAlertService.tradingStateChanged("Scan toggle " + underlying + "=" + request.enabled(), status());
        return status();
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @GetMapping("/routing")
    public Map<String, Object> routing() {
        return status();
    }

    @PostMapping("/routing")
    public ResponseEntity<Map<String, Object>> routing(@RequestBody RoutingRequest request) {
        if (request.executionMode() == ExecutionMode.ZERODHA && !tradingProperties.liveTradingEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "ZERODHA execution requires trading.live-trading-enabled=true"
            ));
        }
        if (request.marketDataMode() == MarketDataMode.ZERODHA && !tokenStore.authenticated()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "ZERODHA market data requires a Kite access token. Call /auth/kite/session first."
            ));
        }
        tradingStateService.setRoutingModes(request.marketDataMode(), request.executionMode());
        if (request.marketDataMode() != null) instrumentCache.refresh();
        telegramAlertService.tradingStateChanged("Routing changed", status());
        return ResponseEntity.ok(status());
    }

    // ── Scheduler toggle ──────────────────────────────────────────────────────

    @PostMapping("/scheduler")
    public Map<String, Object> toggleScheduler(@RequestBody SchedulerRequest request) {
        tradingStateService.setSchedulerEnabled(request.enabled());
        telegramAlertService.tradingStateChanged("REST poll scheduler " + (request.enabled() ? "enabled" : "disabled"), status());
        return status();
    }

    // ── WebSocket control ─────────────────────────────────────────────────────

    @PostMapping("/websocket/reconnect")
    public Map<String, Object> reconnectWebSocket() {
        webSocketClient.disconnect();
        webSocketClient.connect();
        return status();
    }

    @PostMapping("/websocket/disconnect")
    public Map<String, Object> disconnectWebSocket() {
        webSocketClient.disconnect();
        return status();
    }

    // ── Manual order ──────────────────────────────────────────────────────────

    @PostMapping("/orders/place")
    public ResponseEntity<Map<String, Object>> placeManualOrder(@RequestBody ManualOrderRequest request) {
        log.info("Manual order requested: instrument={}, side={}, type={}, product={}, qty={}, limit={}, tag={}",
                request.instrumentKey(), request.side(), request.orderType(), request.productType(),
                request.quantity(), request.limitPrice(), request.tag());

        // ── Safety checks: same gates as strategy orders ──────────────────────
        if (!tradingStateService.running()) {
            log.warn("Manual order rejected: trading engine is stopped");
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "reason", "Trading engine is stopped"));
        }
        if (tradingStateService.killSwitchEnabled()) {
            log.warn("Manual order rejected: kill switch is enabled");
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "reason", "Kill switch is enabled"));
        }
        if (tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.HARD) {
            log.warn("Manual order rejected: hard halt is active");
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "reason", "Hard halt is active"));
        }
        if (!tradingStateService.isDailyApproved()) {
            log.warn("Manual order rejected: daily trading not approved");
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "reason", "Daily trading not approved"));
        }

        try {
            var orderRequest = new com.algo.trade.domain.OrderRequest(
                    "MANUAL-" + java.util.UUID.randomUUID(),
                    request.instrumentKey(),
                    com.algo.trade.domain.OrderSide.valueOf(request.side()),
                    com.algo.trade.domain.OrderType.valueOf(request.orderType()),
                    com.algo.trade.domain.ProductType.valueOf(request.productType()),
                    request.quantity(),
                    request.limitPrice() != null ? java.util.Optional.of(request.limitPrice()) : java.util.Optional.empty(),
                    request.tag() != null ? request.tag() : "manual-ui"
            );
            var response = brokerClient.placeOrder(orderRequest);

            // Reject if broker rejected
            if (response.status() == com.algo.trade.domain.OrderStatus.REJECTED) {
                log.warn("Manual order rejected by broker: {}", response.rejectionReason().orElse("unknown"));
                return ResponseEntity.badRequest().body(Map.of(
                        "accepted", false,
                        "reason", response.rejectionReason().orElse("Broker rejected the order")));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accepted", true);
            body.put("clientOrderId", response.clientOrderId());
            body.put("brokerOrderId", response.brokerOrderId().orElse(null));
            body.put("status", response.status().name());
            body.put("filledQuantity", response.filledQuantity());
            body.put("averageFillPrice", response.averageFillPrice().orElse(null));
            body.put("rejectionReason", response.rejectionReason().orElse(null));
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            log.warn("Manual order failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("accepted", false, "reason", ex.getMessage()));
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", tradingStateService.running());
        map.put("killSwitch", tradingStateService.killSwitchEnabled());
        map.put("haltMode", tradingStateService.haltMode());
        map.put("dailyApproved", tradingStateService.isDailyApproved());
        map.put("extensionsUsedToday", tradingStateService.extensionsUsedToday());
        map.put("dailyLossExtension", tradingStateService.dailyLossExtension());
        map.put("requestedMode", tradingStateService.requestedMode());
        map.put("configuredMode", tradingProperties.mode());
        map.put("marketDataMode", tradingStateService.marketDataMode());
        map.put("executionMode", tradingStateService.executionMode());
        map.put("liveTradingEnabled", tradingProperties.liveTradingEnabled());
        map.put("enabledUnderlyings", tradingStateService.enabledUnderlyings());
        map.put("schedulerEnabled", tradingStateService.schedulerEnabled());
        map.put("webSocketConnected", webSocketClient.isConnected());
        map.put("updatedAt", tradingStateService.updatedAt());
        return map;
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record ModeRequest(TradingMode mode) {}
    public record KillSwitchRequest(boolean enabled) {}
    public record SchedulerRequest(boolean enabled) {}
    public record ScanUnderlyingRequest(boolean enabled) {}
    public record RoutingRequest(MarketDataMode marketDataMode, ExecutionMode executionMode) {}
    public record HaltRequest(String reason) {}
    public record ManualOrderRequest(
            String instrumentKey, String side, String orderType,
            String productType, int quantity, BigDecimal limitPrice, String tag) {}

    /** Determine if a trade is a short entry based on strategy type. */
    private boolean isShortTrade(com.algo.trade.persistence.TradeEntity trade) {
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                return com.algo.trade.strategy.StrategyType.valueOf(trade.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        String reason = trade.getEntryReason();
        return reason != null && (reason.contains("[SELL_CE]") || reason.contains("[SELL_PE]"));
    }
}
