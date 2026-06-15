package com.algo.trade.controller;

import com.algo.trade.domain.*;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.indicator.VolumeDeltaTracker;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.*;
import com.algo.trade.reporting.PerformanceMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics REST API — provides OI heatmap, Greeks dashboard, execution timeline,
 * audit export, and bot activity data for the UI.
 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final PerformanceMetricsService performanceMetricsService;
    private final VolumeDeltaTracker volumeDeltaTracker;
    private final TradingStateService tradingStateService;

    public AnalyticsController(LiveInstrumentCache liveInstrumentCache,
                                ExpiryCalendar expiryCalendar,
                                TradeRepository tradeRepository,
                                OrderRepository orderRepository,
                                StrategyDecisionRepository decisionRepository,
                                PerformanceMetricsService performanceMetricsService,
                                VolumeDeltaTracker volumeDeltaTracker,
                                TradingStateService tradingStateService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
        this.performanceMetricsService = performanceMetricsService;
        this.volumeDeltaTracker = volumeDeltaTracker;
        this.tradingStateService = tradingStateService;
    }

    // ── OI Heatmap ────────────────────────────────────────────────────────

    /**
     * OI heatmap data — strike-level OI, volume, IV for CE and PE.
     * GET /analytics/heatmap/oi/{index}?strikes=15
     */
    @GetMapping("/heatmap/oi/{index}")
    public Map<String, Object> oiHeatmap(@PathVariable String index,
                                          @RequestParam(defaultValue = "15") int strikes) {
        IndexType idx = IndexType.valueOf(index.toUpperCase());
        LocalDate expiry = expiryCalendar.getCurrentExpiry(idx);
        double spot = liveInstrumentCache.getFuturesPrice(idx);
        int atm = idx.roundToATM(spot);

        List<Map<String, Object>> levels = new ArrayList<>();
        int interval = idx.strikeInterval();
        for (int i = -strikes; i <= strikes; i++) {
            int strike = atm + (i * interval);
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("strike", strike);
            level.put("distanceFromATM", i);

            liveInstrumentCache.getOption(idx, strike, "CE", expiry).ifPresent(ce -> {
                level.put("ceOI", ce.getOpenInterest());
                level.put("ceOIChange", ce.getOiChange());
                level.put("ceVolume", ce.getVolume());
                level.put("ceIV", ce.getImpliedVolatility());
                level.put("ceLastPrice", ce.getLastPrice());
                level.put("ceDelta", ce.getDelta());
            });
            liveInstrumentCache.getOption(idx, strike, "PE", expiry).ifPresent(pe -> {
                level.put("peOI", pe.getOpenInterest());
                level.put("peOIChange", pe.getOiChange());
                level.put("peVolume", pe.getVolume());
                level.put("peIV", pe.getImpliedVolatility());
                level.put("peLastPrice", pe.getLastPrice());
                level.put("peDelta", pe.getDelta());
            });
            levels.add(level);
        }

        return Map.of(
                "index", index,
                "spot", spot,
                "atm", atm,
                "expiry", expiry.toString(),
                "levels", levels,
                "updatedAt", Instant.now().toString()
        );
    }

    // ── Greeks Dashboard ──────────────────────────────────────────────────

    /**
     * Greeks for open positions — scoped to the current user.
     * SUPERUSER/ADMIN see all; regular users see only their own.
     * GET /analytics/greeks
     */
    @GetMapping("/greeks")
    public List<Map<String, Object>> greeksDashboard() {
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);

        // Per-user filtering: regular users only see their own positions
        java.util.function.Predicate<TradeEntity> ownerFilter = trade -> {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                return true; // No auth (local dev) — show all
            }
            boolean isPrivileged = auth.getAuthorities().stream().anyMatch(a ->
                    "ROLE_SUPERUSER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
            if (isPrivileged) return true;
            Long currentUserId = com.algo.trade.multiuser.UserContext.getUserId();
            Long tradeOwner = trade.getUserId() != null ? trade.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            return currentUserId.equals(tradeOwner);
        };

        List<Map<String, Object>> result = new ArrayList<>();

        for (TradeEntity trade : openTrades) {
            if (!ownerFilter.test(trade)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tradeId", trade.getTradeId());
            row.put("instrumentKey", trade.getInstrumentKey());
            row.put("underlying", trade.getUnderlying());
            row.put("optionType", trade.getOptionType());
            row.put("entryPrice", trade.getEntryPrice());
            row.put("quantity", trade.getQuantity());
            row.put("strategyType", trade.getStrategyType());

            // Live Greeks from cache
            String symbol = extractSymbol(trade.getInstrumentKey());
            if (symbol != null) {
                liveInstrumentCache.getBySymbol(symbol).ifPresent(opt -> {
                    row.put("lastPrice", opt.getLastPrice());
                    row.put("delta", opt.getDelta());
                    row.put("gamma", opt.getGamma());
                    row.put("theta", opt.getTheta());
                    row.put("vega", opt.getVega());
                    row.put("iv", opt.getImpliedVolatility());
                    row.put("volume", opt.getVolume());
                    row.put("oi", opt.getOpenInterest());
                });
            }

            // Entry Greeks (stored at entry time)
            row.put("entryDelta", trade.getEntryDelta());
            row.put("entryTheta", trade.getEntryTheta());
            row.put("entryIV", trade.getEntryIV());
            row.put("entryGamma", trade.getEntryGamma());

            result.add(row);
        }
        return result;
    }

    // ── Execution Timeline ────────────────────────────────────────────────

    /**
     * Execution timeline — chronological list of orders and trades today.
     * GET /analytics/timeline?period=TODAY
     */
    @GetMapping("/timeline")
    public Map<String, Object> executionTimeline(@RequestParam(defaultValue = "TODAY") String period) {
        Instant[] range = periodToRange(period);
        Instant from = range[0];
        Instant to = range[1];

        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(from, to);
        List<Map<String, Object>> events = new ArrayList<>();

        for (TradeEntity trade : trades) {
            // Entry event
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "ENTRY");
            entry.put("time", trade.getEntryTime() != null ? trade.getEntryTime().toString() : "");
            entry.put("tradeId", trade.getTradeId());
            entry.put("instrument", trade.getInstrumentKey());
            entry.put("underlying", trade.getUnderlying());
            entry.put("optionType", trade.getOptionType());
            entry.put("strategyType", trade.getStrategyType());
            entry.put("price", trade.getEntryPrice());
            entry.put("quantity", trade.getQuantity());
            entry.put("reason", trade.getEntryReason());
            entry.put("paper", trade.isPaperTrade());
            events.add(entry);

            // Exit event (if closed)
            if (trade.getStatus() == TradeStatus.CLOSED && trade.getExitTime() != null) {
                Map<String, Object> exit = new LinkedHashMap<>();
                exit.put("type", "EXIT");
                exit.put("time", trade.getExitTime().toString());
                exit.put("tradeId", trade.getTradeId());
                exit.put("instrument", trade.getInstrumentKey());
                exit.put("price", trade.getExitPrice());
                exit.put("pnl", trade.getRealizedPnl());
                exit.put("reason", trade.getExitReason());
                exit.put("holdMinutes", trade.getEntryTime() != null
                        ? java.time.Duration.between(trade.getEntryTime(), trade.getExitTime()).toMinutes() : 0);
                events.add(exit);
            }
        }

        // Sort by time
        events.sort(Comparator.comparing(e -> String.valueOf(e.get("time"))));

        return Map.of(
                "period", period,
                "events", events,
                "totalEntries", trades.size(),
                "totalExits", trades.stream().filter(t -> t.getStatus() == TradeStatus.CLOSED).count()
        );
    }

    // ── Audit Export ──────────────────────────────────────────────────────

    /**
     * Export trade audit data as JSON.
     * GET /analytics/audit?period=TODAY
     */
    @GetMapping("/audit")
    public Map<String, Object> auditExport(@RequestParam(defaultValue = "TODAY") String period) {
        Instant[] range = periodToRange(period);
        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(range[0], range[1]);

        List<Map<String, Object>> tradeData = trades.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeId", t.getTradeId());
            m.put("status", t.getStatus());
            m.put("instrumentKey", t.getInstrumentKey());
            m.put("underlying", t.getUnderlying());
            m.put("optionType", t.getOptionType());
            m.put("strategyType", t.getStrategyType());
            m.put("entryPrice", t.getEntryPrice());
            m.put("exitPrice", t.getExitPrice());
            m.put("quantity", t.getQuantity());
            m.put("realizedPnl", t.getRealizedPnl());
            m.put("entryTime", t.getEntryTime());
            m.put("exitTime", t.getExitTime());
            m.put("entryReason", t.getEntryReason());
            m.put("exitReason", t.getExitReason());
            m.put("peakPrice", t.getPeakPrice());
            m.put("trailingStopPrice", t.getTrailingStopPrice());
            m.put("bookedPnl", t.getBookedPnl());
            m.put("partialExitLayers", t.getPartialExitLayers());
            m.put("paper", t.isPaperTrade());
            m.put("entryDelta", t.getEntryDelta());
            m.put("entryTheta", t.getEntryTheta());
            m.put("entryIV", t.getEntryIV());
            return m;
        }).toList();

        var perfSnapshot = performanceMetricsService.getSnapshot();

        return Map.of(
                "period", period,
                "exportedAt", Instant.now().toString(),
                "trades", tradeData,
                "performance", perfSnapshot,
                "volumeDelta", Map.of(
                        "NIFTY", volumeDeltaTracker.getSnapshot(IndexType.NIFTY),
                        "BANKNIFTY", volumeDeltaTracker.getSnapshot(IndexType.BANKNIFTY)
                )
        );
    }

    // ── Bot Activity ──────────────────────────────────────────────────────

    /**
     * Bot activity summary — uptime, trade counts, scan counts.
     * GET /analytics/bot-activity
     */
    @GetMapping("/bot-activity")
    public Map<String, Object> botActivity() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, Instant.now());

        long liveTrades = todayTrades.stream().filter(t -> !t.isPaperTrade()).count();
        long paperTrades = todayTrades.stream().filter(TradeEntity::isPaperTrade).count();
        long syncTrades = todayTrades.stream().filter(t -> t.getTradeId().startsWith("SYNC-")).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", tradingStateService.running());
        result.put("schedulerEnabled", tradingStateService.schedulerEnabled());
        result.put("lastScanAt", tradingStateService.lastScanAt() != null ? tradingStateService.lastScanAt().toString() : null);
        result.put("liveTradesToday", liveTrades);
        result.put("paperTradesToday", paperTrades);
        result.put("syncTradesToday", syncTrades);
        result.put("totalTradesToday", todayTrades.size());
        result.put("tradesInLastHour", tradingStateService.tradesInLastHour());
        result.put("rollingWinRate", tradingStateService.rollingWinRate());
        result.put("haltMode", tradingStateService.haltMode());
        result.put("killSwitch", tradingStateService.killSwitchEnabled());
        result.put("dailyApproved", tradingStateService.isDailyApproved());

        // Per-strategy trade counts today
        Map<String, Long> strategyCount = todayTrades.stream()
                .filter(t -> t.getStrategyType() != null)
                .collect(Collectors.groupingBy(TradeEntity::getStrategyType, Collectors.counting()));
        result.put("strategyBreakdown", strategyCount);

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String extractSymbol(String instrumentKey) {
        if (instrumentKey == null || !instrumentKey.contains(":")) return null;
        return instrumentKey.split(":", 2)[1];
    }

    private Instant[] periodToRange(String period) {
        LocalDate today = LocalDate.now(IST);
        return switch (period != null ? period.toUpperCase() : "TODAY") {
            case "YESTERDAY" -> new Instant[]{
                    today.minusDays(1).atStartOfDay(IST).toInstant(),
                    today.atStartOfDay(IST).toInstant()
            };
            case "LAST7" -> new Instant[]{
                    today.minusDays(6).atStartOfDay(IST).toInstant(),
                    Instant.now()
            };
            case "LAST30" -> new Instant[]{
                    today.minusDays(29).atStartOfDay(IST).toInstant(),
                    Instant.now()
            };
            default -> new Instant[]{ // TODAY
                    today.atStartOfDay(IST).toInstant(),
                    Instant.now()
            };
        };
    }
}
