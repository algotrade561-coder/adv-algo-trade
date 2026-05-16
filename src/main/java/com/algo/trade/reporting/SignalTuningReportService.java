package com.algo.trade.reporting;

import com.algo.trade.notification.TelegramAlertService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds a signal-tuning report from existing {@code reports/entry-signals} CSV data,
 * writes HTML under {@code reports/tuning/}, and sends a Telegram summary.
 */
@Service
public class SignalTuningReportService {

    private static final Logger log = LoggerFactory.getLogger(SignalTuningReportService.class);
    private static final Path OUTPUT_DIR = Path.of("reports", "tuning");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final TelegramAlertService telegramAlertService;

    public SignalTuningReportService(TelegramAlertService telegramAlertService) {
        this.telegramAlertService = telegramAlertService;
    }

    public TuningRunResult generate() {
        SignalTuningCsvLoader.Loaded data = SignalTuningCsvLoader.load();
        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);
        Path htmlPath = writeHtml(report);
        String telegramSummary = formatTelegramSummary(report, htmlPath);
        telegramAlertService.signalTuningReport(telegramSummary);
        log.info("Signal tuning report generated: evals={}, buys={}, recommendations={}, html={}",
                report.totalEvaluations(), report.buySignals(), report.recommendations().size(), htmlPath);
        return new TuningRunResult(
                Instant.now(),
                htmlPath.toString(),
                report.totalEvaluations(),
                report.buySignals(),
                report.recommendations().size(),
                telegramSummary);
    }

    public String readHtml(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read tuning report: " + path, ex);
        }
    }

    public java.util.Optional<Path> latestHtmlReport() {
        if (!Files.isDirectory(OUTPUT_DIR)) {
            return java.util.Optional.empty();
        }
        try {
            return Files.list(OUTPUT_DIR)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .max(Comparator.comparing(Path::getFileName));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list tuning reports", ex);
        }
    }

    private Path writeHtml(SignalTuningAnalyzer.Report report) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            Path path = OUTPUT_DIR.resolve("signal-tuning-" + FILE_TS.format(Instant.now()) + ".html");
            Files.writeString(path, renderHtml(report), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return path;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write signal tuning HTML", ex);
        }
    }

    static String formatTelegramSummary(SignalTuningAnalyzer.Report report, Path htmlPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Signal Tuning Report").append(System.lineSeparator());
        sb.append("Period: ").append(DISPLAY_DATE.format(report.periodFrom()));
        if (!report.periodFrom().equals(report.periodTo())) {
            sb.append(" → ").append(DISPLAY_DATE.format(report.periodTo()));
        }
        sb.append(System.lineSeparator());
        sb.append("Evaluations: ").append(report.totalEvaluations());
        sb.append(" | BUY: ").append(report.buySignals());
        sb.append(" | NO_TRADE: ").append(report.noTradeSignals()).append(System.lineSeparator());
        if (report.buysWithoutBreakoutFlag() > 0) {
            sb.append("⚠️ BUY w/o breakout flag: ").append(report.buysWithoutBreakoutFlag()).append(System.lineSeparator());
        }
        if (report.falseBreakoutLabeled() > 0) {
            sb.append("⚠️ False-breakout labeled: ").append(report.falseBreakoutLabeled())
                    .append(" / ").append(report.buyOutcomesAnalyzed()).append(System.lineSeparator());
        }
        if (report.chainOiMismatchCount() > 0) {
            sb.append("⚠️ Chain OI mismatch: ").append(report.chainOiMismatchCount())
                    .append(" BUY(s)").append(System.lineSeparator());
        }
        if (report.chainMissingOiDeltaCount() > 0) {
            sb.append("⚠️ Chain OI Δ all zero: ").append(report.chainMissingOiDeltaCount())
                    .append(" BUY(s)").append(System.lineSeparator());
        }

        sb.append(System.lineSeparator()).append("Strategy funnel:").append(System.lineSeparator());
        int strategyLines = 0;
        for (SignalTuningAnalyzer.StrategySummary s : report.strategies()) {
            if (strategyLines >= 8) {
                sb.append("… +").append(report.strategies().size() - 8).append(" more").append(System.lineSeparator());
                break;
            }
            String top = s.topBlockers().isEmpty() ? "—"
                    : s.topBlockers().get(0).filter() + " (" + String.format("%.0f%%", s.topBlockers().get(0).percent()) + ")";
            sb.append("• ").append(s.strategyType()).append(": ")
                    .append(s.buySignals()).append(" BUY / ").append(s.evaluations()).append(" eval, top block ")
                    .append(top).append(System.lineSeparator());
            strategyLines++;
        }

        sb.append(System.lineSeparator()).append("Top actions:").append(System.lineSeparator());
        int recLines = 0;
        for (SignalTuningAnalyzer.Recommendation rec : report.recommendations()) {
            if (recLines >= 6) {
                sb.append("… see HTML report").append(System.lineSeparator());
                break;
            }
            String icon = switch (rec.severity()) {
                case CRITICAL -> "🔴";
                case WARN -> "🟠";
                case INFO -> "🔵";
            };
            sb.append(icon).append(" ").append(rec.finding()).append(System.lineSeparator());
            sb.append("   → ").append(rec.action()).append(System.lineSeparator());
            recLines++;
        }

        sb.append(System.lineSeparator()).append("BUY chain context:").append(System.lineSeparator());
        int chainLines = 0;
        for (SignalTuningAnalyzer.BuyOutcome b : report.buyOutcomes()) {
            if (chainLines >= 4) {
                sb.append("… see HTML").append(System.lineSeparator());
                break;
            }
            if (b.chain() != null && b.chain().present()) {
                sb.append("• ").append(b.timestamp()).append(" ").append(b.signalType())
                        .append(" — ").append(b.chain().shortSummary()).append(System.lineSeparator());
                chainLines++;
            }
        }

        sb.append(System.lineSeparator()).append("Report: ").append(htmlPath.toAbsolutePath().normalize());
        String text = sb.toString();
        if (text.length() > 4000) {
            return text.substring(0, 3990) + "…";
        }
        return text;
    }

    private String renderHtml(SignalTuningAnalyzer.Report report) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Signal Tuning Report</title>
                  <style>
                    body { margin:0; font-family: "Segoe UI", system-ui, sans-serif;
                           background:linear-gradient(180deg,#0d1722,#102536); color:#dfeaf3; }
                    main { max-width:1200px; margin:0 auto; padding:28px 18px 48px; }
                    h1 { margin:0 0 6px; font-size:32px; }
                    .sub { color:#96aec2; margin:0 0 20px; }
                    .cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:10px; margin-bottom:20px; }
                    .card { background:rgba(19,34,50,.92); border:1px solid #2b465f; border-radius:10px; padding:14px; }
                    .card strong { display:block; font-size:24px; }
                    .card span { color:#96aec2; font-size:12px; text-transform:uppercase; }
                    .panel { background:rgba(19,34,50,.92); border:1px solid #2b465f; border-radius:10px;
                             padding:16px; margin-bottom:18px; overflow:auto; }
                    h2 { margin:0 0 12px; font-size:18px; color:#5bb2ff; }
                    table { width:100%%; border-collapse:collapse; font-size:13px; }
                    th { text-align:left; padding:10px 8px; background:#17344a; border-bottom:1px solid #2b465f; }
                    td { padding:8px; border-bottom:1px solid rgba(43,70,95,.5); vertical-align:top; }
                    .crit { color:#ff6b6b; font-weight:600; }
                    .warn { color:#ffb347; font-weight:600; }
                    .info { color:#5bb2ff; }
                  </style>
                </head>
                <body>
                <main>
                  <h1>Signal Tuning Report</h1>
                  <p class="sub">%s — from reports/entry-signals</p>
                  <div class="cards">%s</div>
                  <section class="panel"><h2>Recommendations</h2>
                    <table><thead><tr><th>Severity</th><th>Area</th><th>Finding</th><th>Action</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>Strategy funnel</h2>
                    <table><thead><tr><th>Strategy</th><th>Evals</th><th>BUY</th><th>NO_TRADE</th><th>BUY %%</th><th>Top blocker</th><th>Near-miss score</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>BUY signals (45m forward + option chain)</h2>
                    <table><thead><tr><th>Time</th><th>Strategy</th><th>Signal</th><th>Und</th><th>BO</th><th>Vol</th><th>OI</th><th>Score</th><th>Stage</th><th>PCR</th><th>ATM</th><th>Max CE Δ</th><th>Max PE Δ</th><th>Chain</th><th>MFE%%</th><th>MAE%%</th><th>False BO</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>Entry execution stages</h2>
                    <table><thead><tr><th>Stage</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                </main>
                </body>
                </html>
                """;
        return html.formatted(
                periodLabel(report),
                cardsHtml(report),
                recommendationsHtml(report),
                strategiesHtml(report),
                buysHtml(report),
                stagesHtml(report)
        );
    }

    private static String periodLabel(SignalTuningAnalyzer.Report report) {
        String from = DISPLAY_DATE.format(report.periodFrom());
        String to = DISPLAY_DATE.format(report.periodTo());
        return from.equals(to) ? "Session " + from : "Period " + from + " → " + to;
    }

    private static String cardsHtml(SignalTuningAnalyzer.Report report) {
        return card(report.totalEvaluations(), "Evaluations")
                + card(report.buySignals(), "BUY signals")
                + card(report.noTradeSignals(), "NO_TRADE")
                + card(report.buysWithoutBreakoutFlag(), "BUY w/o breakout")
                + card(report.falseBreakoutLabeled(), "False breakouts")
                + card(report.chainOiMismatchCount(), "Chain OI mismatch")
                + card(report.recommendations().size(), "Recommendations");
    }

    private static String card(Object value, String label) {
        return "<div class=\"card\"><strong>" + escape(String.valueOf(value)) + "</strong><span>"
                + escape(label) + "</span></div>";
    }

    private static String recommendationsHtml(SignalTuningAnalyzer.Report report) {
        StringBuilder sb = new StringBuilder();
        for (SignalTuningAnalyzer.Recommendation rec : report.recommendations()) {
            String css = switch (rec.severity()) {
                case CRITICAL -> "crit";
                case WARN -> "warn";
                case INFO -> "info";
            };
            sb.append("<tr>")
                    .append(td(rec.severity().name(), css))
                    .append(td(rec.category()))
                    .append(td(rec.finding()))
                    .append(td(rec.action()))
                    .append("</tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='4'>No recommendations</td></tr>");
        }
        return sb.toString();
    }

    private static String strategiesHtml(SignalTuningAnalyzer.Report report) {
        StringBuilder sb = new StringBuilder();
        for (SignalTuningAnalyzer.StrategySummary s : report.strategies()) {
            String top = s.topBlockers().isEmpty() ? "—"
                    : s.topBlockers().get(0).filter() + " (" + String.format("%.0f%%", s.topBlockers().get(0).percent()) + ")";
            sb.append("<tr>")
                    .append(td(s.strategyType()))
                    .append(td(s.evaluations()))
                    .append(td(s.buySignals()))
                    .append(td(s.noTradeSignals()))
                    .append(td(String.format("%.3f", s.buyRatePercent())))
                    .append(td(top))
                    .append(td(s.avgNearMissScore() > 0 ? s.avgNearMissScore() : "—"))
                    .append("</tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='7'>No strategy data</td></tr>");
        }
        return sb.toString();
    }

    private static String buysHtml(SignalTuningAnalyzer.Report report) {
        StringBuilder sb = new StringBuilder();
        for (SignalTuningAnalyzer.BuyOutcome b : report.buyOutcomes()) {
            String rowClass = b.falseBreakout() || (b.chain() != null && b.chain().oiMismatch()) ? "warn" : null;
            SignalTuningChainSummarizer.ChainSummary c = b.chain();
            String pcr = c != null && c.present() ? c.pcr().toPlainString() : "—";
            String atm = c != null && c.present() ? String.valueOf(c.atmStrike()) : "—";
            String maxCe = c != null && c.present()
                    ? c.maxCallChangeStrike() + " (" + c.maxCallChange() + ")" : "—";
            String maxPe = c != null && c.present()
                    ? c.maxPutChangeStrike() + " (" + c.maxPutChange() + ")" : "—";
            String chainAlign = c != null && c.present() ? c.alignment() : "—";
            sb.append("<tr>")
                    .append(td(b.timestamp(), rowClass))
                    .append(td(b.strategyType(), rowClass))
                    .append(td(b.signalType(), rowClass))
                    .append(td(b.underlying(), rowClass))
                    .append(td(b.breakoutPassed() ? "Y" : "N", rowClass))
                    .append(td(b.volumeSpike() ? "Y" : "N", rowClass))
                    .append(td(b.oiPassed() ? "Y" : "N", rowClass))
                    .append(td(b.score(), rowClass))
                    .append(td(b.executionStage(), rowClass))
                    .append(td(pcr, rowClass))
                    .append(td(atm, rowClass))
                    .append(td(maxCe, rowClass))
                    .append(td(maxPe, rowClass))
                    .append(td(chainAlign, rowClass))
                    .append(td(b.mfePct().signum() > 0 ? b.mfePct() : "—", rowClass))
                    .append(td(b.maePct().signum() > 0 ? b.maePct() : "—", rowClass))
                    .append(td(b.falseBreakout() ? "YES" : "no", rowClass))
                    .append("</tr>");
        }
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='16'>No BUY signals in period</td></tr>");
        }
        return sb.toString();
    }

    private static String stagesHtml(SignalTuningAnalyzer.Report report) {
        StringBuilder sb = new StringBuilder();
        report.executionStages().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("<tr>").append(td(e.getKey())).append(td(e.getValue())).append("</tr>"));
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='2'>No execution outcomes CSV</td></tr>");
        }
        return sb.toString();
    }

    private static String td(Object value) {
        return td(value, null);
    }

    private static String td(Object value, String cssClass) {
        String cls = cssClass == null ? "" : " class='" + cssClass + "'";
        return "<td" + cls + ">" + escape(value == null ? "" : String.valueOf(value)) + "</td>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record TuningRunResult(
            Instant generatedAt,
            String htmlReportPath,
            long totalEvaluations,
            long buySignals,
            int recommendationCount,
            String telegramSummary
    ) {
    }
}
