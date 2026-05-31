package com.algo.trade.tuning.diff;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured output from legacy dual-write diff tooling (removed Phase 6). Each comparison carries
 * a pass/fail flag using the Phase 2 tolerance rules (≤1% relative or ≤1 row absolute).
 */
public record DiffReport(
        LocalDate fromDate,
        LocalDate toDate,
        List<CountComparison> captureCounts,
        List<CellComparison> heatmapCells,
        List<CellComparison> concordanceCells,
        boolean passed
) {

    public record CountComparison(
            String label,
            long legacyCount,
            long unifiedCount,
            boolean passed,
            String note
    ) {
    }

    public record CellComparison(
            String key,
            double legacyValue,
            double unifiedValue,
            boolean passed,
            String note
    ) {
    }

    public String toHtml() {
        StringBuilder html = new StringBuilder(2048);
        html.append("<h2>OI Momentum dual-write parity (")
                .append(fromDate).append(" → ").append(toDate).append(")</h2>");
        html.append("<p>Overall: <strong>")
                .append(passed ? "PASS" : "FAIL")
                .append("</strong></p>");

        html.append("<h3>Capture row counts</h3><table><thead><tr>")
                .append("<th>Stream</th><th>Legacy</th><th>Unified</th><th>Status</th><th>Note</th>")
                .append("</tr></thead><tbody>");
        for (CountComparison c : captureCounts) {
            html.append("<tr><td>").append(escape(c.label()))
                    .append("</td><td>").append(c.legacyCount())
                    .append("</td><td>").append(c.unifiedCount())
                    .append("</td><td>").append(c.passed() ? "PASS" : "FAIL")
                    .append("</td><td>").append(escape(c.note()))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");

        if (!heatmapCells.isEmpty()) {
            html.append("<h3>Heatmap cell counts (legacy signals vs unified plugin)</h3>")
                    .append(renderCellTable(heatmapCells));
        }
        if (!concordanceCells.isEmpty()) {
            html.append("<h3>Concordance cell counts</h3>")
                    .append(renderCellTable(concordanceCells));
        }
        return html.toString();
    }

    private static String renderCellTable(List<CellComparison> cells) {
        StringBuilder html = new StringBuilder();
        html.append("<table><thead><tr>")
                .append("<th>Cell</th><th>Legacy</th><th>Unified</th><th>Status</th><th>Note</th>")
                .append("</tr></thead><tbody>");
        for (CellComparison c : cells) {
            html.append("<tr><td>").append(escape(c.key()))
                    .append("</td><td>").append(formatNum(c.legacyValue()))
                    .append("</td><td>").append(formatNum(c.unifiedValue()))
                    .append("</td><td>").append(c.passed() ? "PASS" : "FAIL")
                    .append("</td><td>").append(escape(c.note()))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static String formatNum(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        if (Math.rint(v) == v) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
