package com.algo.trade.tuning.analyzer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Assembled HTML tuning report from {@link TuningAnalyzerCoordinator}. */
public final class TuningReport {

    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final List<String> strategies;
    private final List<AnalyzerSection> sections;

    public TuningReport(LocalDate fromDate, LocalDate toDate, List<String> strategies,
                        List<AnalyzerSection> sections) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.strategies = List.copyOf(strategies);
        this.sections = new ArrayList<>(sections);
    }

    public LocalDate fromDate() { return fromDate; }
    public LocalDate toDate() { return toDate; }
    public List<String> strategies() { return strategies; }
    public int sectionCount() { return sections.size(); }
    public List<AnalyzerSection> sections() { return List.copyOf(sections); }

    public void addSection(AnalyzerSection section) {
        if (section != null) {
            sections.add(section);
        }
    }

    public void addSections(List<AnalyzerSection> more) {
        if (more != null) {
            sections.addAll(more);
        }
    }

    public String renderHtml() {
        StringBuilder body = new StringBuilder();
        body.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
                .append("<title>Tuning report ").append(fromDate).append(" – ").append(toDate).append("</title>")
                .append("<style>")
                .append("body{font-family:system-ui,sans-serif;margin:24px;max-width:1200px;color:#212121}")
                .append("h1{color:#1a237e}")
                .append("h2.strategy{color:#1a237e;font-size:22px;margin:40px 0 8px;padding:8px 14px;")
                .append("background:linear-gradient(90deg,#e8eaf6 0%,#fff 100%);")
                .append("border-left:6px solid #1a237e;border-radius:4px}")
                .append("h2:not(.strategy){margin-top:28px;border-bottom:1px solid #ccc;padding-bottom:4px}")
                .append("h3{color:#37474f;margin:18px 0 6px;font-size:15px;")
                .append("border-bottom:1px solid #cfd8dc;padding-bottom:3px}")
                .append("table{border-collapse:collapse;margin:8px 0 16px;font-size:14px}")
                .append("td,th{border:1px solid #ddd;padding:5px 9px}")
                .append("th{background:#eceff1;text-align:left}")
                .append("tr:nth-child(even){background:#f9f9f9}")
                .append(".error{color:#c62828}")
                .append(".strategy-subtitle{color:#546e7a;font-style:italic;margin:0 0 12px}")
                .append(".strategy-block{margin-bottom:24px;padding-bottom:8px;")
                .append("border-bottom:2px dashed #b0bec5}")
                .append("</style></head><body>");
        body.append("<h1>Signal tuning report</h1><p>Period: <strong>")
                .append(fromDate).append("</strong> to <strong>").append(toDate)
                .append("</strong><br/>Strategies: ").append(String.join(", ", strategies))
                .append("</p>");

        // Two-tier rendering: titles starting with "## " open a new
        // strategy-block wrapped in a banner h2.strategy; subsequent
        // sections render as h3 until the next banner. Pre-banner sections
        // (e.g. "Strategies with no captured events") render as plain h2.
        boolean inStrategyBlock = false;
        for (AnalyzerSection s : sections) {
            String title = s.title();
            if (title.startsWith("## ")) {
                if (inStrategyBlock) body.append("</div>");
                body.append("<div class=\"strategy-block\">")
                        .append("<h2 class=\"strategy\">")
                        .append(escape(title.substring(3)))
                        .append("</h2>")
                        .append(s.htmlBody());
                inStrategyBlock = true;
            } else if (inStrategyBlock) {
                body.append("<h3>").append(escape(title)).append("</h3>")
                        .append(s.htmlBody());
            } else {
                body.append("<h2>").append(escape(title)).append("</h2>")
                        .append(s.htmlBody());
            }
        }
        if (inStrategyBlock) body.append("</div>");
        body.append("</body></html>");
        return body.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
