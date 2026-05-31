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
 * Phase 2 Week 2 Commit 5 — OI Shift Trap analyzer plugin.
 */
class OiShiftTrapAnalyzerPluginTest {

    @TempDir Path tempDir;

    private Path eventsDir;
    private TuningEventStore store;
    private OiShiftTrapAnalyzerPlugin plugin;

    @BeforeEach
    void setUp() {
        eventsDir = tempDir.resolve("events");
        TuningEventStoreProperties props = new TuningEventStoreProperties();
        props.setMemoryLimit("64MB");
        props.setThreads(1);
        props.setTempDirectory(tempDir.resolve("duckdb-tmp").toString());
        props.setStatementTimeoutSec(10);
        store = new TuningEventStore(eventsDir, props);
        plugin = new OiShiftTrapAnalyzerPlugin();
    }

    @Test
    void declaresOiShiftTrapStrategy() {
        assertThat(plugin.strategy()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
    }

    @Test
    void returnsEmptyList_whenQueryDoesNotIncludeOiShiftTrap() {
        TuningEventQuery query = new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.OI_MOMENTUM),
                store);

        assertThat(plugin.customSections(query)).isEmpty();
    }

    @Test
    void returnsTwoSections_whenOiShiftTrapInScope() {
        TuningEventQuery query = sampleQuery();

        List<AnalyzerSection> sections = plugin.customSections(query);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("Confirmation-effectiveness ranker");
        assertThat(sections.get(1).title()).isEqualTo("Late-entry simulation");
    }

    @Test
    void confirmationRanker_emptyShadowDataYieldsPlaceholder() {
        AnalyzerSection section = plugin.confirmationEffectivenessRanker(sampleQuery());

        assertThat(section.htmlBody()).contains("No OI Shift Trap shadow-gate data");
    }

    @Test
    void confirmationRanker_ranksGateCombinations() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 1);
        writeSignals(date, List.of("sig-a", "sig-b"));
        writeExits(date, List.of(
                new String[]{"sig-a", "10.0", "-8.0"},
                new String[]{"sig-b", "-5.0", "-15.0"}
        ));
        writeShadowGates(date, "sig-a", new boolean[]{true, true, true, false, false, false, false, false});
        writeShadowGates(date, "sig-b", new boolean[]{true, false, true, false, false, false, false, false});

        AnalyzerSection section = plugin.confirmationEffectivenessRanker(sampleQuery());

        assertThat(section.htmlBody()).contains("confirm_momentumDecelerating");
        assertThat(section.htmlBody()).contains("Score");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) section.data().get("rows");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("score")).isNotNull();
        double firstScore = ((Number) rows.get(0).get("score")).doubleValue();
        double lastScore = ((Number) rows.get(rows.size() - 1).get("score")).doubleValue();
        assertThat(firstScore).isGreaterThanOrEqualTo(lastScore);
    }

    @Test
    void lateEntry_emptyForwardDataYieldsPlaceholder() {
        AnalyzerSection section = plugin.lateEntrySimulation(sampleQuery());

        assertThat(section.htmlBody()).contains("No forward checkpoint or exit data");
    }

    @Test
    void lateEntry_simulatesDelayedEntryForWorstDrawdowns() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 1);
        writeSignals(date, List.of("sig-deep", "sig-shallow"));
        writeExits(date, List.of(
                new String[]{"sig-deep", "120.0", "-12.0"},
                new String[]{"sig-shallow", "105.0", "-3.0"}
        ));
        writeShadowGates(date, "sig-deep", allTrueGates());
        writeShadowGates(date, "sig-shallow", allTrueGates());
        writeForward(date, "sig-deep",
                100.6, 100.5, 100.2, 100.1, 100.0,
                "{\"spotAtSignal\":100.0,\"trappedOiDelta_30s\":500}");
        writeForward(date, "sig-shallow",
                100.0, 100.1, 100.05, 100.0, 99.9,
                "{\"spotAtSignal\":100.0,\"trappedOiDelta_30s\":100}");

        AnalyzerSection section = plugin.lateEntrySimulation(sampleQuery());

        assertThat(section.htmlBody()).contains("sig-deep");
        assertThat(section.htmlBody()).contains("30s");
        assertThat(section.htmlBody()).doesNotContain("NaN");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) section.data().get("rows");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("correlationKey")).isEqualTo("sig-deep");
        double simPnl = ((Number) rows.get(0).get("simulatedPnlPct")).doubleValue();
        assertThat(simPnl).isFinite();
    }

    @Test
    void rankConfirmationCombos_sortsByScoreDescending() {
        List<OiShiftTrapAnalyzerPlugin.SignalOutcomeRow> rows = List.of(
                row("a", 20, -5, gates(true, true, false, false, false, false, false, false)),
                row("b", -10, -20, gates(false, true, false, false, false, false, false, false)),
                row("c", 15, -8, gates(true, true, false, false, false, false, false, false))
        );

        List<OiShiftTrapAnalyzerPlugin.ConfirmationComboRow> combos =
                OiShiftTrapAnalyzerPlugin.rankConfirmationCombos(rows, 3);

        assertThat(combos).isNotEmpty();
        for (int i = 1; i < combos.size(); i++) {
            assertThat(combos.get(i - 1).score()).isGreaterThanOrEqualTo(combos.get(i).score());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private TuningEventQuery sampleQuery() {
        return new TuningEventQuery(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7),
                Set.of(StrategyType.OI_SHIFT_TRAP),
                store);
    }

    private void writeSignals(LocalDate date, List<String> keys) throws Exception {
        Path dir = trapDir(date);
        StringBuilder sb = new StringBuilder();
        sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,instrumentKey,strike,optionType,entryPremium,attr_extra\n");
        for (String key : keys) {
            sb.append("2026-06-01T09:30:00Z,0,NIFTY,").append(key)
                    .append(",NFO:X,23500,CE,100.00,{\"trapSide\":\"CE\"}\n");
        }
        Files.writeString(dir.resolve("signal.csv"), sb.toString());
    }

    private void writeExits(LocalDate date, List<String[]> rows) throws Exception {
        Path dir = trapDir(date);
        StringBuilder sb = new StringBuilder();
        sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,tradeId,exitReason,entryPrice,exitPrice,realizedPnlPct,holdSec,maePct,mfePct,timeToMaeSec,timeToMfeSec,reversal,attr_extra\n");
        for (String[] r : rows) {
            sb.append("2026-06-01T09:45:00Z,0,NIFTY,").append(r[0])
                    .append(",TRD-").append(r[0])
                    .append(",TARGET,100.00,").append(r[1]).append(",")
                    .append(r[2]).append(",900,-2.5,8.0,300,600,false,{}\n");
        }
        Files.writeString(dir.resolve("exit.csv"), sb.toString());
    }

    private void writeShadowGates(LocalDate date, String correlationKey, boolean[] passed) throws Exception {
        Path file = trapDir(date).resolve("shadow_gate.csv");
        boolean append = Files.exists(file);
        StringBuilder sb = new StringBuilder();
        if (!append) {
            sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,gateName,passed,bandValue,attr_extra\n");
        }
        for (int i = 0; i < OiShiftTrapAnalyzerPlugin.SHADOW_GATES.length; i++) {
            sb.append("2026-06-01T09:30:00Z,0,NIFTY,").append(correlationKey).append(',')
                    .append(OiShiftTrapAnalyzerPlugin.SHADOW_GATES[i]).append(',')
                    .append(passed[i]).append(",,{}\n");
        }
        if (append) {
            Files.writeString(file, Files.readString(file) + sb);
        } else {
            Files.writeString(file, sb.toString());
        }
    }

    private void writeForward(LocalDate date, String correlationKey,
                              double spot30s, double spot1m, double spot5m, double spot15m, double spot30m,
                              String attrExtra) throws Exception {
        Path dir = trapDir(date);
        Path file = dir.resolve("forward_checkpoint.csv");
        boolean append = Files.exists(file);
        StringBuilder sb = new StringBuilder();
        if (!append) {
            sb.append("eventTime,recordedAtDeltaUs,index,correlationKey,fwdSpot30s,fwdSpot1m,fwdSpot5m,fwdSpot15m,fwdSpot30m,fwdMfe30mPct,fwdMae30mPct,attr_extra\n");
        }
        sb.append("2026-06-01T09:30:00Z,0,NIFTY,").append(correlationKey).append(',')
                .append(spot30s).append(',').append(spot1m).append(',').append(spot5m).append(',')
                .append(spot15m).append(',').append(spot30m).append(",1.0,-1.0,")
                .append('"').append(attrExtra.replace("\"", "\"\"")).append('"')
                .append('\n');
        if (append) {
            Files.writeString(file, Files.readString(file) + sb);
        } else {
            Files.writeString(file, sb.toString());
        }
    }

    private Path trapDir(LocalDate date) throws Exception {
        Path dir = eventsDir.resolve(date.toString()).resolve("oi_shift_trap");
        Files.createDirectories(dir);
        return dir;
    }

    private static boolean[] allTrueGates() {
        boolean[] gates = new boolean[OiShiftTrapAnalyzerPlugin.SHADOW_GATES.length];
        java.util.Arrays.fill(gates, true);
        return gates;
    }

    private static boolean[] gates(boolean... values) {
        return values;
    }

    private static OiShiftTrapAnalyzerPlugin.SignalOutcomeRow row(
            String key, double pnl, double mae, boolean[] gates) {
        return new OiShiftTrapAnalyzerPlugin.SignalOutcomeRow(key, pnl, mae, gates);
    }
}
