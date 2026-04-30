package com.algo.trade.ml;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks up to 10 "virtual trades" where ML said ENTER but the system said SKIP.
 * Monitors them using the same SL/target/trailing-stop rules as real trades,
 * using live market quotes, and records the outcome.
 *
 * <p>This answers: "If we had listened to the ML model, would those trades have been profitable?"</p>
 */
@Component
public class MlVirtualTradeTracker {

    private static final Logger log = LoggerFactory.getLogger(MlVirtualTradeTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int MAX_OPEN_VIRTUAL_TRADES = 10;

    static final Path OUTPUT_DIR = Path.of("reports/ml-shadow");
    static final Path VIRTUAL_TRADES_CSV = OUTPUT_DIR.resolve("ml-virtual-trades.csv");

    static final String HEADER = String.join(",",
            "virtualTradeId", "entryTime", "underlying", "optionType", "strategyType", "instrumentKey",
            "entryPrice", "currentPrice", "peakPrice",
            "stopLossPercent", "targetPercent", "trailingActivationPercent", "trailingGapPercent",
            "maxHoldMinutes", "squareoffTime",
            "systemScore", "mlScore", "mlProbability",
            "status", "exitTime", "exitPrice", "exitReason",
            "profitPercent", "holdMinutes"
    ) + System.lineSeparator();

    private final MarketDataService marketDataService;
    private final StrategyConfigService strategyConfigService;
    private final GlobalConfigService globalConfigService;

    /** Open virtual trades keyed by virtualTradeId. */
    private final Map<String, VirtualTrade> openTrades = new ConcurrentHashMap<>();

    /** Completed virtual trades (kept in memory for UI, also persisted to CSV). */
    private final List<VirtualTrade> completedTrades = Collections.synchronizedList(new ArrayList<>());

    public MlVirtualTradeTracker(MarketDataService marketDataService,
                                  StrategyConfigService strategyConfigService,
                                  GlobalConfigService globalConfigService) {
        this.marketDataService = marketDataService;
        this.strategyConfigService = strategyConfigService;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Called by MlShadowRecorder when ML says ENTER but system says SKIP.
     * Opens a virtual trade if we have capacity.
     */
    public void openVirtualTrade(StrategyDecision decision, String instrumentKey,
                                  BigDecimal entryPrice, int systemScore, int mlScore,
                                  double mlProbability, String strategyType) {
        if (openTrades.size() >= MAX_OPEN_VIRTUAL_TRADES) {
            log.debug("ML virtual trade skipped — already tracking {} trades", openTrades.size());
            return;
        }
        boolean alreadyOpen = openTrades.values().stream()
                .anyMatch(t -> t.instrumentKey().equals(instrumentKey));
        if (alreadyOpen) {
            log.debug("ML virtual trade skipped — already tracking instrument {}", instrumentKey);
            return;
        }
        if (entryPrice == null || entryPrice.signum() <= 0) return;

        StrategyConfig config = strategyConfigService.getDirectionalBuyConfig(
                decision.underlying().name());

        String id = "ML-VT-" + UUID.randomUUID().toString().substring(0, 8);
        VirtualTrade vt = new VirtualTrade(
                id,
                Instant.now(),
                decision.underlying().name(),
                decision.optionType().map(Enum::name).orElse("CE"),
                strategyType != null ? strategyType : "DIRECTIONAL_BUY",
                instrumentKey,
                entryPrice,
                entryPrice, // currentPrice = entryPrice initially
                entryPrice, // peakPrice = entryPrice initially
                config.getStopLossPercent(),
                config.getTargetPercent(),
                config.getTrailingStopActivationPercent(),
                config.getTrailingGapPercent(),
                config.getMaxHoldMinutes(),
                LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()),
                systemScore,
                mlScore,
                mlProbability,
                "OPEN",
                null, null, null,
                0.0, 0
        );

        openTrades.put(id, vt);
        log.info("[ML-VirtualTrade] Opened: id={} instrument={} entry={} systemScore={} mlScore={}",
                id, instrumentKey, entryPrice, systemScore, mlScore);
    }

    /**
     * Evaluate all open virtual trades against live prices.
     * Runs every 10 seconds during market hours.
     */
    @Scheduled(fixedDelay = 10_000)
    public void evaluateOpenTrades() {
        if (openTrades.isEmpty()) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 16)) || now.isAfter(LocalTime.of(15, 35))) return;

        for (VirtualTrade vt : new ArrayList<>(openTrades.values())) {
            try {
                evaluateTrade(vt, now);
            } catch (Exception e) {
                log.debug("[ML-VirtualTrade] Error evaluating {}: {}", vt.virtualTradeId, e.getMessage());
            }
        }
    }

    private void evaluateTrade(VirtualTrade vt, LocalTime now) {
        Optional<Quote> quoteOpt = marketDataService.quote(vt.instrumentKey);
        if (quoteOpt.isEmpty()) return;

        BigDecimal currentPrice = quoteOpt.get().lastPrice();
        if (currentPrice == null || currentPrice.signum() <= 0) return;

        BigDecimal entryPrice = vt.entryPrice;
        BigDecimal peak = vt.peakPrice.max(currentPrice);
        double profitPct = profitPercent(entryPrice, currentPrice);
        double peakPct = profitPercent(entryPrice, peak);
        long holdMinutes = Duration.between(vt.entryTime, Instant.now()).toMinutes();

        // Update tracking state
        vt = vt.withCurrentPrice(currentPrice).withPeakPrice(peak);
        openTrades.put(vt.virtualTradeId, vt);

        // ── 1. Stop Loss ──────────────────────────────────────────────
        double slPct = vt.stopLossPercent.doubleValue();
        if (profitPct <= -slPct) {
            closeTrade(vt, currentPrice, "STOP_LOSS", profitPct, holdMinutes);
            return;
        }

        // ── 2. Target ─────────────────────────────────────────────────
        double targetPct = vt.targetPercent.doubleValue();
        if (profitPct >= targetPct) {
            closeTrade(vt, currentPrice, "TARGET", profitPct, holdMinutes);
            return;
        }

        // ── 3. Trailing Stop ──────────────────────────────────────────
        double activationPct = vt.trailingActivationPercent.doubleValue();
        double gapPct = vt.trailingGapPercent.doubleValue();
        if (peakPct >= activationPct) {
            double trailStop = peakPct - gapPct;
            if (profitPct <= trailStop) {
                closeTrade(vt, currentPrice, "TRAILING_STOP", profitPct, holdMinutes);
                return;
            }
        }

        // ── 4. Max Hold Time ──────────────────────────────────────────
        if (vt.maxHoldMinutes > 0 && holdMinutes >= vt.maxHoldMinutes) {
            closeTrade(vt, currentPrice, "MAX_HOLD_TIME", profitPct, holdMinutes);
            return;
        }

        // ── 5. Squareoff Time ─────────────────────────────────────────
        if (now.isAfter(vt.squareoffTime) || now.equals(vt.squareoffTime)) {
            closeTrade(vt, currentPrice, "SQUAREOFF_TIME", profitPct, holdMinutes);
            return;
        }
    }

    private void closeTrade(VirtualTrade vt, BigDecimal exitPrice, String reason,
                             double profitPct, long holdMinutes) {
        VirtualTrade closed = new VirtualTrade(
                vt.virtualTradeId, vt.entryTime, vt.underlying, vt.optionType, vt.strategyType, vt.instrumentKey,
                vt.entryPrice, exitPrice, vt.peakPrice,
                vt.stopLossPercent, vt.targetPercent, vt.trailingActivationPercent, vt.trailingGapPercent,
                vt.maxHoldMinutes, vt.squareoffTime,
                vt.systemScore, vt.mlScore, vt.mlProbability,
                profitPct > 0 ? "PROFIT" : "LOSS",
                Instant.now(), exitPrice, reason,
                profitPct, (int) holdMinutes
        );

        openTrades.remove(vt.virtualTradeId);
        completedTrades.add(closed);
        persistTrade(closed);

        log.info("[ML-VirtualTrade] Closed: id={} instrument={} entry={} exit={} reason={} pnl={}%",
                vt.virtualTradeId, vt.instrumentKey, vt.entryPrice, exitPrice, reason,
                String.format("%.1f", profitPct));
    }

    /**
     * Force-close all open virtual trades at end of day.
     * Runs at 15:30 IST.
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void endOfDayClose() {
        for (VirtualTrade vt : new ArrayList<>(openTrades.values())) {
            Optional<Quote> quoteOpt = marketDataService.quote(vt.instrumentKey);
            BigDecimal exitPrice = quoteOpt.map(q -> q.lastPrice()).orElse(vt.entryPrice);
            double profitPct = profitPercent(vt.entryPrice, exitPrice);
            long holdMinutes = Duration.between(vt.entryTime, Instant.now()).toMinutes();
            closeTrade(vt, exitPrice, "END_OF_DAY", profitPct, holdMinutes);
        }
    }

    // ── Data access for UI ──────────────────────────────────────────────

    public VirtualTradeSummary getSummary() {
        // Also load from CSV for historical data
        List<VirtualTrade> allCompleted = loadCompletedFromCsv();

        int totalCompleted = allCompleted.size();
        int profitable = 0;
        int losses = 0;
        double totalPnlPct = 0;

        for (VirtualTrade vt : allCompleted) {
            if (vt.profitPercent > 0) profitable++;
            else losses++;
            totalPnlPct += vt.profitPercent;
        }

        double winRate = totalCompleted > 0 ? (double) profitable / totalCompleted * 100 : 0;
        double avgPnl = totalCompleted > 0 ? totalPnlPct / totalCompleted : 0;

        // Build open trades list
        List<Map<String, Object>> openList = openTrades.values().stream()
                .map(this::tradeToMap).toList();

        // Build completed trades list (newest first, max 50)
        List<Map<String, Object>> completedList = allCompleted.stream()
                .sorted(Comparator.comparing((VirtualTrade t) -> t.entryTime).reversed())
                .limit(50)
                .map(this::tradeToMap).toList();

        return new VirtualTradeSummary(
                openTrades.size(), totalCompleted, profitable, losses,
                winRate, avgPnl, totalPnlPct,
                openList, completedList
        );
    }

    private Map<String, Object> tradeToMap(VirtualTrade vt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("virtualTradeId", vt.virtualTradeId);
        m.put("entryTime", vt.entryTime != null ? vt.entryTime.toString() : "");
        m.put("underlying", vt.underlying);
        m.put("optionType", vt.optionType);
        m.put("strategyType", vt.strategyType);
        m.put("instrumentKey", vt.instrumentKey);
        m.put("entryPrice", vt.entryPrice);
        m.put("currentPrice", vt.currentPrice);
        m.put("peakPrice", vt.peakPrice);
        m.put("stopLossPercent", vt.stopLossPercent);
        m.put("targetPercent", vt.targetPercent);
        m.put("systemScore", vt.systemScore);
        m.put("mlScore", vt.mlScore);
        m.put("mlProbability", String.format("%.4f", vt.mlProbability));
        m.put("status", vt.status);
        m.put("exitTime", vt.exitTime != null ? vt.exitTime.toString() : "");
        m.put("exitPrice", vt.exitPrice);
        m.put("exitReason", vt.exitReason);
        m.put("profitPercent", String.format("%.1f", vt.profitPercent));
        m.put("holdMinutes", vt.holdMinutes);
        return m;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private void persistTrade(VirtualTrade vt) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            if (!Files.exists(VIRTUAL_TRADES_CSV)) {
                Files.writeString(VIRTUAL_TRADES_CSV, HEADER,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            String row = String.join(",",
                    csv(vt.virtualTradeId), csv(vt.entryTime), csv(vt.underlying), csv(vt.optionType),
                    csv(vt.strategyType), csv(vt.instrumentKey), csv(vt.entryPrice), csv(vt.currentPrice), csv(vt.peakPrice),
                    csv(vt.stopLossPercent), csv(vt.targetPercent),
                    csv(vt.trailingActivationPercent), csv(vt.trailingGapPercent),
                    csv(vt.maxHoldMinutes), csv(vt.squareoffTime),
                    csv(vt.systemScore), csv(vt.mlScore), csv(String.format("%.4f", vt.mlProbability)),
                    csv(vt.status), csv(vt.exitTime), csv(vt.exitPrice), csv(vt.exitReason),
                    csv(String.format("%.2f", vt.profitPercent)), csv(vt.holdMinutes)
            ) + System.lineSeparator();
            Files.writeString(VIRTUAL_TRADES_CSV, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("ML virtual trade CSV write failed: {}", e.getMessage());
        }
    }

    private List<VirtualTrade> loadCompletedFromCsv() {
        List<VirtualTrade> result = new ArrayList<>();
        if (!Files.exists(VIRTUAL_TRADES_CSV)) return result;
        try {
            List<String> lines = Files.readAllLines(VIRTUAL_TRADES_CSV);
            if (lines.size() <= 1) return result;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                String[] v = line.split(",", -1);
                if (v.length < 23) continue;
                try {
                    // Support both old format (23 cols, no strategyType) and new format (24 cols)
                    boolean hasStrategyType = v.length >= 24;
                    int offset = hasStrategyType ? 1 : 0;
                    String strategyType = hasStrategyType ? v[4] : "DIRECTIONAL_BUY";
                    result.add(new VirtualTrade(
                            v[0], parseInstant(v[1]), v[2], v[3], strategyType, v[4 + offset],
                            parseBd(v[5 + offset]), parseBd(v[6 + offset]), parseBd(v[7 + offset]),
                            parseBd(v[8 + offset]), parseBd(v[9 + offset]), parseBd(v[10 + offset]), parseBd(v[11 + offset]),
                            parseInt(v[12 + offset]), parseTime(v[13 + offset]),
                            parseInt(v[14 + offset]), parseInt(v[15 + offset]), parseDouble(v[16 + offset]),
                            v[17 + offset], parseInstant(v[18 + offset]), parseBd(v[19 + offset]), v[20 + offset],
                            parseDouble(v[21 + offset]), parseInt(v[22 + offset])
                    ));
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            log.debug("Failed to load virtual trades CSV: {}", e.getMessage());
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static double profitPercent(BigDecimal entry, BigDecimal current) {
        if (entry.signum() <= 0) return 0;
        return current.subtract(entry).divide(entry, MC).doubleValue() * 100;
    }

    private static String csv(Object v) {
        if (v == null) return "";
        return v.toString();
    }

    private static BigDecimal parseBd(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.of(15, 15);
        try { return LocalTime.parse(s.trim()); } catch (Exception e) { return LocalTime.of(15, 15); }
    }

    // ── Data types ──────────────────────────────────────────────────────

    record VirtualTrade(
            String virtualTradeId,
            Instant entryTime,
            String underlying,
            String optionType,
            String strategyType,
            String instrumentKey,
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal peakPrice,
            BigDecimal stopLossPercent,
            BigDecimal targetPercent,
            BigDecimal trailingActivationPercent,
            BigDecimal trailingGapPercent,
            int maxHoldMinutes,
            LocalTime squareoffTime,
            int systemScore,
            int mlScore,
            double mlProbability,
            String status,
            Instant exitTime,
            BigDecimal exitPrice,
            String exitReason,
            double profitPercent,
            int holdMinutes
    ) {
        VirtualTrade withCurrentPrice(BigDecimal price) {
            return new VirtualTrade(virtualTradeId, entryTime, underlying, optionType, strategyType, instrumentKey,
                    entryPrice, price, peakPrice, stopLossPercent, targetPercent,
                    trailingActivationPercent, trailingGapPercent, maxHoldMinutes, squareoffTime,
                    systemScore, mlScore, mlProbability, status, exitTime, exitPrice, exitReason,
                    profitPercent, holdMinutes);
        }
        VirtualTrade withPeakPrice(BigDecimal price) {
            return new VirtualTrade(virtualTradeId, entryTime, underlying, optionType, strategyType, instrumentKey,
                    entryPrice, currentPrice, price, stopLossPercent, targetPercent,
                    trailingActivationPercent, trailingGapPercent, maxHoldMinutes, squareoffTime,
                    systemScore, mlScore, mlProbability, status, exitTime, exitPrice, exitReason,
                    profitPercent, holdMinutes);
        }
    }

    public record VirtualTradeSummary(
            int openTrades,
            int completedTrades,
            int profitable,
            int losses,
            double winRatePercent,
            double avgPnlPercent,
            double totalPnlPercent,
            List<Map<String, Object>> openTradesList,
            List<Map<String, Object>> completedTradesList
    ) {}
}
