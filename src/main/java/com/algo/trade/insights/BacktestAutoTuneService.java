package com.algo.trade.insights;

import com.algo.trade.backtest.BacktestMetrics;
import com.algo.trade.backtest.BacktestSuiteService;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.persistence.AiRecommendationEntity;
import com.algo.trade.persistence.AiRecommendationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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
public class BacktestAutoTuneService {

    private static final Logger log = LoggerFactory.getLogger(BacktestAutoTuneService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LOOKBACK_DAYS = 30;
    private static final BigDecimal STEP = new BigDecimal("0.5");
    private static final BigDecimal MIN_SL = new BigDecimal("0.3");
    private static final BigDecimal MIN_TARGET = new BigDecimal("0.5");

    private final BacktestSuiteService suiteService;
    private final GlobalConfigService globalConfigService;
    private final AiRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public BacktestAutoTuneService(BacktestSuiteService suiteService,
                                   GlobalConfigService globalConfigService,
                                   AiRecommendationRepository recommendationRepository,
                                   ObjectMapper objectMapper) {
        this.suiteService = suiteService;
        this.globalConfigService = globalConfigService;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
    }

    public AiRecommendationEntity runTune(UnderlyingSymbol underlying) throws Exception {
        LocalDate to = LocalDate.now(IST).minusDays(1);
        LocalDate from = to.minusDays(LOOKBACK_DAYS);
        log.info("Backtest auto-tune started: underlying={}, from={}, to={}", underlying, from, to);

        BigDecimal currentSl = globalConfigService.getStopLossPercent();
        BigDecimal currentTarget = globalConfigService.getTargetPercent();

        // 3 × 3 parameter grid around current values
        List<BigDecimal> slGrid = List.of(
                currentSl.subtract(STEP).max(MIN_SL),
                currentSl,
                currentSl.add(STEP));
        List<BigDecimal> targetGrid = List.of(
                currentTarget.subtract(STEP).max(MIN_TARGET),
                currentTarget,
                currentTarget.add(STEP));

        List<BacktestSuiteService.SuiteVariant> variants = new ArrayList<>();
        for (BigDecimal sl : slGrid) {
            for (BigDecimal target : targetGrid) {
                if (sl.compareTo(target) >= 0) continue;
                String name = "SL" + sl.toPlainString() + "-T" + target.toPlainString();
                variants.add(new BacktestSuiteService.SuiteVariant(
                        name, sl, target,
                        null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));
            }
        }

        BacktestSuiteService.SuiteRequest request = new BacktestSuiteService.SuiteRequest(
                underlying,
                to,
                List.of(new BacktestSuiteService.SuiteWindow("30d", from, to)),
                List.of(OptionType.CE, OptionType.PE),
                List.of(Timeframe.ONE_MINUTE),
                variants,
                null, null, null);

        BacktestSuiteService.SuiteResult suiteResult = suiteService.run(request);

        // Aggregate cumulative P&L per variant across CE + PE
        Map<String, BigDecimal> pnlByVariant = new LinkedHashMap<>();
        Map<String, BacktestMetrics> metricsByVariant = new LinkedHashMap<>();
        for (BacktestSuiteService.SuiteRunResult run : suiteResult.runs()) {
            if (!"ok".equals(run.status()) || run.metrics() == null) continue;
            pnlByVariant.merge(run.variant(), run.metrics().cumulativePnl(), BigDecimal::add);
            metricsByVariant.putIfAbsent(run.variant(), run.metrics());
        }

        // Best variant by combined P&L
        String bestName = pnlByVariant.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Current variant name in the grid
        String currentName = "SL" + currentSl.toPlainString() + "-T" + currentTarget.toPlainString();
        BigDecimal currentPnl = pnlByVariant.getOrDefault(currentName, BigDecimal.ZERO);
        BigDecimal bestPnl = bestName != null ? pnlByVariant.getOrDefault(bestName, BigDecimal.ZERO) : currentPnl;

        // Parse best params from name
        BigDecimal bestSl = currentSl;
        BigDecimal bestTarget = currentTarget;
        if (bestName != null) {
            BacktestSuiteService.SuiteRunResult bestRun = suiteResult.runs().stream()
                    .filter(r -> bestName.equals(r.variant()) && r.metrics() != null)
                    .findFirst().orElse(null);
            if (bestRun != null) {
                bestSl = bestRun.stopLossPercent();
                bestTarget = bestRun.targetPercent();
            }
        }

        // Build findings + suggestions
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        boolean paramsAreOptimal = currentName.equals(bestName) || bestPnl.compareTo(currentPnl) <= 0;
        int totalVariantsRun = pnlByVariant.size();
        BacktestMetrics bestMetrics = bestName != null ? metricsByVariant.get(bestName) : null;

        if (paramsAreOptimal) {
            findings.add(finding("INFO",
                    String.format("Current parameters (SL %.1f%% / T %.1f%%) are optimal among %d variants tested over %d days.",
                            currentSl.doubleValue(), currentTarget.doubleValue(), totalVariantsRun, LOOKBACK_DAYS)));
            suggestions.add(suggestion("stopLossPercent", currentSl.toPlainString(), currentSl.toPlainString(),
                    "No change needed — current params performed best in backtests."));
        } else {
            BigDecimal improvement = bestPnl.subtract(currentPnl)
                    .divide(currentPnl.abs().max(BigDecimal.ONE), 1, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            findings.add(finding("WARN",
                    String.format("Better parameter set found: SL %.1f%% / T %.1f%% outperformed current by ~%.0f%% P&L over %d days.",
                            bestSl.doubleValue(), bestTarget.doubleValue(), improvement.doubleValue(), LOOKBACK_DAYS)));
            suggestions.add(suggestion("stopLossPercent", currentSl.toPlainString(), bestSl.toPlainString(),
                    "Backtest shows better P&L with this stop loss over last " + LOOKBACK_DAYS + " days."));
            suggestions.add(suggestion("targetPercent", currentTarget.toPlainString(), bestTarget.toPlainString(),
                    "Backtest shows better P&L with this target over last " + LOOKBACK_DAYS + " days."));
        }

        if (bestMetrics != null && bestMetrics.totalTrades() < 5) {
            findings.add(finding("INFO",
                    String.format("Low trade count (%d trades). Results may not be statistically significant yet.",
                            bestMetrics.totalTrades())));
        }

        String overallAssessment = paramsAreOptimal ? "Params optimal" : "Consider adjustments";

        // Build context maps
        Map<String, Object> marketCtx = new LinkedHashMap<>();
        marketCtx.put("underlying", underlying.name());
        marketCtx.put("from", from.toString());
        marketCtx.put("to", to.toString());
        marketCtx.put("lookbackDays", LOOKBACK_DAYS);
        marketCtx.put("variantsTested", totalVariantsRun);

        Map<String, Object> tradeSummary = new LinkedHashMap<>();
        if (bestMetrics != null) {
            tradeSummary.put("totalTrades", bestMetrics.totalTrades());
            tradeSummary.put("winRate", bestMetrics.winRatePercent());
            tradeSummary.put("cumulativePnl", bestMetrics.cumulativePnl());
            tradeSummary.put("expectancy", bestMetrics.expectancy());
            tradeSummary.put("maxDrawdown", bestMetrics.maxDrawdown());
            tradeSummary.put("profitFactor", bestMetrics.profitFactor());
        }

        Map<String, Object> signalSummary = new LinkedHashMap<>();
        signalSummary.put("bestVariant", bestName != null ? bestName : "none");
        signalSummary.put("currentVariant", currentName);
        signalSummary.put("allVariantPnl", pnlByVariant);

        AiRecommendationEntity entity = new AiRecommendationEntity(
                Instant.now(), "BACKTEST_TUNE",
                objectMapper.writeValueAsString(marketCtx),
                objectMapper.writeValueAsString(tradeSummary),
                objectMapper.writeValueAsString(signalSummary),
                objectMapper.writeValueAsString(findings),
                objectMapper.writeValueAsString(suggestions),
                overallAssessment);

        AiRecommendationEntity saved = recommendationRepository.save(entity);
        log.info("Backtest auto-tune completed: best={}, optimal={}", bestName, paramsAreOptimal);
        return saved;
    }

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
}
