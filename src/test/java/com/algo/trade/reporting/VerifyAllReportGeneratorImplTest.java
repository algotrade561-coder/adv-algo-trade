package com.algo.trade.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.backtest.*;
import com.algo.trade.backtest.ExecutionFlowTracker.ExecutionFlowCoverage;
import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

class VerifyAllReportGeneratorImplTest {

    private VerifyAllReportGeneratorImpl generator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        generator = new VerifyAllReportGeneratorImpl();
    }

    // ── Test data builders ────────────────────────────────────────────────

    private BacktestMetrics sampleMetrics(int trades, BigDecimal pnl, BigDecimal winRate) {
        return new BacktestMetrics(
                trades, winRate,
                BigDecimal.valueOf(500), BigDecimal.valueOf(-200),
                BigDecimal.valueOf(150), BigDecimal.valueOf(3000),
                pnl, Map.of("2025-01-15", BigDecimal.valueOf(1200)),
                trades * 2, trades / 2, 0,
                BigDecimal.valueOf(2.5), BigDecimal.valueOf(22),
                5, 3, BigDecimal.valueOf(2.5), BigDecimal.valueOf(50)
        );
    }

    private BacktestTrade sampleTrade() {
        return new BacktestTrade(
                "T-001", "NFO:NIFTY25JAN24500CE",
                Instant.parse("2025-01-15T04:00:00Z"),
                Instant.parse("2025-01-15T05:30:00Z"),
                75, BigDecimal.valueOf(120), BigDecimal.valueOf(145),
                BigDecimal.valueOf(1875), "VWAP breakout", "target"
        );
    }

    private StrategyVerificationResult okResult(StrategyType type) {
        return new StrategyVerificationResult(
                type, "ok",
                sampleMetrics(20, BigDecimal.valueOf(5000), BigDecimal.valueOf(65)),
                List.of(sampleTrade()),
                40, 10,
                Map.of("premium-too-high", 5, "risk-rejected", 5),
                Map.of("avgIvRank", "32.5"),
                null
        );
    }

    private StrategyVerificationResult errorResult(StrategyType type, String errorMsg) {
        return new StrategyVerificationResult(
                type, "error", null, List.of(),
                0, 0, Map.of(), Map.of(), errorMsg
        );
    }

    private ExecutionFlowCoverage sampleFlowCoverage() {
        var tracker = new ExecutionFlowTracker();
        tracker.recordHit("entry.signal-to-trade", "NIFTY CE breakout");
        tracker.recordHit("exit.stop-loss", "SL at 42.5");
        tracker.recordHit("exit.target");
        tracker.recordHit("atr.computed");
        tracker.recordHit("regime.score-computed", "score=0.75");
        return tracker.getCoverage();
    }

    private ConfigSnapshot sampleConfigSnapshot() {
        TradingProperties tp = new TradingProperties(null, false, null,
                null, null, null, null, null, null, null, null, null);
        GlobalConfig gc = new GlobalConfig(tp);
        Map<StrategyType, StrategyConfig> configs = new LinkedHashMap<>();
        configs.put(StrategyType.DIRECTIONAL_BUY, new StrategyConfig(StrategyType.DIRECTIONAL_BUY));
        configs.put(StrategyType.IRON_CONDOR, new StrategyConfig(StrategyType.IRON_CONDOR));
        return new ConfigSnapshot(gc, tp, configs, List.of("SL mismatch: global=10%, DIRECTIONAL_BUY=12%"));
    }

    private VerifyAllResult buildFullResult() {
        List<StrategyVerificationResult> results = new ArrayList<>();
        for (StrategyType type : StrategyType.values()) {
            results.add(okResult(type));
        }
        return new VerifyAllResult(
                "VERIFY-TEST-001",
                Instant.parse("2025-01-15T04:00:00Z"),
                Instant.parse("2025-01-15T04:05:30Z"),
                UnderlyingSymbol.NIFTY,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 3, 31),
                results,
                sampleFlowCoverage(),
                sampleConfigSnapshot(),
                tempDir,
                tempDir.resolve("verify-report.html"),
                tempDir.resolve("verify-summary.json")
        );
    }

    // ── HTML: Summary section ─────────────────────────────────────────────

    @Test
    void htmlContainsSummaryWithStrategyCountAndTotalTrades() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(html).contains("Verify-All Report");
        assertThat(html).contains("VERIFY-TEST-001");
        // All strategies present
        assertThat(html).contains(String.valueOf(StrategyType.values().length));
        // Total trades = N * 20
        assertThat(html).contains(String.valueOf(StrategyType.values().length * 20));
    }

    @Test
    void htmlContainsPnlAndWinRateAndProfitFactor() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        // Total PnL = N * 5000
        assertThat(html).contains(String.valueOf(StrategyType.values().length * 5000));
        // Win Rate card present
        assertThat(html).contains("Win Rate");
        // Profit Factor card present
        assertThat(html).contains("Profit Factor");
        // Data range
        assertThat(html).contains("2025-01-01");
        assertThat(html).contains("2025-03-31");
    }

    // ── HTML: Per-strategy sections ───────────────────────────────────────

    @Test
    void htmlContainsAllSixteenStrategyTypes() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        for (StrategyType type : StrategyType.values()) {
            String escaped = type.displayName().replace("&", "&amp;");
            assertThat(html).contains(escaped);
            assertThat(html).contains(type.name());
        }
    }

    @Test
    void htmlContainsPerStrategyMetricsAndSignals() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        // Per-strategy section header
        assertThat(html).contains("Per-Strategy Results");
        // Entry signals and rejection reasons
        assertThat(html).contains("Entry Signals");
        assertThat(html).contains("Rejected Signals");
        assertThat(html).contains("premium-too-high");
        // Strategy-specific metrics
        assertThat(html).contains("avgIvRank");
        assertThat(html).contains("32.5");
    }

    @Test
    void htmlContainsSampleTradesTable() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(html).contains("T-001");
        assertThat(html).contains("NFO:NIFTY25JAN24500CE");
        assertThat(html).contains("VWAP breakout");
        assertThat(html).contains("target");
    }

    // ── HTML: Flow coverage section ───────────────────────────────────────

    @Test
    void htmlContainsFlowCoverageWithHitAndMissMarkers() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(html).contains("Execution Flow Coverage");
        // Hit branches show ✅
        assertThat(html).contains("✅");
        // Unhit branches show ❌
        assertThat(html).contains("❌");
        // Specific hit branches
        assertThat(html).contains("entry.signal-to-trade");
        assertThat(html).contains("exit.stop-loss");
        // Hit detail
        assertThat(html).contains("NIFTY CE breakout");
    }

    // ── HTML: Config snapshot section ─────────────────────────────────────

    @Test
    void htmlContainsConfigSnapshotWithTradingPropertiesAndStrategyConfigs() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(html).contains("Configuration Snapshot");
        assertThat(html).contains("Trading Properties");
        // Per-strategy config table
        assertThat(html).contains("Per-Strategy Configs");
        assertThat(html).contains("Directional Buy");
        assertThat(html).contains("Iron Condor");
        // Config mismatch
        assertThat(html).contains("Config Mismatches");
        assertThat(html).contains("SL mismatch");
    }

    // ── HTML: Valid structure ──────────────────────────────────────────────

    @Test
    void htmlIsWellFormedDocument() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(htmlPath.getFileName().toString()).isEqualTo("verify-report.html");
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<html lang='en'>");
        assertThat(html).endsWith("</body></html>");
    }

    // ── JSON: Round-trip deserialization ───────────────────────────────────

    @Test
    void jsonSummaryDeserializesToExpectedStructure() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path jsonPath = generator.generateJsonSummary(result, tempDir);
        assertThat(jsonPath.getFileName().toString()).isEqualTo("verify-summary.json");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode root = mapper.readTree(jsonPath.toFile());

        assertThat(root.get("verifyId").asText()).isEqualTo("VERIFY-TEST-001");
        assertThat(root.get("underlying").asText()).isEqualTo("NIFTY");
        assertThat(root.get("from").asText()).isEqualTo("2025-01-01");
        assertThat(root.get("to").asText()).isEqualTo("2025-03-31");

        // Strategy results array
        JsonNode strategies = root.get("strategyResults");
        assertThat(strategies.isArray()).isTrue();
        assertThat(strategies.size()).isEqualTo(StrategyType.values().length);

        // First strategy has expected fields
        JsonNode first = strategies.get(0);
        assertThat(first.get("strategyType").asText()).isEqualTo("DIRECTIONAL_BUY");
        assertThat(first.get("status").asText()).isEqualTo("ok");
        assertThat(first.get("metrics")).isNotNull();
        assertThat(first.get("metrics").get("totalTrades").asInt()).isEqualTo(20);
    }

    @Test
    void jsonContainsFlowCoverageAndConfigSnapshot() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path jsonPath = generator.generateJsonSummary(result, tempDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode root = mapper.readTree(jsonPath.toFile());

        // Flow coverage
        JsonNode flowCoverage = root.get("flowCoverage");
        assertThat(flowCoverage).isNotNull();
        JsonNode branches = flowCoverage.get("branches");
        assertThat(branches).isNotNull();
        assertThat(branches.has("entry.signal-to-trade")).isTrue();
        assertThat(branches.get("entry.signal-to-trade").get("hit").asBoolean()).isTrue();

        // Config snapshot
        JsonNode config = root.get("configSnapshot");
        assertThat(config).isNotNull();
        assertThat(config.has("globalConfig")).isTrue();
        assertThat(config.has("tradingProperties")).isTrue();
        assertThat(config.has("strategyConfigs")).isTrue();
    }

    @Test
    void jsonContainsPathFieldsAsStrings() throws Exception {
        VerifyAllResult result = buildFullResult();

        Path jsonPath = generator.generateJsonSummary(result, tempDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode root = mapper.readTree(jsonPath.toFile());

        // Path fields serialized as strings (not objects)
        assertThat(root.get("outputDirectory").isTextual()).isTrue();
        assertThat(root.get("reportHtml").isTextual()).isTrue();
        assertThat(root.get("summaryJson").isTextual()).isTrue();
    }

    // ── Empty / all-error results ─────────────────────────────────────────

    @Test
    void htmlWithAllErrorStrategiesStillProducesValidReport() throws Exception {
        List<StrategyVerificationResult> errorResults = new ArrayList<>();
        for (StrategyType type : StrategyType.values()) {
            errorResults.add(errorResult(type, "No data available for " + type.displayName()));
        }
        VerifyAllResult result = new VerifyAllResult(
                "VERIFY-ERR-001",
                Instant.parse("2025-01-15T04:00:00Z"),
                Instant.parse("2025-01-15T04:00:05Z"),
                UnderlyingSymbol.NIFTY,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 3, 31),
                errorResults,
                sampleFlowCoverage(),
                sampleConfigSnapshot(),
                tempDir,
                tempDir.resolve("verify-report.html"),
                tempDir.resolve("verify-summary.json")
        );

        Path htmlPath = generator.generateHtmlReport(result, tempDir);
        String html = Files.readString(htmlPath);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).endsWith("</body></html>");
        // All strategies show ERROR status
        assertThat(html).contains("ERROR");
        // Error messages present
        assertThat(html).contains("No data available for Directional Buy");
        // Summary shows 0 trades
        assertThat(html).contains("0");
        // All strategy types still listed (HTML-escape & for display names containing it)
        for (StrategyType type : StrategyType.values()) {
            String escaped = type.displayName().replace("&", "&amp;");
            assertThat(html).contains(escaped);
        }
    }

    @Test
    void jsonWithAllErrorStrategiesDeserializesCorrectly() throws Exception {
        List<StrategyVerificationResult> errorResults = new ArrayList<>();
        for (StrategyType type : StrategyType.values()) {
            errorResults.add(errorResult(type, "Failed: " + type.name()));
        }
        VerifyAllResult result = new VerifyAllResult(
                "VERIFY-ERR-002",
                Instant.parse("2025-01-15T04:00:00Z"),
                Instant.parse("2025-01-15T04:00:05Z"),
                UnderlyingSymbol.NIFTY,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 3, 31),
                errorResults,
                sampleFlowCoverage(),
                sampleConfigSnapshot(),
                tempDir,
                tempDir.resolve("verify-report.html"),
                tempDir.resolve("verify-summary.json")
        );

        Path jsonPath = generator.generateJsonSummary(result, tempDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode root = mapper.readTree(jsonPath.toFile());

        assertThat(root.get("verifyId").asText()).isEqualTo("VERIFY-ERR-002");
        JsonNode strategies = root.get("strategyResults");
        assertThat(strategies.size()).isEqualTo(StrategyType.values().length);
        for (int i = 0; i < strategies.size(); i++) {
            assertThat(strategies.get(i).get("status").asText()).isEqualTo("error");
            assertThat(strategies.get(i).get("metrics").isNull()).isTrue();
            assertThat(strategies.get(i).get("errorMessage").asText()).startsWith("Failed:");
        }
    }
}
