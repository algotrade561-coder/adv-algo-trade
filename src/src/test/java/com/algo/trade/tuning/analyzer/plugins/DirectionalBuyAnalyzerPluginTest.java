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

class DirectionalBuyAnalyzerPluginTest {

    @TempDir Path tempDir;

    private TuningEventStore store;
    private DirectionalBuyAnalyzerPlugin plugin;

    @BeforeEach
    void setUp() {
        Path eventsDir = tempDir.resolve("events");
        TuningEventStoreProperties props = new TuningEventStoreProperties();
        props.setMemoryLimit("64MB");
        props.setThreads(1);
        props.setTempDirectory(tempDir.resolve("duckdb-tmp").toString());
        props.setStatementTimeoutSec(10);
        store = new TuningEventStore(eventsDir, props);
        plugin = new DirectionalBuyAnalyzerPlugin();
    }

    @Test
    void returnsEmptyWhenStrategyNotInScope() {
        TuningEventQuery query = new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.MOMENTUM), store);

        assertThat(plugin.customSections(query)).isEmpty();
    }

    @Test
    void funnel_emptyDataYieldsPlaceholder() {
        AnalyzerSection section = plugin.sixFilterFunnel(sampleQuery());

        assertThat(section.title()).isEqualTo("6-filter pass/fail funnel");
        assertThat(section.htmlBody()).contains("No Directional Buy evaluation data");
    }

    @Test
    void funnel_rendersStagePassRates() throws Exception {
        writeEvaluations(LocalDate.of(2026, 6, 1), List.of(
                evalRow("BLOCKED", "breakout",
                        "{\"funnel_vwap\":true,\"funnel_breakout\":false,\"funnel_volume\":false,"
                                + "\"funnel_oi\":false,\"funnel_iv\":false,\"funnel_rsi\":false,\"funnel_score\":false}"),
                evalRow("BLOCKED", "volumeSpike",
                        "{\"funnel_vwap\":true,\"funnel_breakout\":true,\"funnel_volume\":false,"
                                + "\"funnel_oi\":false,\"funnel_iv\":false,\"funnel_rsi\":false,\"funnel_score\":false}")
        ));

        AnalyzerSection section = plugin.sixFilterFunnel(sampleQuery());

        assertThat(section.htmlBody()).contains("VWAP / trend");
        assertThat(section.htmlBody()).contains("100%");
        assertThat(section.htmlBody()).contains("breakout");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) section.data().get("stages");
        assertThat(stages).hasSize(DirectionalBuyAnalyzerPlugin.FUNNEL_STAGES.length);
    }

    private TuningEventQuery sampleQuery() {
        return new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.DIRECTIONAL_BUY), store);
    }

    private void writeEvaluations(LocalDate date, List<String> rows) throws Exception {
        Path dir = tempDir.resolve("events").resolve(date.toString()).resolve("directional_buy");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,outcome,blocker,episodeTickCount,attr_extra\n");
        for (String row : rows) {
            sb.append("2026-06-01T09:30:00Z,0,NIFTY,").append(row).append('\n');
        }
        Files.writeString(dir.resolve("evaluation.csv"), sb.toString());
    }

    private static String evalRow(String outcome, String blocker, String attrs) {
        return "EVAL-" + blocker.hashCode() + "," + outcome + "," + blocker + ",1,\""
                + attrs.replace("\"", "\"\"") + "\"";
    }
}
