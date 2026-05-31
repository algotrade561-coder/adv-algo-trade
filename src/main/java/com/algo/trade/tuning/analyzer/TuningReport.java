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
                .append("<style>body{font-family:system-ui,sans-serif;margin:24px;max-width:1200px}")
                .append("h1{color:#1a237e}h2{margin-top:28px;border-bottom:1px solid #ccc;padding-bottom:4px}")
                .append("table{border-collapse:collapse;margin:12px 0}td,th{border:1px solid #ddd;padding:6px 10px}")
                .append("tr:nth-child(even){background:#f9f9f9}.error{color:#c62828}</style></head><body>");
        body.append("<h1>Signal tuning report</h1><p>Period: <strong>")
                .append(fromDate).append("</strong> to <strong>").append(toDate)
                .append("</strong><br/>Strategies: ").append(String.join(", ", strategies))
                .append("</p>");
        for (AnalyzerSection s : sections) {
            body.append("<h2>").append(escape(s.title())).append("</h2>").append(s.htmlBody());
        }
        body.append("</body></html>");
        return body.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
