package com.algo.trade.tuning.analyzer.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningEventStore;
import com.algo.trade.tuning.store.TuningEventStoreProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2, Commit 5 — OI Momentum analyzer plugin. Uses real DuckDB against
 * synthetic fixture CSVs to verify the matrix-case heatmap and V3-vs-legacy
 * concordance sections produce the expected output.
 */
class OiMomentumAnalyzerPluginTest {

    @TempDir Path tempDir;

    private Path eventsDir;
    private TuningEventStore store;
    private OiMomentumAnalyzerPlugin plugin;

    @BeforeEach
    void setUp() {
        eventsDir = tempDir.resolve("events");
        TuningEventStoreProperties props = new TuningEventStoreProperties();
        props.setMemoryLimit("64MB");
        props.setThreads(1);
        props.setTempDirectory(tempDir.resolve("duckdb-tmp").toString());
        props.setStatementTimeoutSec(10);
        store = new TuningEventStore(eventsDir, props);
        plugin = new OiMomentumAnalyzerPlugin();
    }

    @Test
    void declaresOiMomentumStrategy() {
        assertThat(plugin.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
    }

    @Test
    void returnsEmptyList_whenQueryDoesNotIncludeOiMomentum() {
        TuningEventQuery query = new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.OI_SHIFT_TRAP),
                store);

        List<AnalyzerSection> sections = plugin.customSections(query);
        assertThat(sections).isEmpty();
    }

    @Test
    void returnsTwoSections_whenOiMomentumInScope() {
        TuningEventQuery query = sampleQuery();

        List<AnalyzerSection> sections = plugin.customSections(query);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("Matrix-case × bias-score heatmap");
        assertThat(sections.get(1).title()).isEqualTo("V3-vs-legacy concordance");
    }

    @Test
    void heatmap_emptyDataYieldsPlaceholder() {
        AnalyzerSection section = plugin.matrixCaseBiasHeatmap(sampleQuery());

        assertThat(section.title()).isEqualTo("Matrix-case × bias-score heatmap");
        assertThat(section.htmlBody()).contains("No OI Momentum signal data");
    }

    @Test
    void heatmap_aggregatesByMatrixCaseAndBiasBand() throws Exception {
        // Write a synthetic signal.csv with multiple cases and bias scores.
        writeFixtureSignals(LocalDate.of(2026, 6, 1), List.of(
                /* correlationKey, matrixCase, biasScore */
                row("d1", "CASE2", 72),
                row("d2", "CASE2", 38),
                row("d3", "CASE2", 65),
                row("d4", "CASE1", 80),
                row("d5", "CASE3", 45)
        ));
        // Write a synthetic exit.csv linking PnL outcomes to those signals.
        writeFixtureExits(LocalDate.of(2026, 6, 1), List.of(
                exitRow("d1", 18.5),
                exitRow("d2", -12.0),
                exitRow("d3", 5.0),
                exitRow("d4", 22.0)
                // d5 has no exit row → LEFT JOIN preserves it with NULL PnL
        ));

        AnalyzerSection section = plugin.matrixCaseBiasHeatmap(sampleQuery());

        // Heatmap rendered as HTML table with all three matrixCases as rows.
        assertThat(section.htmlBody())
                .contains("CASE1")
                .contains("CASE2")
                .contains("CASE3")
                .contains("70–79")     // d1 lands here
                .contains("30–39")     // d2 lands here
                .contains("60–69")     // d3 lands here
                .contains("80+")       // d4 lands here
                .contains("40–49");    // d5 lands here

        // Structured data exposes the raw rows for tests / exports.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) section.data().get("rows");
        assertThat(rows).hasSize(5);     // one per (matrixCase, biasBand) cell with data
    }

    @Test
    void heatmap_handlesMissingExitFileGracefully() throws Exception {
        writeFixtureSignals(LocalDate.of(2026, 6, 1), java.util.Collections.singletonList(
                row("d1", "CASE2", 72)
        ));
        // No exit file at all.
        AnalyzerSection section = plugin.matrixCaseBiasHeatmap(sampleQuery());

        // Heatmap still renders with the signal count, just no PnL.
        assertThat(section.htmlBody()).contains("CASE2").contains("70–79");
    }

    @Test
    void concordance_missingDirectories_yieldsPlaceholder() {
        // No V3 or legacy directory exists in tempDir.
        AnalyzerSection section = plugin.v3VsLegacyConcordance(sampleQuery());

        assertThat(section.title()).isEqualTo("V3-vs-legacy concordance");
        assertThat(section.htmlBody()).contains("not present");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private TuningEventQuery sampleQuery() {
        return new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.OI_MOMENTUM, StrategyType.OI_SHIFT_TRAP),
                store);
    }

    private void writeFixtureSignals(LocalDate date, List<String[]> rows) throws Exception {
        Path dir = eventsDir.resolve(date.toString()).resolve("oi_momentum");
        Files.createDirectories(dir);
        Path file = dir.resolve("signal.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,instrumentKey,strike,optionType,entryPremium,attr_extra\n");
        for (String[] r : rows) {
            sb.append("2026-06-01T09:30:00Z,0,NIFTY,").append(r[0])
                    .append(",NFO:X,23500,CE,100.00,")
                    .append("\"{\"\"matrixCase\"\":\"\"").append(r[1])
                    .append("\"\",\"\"biasScore\"\":").append(r[2]).append("}\"")
                    .append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    private void writeFixtureExits(LocalDate date, List<String[]> rows) throws Exception {
        Path dir = eventsDir.resolve(date.toString()).resolve("oi_momentum");
        Files.createDirectories(dir);
        Path file = dir.resolve("exit.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,tradeId,exitReason,entryPrice,exitPrice,realizedPnlPct,holdSec,maePct,mfePct,timeToMaeSec,timeToMfeSec,reversal,attr_extra\n");
        for (String[] r : rows) {
            sb.append("2026-06-01T09:45:00Z,0,NIFTY,").append(r[0])
                    .append(",TRD-").append(r[0])
                    .append(",TARGET,100.00,").append(r[1]).append(",")
                    .append(r[2]).append(",900,-2.5,8.0,300,600,false,{}\n");
        }
        Files.writeString(file, sb.toString());
    }

    private static String[] row(String correlationKey, String matrixCase, int biasScore) {
        return new String[]{correlationKey, matrixCase, String.valueOf(biasScore)};
    }

    private static String[] exitRow(String correlationKey, double pnlPct) {
        // [correlationKey, exitPrice, realizedPnlPct] — exitPrice doesn't matter for the join
        double exitPrice = 100.0 * (1 + pnlPct / 100.0);
        return new String[]{correlationKey, String.format("%.2f", exitPrice), String.format("%.2f", pnlPct)};
    }
}
