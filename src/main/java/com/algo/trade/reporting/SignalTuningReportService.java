package com.algo.trade.reporting;

import com.algo.trade.notification.TelegramAlertService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
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
    private static final DateTimeFormatter IST_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Kolkata"));
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TelegramAlertService telegramAlertService;
    private final SignalTuningProperties tuningProperties;

    public SignalTuningReportService(TelegramAlertService telegramAlertService,
                                     SignalTuningProperties tuningProperties) {
        this.telegramAlertService = telegramAlertService;
        this.tuningProperties = tuningProperties;
    }

    public TuningRunResult generate() {
        assertSafeToGenerate();
        SignalTuningCsvLoader.Loaded data = SignalTuningCsvLoader.load(tuningProperties.isLoadForwardCandles());
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

    private void assertSafeToGenerate() {
        if (!tuningProperties.isBlockDuringMarketHours()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return;
        }
        LocalTime t = now.toLocalTime();
        if (!t.isBefore(LocalTime.of(9, 15)) && t.isBefore(LocalTime.of(15, 30))) {
            throw new IllegalArgumentException(
                    "Signal tuning is blocked during market hours (09:15–15:30 IST) — it loads large CSVs "
                            + "into JVM heap and may crash the application. Run after the session closes.");
        }
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
        if (report.buysWithoutBreakoutConfirmed() > 0) {
            sb.append("⚠️ DIRECTIONAL BUY w/o confirmation: ").append(report.buysWithoutBreakoutConfirmed())
                    .append(System.lineSeparator());
        }
        if (report.oiSpikeBurstDuplicates() > 0) {
            sb.append("⚠️ OI SPIKE duplicate rows (same und, 60s): ").append(report.oiSpikeBurstDuplicates())
                    .append(System.lineSeparator());
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

        OiMomentumTuningAnalyzer.OiReport oi = report.oiMomentumReport();
        OiShiftTrapTuningAnalyzer.TrapReport trap = report.oiShiftTrapReport();
        if (trap.evaluationSamples() > 0 || trap.nearMissRows() > 0 || trap.trapSignalRows() > 0) {
            sb.append(System.lineSeparator()).append("OI Shift Trap:").append(System.lineSeparator());
            sb.append("Eval samples: ").append(trap.evaluationSamples())
                    .append(" | Near-miss: ").append(trap.nearMissRows())
                    .append(" | Signals: ").append(trap.trapSignalRows()).append(System.lineSeparator());
            trap.blockersByGate().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> sb.append("• blocker ").append(e.getKey()).append(": ").append(e.getValue())
                            .append(System.lineSeparator()));
        }

        if (oi.signalCount() > 0 || oi.rejectSampleCount() > 0) {
            sb.append(System.lineSeparator()).append("OI Momentum:").append(System.lineSeparator());
            sb.append("Signals: ").append(oi.signalCount())
                    .append(" | Reject samples: ").append(oi.rejectSampleCount())
                    .append(" | Spike dupes: ").append(oi.spikeDuplicates()).append(System.lineSeparator());
            int caseLines = 0;
            for (var e : oi.statsByCase().entrySet()) {
                if (caseLines >= 4) {
                    sb.append("… +").append(oi.statsByCase().size() - 4).append(" cases in HTML")
                            .append(System.lineSeparator());
                    break;
                }
                OiMomentumTuningAnalyzer.CaseStats stats = e.getValue();
                sb.append("• ").append(e.getKey()).append(": ").append(stats.count()).append(" sig");
                if (stats.closed() > 0) {
                    sb.append(", ").append(stats.closed()).append(" closed, win ")
                            .append(stats.winRatePct()).append("%");
                }
                sb.append(System.lineSeparator());
                caseLines++;
            }
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
                    <table><thead><tr><th>Time</th><th>Strategy</th><th>Signal</th><th>Und</th><th>Swing</th><th>Confirm</th><th>Case</th><th>Vol</th><th>OI</th><th>Score</th><th>Stage</th><th>PCR</th><th>ATM</th><th>Max CE Δ</th><th>Max PE Δ</th><th>Chain</th><th>MFE%%</th><th>MAE%%</th><th>False BO</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>OI Shift Trap (dedicated CSV)</h2>
                    <p class="sub">From oi-shift-trap-evaluations.csv — why no trap matched (30s sample per index)</p>
                    <table><thead><tr><th>Blocker</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Near-miss strikes (score 40–49)</h2>
                    <table><thead><tr><th>Time</th><th>Und</th><th>Side</th><th>Strike</th><th>Score</th><th>Gate</th><th>Imbalance</th><th>Prox %%</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">MAE drawdown (exits CSV)</h2>
                    <table><thead><tr><th>Metric</th><th>P25</th><th>P50</th><th>P75</th><th>P90</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Per-score bucket (by index)</h2>
                    <table><thead><tr><th>Index</th><th>Band</th><th>Signals</th><th>Closed</th><th>Win %%</th><th>Avg MAE</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Confirmation gate effectiveness (top 10)</h2>
                    <table><thead><tr><th>Gates</th><th>Retained</th><th>Ret %%</th><th>Avg MAE kept</th><th>Avg PnL kept</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Late-entry simulation</h2>
                    <table><thead><tr><th>Decision</th><th>Checkpoint</th><th>Actual MAE</th><th>Spot move</th><th>OI delta</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Avg slippage by score band</h2>
                    <table><thead><tr><th>Score band</th><th>Avg slippage %%</th><th>Avg fill %%</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>OI Momentum (dedicated CSV)</h2>
                    <p class="sub">From oi-momentum-signals.csv — joined to execution outcomes and exits</p>
                    <table><thead><tr><th>Case</th><th>Signals</th><th>Closed</th><th>Win %%</th><th>Avg PnL</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Signal vs fill (by matrixCase)</h2>
                    <table><thead><tr><th>Case</th><th>Signals</th><th>Filled</th><th>Partial</th><th>Open</th><th>Not filled</th><th>Closed</th><th>Win %%</th><th>Avg PnL</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Per-index case stats</h2>
                    <table><thead><tr><th>Index</th><th>Case</th><th>Signals</th><th>Closed</th><th>Win %%</th><th>Avg PnL</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Daily rollup</h2>
                    <table><thead><tr><th>Date</th><th>Signals</th><th>Filled</th><th>Closed</th><th>Win %%</th><th>Net PnL</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Exit reason × case</h2>
                    <table><thead><tr><th>Exit reason</th><th>Case</th><th>Signals</th><th>Closed</th><th>Win %%</th><th>Avg PnL</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">V3 vs legacy concordance (±2s)</h2>
                    <table><thead><tr><th>V3 verdict</th><th>Legacy decision</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Bias score histogram (rejects)</h2>
                    <table><thead><tr><th>Band</th><th>Rejects</th><th>With fwd30m</th><th>Avg spot move %% @30m</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Avg slippage by case</h2>
                    <table><thead><tr><th>Case</th><th>Avg slippage %%</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">Reject funnel (normalized reason)</h2>
                    <table><thead><tr><th>Reason</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">V3 operator (data/v3-decisions)</h2>
                    <table><thead><tr><th>Verdict / gate</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table>
                    <h2 style="margin-top:16px">OI trades</h2>
                    <table><thead><tr><th>Time</th><th>Case</th><th>Path</th><th>Und</th><th>Opt</th><th>Premium</th><th>Fill</th><th>Slip %%</th><th>Partial</th><th>PCR</th><th>Mom</th><th>OI dir</th><th>Stage</th><th>Exit</th><th>PnL</th><th>Hold</th><th>Chain</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>Entry execution stages</h2>
                    <table><thead><tr><th>Stage</th><th>Count</th></tr></thead>
                    <tbody>%s</tbody></table></section>
                  <section class="panel"><h2>Data quality</h2>
                    <p class="sub">%s</p>
                  </section>
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
                trapBlockersHtml(report),
                trapNearMissHtml(report),
                trapDrawdownHtml(report),
                trapScoreBucketsHtml(report),
                trapConfirmationHtml(report),
                trapLateEntryHtml(report),
                trapSlippageHtml(report),
                oiCaseStatsHtml(report),
                oiSignalFillHtml(report),
                oiPerIndexCaseStatsHtml(report),
                oiDailyRollupHtml(report),
                oiExitReasonBreakdownHtml(report),
                oiConcordanceHtml(report),
                oiBiasHistogramHtml(report),
                oiSlippageByCaseHtml(report),
                oiRejectBreakdownHtml(report),
                v3SummaryHtml(report),
                oiTradesHtml(report),
                stagesHtml(report),
                dataQualityFooter(report)
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
                + card(report.buysWithoutBreakoutConfirmed(), "BUY w/o confirm")
                + card(report.oiSpikeBurstDuplicates(), "OI SPIKE dupes (per und)")
                + card(report.falseBreakoutLabeled(), "False breakouts")
                + card(report.chainOiMismatchCount(), "Chain OI mismatch")
                + card(report.oiMomentumReport().signalCount(), "OI signals")
                + card(report.oiMomentumReport().rejectSampleCount(), "OI reject samples")
                + card(report.oiMomentumReport().v3Summary().decisionCount(), "V3 evaluations")
                + card(report.oiShiftTrapReport().evaluationSamples(), "Trap eval samples")
                + card(report.oiShiftTrapReport().nearMissRows(), "Trap near-miss")
                + card(report.oiShiftTrapReport().exitRows(), "Trap exits")
                + card(report.recommendations().size(), "Recommendations");
    }

    private static String trapBlockersHtml(SignalTuningAnalyzer.Report report) {
        OiShiftTrapTuningAnalyzer.TrapReport trap = report.oiShiftTrapReport();
        if (trap.evaluationSamples() == 0) {
            return "<tr><td colspan='2'>No oi-shift-trap-evaluations.csv yet (enable OI_SHIFT_TRAP scans)</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        trap.blockersByGate().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("<tr>").append(td(e.getKey())).append(td(e.getValue())).append("</tr>"));
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='2'>No blocker data</td></tr>");
        }
        return sb.toString();
    }

    private static String trapNearMissHtml(SignalTuningAnalyzer.Report report) {
        List<SignalTuningCsvLoader.OiShiftTrapNearMissRow> rows = report.oiShiftTrapReport().nearMisses();
        if (rows.isEmpty()) {
            return "<tr><td colspan='8'>No near-miss rows (strikes scoring 40–49)</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        rows.stream()
                .sorted(Comparator.comparing(SignalTuningCsvLoader.OiShiftTrapNearMissRow::timestamp).reversed())
                .limit(40)
                .forEach(r -> sb.append("<tr>")
                        .append(td(r.timestamp()))
                        .append(td(r.underlying()))
                        .append(td(r.side()))
                        .append(td(r.strike()))
                        .append(td(r.score()))
                        .append(td(r.failedGate()))
                        .append(td(String.format("%.1f", r.imbalance())))
                        .append(td(String.format("%.2f", r.proximityPct())))
                        .append("</tr>"));
        return sb.toString();
    }

    private static String trapDrawdownHtml(SignalTuningAnalyzer.Report report) {
        OiShiftTrapTuningAnalyzer.DrawdownStats dd = report.oiShiftTrapReport().drawdownStats();
        if (report.oiShiftTrapReport().exitRows() == 0) {
            return "<tr><td colspan='5'>No oi-shift-trap-exits.csv yet</td></tr>";
        }
        OiShiftTrapTuningAnalyzer.Percentiles p = dd.overall();
        return "<tr><td>All trades</td>"
                + td(String.format("%.1f", p.p25())) + td(String.format("%.1f", p.p50()))
                + td(String.format("%.1f", p.p75())) + td(String.format("%.1f", p.p90())) + "</tr>";
    }

    private static String trapScoreBucketsHtml(SignalTuningAnalyzer.Report report) {
        Map<String, List<OiShiftTrapTuningAnalyzer.BucketStats>> buckets =
                report.oiShiftTrapReport().scoreBucketsByIndex();
        if (buckets.isEmpty()) {
            return "<tr><td colspan='6'>No trap signals with score bands</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        buckets.forEach((index, list) -> list.forEach(b -> sb.append("<tr>")
                .append(td(index)).append(td(b.band())).append(td(b.signals()))
                .append(td(b.closed())).append(td(String.format("%.0f", b.winPct())))
                .append(td(String.format("%.1f", b.avgMae()))).append("</tr>")));
        return sb.toString();
    }

    private static String trapConfirmationHtml(SignalTuningAnalyzer.Report report) {
        List<OiShiftTrapTuningAnalyzer.ConfirmationComboStats> combos =
                report.oiShiftTrapReport().confirmationCombos();
        if (combos.isEmpty()) {
            return "<tr><td colspan='5'>No confirmation shadow data yet</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        combos.forEach(c -> sb.append("<tr>")
                .append(td(c.gates())).append(td(c.retained()))
                .append(td(String.format("%.0f", c.retainedPct())))
                .append(td(String.format("%.1f", c.avgMaeRetained())))
                .append(td(String.format("%.0f", c.avgPnlRetained())))
                .append("</tr>"));
        return sb.toString();
    }

    private static String trapLateEntryHtml(SignalTuningAnalyzer.Report report) {
        List<OiShiftTrapTuningAnalyzer.LateEntrySimRow> rows =
                report.oiShiftTrapReport().lateEntrySimulation();
        if (rows.isEmpty()) {
            return "<tr><td colspan='5'>No forward checkpoint data for late-entry sim</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        rows.stream().limit(20).forEach(r -> sb.append("<tr>")
                .append(td(r.decisionKey())).append(td(r.checkpoint()))
                .append(td(String.format("%.1f", r.actualMae())))
                .append(td(String.format("%.2f", r.spotMoveAtCp())))
                .append(td(String.format("%.0f", r.oiDeltaAtCp())))
                .append("</tr>"));
        return sb.toString();
    }

    private static String trapSlippageHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Double> slip = report.oiShiftTrapReport().slippageByScoreBand();
        Map<String, Double> fill = report.oiShiftTrapReport().fillRatioByScoreBand();
        if (slip.isEmpty() && fill.isEmpty()) {
            return "<tr><td colspan='3'>No slippage data (need signalPremium + fills)</td></tr>";
        }
        java.util.Set<String> bands = new java.util.TreeSet<>();
        bands.addAll(slip.keySet());
        bands.addAll(fill.keySet());
        StringBuilder sb = new StringBuilder();
        for (String band : bands) {
            sb.append("<tr>").append(td(band))
                    .append(td(slip.containsKey(band) ? String.format("%.2f", slip.get(band)) : "—"))
                    .append(td(fill.containsKey(band) ? String.format("%.0f", fill.get(band)) : "—"))
                    .append("</tr>");
        }
        return sb.toString();
    }

    private static String oiCaseStatsHtml(SignalTuningAnalyzer.Report report) {
        OiMomentumTuningAnalyzer.OiReport oi = report.oiMomentumReport();
        if (oi.signalCount() == 0 && oi.rejectSampleCount() == 0) {
            return "<tr><td colspan='5'>No oi-momentum-*.csv data (run OI_MOMENTUM during market hours)</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        oi.statsByCase().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    OiMomentumTuningAnalyzer.CaseStats stats = e.getValue();
                    sb.append("<tr>")
                            .append(td(e.getKey()))
                            .append(td(stats.count()))
                            .append(td(stats.closed()))
                            .append(td(stats.closed() > 0 ? stats.winRatePct() + "%" : "—"))
                            .append(td(stats.closed() > 0 ? stats.avgPnl().setScale(2, RoundingMode.HALF_UP).toPlainString() : "—"))
                            .append("</tr>");
                });
        if (sb.isEmpty()) {
            sb.append("<tr><td colspan='5'>No entry cases recorded</td></tr>");
        }
        return sb.toString();
    }

    private static String oiSignalFillHtml(SignalTuningAnalyzer.Report report) {
        Map<String, OiMomentumTuningAnalyzer.SignalFillStats> byCase =
                report.oiMomentumReport().signalFillByCase();
        if (byCase.isEmpty()) {
            return "<tr><td colspan='9'>No OI momentum signals</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        byCase.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    OiMomentumTuningAnalyzer.SignalFillStats s = e.getValue();
                    sb.append("<tr>")
                            .append(td(e.getKey()))
                            .append(td(s.signals()))
                            .append(td(s.filled()))
                            .append(td(s.partialFills() > 0 ? s.partialFills() : "—"))
                            .append(td(s.openNoFill()))
                            .append(td(s.notFilled()))
                            .append(td(s.closed()))
                            .append(td(s.closed() > 0 ? s.winRatePct() + "%" : "—"))
                            .append(td(s.closed() > 0
                                    ? s.avgPnl().setScale(2, RoundingMode.HALF_UP).toPlainString() : "—"))
                            .append("</tr>");
                });
        return sb.toString();
    }

    private static String oiPerIndexCaseStatsHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Map<String, OiMomentumTuningAnalyzer.CaseStats>> byIndex =
                report.oiMomentumReport().statsByCaseByIndex();
        if (byIndex.isEmpty()) {
            return "<tr><td colspan='6'>No per-index case data</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        byIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(indexEntry -> indexEntry.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(caseEntry -> {
                            OiMomentumTuningAnalyzer.CaseStats stats = caseEntry.getValue();
                            sb.append("<tr>")
                                    .append(td(indexEntry.getKey()))
                                    .append(td(caseEntry.getKey()))
                                    .append(td(stats.count()))
                                    .append(td(stats.closed()))
                                    .append(td(stats.closed() > 0 ? stats.winRatePct() + "%" : "—"))
                                    .append(td(stats.closed() > 0
                                            ? stats.avgPnl().setScale(2, RoundingMode.HALF_UP).toPlainString() : "—"))
                                    .append("</tr>");
                        }));
        return sb.toString();
    }

    private static String oiDailyRollupHtml(SignalTuningAnalyzer.Report report) {
        Map<java.time.LocalDate, OiMomentumTuningAnalyzer.DailyStats> daily =
                report.oiMomentumReport().dailyStats();
        if (daily.isEmpty()) {
            return "<tr><td colspan='6'>No daily rollup</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        daily.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    OiMomentumTuningAnalyzer.DailyStats s = e.getValue();
                    sb.append("<tr>")
                            .append(td(e.getKey()))
                            .append(td(s.signals()))
                            .append(td(s.filled()))
                            .append(td(s.closed()))
                            .append(td(s.closed() > 0 ? s.winRatePct() + "%" : "—"))
                            .append(td(s.closed() > 0 ? formatInr(s.netPnl()) : "—"))
                            .append("</tr>");
                });
        return sb.toString();
    }

    private static String oiExitReasonBreakdownHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Map<String, OiMomentumTuningAnalyzer.CaseStats>> byExit =
                report.oiMomentumReport().statsByCaseAndExitReason();
        if (byExit.isEmpty()) {
            return "<tr><td colspan='6'>No exit-reason breakdown</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        byExit.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(exitEntry -> exitEntry.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(caseEntry -> {
                            OiMomentumTuningAnalyzer.CaseStats stats = caseEntry.getValue();
                            sb.append("<tr>")
                                    .append(td(exitEntry.getKey()))
                                    .append(td(caseEntry.getKey()))
                                    .append(td(stats.count()))
                                    .append(td(stats.closed()))
                                    .append(td(stats.closed() > 0 ? stats.winRatePct() + "%" : "—"))
                                    .append(td(stats.closed() > 0
                                            ? stats.avgPnl().setScale(2, RoundingMode.HALF_UP).toPlainString() : "—"))
                                    .append("</tr>");
                        }));
        return sb.toString();
    }

    private static String oiConcordanceHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Map<String, Long>> matrix = report.oiMomentumReport().concordanceMatrix();
        if (matrix.isEmpty()) {
            return "<tr><td colspan='3'>No V3 or legacy detection data for concordance</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        matrix.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(v3Entry -> v3Entry.getValue().entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .forEach(legacyEntry -> sb.append("<tr>")
                                .append(td(v3Entry.getKey()))
                                .append(td(legacyEntry.getKey()))
                                .append(td(legacyEntry.getValue()))
                                .append("</tr>")));
        return sb.toString();
    }

    private static String oiBiasHistogramHtml(SignalTuningAnalyzer.Report report) {
        Map<String, OiMomentumTuningAnalyzer.BiasBandStats> bands = report.oiMomentumReport().biasHistogram();
        if (bands.isEmpty()) {
            return "<tr><td colspan='4'>No reject bias scores</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        for (String band : List.of("0-20", "20-30", "30-40", "40-50")) {
            OiMomentumTuningAnalyzer.BiasBandStats stats = bands.get(band);
            if (stats == null || stats.count() == 0) {
                sb.append("<tr>").append(td(band)).append(td(0)).append(td("—")).append(td("—")).append("</tr>");
                continue;
            }
            Double avgMove = stats.avgFwdSpot30mMovePct();
            sb.append("<tr>")
                    .append(td(band))
                    .append(td(stats.count()))
                    .append(td(stats.fwdCount()))
                    .append(td(avgMove != null ? String.format("%.2f", avgMove) : "—"))
                    .append("</tr>");
        }
        return sb.toString();
    }

    private static String oiSlippageByCaseHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Double> slippage = report.oiMomentumReport().avgSlippageByCase();
        if (slippage.isEmpty()) {
            return "<tr><td colspan='2'>No fill slippage data (needs premium + averageFillPrice)</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        slippage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("<tr>")
                        .append(td(e.getKey()))
                        .append(td(String.format("%.2f", e.getValue())))
                        .append("</tr>"));
        return sb.toString();
    }

    private static String oiRejectBreakdownHtml(SignalTuningAnalyzer.Report report) {
        Map<String, Long> counts = report.oiMomentumReport().rejectReasonCounts();
        if (counts.isEmpty()) {
            return "<tr><td colspan='2'>No oi-momentum-rejects.csv rows</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> sb.append("<tr>").append(td(e.getKey())).append(td(e.getValue())).append("</tr>"));
        return sb.toString();
    }

    private static String v3SummaryHtml(SignalTuningAnalyzer.Report report) {
        OiMomentumTuningAnalyzer.V3Summary v3 = report.oiMomentumReport().v3Summary();
        if (v3.decisionCount() == 0) {
            return "<tr><td colspan='2'>No data/v3-decisions/*.csv (enable oi-momentum.v3.decision-log-enabled)</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<tr>").append(td("ENTER")).append(td(v3.enterCount())).append("</tr>");
        v3.verdictCounts().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> sb.append("<tr>").append(td(e.getKey())).append(td(e.getValue())).append("</tr>"));
        v3.gateFailReasons().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .forEach(e -> sb.append("<tr>").append(td(e.getKey())).append(td(e.getValue())).append("</tr>"));
        return sb.toString();
    }

    private static String oiTradesHtml(SignalTuningAnalyzer.Report report) {
        List<OiMomentumTuningAnalyzer.OiTradeOutcome> trades = report.oiMomentumReport().trades();
        if (trades.isEmpty()) {
            return "<tr><td colspan='17'>No OI momentum signals in period</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        for (OiMomentumTuningAnalyzer.OiTradeOutcome t : trades) {
            var s = t.signal();
            SignalTuningChainSummarizer.ChainSummary c = t.chain();
            String chain = c != null && c.present() ? c.shortSummary() : "—";
            String pnl = formatInr(t.realizedPnl());
            String fillRatio = t.fillRatio() != null ? t.fillRatio().toPlainString() : "—";
            String slippage = t.slippagePct() != null ? t.slippagePct().toPlainString() : "—";
            String rowClass = "ORDER_OPEN".equals(t.executionStage()) ? "warn"
                    : t.partialFill() ? "warn" : null;
            sb.append("<tr>")
                    .append(td(IST_TS.format(s.timestamp()), rowClass))
                    .append(td(s.entryCase(), rowClass))
                    .append(td(s.entryPath() != null && !s.entryPath().isBlank() ? s.entryPath() : "—", rowClass))
                    .append(td(s.underlying(), rowClass))
                    .append(td(s.optionType(), rowClass))
                    .append(td(s.premium() != null ? s.premium() : "—", rowClass))
                    .append(td(fillRatio, rowClass))
                    .append(td(slippage, rowClass))
                    .append(td(t.partialFill() ? "YES" : "no", rowClass))
                    .append(td(String.format("%.2f", s.pcr()), rowClass))
                    .append(td(s.momentumType() + " " + s.momentumDir(), rowClass))
                    .append(td(s.oiDir(), rowClass))
                    .append(td(t.executionStage(), rowClass))
                    .append(td(t.exitReason().isBlank() ? "—" : t.exitReason(), rowClass))
                    .append(td(pnl, rowClass))
                    .append(td(t.holdSeconds() > 0 ? t.holdSeconds() + "s" : "—", rowClass))
                    .append(td(chain, rowClass))
                    .append("</tr>");
        }
        return sb.toString();
    }

    private static String breakoutCell(SignalTuningAnalyzer.BuyOutcome b) {
        if (!"DIRECTIONAL_BUY".equals(b.strategyType())) {
            return "—";
        }
        return b.breakoutPassed() ? "Y" : "N";
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
            String oiCase = b.oiEntryCase() == null || b.oiEntryCase().isBlank() ? "—" : b.oiEntryCase();
            sb.append("<tr>")
                    .append(td(b.timestamp(), rowClass))
                    .append(td(b.strategyType(), rowClass))
                    .append(td(b.signalType(), rowClass))
                    .append(td(b.underlying(), rowClass))
                    .append(td(breakoutCell(b), rowClass))
                    .append(td(b.breakoutConfirmed() ? "Y" : "N", rowClass))
                    .append(td(oiCase, rowClass))
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
            sb.append("<tr><td colspan='19'>No BUY signals in period</td></tr>");
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

    private static String formatInr(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return "₹" + v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Short notes so operators interpret cards and CSV joins correctly. */
    private static String dataQualityFooter(SignalTuningAnalyzer.Report report) {
        String text = "BUY table: times shown in Asia/Kolkata. OI dedicated CSV: join via decisionKey; "
                + "OI SPIKE duplicate count = extra SPIKE BUY rows within 60s on the same underlying (not cross-index). "
                + "entry-signals merges all strategies; trap/OI-specific CSVs improve funnel accuracy. "
                + "Loader merges active reports/entry-signals/ plus the 3 newest reports/archive/*.zip. "
                + "Merged signal rows in this report: " + report.totalEvaluations() + ".";
        return escape(text);
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
