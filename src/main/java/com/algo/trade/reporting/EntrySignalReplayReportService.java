package com.algo.trade.reporting;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EntrySignalReplayReportService {

    private static final Logger log = LoggerFactory.getLogger(EntrySignalReplayReportService.class);
    private static final Path OUTPUT_DIR = Path.of("reports", "replay");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final TradingProperties properties;
    private final StrategyConfigService strategyConfigService;

    public EntrySignalReplayReportService(TradingProperties properties, StrategyConfigService strategyConfigService) {
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
    }

    public ReplayRunResult generate() {
        StrategyConfig dc = strategyConfigService.getDirectionalBuyConfig();
        EntrySignalReplayAnalyzer analyzer = new EntrySignalReplayAnalyzer(new EntrySignalReplayAnalyzer.Config(
                properties.risk().totalCapital(),
                properties.risk().maxRiskPerTradePercent(),
                dc.getStopLossPercent(),
                dc.getTargetPercent(),
                dc.getTrailingStopActivationPercent(),
                dc.getTrailingGapPercent(),
                properties.exit().forcedExitTime(),
                dc.getMaxHoldMinutes(),
                properties.risk().maxOpenTrades(),
                65,
                35
        ));
        EntrySignalReplayAnalyzer.Summary summary = analyzer.analyze();
        Path htmlPath = writeHtml(summary);
        log.info("Entry signal replay generated: evaluations={}, accepted={}, executedTrades={}, totalPnl={}, htmlPath={}",
                summary.totalEvaluations(), summary.acceptedByFilters(), summary.executedTrades(), summary.totalPnl(), htmlPath);
        return new ReplayRunResult(
                Instant.now(),
                properties.risk().totalCapital(),
                properties.risk().maxRiskPerTradePercent(),
                dc.getStopLossPercent(),
                dc.getTargetPercent(),
                dc.getTrailingStopActivationPercent(),
                dc.getTrailingGapPercent(),
                properties.exit().forcedExitTime(),
                dc.getMaxHoldMinutes(),
                properties.risk().maxOpenTrades(),
                htmlPath.toString(),
                summary
        );
    }

    public Optional<Path> latestHtmlReport() {
        if (!Files.isDirectory(OUTPUT_DIR)) {
            return Optional.empty();
        }
        try {
            return Files.list(OUTPUT_DIR)
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .max(Comparator.comparing(Path::getFileName));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list replay reports", ex);
        }
    }

    public String readHtml(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read replay report: " + path, ex);
        }
    }

    private Path writeHtml(EntrySignalReplayAnalyzer.Summary summary) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            Path path = OUTPUT_DIR.resolve("entry-signal-replay-" + FILE_TS.format(Instant.now()) + ".html");
            Files.writeString(path, html(summary), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return path;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write replay HTML report", ex);
        }
    }

    private String html(EntrySignalReplayAnalyzer.Summary summary) {
        StringBuilder trades = new StringBuilder();
        for (EntrySignalReplayAnalyzer.Trade trade : summary.trades()) {
            trades.append("<tr>")
                    .append(td(trade.decisionKey()))
                    .append(td(trade.instrument()))
                    .append(td(trade.optionType()))
                    .append(td(trade.quantity()))
                    .append(td(trade.entryTime()))
                    .append(td(trade.entryPrice()))
                    .append(td(trade.exitTime()))
                    .append(td(trade.exitPrice()))
                    .append(td(trade.pnl(), trade.pnl().signum() >= 0 ? "pos" : "neg"))
                    .append(td(trade.exitReason()))
                    .append("</tr>");
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Entry Signal Replay Report</title>
                  <style>
                    :root { --bg:#0d1722; --panel:#132232; --line:#2b465f; --text:#dfeaf3; --muted:#96aec2; --accent:#5bb2ff; --pos:#55d28c; --neg:#ff7b7b; }
                    body { margin:0; font-family: Georgia, "Segoe UI", sans-serif; background:linear-gradient(180deg,#0d1722,#102536); color:var(--text); }
                    main { max-width:1200px; margin:0 auto; padding:32px 20px 48px; }
                    h1 { margin:0 0 8px; font-size:34px; }
                    p { color:var(--muted); margin:0; }
                    .cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,1fr)); gap:12px; margin:24px 0; }
                    .card, .panel { background:rgba(19,34,50,.92); border:1px solid var(--line); border-radius:12px; box-shadow:0 14px 32px rgba(0,0,0,.22); }
                    .card { padding:16px; }
                    .card strong { display:block; font-size:28px; margin-bottom:4px; }
                    .card span { color:var(--muted); font-size:13px; text-transform:uppercase; letter-spacing:.08em; }
                    .panel { padding:18px; margin-top:18px; overflow:auto; }
                    table { width:100%; border-collapse:collapse; font-size:14px; }
                    th { text-align:left; padding:12px 10px; background:linear-gradient(90deg,#17344a,#234764); color:#f6fbff; border-bottom:1px solid var(--line); }
                    td { padding:10px; border-bottom:1px solid rgba(43,70,95,.65); vertical-align:top; }
                    .pos { color:var(--pos); font-weight:700; }
                    .neg { color:var(--neg); font-weight:700; }
                  </style>
                </head>
                <body>
                <main>
                  <h1>Entry Signal Replay Report</h1>
                  <p>Current-config replay using stricter entry filters and current risk/exit settings.</p>
                  <div class="cards">
                    %s
                  </div>
                  <section class="panel">
                    <h2>Executed Trades</h2>
                    <table>
                      <thead>
                        <tr>
                          <th>Decision</th><th>Instrument</th><th>Type</th><th>Qty</th><th>Entry Time</th><th>Entry</th><th>Exit Time</th><th>Exit</th><th>PnL</th><th>Exit Reason</th>
                        </tr>
                      </thead>
                      <tbody>%s</tbody>
                    </table>
                  </section>
                </main>
                </body>
                </html>
                """.replace("%", "%%").replace("%%s", "%s").formatted(cards(summary), trades);
    }

    private String cards(EntrySignalReplayAnalyzer.Summary summary) {
        return card(summary.totalEvaluations(), "Total Evaluations")
                + card(summary.acceptedByFilters(), "Accepted By Filters")
                + card(summary.executedTrades(), "Executed Trades")
                + card(summary.winningTrades(), "Winning Trades")
                + card(summary.losingTrades(), "Losing Trades")
                + card(summary.totalPnl(), "Total PnL")
                + card(summary.blockedByBreakoutConfirmation(), "Breakout Blocks")
                + card(summary.blockedByOiSupport(), "OI Blocks")
                + card(summary.blockedByHeadroom(), "Headroom Blocks");
    }

    private String card(Object value, String label) {
        return "<div class='card'><strong>" + escape(String.valueOf(value)) + "</strong><span>" + escape(label) + "</span></div>";
    }

    private String td(Object value) {
        return td(value, null);
    }

    private String td(Object value, String css) {
        String classAttr = css == null ? "" : " class='" + css + "'";
        return "<td" + classAttr + ">" + escape(value == null ? "" : String.valueOf(value)) + "</td>";
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record ReplayRunResult(
            Instant generatedAt,
            BigDecimal totalCapital,
            BigDecimal maxRiskPerTradePercent,
            BigDecimal stopLossPercent,
            BigDecimal targetPercent,
            BigDecimal trailingStopActivationPercent,
            BigDecimal trailingGapPercent,
            LocalTime forcedExitTime,
            int maxHoldMinutes,
            int maxOpenTrades,
            String htmlReportPath,
            EntrySignalReplayAnalyzer.Summary summary
    ) {
    }
}
