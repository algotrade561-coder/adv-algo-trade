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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Optional — for per-user circuit breaker reset on manual orders. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.broker.zerodha.ZerodhaBrokerClient zerodhaBrokerClient;

    /** Optional — P0-7: resolve the SOFT halt banner PER-USER so one user's daily-loss halt does not
     *  show (or appear active) for other users. HARD halt stays global (system-wide). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.UserTradingStateManager userTradingStateManager;

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
    public Map<String, Object> tradingStatus(@RequestParam(value = "userId", required = false) Long userId) {
        // Scope to the requested user (or current logged-in user if not specified).
        // Multi-user: each user sees their own P&L/trades; superuser can view others via ?userId=
        Long currentUserId = userId != null ? userId : com.algo.trade.multiuser.UserContext.getUserId();
        var pnl = reportingService.pnl(currentUserId);
        var allOpenTrades = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> isOwnedByCurrentUser(t, currentUserId))
                .toList();
        // Exclude MANUAL/broker-synced (SYNC-) positions from the algo open count + block-reason panel — a
        // manual trade must not count toward the algo "max open trades" gate (matches the entry gate's filter).
        int openLiveTrades = (int) allOpenTrades.stream().filter(t -> !t.isPaperTrade())
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .count();
        int openPaperTrades = (int) allOpenTrades.stream().filter(t -> t.isPaperTrade()).count();
        // Include pending orders in the open count (user-scoped)
        int pendingOrders = (int) orderRepository.findByStatusIn(
                java.util.List.of(com.algo.trade.domain.OrderStatus.OPEN, com.algo.trade.domain.OrderStatus.NEW))
                .stream().filter(o -> isOrderOwnedByCurrentUser(o, currentUserId)).count();

        java.time.Instant todayStart = java.time.LocalDate.now(tradingProperties.timezone())
                .atStartOfDay(tradingProperties.timezone()).toInstant();
        java.time.Instant todayEnd = java.time.LocalDate.now(tradingProperties.timezone())
                .plusDays(1).atStartOfDay(tradingProperties.timezone()).toInstant();
        var todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, todayEnd).stream()
                .filter(t -> isOwnedByCurrentUser(t, currentUserId))
                // Exclude MANUAL/broker-synced (SYNC-) trades from the ALGO metrics (trades/day, consec losses,
                // win-rate) and the block panel — they must not count toward the algo "max trades per day" gate
                // (the entry gate's tradesToday()/consecutiveLosses() already exclude them). Manual P&L still
                // shows via the broker-authoritative pnl(). Honors the manage-synced override.
                .filter(t -> t.isPaperTrade()
                        || globalConfigService.isManageSyncedTrades()
                        || t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                .toList();

        int liveTradesToday = (int) todayTrades.stream()
                .filter(t -> !t.isPaperTrade())
                .count();
        int paperTradesToday = (int) todayTrades.stream()
                .filter(t -> t.isPaperTrade())
                .count();

        // Consecutive losses (exclude paper trades) — use today's trades only
        var closedTodayLive = todayTrades.stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .toList();
        int consecutiveLosses = 0;
        for (var t : closedTodayLive) {
            if (t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0) consecutiveLosses++;
            else break;
        }
        // Real today win-rate from actual closed ALGO trades. The in-memory tradingStateService.rollingWinRate()
        // resets to 0 on every restart and returns 100% when empty — it falsely showed 100%. (Still based on
        // the bot's per-trade P&L, which is approximate; the TOTAL P&L is broker-authoritative.)
        int winsTodayLive = (int) closedTodayLive.stream()
                .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl().signum() > 0).count();
        double liveWinRate = closedTodayLive.isEmpty() ? 0.0
                : (double) winsTodayLive / closedTodayLive.size() * 100.0;

        // Daily-loss gate must use the BOT-ONLY P&L (SYNC-/manual excluded), matching the real entry gate in
        // ExecutionEngine.dailyPnl(). pnl.realizedPnl() is the broker-authoritative WHOLE-ACCOUNT day P&L and
        // includes manual Kite orders — using it here falsely tripped "Max daily loss reached" off manual losses.
        BigDecimal botDailyPnl = executionEngine.dailyPnl();
        java.util.List<String> blockingReasons = new java.util.ArrayList<>(
                tradingStateService.entryBlockReasons(
                        botDailyPnl, openLiveTrades + pendingOrders, liveTradesToday, consecutiveLosses,
                        webSocketClient.isConnected(),
                        globalConfigService.getMaxOpenTrades(),
                        globalConfigService.getMaxTradesPerDay(),
                        globalConfigService.getMaxConsecutiveLosses(),
                        riskEngine.effectiveDailyLossLimit()));

        // Layer 4 — MarketGuard
        // 4 Jun 2026: allowEventDay=true matches the OIM + AlgoTradeExecution
        // entry gates — pre-event day is no longer a hard block for long-buy
        // intraday MIS entries. eventDay is still exposed separately if the UI
        // wants to render an advisory.
        String marketBlock = marketGuard.longPremiumBlockReason(true);
        if (marketBlock != null) blockingReasons.add(marketBlock);

        // Config-level kill switch (trading.safety.kill-switch-enabled) halts the gate even when the RUNTIME
        // switch is off — surface it so the dashboard truthfully shows the bot is halted (not "Entries Allowed").
        if (tradingProperties.safety() != null && tradingProperties.safety().killSwitchEnabled()) {
            blockingReasons.add("Kill switch enabled (config) — trading halted");
        }

        // Layer 6 — Rolling win-rate auto-pause
        double rollingWinRate = liveWinRate;            // real today win-rate (not the in-memory reset bug)
        int rollingTotal = closedTodayLive.size();      // today's closed algo trades
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
                .filter(t -> t.isPaperTrade() && t.getStatus() == TradeStatus.CLOSED)
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
            if (t.getStatus() == TradeStatus.CLOSED
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> start() {
        tradingStateService.start();
        telegramAlertService.tradingStateChanged("Scanner started", status());
        return status();
    }

    @PostMapping("/stop")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> stop() {
        tradingStateService.stop();
        telegramAlertService.tradingStateChanged("Scanner stopped", status());
        return status();
    }

    @PostMapping("/kill-switch")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> softHalt(@RequestBody HaltRequest request) {
        String reason = request.reason() != null ? request.reason() : "Manual soft halt";
        tradingStateService.softHalt(reason);
        telegramAlertService.tradingStateChanged("Soft halt: " + reason, status());
        return status();
    }

    @PostMapping("/halt/hard")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> hardHalt(@RequestBody HaltRequest request) {
        String reason = request.reason() != null ? request.reason() : "Emergency stop — all activity halted";
        tradingStateService.hardHalt(reason);
        telegramAlertService.tradingStateChanged("🚨 EMERGENCY STOP: " + reason, status());
        return status();
    }

    @PostMapping("/halt/resume")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> resumeFromHalt() {
        tradingStateService.resumeFromHalt();
        executionEngine.resetRejectionCounter();
        telegramAlertService.tradingStateChanged("Halt cleared — trading resumed", status());
        return status();
    }

    // ── Rolling win-rate auto-pause reset ─────────────────────────────────────

    /**
     * Clear the rolling win-rate counters so a win-rate auto-pause can be lifted from the UI without a restart
     * or waiting for the midnight reset. Mirrors {@code /halt/resume} (ADMIN/SUPERUSER only). Pass ?userId= to
     * reset a specific user (admin action); omit it to reset the calling user's own counters.
     *
     * UI note: wire a "Reset win-rate pause" button on the trading-control panel to
     *   POST /advalgotrade/winrate-pause/reset            (self)  or
     *   POST /advalgotrade/winrate-pause/reset?userId=1   (a specific user).
     */
    @PostMapping("/winrate-pause/reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> resetWinRatePause(@RequestParam(value = "userId", required = false) Long userId) {
        Long target = userId != null ? userId : com.algo.trade.multiuser.UserContext.getUserId();
        tradingStateService.resetRollingWinRate(target);
        telegramAlertService.tradingStateChanged("Rolling win-rate counter reset for userId=" + target, status());
        Map<String, Object> resp = new LinkedHashMap<>(status());
        resp.put("message", "Rolling win-rate counter reset for userId=" + target);
        return resp;
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> reconnectWebSocket() {
        webSocketClient.disconnect();
        webSocketClient.connect();
        return status();
    }

    @PostMapping("/websocket/disconnect")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public Map<String, Object> disconnectWebSocket() {
        webSocketClient.disconnect();
        return status();
    }

    // ── Manual order ──────────────────────────────────────────────────────────

    @PostMapping("/orders/place")
    public ResponseEntity<Map<String, Object>> placeManualOrder(
            @RequestBody ManualOrderRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        // P4 #48: Caller attribution — tag with IP or authenticated user
        String caller = httpRequest.getRemoteAddr();
        if (httpRequest.getUserPrincipal() != null) {
            caller = httpRequest.getUserPrincipal().getName();
        }
        String effectiveTag = request.tag() != null ? request.tag() + "@" + caller : "manual-ui@" + caller;

        log.info("Manual order requested: instrument={}, side={}, type={}, product={}, qty={}, limit={}, tag={}, caller={}",
                request.instrumentKey(), request.side(), request.orderType(), request.productType(),
                request.quantity(), request.limitPrice(), effectiveTag, caller);

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
            // Auto-reset per-user circuit breaker for manual orders — the user is
            // explicitly trying, indicating their session is likely fixed.
            Long currentUserId = com.algo.trade.multiuser.UserContext.getUserId();
            if (zerodhaBrokerClient != null) {
                zerodhaBrokerClient.resetCircuitBreakerForUser(currentUserId);
            }

            var orderRequest = new com.algo.trade.domain.OrderRequest(
                    "MANUAL-" + java.util.UUID.randomUUID(),
                    request.instrumentKey(),
                    com.algo.trade.domain.OrderSide.valueOf(request.side()),
                    com.algo.trade.domain.OrderType.valueOf(request.orderType()),
                    com.algo.trade.domain.ProductType.valueOf(request.productType()),
                    request.quantity(),
                    request.limitPrice() != null ? java.util.Optional.of(request.limitPrice()) : java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    com.algo.trade.domain.OrderVariety.fromString(request.variety()),
                    effectiveTag
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

    /** P0-7: the halt that applies to the CURRENT user. HARD halt is global (system-wide); a secondary
     *  user's SOFT halt is their own; the primary/default user uses the global TradingStateService. */
    private com.algo.trade.risk.HaltMode effectiveHaltModeForCurrentUser() {
        com.algo.trade.risk.HaltMode global = tradingStateService.haltMode();
        if (global == com.algo.trade.risk.HaltMode.HARD) return global;
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (userTradingStateManager != null && uid != null
                    && !uid.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                com.algo.trade.risk.HaltMode h = userTradingStateManager.getState(uid).getHaltMode();
                return h != null ? h : com.algo.trade.risk.HaltMode.NONE;
            }
        } catch (Exception ex) {
            log.debug("[P0-7] per-user halt resolution failed in status(), using global: {}", ex.getMessage());
        }
        return global;
    }

    private Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", tradingStateService.running());
        map.put("killSwitch", tradingStateService.killSwitchEnabled());
        map.put("haltMode", effectiveHaltModeForCurrentUser());
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
            String productType, int quantity, BigDecimal limitPrice, String variety, String tag) {}

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

    /** Check if a trade belongs to the current user (or is unowned / default user). */
    private boolean isOwnedByCurrentUser(com.algo.trade.persistence.TradeEntity trade, Long currentUserId) {
        // Per-user scoping that MATCHES the entry gate's sameUser logic: treat a null owner / null current
        // user as the DEFAULT (single-user) id, then require an exact match. Previously the primary
        // (== DEFAULT_USER_ID) short-circuited to "see everything", which made the entries-blocked panel
        // AGGREGATE every user's open trades / loss streak against ONE user's limit — the cross-user "2/2"
        // and "consec losses (2)" display on 2026-06-29 (1 loss each for u=1 + u=8). The gate is per-user,
        // so the dashboard must be too. Single-user mode is unaffected (everything maps to DEFAULT).
        Long owner = trade.getUserId() != null ? trade.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        Long cu = currentUserId != null ? currentUserId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        return owner.equals(cu);
    }

    /** Check if an order belongs to the current user (per-user, matches the gate's sameUser logic). */
    private boolean isOrderOwnedByCurrentUser(com.algo.trade.persistence.OrderEntity order, Long currentUserId) {
        Long owner = order.getUserId() != null ? order.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        Long cu = currentUserId != null ? currentUserId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        return owner.equals(cu);
    }
}
