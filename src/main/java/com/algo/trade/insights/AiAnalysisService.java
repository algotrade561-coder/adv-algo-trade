package com.algo.trade.insights;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.AiRecommendationEntity;
import com.algo.trade.persistence.AiRecommendationRepository;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.risk.MarketGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final MarketGuard marketGuard;
    private final GlobalConfigService globalConfigService;
    private final AiRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public AiAnalysisService(TradeRepository tradeRepository,
                             StrategyDecisionRepository decisionRepository,
                             MarketGuard marketGuard,
                             GlobalConfigService globalConfigService,
                             AiRecommendationRepository recommendationRepository,
                             ObjectMapper objectMapper) {
        this.tradeRepository = tradeRepository;
        this.decisionRepository = decisionRepository;
        this.marketGuard = marketGuard;
        this.globalConfigService = globalConfigService;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
    }

    public AiRecommendationEntity runAnalysis(String runType) {
        log.info("AI analysis started: runType={}", runType);

        Instant now = Instant.now();
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        Instant sevenDaysAgo = LocalDate.now(IST).minusDays(7).atStartOfDay(IST).toInstant();
        Instant tomorrow = LocalDate.now(IST).plusDays(1).atStartOfDay(IST).toInstant();

        // ── Today's trades ───────────────────────────────────────────────────
        List<TradeEntity> allTodayTrades = tradeRepository.findByEntryTimeBetween(todayStart, now);
        List<TradeEntity> closedTrades = allTodayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .filter(t -> !t.isPaperTrade())
                .toList();

        // ── Today's signals ──────────────────────────────────────────────────
        List<StrategyDecisionEntity> todaySignals =
                decisionRepository.findByTimestampGreaterThanEqualOrderByTimestampAsc(todayStart);
        List<StrategyDecisionEntity> noTrades = todaySignals.stream()
                .filter(s -> "NO_TRADE".equals(s.getSignalType()))
                .toList();
        List<StrategyDecisionEntity> entries = todaySignals.stream()
                .filter(s -> s.getSignalType() != null &&
                        (s.getSignalType().equals("BUY_CE") || s.getSignalType().equals("BUY_PE")))
                .toList();

        // ── Market context ───────────────────────────────────────────────────
        double vix = marketGuard.getCurrentVix();
        double pcr = marketGuard.getCurrentPcr();
        String vixStatus = vixStatus(vix);
        String pcrBias = pcrBias(pcr);

        // ── Trade metrics ────────────────────────────────────────────────────
        long wins = closedTrades.stream().filter(t -> t.getRealizedPnl().signum() > 0).count();
        long losses = closedTrades.stream().filter(t -> t.getRealizedPnl().signum() < 0).count();
        double avgPnl = closedTrades.isEmpty() ? 0.0 :
                closedTrades.stream().mapToDouble(t -> t.getRealizedPnl().doubleValue()).average().orElse(0.0);
        Map<String, Long> exitReasonCounts = closedTrades.stream()
                .filter(t -> t.getExitReason() != null)
                .collect(Collectors.groupingBy(
                        t -> normalizeExitReason(t.getExitReason()), Collectors.counting()));

        long slCount = countExitReason(closedTrades, "STOP_LOSS", "STOPLOSS");
        long targetCount = countExitReason(closedTrades, "TARGET");
        long squareoffCount = countExitReason(closedTrades, "SQUARE");

        // ── Signal metrics ───────────────────────────────────────────────────
        String topBlocker = findTopBlocker(noTrades);
        long topBlockerCount = topBlocker == null ? 0 : noTrades.stream()
                .filter(s -> topBlocker.equals(s.getFirstFailedFilter()))
                .count();
        long ceBuys = entries.stream().filter(s -> "BUY_CE".equals(s.getSignalType())).count();
        long peBuys = entries.stream().filter(s -> "BUY_PE".equals(s.getSignalType())).count();
        long neutralIvCount = entries.stream()
                .filter(e -> "NEUTRAL".equals(e.getIvRankSource())).count();
        double avgBidAskSpread = entries.stream()
                .filter(e -> e.getOptionAsk() != null && e.getOptionBid() != null
                          && e.getOptionAsk().compareTo(BigDecimal.ZERO) > 0)
                .mapToDouble(e -> e.getOptionAsk().subtract(e.getOptionBid()).doubleValue())
                .average().orElse(0);

        // ── Consecutive losses ────────────────────────────────────────────────
        int consecutiveLosses = calculateConsecutiveLosses(tomorrow);
        int maxConsecutiveLosses = globalConfigService.getMaxConsecutiveLosses();

        // ── 7-day historical ─────────────────────────────────────────────────
        List<TradeEntity> sevenDayTrades = tradeRepository.findByEntryTimeBetween(sevenDaysAgo, now)
                .stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .filter(t -> !t.isPaperTrade())
                .toList();
        long sevenDayWins = sevenDayTrades.stream().filter(t -> t.getRealizedPnl().signum() > 0).count();
        double sevenDayWinRate = sevenDayTrades.isEmpty() ? 0.0 :
                (double) sevenDayWins / sevenDayTrades.size() * 100;

        // ── Apply rules ──────────────────────────────────────────────────────
        List<Map<String, String>> findingsList = new ArrayList<>();
        List<Map<String, String>> suggestionsList = new ArrayList<>();

        // Rule 1: SL Efficiency
        if (!closedTrades.isEmpty() && slCount * 2 > closedTrades.size()) {
            findingsList.add(finding("WARN",
                    String.format("Stop loss hit on %d/%d trades today — exits happening too early.",
                            slCount, closedTrades.size())));
            double suggested = globalConfigService.getStopLossPercent().doubleValue() + 0.5;
            suggestionsList.add(suggestion("stopLossPercent",
                    globalConfigService.getStopLossPercent().toPlainString(),
                    String.format("%.1f", suggested),
                    "SL hit too frequently — wider stop may allow trades more room to move"));
        }

        // Rule 2: Target Never Reached
        if (closedTrades.size() > 2 && targetCount == 0) {
            findingsList.add(finding("WARN",
                    "Target not reached in any trade today. Market may be choppy."));
            double reducedTarget = round(globalConfigService.getTargetPercent().doubleValue() * 0.8);
            suggestionsList.add(suggestion("targetPercent",
                    globalConfigService.getTargetPercent().toPlainString(),
                    String.format("%.1f", reducedTarget),
                    "No targets hit — consider a lower target or enable trailing stop"));
        }

        // Rule 3: Filter Over-blocking
        if (topBlocker != null && !noTrades.isEmpty() && topBlockerCount * 100 / noTrades.size() > 60) {
            long pct = topBlockerCount * 100 / noTrades.size();
            findingsList.add(finding("INFO",
                    String.format("Filter '%s' blocked %d%% of entries. May be too restrictive.",
                            topBlocker, pct)));
            suggestionsList.add(suggestion(topBlocker, "current threshold", "review",
                    "Dominant blocker — review this filter threshold in Settings"));
        }

        // Rule 4: High VIX
        if ("HIGH".equals(vixStatus)) {
            findingsList.add(finding("ALERT",
                    String.format("India VIX is elevated at %.1f. Options premiums are inflated.", vix)));
            suggestionsList.add(suggestion("lotSize", "current", "reduce",
                    "High VIX — reduce lot size or pause entries until VIX normalizes below 21"));
        }

        // Rule 5: Consecutive Losses
        if (consecutiveLosses > 0 && consecutiveLosses >= maxConsecutiveLosses - 1) {
            findingsList.add(finding("ALERT",
                    String.format("%d consecutive losses. Approaching daily loss gate (%d max).",
                            consecutiveLosses, maxConsecutiveLosses)));
            suggestionsList.add(suggestion("haltMode", "NONE", "SOFT",
                    "Consider soft halt and reviewing market conditions before next entry"));
        }

        // Rule 6: Good Win Streak
        double todayWinRate = closedTrades.isEmpty() ? 0.0 :
                (double) wins / closedTrades.size() * 100;
        if (todayWinRate > 70 && closedTrades.size() >= 3) {
            findingsList.add(finding("INFO",
                    String.format("Strong session — %d/%d trades profitable.", wins, closedTrades.size())));
            suggestionsList.add(suggestion("parameters", "current", "keep",
                    "Current parameters working well. No changes suggested."));
        }

        // Rule 7: No Entries at 10:30 AM
        LocalTime nowIst = LocalTime.now(IST);
        if (nowIst.isAfter(LocalTime.of(10, 30)) && entries.isEmpty() && noTrades.size() > 5) {
            findingsList.add(finding("WARN",
                    String.format("No entries taken despite %d scans. All blocked by filters.",
                            noTrades.size())));
            suggestionsList.add(suggestion("filters", "active", "review",
                    "Check if filters are misconfigured or market is in a ranging phase"));
        }

        // Rule 8: Squareoff Dominating
        if (!closedTrades.isEmpty() && squareoffCount * 2 > closedTrades.size()) {
            findingsList.add(finding("INFO",
                    String.format("Most trades closed at squareoff (%d/%d). Not by SL/target.",
                            squareoffCount, closedTrades.size())));
            int reducedMinutes = Math.max(30, globalConfigService.getMaxHoldMinutes() - 30);
            suggestionsList.add(suggestion("maxHoldMinutes",
                    String.valueOf(globalConfigService.getMaxHoldMinutes()),
                    String.valueOf(reducedMinutes),
                    "Consider reducing maxHoldMinutes or adjusting entry timing window"));
        }

        // Rule 9: PCR/Signal Mismatch
        if ("BEARISH".equals(pcrBias) && entries.size() > 2 && ceBuys > peBuys) {
            findingsList.add(finding("INFO",
                    String.format("PCR suggests bearish but system took %d CE vs %d PE entries.",
                            ceBuys, peBuys)));
            suggestionsList.add(suggestion("trendFilterEnabled",
                    String.valueOf(globalConfigService.isTrendFilterEnabled()),
                    "true",
                    "Enable trend filter to align entries with PCR bias direction"));
        }

        // Rule 10: 7-Day Drawdown
        if (sevenDayTrades.size() >= 5 && sevenDayWinRate < 40) {
            findingsList.add(finding("ALERT",
                    String.format("Win rate %.0f%% over last 7 days — strategy underperforming.",
                            sevenDayWinRate)));
            suggestionsList.add(suggestion("tradingMode", "LIVE", "PAPER",
                    "Consider paper trading mode while reviewing and tuning parameters"));
        }

        // Rule 11: IV Rank Reliability — warns when IVRankTracker hasn't built up history yet
        if (!entries.isEmpty() && neutralIvCount * 100 / entries.size() > 70) {
            long pct = neutralIvCount * 100 / entries.size();
            findingsList.add(finding("INFO",
                    String.format("%d%% of entries used default IV rank 50.0 (no tracker history). " +
                            "IV-based thresholds (spread min, event buy max) may not reflect real conditions.", pct)));
            suggestionsList.add(suggestion("ivRankSource", "NEUTRAL", "wait for TRACKER",
                    "Let IVRankTracker accumulate hourly samples — IV decisions become reliable once source shows TRACKER"));
        }

        // Rule 12: Wide Bid-Ask Spread — high slippage risk on entry
        if (avgBidAskSpread > 5.0 && entries.size() > 2) {
            findingsList.add(finding("WARN",
                    String.format("Avg bid-ask spread ₹%.2f across entries today — wide spread means fill slippage.",
                            avgBidAskSpread)));
            suggestionsList.add(suggestion("minLiquidityVolume", "current", "increase",
                    "Illiquid options — raise minLiquidityVolume or avoid strikes with spread > ₹5"));
        }

        // ── Overall assessment ────────────────────────────────────────────────
        boolean hasAlert = findingsList.stream().anyMatch(f -> "ALERT".equals(f.get("severity")));
        boolean hasWarn = findingsList.stream().anyMatch(f -> "WARN".equals(f.get("severity")));
        String overallAssessment = hasAlert ? "Review needed" :
                hasWarn ? "Caution advised" : "Session going well";

        // ── Build context maps ────────────────────────────────────────────────
        try {
            Map<String, Object> marketContextMap = new LinkedHashMap<>();
            marketContextMap.put("vix", vix);
            marketContextMap.put("pcr", pcr);
            marketContextMap.put("vixStatus", vixStatus);
            marketContextMap.put("pcrBias", pcrBias);

            Map<String, Object> tradeSummaryMap = new LinkedHashMap<>();
            tradeSummaryMap.put("totalTrades", allTodayTrades.size());
            tradeSummaryMap.put("closedTrades", closedTrades.size());
            tradeSummaryMap.put("wins", wins);
            tradeSummaryMap.put("losses", losses);
            tradeSummaryMap.put("avgPnl", round(avgPnl));
            tradeSummaryMap.put("exitReasons", exitReasonCounts);

            Map<String, Object> signalSummaryMap = new LinkedHashMap<>();
            signalSummaryMap.put("totalScans", todaySignals.size());
            signalSummaryMap.put("entries", entries.size());
            signalSummaryMap.put("rejections", noTrades.size());
            signalSummaryMap.put("topBlocker", topBlocker != null ? topBlocker : "");
            signalSummaryMap.put("ceBuys", ceBuys);
            signalSummaryMap.put("peBuys", peBuys);
            signalSummaryMap.put("neutralIvEntries", neutralIvCount);
            signalSummaryMap.put("avgBidAskSpread", round(avgBidAskSpread));

            AiRecommendationEntity entity = new AiRecommendationEntity(
                    now, runType,
                    objectMapper.writeValueAsString(marketContextMap),
                    objectMapper.writeValueAsString(tradeSummaryMap),
                    objectMapper.writeValueAsString(signalSummaryMap),
                    objectMapper.writeValueAsString(findingsList),
                    objectMapper.writeValueAsString(suggestionsList),
                    overallAssessment
            );

            AiRecommendationEntity saved = recommendationRepository.save(entity);
            log.info("AI analysis saved: runType={}, findings={}, assessment={}",
                    runType, findingsList.size(), overallAssessment);
            return saved;

        } catch (Exception e) {
            log.error("AI analysis failed during serialization", e);
            throw new RuntimeException("AI analysis failed", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> finding(String severity, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("text", text);
        return m;
    }

    private Map<String, String> suggestion(String parameter, String currentValue,
                                           String suggestedValue, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("parameter", parameter);
        m.put("currentValue", currentValue);
        m.put("suggestedValue", suggestedValue);
        m.put("reason", reason);
        return m;
    }

    private String vixStatus(double vix) {
        if (vix <= 0) return "UNKNOWN";
        if (vix < 12) return "LOW";
        if (vix <= 18) return "NORMAL";
        if (vix <= 21) return "ELEVATED";
        return "HIGH";
    }

    private String pcrBias(double pcr) {
        if (pcr <= 0) return "UNKNOWN";
        if (pcr > 1.3) return "BULLISH";
        if (pcr < 0.7) return "BEARISH";
        return "NEUTRAL";
    }

    private long countExitReason(List<TradeEntity> trades, String... keywords) {
        return trades.stream()
                .filter(t -> t.getExitReason() != null)
                .filter(t -> {
                    String upper = t.getExitReason().toUpperCase();
                    for (String kw : keywords) {
                        if (upper.contains(kw)) return true;
                    }
                    return false;
                })
                .count();
    }

    private String normalizeExitReason(String reason) {
        String upper = reason.toUpperCase();
        if (upper.contains("STOP_LOSS") || upper.contains("STOPLOSS")) return "STOP_LOSS";
        if (upper.contains("TARGET")) return "TARGET";
        if (upper.contains("TRAIL")) return "TRAILING_STOP";
        if (upper.contains("SQUARE")) return "SQUAREOFF";
        if (upper.contains("VWAP")) return "VWAP";
        if (upper.contains("MANUAL")) return "MANUAL";
        return "OTHER";
    }

    private String findTopBlocker(List<StrategyDecisionEntity> noTrades) {
        return noTrades.stream()
                .filter(s -> s.getFirstFailedFilter() != null && !s.getFirstFailedFilter().isBlank())
                .collect(Collectors.groupingBy(
                        StrategyDecisionEntity::getFirstFailedFilter, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int calculateConsecutiveLosses(Instant tomorrow) {
        Instant thirtyDaysAgo = LocalDate.now(IST).minusDays(30).atStartOfDay(IST).toInstant();
        List<TradeEntity> recent = tradeRepository.findByEntryTimeBetween(thirtyDaysAgo, tomorrow)
                .stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .filter(t -> !t.isPaperTrade())
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .limit(20)
                .toList();
        int count = 0;
        for (TradeEntity t : recent) {
            if (t.getRealizedPnl().signum() < 0) count++;
            else break;
        }
        return count;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
