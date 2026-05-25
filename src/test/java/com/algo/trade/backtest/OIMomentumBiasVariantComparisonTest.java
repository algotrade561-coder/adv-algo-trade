package com.algo.trade.backtest;

import com.algo.trade.strategy.oimomentum.OIMomentumConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick side-by-side comparison of the production bias-score formula (V1) against the
 * two candidate graded-BAL rewrites (V2 symmetric, V3 reward-only). Runs against whatever
 * Global-Datafeeds CSVs are present under {@code data/backtest/imports/global-datafeeds/by-day/};
 * if no data is checked in, the test is skipped so CI stays green.
 *
 * <p>Output: per-variant headline metrics and the V2/V3 deltas vs V1 are printed to stdout
 * so the comparison is visible in the test log.
 *
 * <p>Run from the repo root: {@code mvn test -Dtest=OIMomentumBiasVariantComparisonTest}.
 *
 * <p><strong>Caveat the diagnostic flagged but this harness cannot probe:</strong> the
 * Operator-Framework conviction bonus and the book-imbalance / IV-skew / OI-velocity /
 * max-pain bonuses are not part of the backtest scoring path — only M, OI, PCR, BAL,
 * open-noise, and decay are. So this test isolates the BAL grading change cleanly,
 * but the absolute trade counts are conservative vs production.
 */
class OIMomentumBiasVariantComparisonTest {

    private static final Path BY_DAY_DIR =
            Path.of("data", "backtest", "imports", "global-datafeeds", "by-day");

    static boolean hasBacktestData() {
        if (!Files.isDirectory(BY_DAY_DIR)) return false;
        try (Stream<Path> walk = Files.walk(BY_DAY_DIR)) {
            return walk.anyMatch(p -> p.toString().endsWith(".csv"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("hasBacktestData")
    void prints_v1_v2_v3_comparison_across_indices() {
        OIMomentumConfig config = new OIMomentumConfig();
        OIMomentumBacktestService svc = new OIMomentumBacktestService(config);

        LocalDate from = LocalDate.of(2026, 1, 28);
        LocalDate to   = LocalDate.of(2026, 4, 27);

        for (String underlying : List.of("NIFTY", "BANKNIFTY", "SENSEX")) {
            OIMomentumBacktestService.BacktestRequest base =
                    new OIMomentumBacktestService.BacktestRequest(from, to, underlying, BY_DAY_DIR);
            OIMomentumBacktestService.BiasComparisonResult cmp = svc.compareBiasVariants(base);
            printReport(cmp);

            assertThat(cmp.v1()).isNotNull();
            assertThat(cmp.v2()).isNotNull();
            assertThat(cmp.v3()).isNotNull();
            // V3 (reward-only) is a strict superset of V1's BAL contributions, so it cannot
            // award fewer points than V1 for any single bar; this implies V3 trade count
            // must be ≥ V1 trade count.
            assertThat(cmp.v3().trades().size())
                    .as("V3 trade count must be ≥ V1 trade count for %s", underlying)
                    .isGreaterThanOrEqualTo(cmp.v1().trades().size());
        }
    }

    private static void printReport(OIMomentumBacktestService.BiasComparisonResult cmp) {
        StringBuilder out = new StringBuilder();
        out.append('\n').append(repeat('═', 88)).append('\n')
           .append("  OI Momentum Bias-Score Variant Comparison\n")
           .append("  Underlying=").append(cmp.underlying())
           .append("   Range=").append(cmp.from()).append(" → ").append(cmp.to()).append('\n')
           .append(repeat('═', 88)).append('\n');

        out.append(String.format(Locale.ROOT,
                "  %-32s  %10s  %10s  %10s%n",
                "Metric",
                "V1 (prod)",
                "V2 (sym)",
                "V3 (rew+)"));
        out.append("  ").append(repeat('-', 84)).append('\n');

        appendRow(out, "trades",          cmp.v1().trades().size(),
                                          cmp.v2().trades().size(),
                                          cmp.v3().trades().size());
        appendRow(out, "activeDays",      cmp.v1().activeDays(),
                                          cmp.v2().activeDays(),
                                          cmp.v3().activeDays());
        appendRowD(out, "winRate%",       num(cmp.v1().metrics().get("winRate")),
                                          num(cmp.v2().metrics().get("winRate")),
                                          num(cmp.v3().metrics().get("winRate")));
        appendRowD(out, "netPnlInr",      num(cmp.v1().metrics().get("netPnlInr")),
                                          num(cmp.v2().metrics().get("netPnlInr")),
                                          num(cmp.v3().metrics().get("netPnlInr")));
        appendRowD(out, "profitFactor",   num(cmp.v1().metrics().get("profitFactor")),
                                          num(cmp.v2().metrics().get("profitFactor")),
                                          num(cmp.v3().metrics().get("profitFactor")));
        appendRowD(out, "maxDrawdownInr", num(cmp.v1().metrics().get("maxDrawdownInr")),
                                          num(cmp.v2().metrics().get("maxDrawdownInr")),
                                          num(cmp.v3().metrics().get("maxDrawdownInr")));
        appendRowD(out, "avgWinInr",      num(cmp.v1().metrics().get("avgWinInr")),
                                          num(cmp.v2().metrics().get("avgWinInr")),
                                          num(cmp.v3().metrics().get("avgWinInr")));
        appendRowD(out, "avgLossInr",     num(cmp.v1().metrics().get("avgLossInr")),
                                          num(cmp.v2().metrics().get("avgLossInr")),
                                          num(cmp.v3().metrics().get("avgLossInr")));

        out.append('\n');
        out.append("  Deltas vs V1 (production):\n");
        out.append("  ").append(repeat('-', 84)).append('\n');
        appendDeltaBlock(out, "V2 (sym)",   (Map<?, ?>) cmp.diffSummary().get("v2_vs_v1"));
        appendDeltaBlock(out, "V3 (rew+)",  (Map<?, ?>) cmp.diffSummary().get("v3_vs_v1"));
        out.append(repeat('═', 88)).append('\n');

        // Re-emit also as plain INFO via System.out so it appears in the surefire report
        System.out.println(out);
    }

    private static void appendDeltaBlock(StringBuilder out, String label, Map<?, ?> delta) {
        if (delta == null) return;
        Object deltaTradesObj = delta.get("deltaTrades");
        int deltaTrades = deltaTradesObj instanceof Number n ? n.intValue() : 0;
        out.append(String.format(Locale.ROOT, "  %-12s | Δtrades=%+d  ΔnetPnl=%+.2f  Δwin%%=%+.2f  ΔPF=%+.2f  ΔmaxDD=%+.2f%n",
                label,
                deltaTrades,
                num(delta.get("deltaNetPnlInr")),
                num(delta.get("deltaWinRatePct")),
                num(delta.get("deltaProfitFactor")),
                num(delta.get("deltaMaxDrawdownInr"))));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static void appendRow(StringBuilder out, String name, int v1, int v2, int v3) {
        out.append(String.format(Locale.ROOT, "  %-32s  %10d  %10d  %10d%n", name, v1, v2, v3));
    }

    private static void appendRowD(StringBuilder out, String name, double v1, double v2, double v3) {
        out.append(String.format(Locale.ROOT, "  %-32s  %10.2f  %10.2f  %10.2f%n", name, v1, v2, v3));
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }
}
