package com.algo.trade.tuning.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks the bug the review caught: the coordinator prefixes every section title with the strategy display
 * name ("OI Momentum — …"), so the synthesizer MUST match by contains(), not startsWith(), or actions.json
 * is silently empty even when the analyzer rows are present.
 */
class TuningActionSynthesizerTest {

    private final TuningActionSynthesizer synth = new TuningActionSynthesizer();

    private static TuningReport reportWith(AnalyzerSection... sections) {
        return new TuningReport(LocalDate.of(2026, 6, 26), LocalDate.of(2026, 6, 26),
                List.of("OI Momentum"), List.of(sections));
    }

    @Test
    void emitsLoosenGateActionDespitePrefixedTitle() {
        // Title exactly as the coordinator renders it (prefixed).
        AnalyzerSection s = new AnalyzerSection(
                "OI Momentum — Blocker opportunity-cost (did a gate reject winners?)",
                "<table></table>",
                Map.of("rows", List.of(Map.of(
                        "blocker", "charges_filter:net_below_floor",
                        "n", 184L,
                        "pct_would_profit", 41.0))));

        List<Map<String, Object>> actions = synth.synthesize(reportWith(s));

        assertThat(actions).isNotEmpty();
        assertThat(actions.get(0).get("type")).isEqualTo("loosen_gate");
        assertThat(actions.get(0).get("target")).isEqualTo("charges_filter:net_below_floor");
    }

    @Test
    void respectsSampleSizeGuard() {
        AnalyzerSection s = new AnalyzerSection(
                "OI Momentum — Blocker opportunity-cost",
                "<table></table>",
                Map.of("rows", List.of(Map.of(
                        "blocker", "tiny_n_gate", "n", 3L, "pct_would_profit", 90.0))));
        // n=3 is below MIN_REJECT_N → no action.
        assertThat(synth.synthesize(reportWith(s))).isEmpty();
    }

    @Test
    void emitsTightenExitForHighGiveback() {
        AnalyzerSection s = new AnalyzerSection(
                "OI Momentum — Exit attribution (give-back & early cuts by exit reason)",
                "<table></table>",
                Map.of("rows", List.of(Map.of(
                        "exitReason", "trailing_stop", "n", 12L, "avg_giveback", 7.5, "avg_pnl", 1.2))));

        List<Map<String, Object>> actions = synth.synthesize(reportWith(s));
        assertThat(actions).anyMatch(a -> "tighten_exit".equals(a.get("type")));
    }

    @Test
    void emptyReportYieldsNoActions() {
        assertThat(synth.synthesize(reportWith())).isEmpty();
        assertThat(synth.synthesize(null)).isEmpty();
    }
}
