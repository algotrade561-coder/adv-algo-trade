package com.algo.trade.reporting;

import com.algo.trade.backtest.*;
import com.algo.trade.backtest.ExecutionFlowTracker.BranchStatus;
import com.algo.trade.backtest.ExecutionFlowTracker.ExecutionFlowCoverage;
import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.IstTimeConfiguration;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates HTML and JSON reports for a verify-all run.
 */
@Component
public class VerifyAllReportGeneratorImpl implements VerifyAllReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(VerifyAllReportGeneratorImpl.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST);

    private static final ObjectMapper JSON_MAPPER = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        SimpleModule pathModule = new SimpleModule("PathModule");
        pathModule.addSerializer(Path.class, new StdSerializer<>(Path.class) {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value == null ? null : value.toString().replace('\\', '/'));
            }
        });

        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(IstTimeConfiguration.istModule())
                .registerModule(pathModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }


    // ── HTML Report ───────────────────────────────────────────────────────

    @Override
    public Path generateHtmlReport(VerifyAllResult result, Path outputDirectory) {
        Path reportPath = outputDirectory.resolve("verify-report.html");
        try {
            Files.createDirectories(outputDirectory);
            StringBuilder html = new StringBuilder(16_384);
            html.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
            html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            html.append("<title>Verify-All Report ").append(esc(result.verifyId())).append("</title>");
            appendStyles(html);
            html.append("</head><body>");

            appendSummarySection(html, result);
            appendStrategyRanking(html, result.strategyResults());
            appendStrategyComparisonTable(html, result.strategyResults());
            appendGuardImpactAnalysis(html, result.strategyResults(), result.flowCoverage());
            appendMonthlyPnlHeatmap(html, result.strategyResults());
            appendStrategyDetails(html, result.strategyResults());
            appendFlowCoverage(html, result.flowCoverage());
            appendConfigSnapshot(html, result.configSnapshot());

            html.append("</body></html>");
            Files.writeString(reportPath, html.toString());
            log.info("Verify-all HTML report written to {}", reportPath);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write verify-all HTML report", ex);
        }
        return reportPath;
    }

    // ── Styles ────────────────────────────────────────────────────────────

    private void appendStyles(StringBuilder html) {
        html.append("<style>");
        html.append("body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#f8fafc;color:#1f2937}");
        html.append("h1{font-size:1.4rem;margin:0 0 4px}");
        html.append("h2{font-size:1.1rem;margin:28px 0 10px;color:#4b5563;border-bottom:1px solid #e5e7eb;padding-bottom:6px}");
        html.append("h3{font-size:.95rem;margin:18px 0 6px;color:#374151}");
        html.append(".meta{font-size:.82rem;color:#6b7280;margin-bottom:20px}");
        html.append(".cards{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px}");
        html.append(".card{background:white;border-radius:8px;padding:14px 18px;box-shadow:0 1px 3px rgba(0,0,0,.08);min-width:120px}");
        html.append(".label{font-size:.75rem;color:#6b7280;margin-bottom:2px}");
        html.append(".value{font-size:1.15rem;font-weight:650}");
        html.append(".green{color:#166534}.red{color:#b91c1c}");
        html.append(".strategy-card{background:white;border-radius:8px;padding:16px 20px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}");
        html.append(".strategy-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}");
        html.append(".status-ok{color:#166534;font-weight:600}.status-error{color:#b91c1c;font-weight:600}.status-nodata{color:#92400e;font-weight:600}.status-skipped{color:#6b7280;font-weight:600}");
        html.append(".metrics-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:8px;margin:8px 0}");
        html.append(".metric{font-size:.82rem}.metric .mk{color:#6b7280}.metric .mv{font-weight:600}");
        html.append("table{width:100%;border-collapse:collapse;background:white;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08);margin:8px 0}");
        html.append("th{background:#eef2f7;padding:7px 10px;text-align:left;font-size:.76rem;color:#4b5563;white-space:nowrap}");
        html.append("td{padding:6px 10px;font-size:.8rem;border-top:1px solid #edf2f7;white-space:nowrap}");
        html.append(".flow-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:8px;margin:8px 0}");
        html.append(".flow-item{display:flex;align-items:center;gap:8px;font-size:.82rem;padding:4px 8px;background:white;border-radius:6px;box-shadow:0 1px 2px rgba(0,0,0,.05)}");
        html.append(".flow-hit{color:#166534}.flow-miss{color:#b91c1c}");
        html.append(".cfg-section{background:white;border-radius:8px;padding:14px 18px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.08)}");
        html.append(".cfg-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:4px 16px;font-size:.82rem}");
        html.append(".cfg-item .ck{color:#6b7280}.cfg-item .cv{font-weight:500}");
        html.append("</style>");
    }


    // ── Summary Section ───────────────────────────────────────────────────

    private void appendSummarySection(StringBuilder html, VerifyAllResult result) {
        html.append("<h1>Verify-All Report</h1>");
        html.append("<div class='meta'>ID: ").append(esc(result.verifyId()));
        html.append(" | Underlying: ").append(result.underlying());
        html.append(" | Data: ").append(result.from()).append(" → ").append(result.to());
        html.append(" | Generated: ").append(IST_FMT.format(result.completedAt())).append("</div>");

        List<StrategyVerificationResult> results = result.strategyResults();
        int totalStrategies = results.size();
        int totalTrades = results.stream()
                .filter(r -> r.metrics() != null)
                .mapToInt(r -> r.metrics().totalTrades())
                .sum();
        BigDecimal totalPnl = results.stream()
                .filter(r -> r.metrics() != null)
                .map(r -> r.metrics().cumulativePnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overallWinRate = computeOverallWinRate(results);
        BigDecimal overallProfitFactor = computeOverallProfitFactor(results);
        BigDecimal maxDrawdown = results.stream()
                .filter(r -> r.metrics() != null)
                .map(r -> r.metrics().maxDrawdown())
                .reduce(BigDecimal.ZERO, BigDecimal::max);
        Duration timeTaken = Duration.between(result.startedAt(), result.completedAt());

        html.append("<div class='cards'>");
        card(html, "Strategies", String.valueOf(totalStrategies));
        long withTrades = results.stream().filter(r -> r.metrics() != null && r.metrics().totalTrades() > 0).count();
        card(html, "With Trades", withTrades + " / " + totalStrategies);
        card(html, "Total Trades", String.valueOf(totalTrades));
        card(html, "Total PnL", formatPnl(totalPnl), totalPnl.signum() >= 0 ? "green" : "red");
        card(html, "Win Rate", overallWinRate.setScale(1, RoundingMode.HALF_UP) + "%");
        card(html, "Profit Factor", overallProfitFactor.setScale(2, RoundingMode.HALF_UP).toPlainString());
        card(html, "Max Drawdown", formatPnl(maxDrawdown));

        // Best and worst strategy by PnL
        results.stream().filter(r -> r.metrics() != null && r.metrics().totalTrades() > 0)
                .max((a, b) -> a.metrics().cumulativePnl().compareTo(b.metrics().cumulativePnl()))
                .ifPresent(best -> card(html, "Best Strategy",
                        best.strategyType().displayName() + " " + formatPnl(best.metrics().cumulativePnl()), "green"));
        results.stream().filter(r -> r.metrics() != null && r.metrics().totalTrades() > 0)
                .min((a, b) -> a.metrics().cumulativePnl().compareTo(b.metrics().cumulativePnl()))
                .ifPresent(worst -> {
                    if (worst.metrics().cumulativePnl().signum() < 0)
                        card(html, "Worst Strategy",
                                worst.strategyType().displayName() + " " + formatPnl(worst.metrics().cumulativePnl()), "red");
                });

        card(html, "Data Range", result.from() + " → " + result.to());
        card(html, "Time Taken", formatDuration(timeTaken));
        html.append("</div>");
    }

    private BigDecimal computeOverallWinRate(List<StrategyVerificationResult> results) {
        int totalTrades = 0;
        BigDecimal weightedWinRate = BigDecimal.ZERO;
        for (StrategyVerificationResult r : results) {
            if (r.metrics() != null && r.metrics().totalTrades() > 0) {
                int trades = r.metrics().totalTrades();
                totalTrades += trades;
                weightedWinRate = weightedWinRate.add(
                        r.metrics().winRatePercent().multiply(BigDecimal.valueOf(trades)));
            }
        }
        if (totalTrades == 0) return BigDecimal.ZERO;
        return weightedWinRate.divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeOverallProfitFactor(List<StrategyVerificationResult> results) {
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (StrategyVerificationResult r : results) {
            if (r.metrics() != null) {
                BigDecimal avgWin = r.metrics().averageWin();
                BigDecimal avgLoss = r.metrics().averageLoss();
                int trades = r.metrics().totalTrades();
                BigDecimal winRate = r.metrics().winRatePercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                int wins = winRate.multiply(BigDecimal.valueOf(trades)).intValue();
                int losses = trades - wins;
                if (avgWin != null && wins > 0) totalGross = totalGross.add(avgWin.multiply(BigDecimal.valueOf(wins)));
                if (avgLoss != null && losses > 0) totalLoss = totalLoss.add(avgLoss.abs().multiply(BigDecimal.valueOf(losses)));
            }
        }
        if (totalLoss.signum() == 0) return totalGross.signum() > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
        return totalGross.divide(totalLoss, 2, RoundingMode.HALF_UP);
    }


    // ── Strategy Ranking (sorted by profit factor) ──────────────────────

    private void appendStrategyRanking(StringBuilder html, List<StrategyVerificationResult> results) {
        html.append("<h2>🏆 Strategy Ranking (Live Trading Readiness)</h2>");

        // Filter strategies with trades, sort by profit factor descending
        List<StrategyVerificationResult> ranked = results.stream()
                .filter(r -> r.metrics() != null && r.metrics().totalTrades() > 0)
                .sorted((a, b) -> b.metrics().profitFactor().compareTo(a.metrics().profitFactor()))
                .toList();

        if (ranked.isEmpty()) {
            html.append("<div style='font-size:.85rem;color:#6b7280;padding:12px'>No strategies produced trades. Check data availability and entry conditions.</div>");
            return;
        }

        html.append("<table><thead><tr>");
        html.append("<th>Rank</th><th>Strategy</th><th>Verdict</th><th>Trades</th>");
        html.append("<th>PnL ₹</th><th>Win Rate</th><th>Profit Factor</th>");
        html.append("<th>Max DD ₹</th><th>DD/Capital %</th><th>Expectancy</th><th>R:R</th>");
        html.append("</tr></thead><tbody>");

        int rank = 1;
        for (StrategyVerificationResult r : ranked) {
            BacktestMetrics m = r.metrics();
            BigDecimal ddPct = m.maxDrawdown().multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(60000), 1, RoundingMode.HALF_UP);

            // Verdict logic
            String verdict;
            String verdictClass;
            if (m.profitFactor().compareTo(BigDecimal.valueOf(1.5)) >= 0
                    && m.totalTrades() >= 10
                    && ddPct.compareTo(BigDecimal.valueOf(50)) < 0) {
                verdict = "✅ READY";
                verdictClass = "green";
            } else if (m.profitFactor().compareTo(BigDecimal.ONE) >= 0
                    && m.totalTrades() >= 5) {
                verdict = "⚠️ TUNE";
                verdictClass = "color:#92400e";
            } else {
                verdict = "❌ SKIP";
                verdictClass = "red";
            }

            String pnlClass = m.cumulativePnl().signum() >= 0 ? "green" : "red";
            html.append("<tr>");
            html.append("<td><b>").append(rank++).append("</b></td>");
            html.append("<td>").append(esc(r.strategyType().displayName())).append("</td>");
            html.append("<td style='").append(verdictClass).append(";font-weight:600'>").append(verdict).append("</td>");
            html.append("<td>").append(m.totalTrades()).append("</td>");
            html.append("<td class='").append(pnlClass).append("'>").append(formatPnl(m.cumulativePnl())).append("</td>");
            html.append("<td>").append(m.winRatePercent().setScale(1, RoundingMode.HALF_UP)).append("%</td>");
            html.append("<td><b>").append(fmt(m.profitFactor())).append("</b></td>");
            html.append("<td>").append(formatPnl(m.maxDrawdown())).append("</td>");
            html.append("<td>").append(ddPct).append("%</td>");
            html.append("<td>").append(fmt(m.expectancy())).append("</td>");
            html.append("<td>").append(fmt(m.riskRewardRatio())).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");

        // Summary recommendation
        long readyCount = ranked.stream().filter(r -> {
            BacktestMetrics m = r.metrics();
            BigDecimal dd = m.maxDrawdown().multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(60000), 1, RoundingMode.HALF_UP);
            return m.profitFactor().compareTo(BigDecimal.valueOf(1.5)) >= 0
                    && m.totalTrades() >= 10 && dd.compareTo(BigDecimal.valueOf(50)) < 0;
        }).count();
        long tuneCount = ranked.stream().filter(r -> {
            BacktestMetrics m = r.metrics();
            BigDecimal dd = m.maxDrawdown().multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(60000), 1, RoundingMode.HALF_UP);
            return !(m.profitFactor().compareTo(BigDecimal.valueOf(1.5)) >= 0
                    && m.totalTrades() >= 10 && dd.compareTo(BigDecimal.valueOf(50)) < 0)
                    && m.profitFactor().compareTo(BigDecimal.ONE) >= 0 && m.totalTrades() >= 5;
        }).count();
        long noTradeCount = results.stream().filter(r -> r.metrics() == null || r.metrics().totalTrades() == 0).count();

        html.append("<div style='font-size:.85rem;margin-top:10px;padding:10px;background:white;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08)'>");
        html.append("<b>Summary:</b> ").append(readyCount).append(" strategies ready for live, ");
        html.append(tuneCount).append(" need tuning, ");
        html.append(noTradeCount).append(" produced no trades (check entry conditions).");
        html.append("<br><small style='color:#6b7280'>Criteria: READY = PF≥1.5, ≥10 trades, DD&lt;50% of capital. TUNE = PF≥1.0, ≥5 trades.</small>");
        html.append("</div>");
    }

    // ── Guard Impact Analysis ─────────────────────────────────────────────

    private void appendGuardImpactAnalysis(StringBuilder html, List<StrategyVerificationResult> results,
                                            ExecutionFlowCoverage coverage) {
        html.append("<h2>🛡️ Guard Impact Analysis</h2>");
        html.append("<div style='font-size:.85rem;color:#6b7280;margin-bottom:10px'>");
        html.append("Shows how many entries each guard would have blocked. Guards are disabled in backtest to show true strategy performance.");
        html.append("</div>");

        // Aggregate guard blocks across all strategies
        Map<String, Integer> guardBlocks = new LinkedHashMap<>();
        for (StrategyVerificationResult r : results) {
            for (Map.Entry<String, Integer> entry : r.rejectionReasons().entrySet()) {
                if (entry.getKey().contains("would-block") || entry.getKey().contains("guard")
                        || entry.getKey().contains("expiry") || entry.getKey().contains("theta")) {
                    guardBlocks.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }

        if (guardBlocks.isEmpty()) {
            html.append("<div style='font-size:.85rem;color:#6b7280'>No guard blocks recorded.</div>");
            return;
        }

        html.append("<table><thead><tr><th>Guard</th><th>Would-Block Count</th><th>Impact</th></tr></thead><tbody>");
        for (Map.Entry<String, Integer> entry : guardBlocks.entrySet()) {
            String impact;
            if (entry.getValue() > 100) impact = "🔴 High — significantly limits entries";
            else if (entry.getValue() > 30) impact = "🟡 Medium — noticeable reduction";
            else impact = "🟢 Low — minimal impact";
            html.append("<tr><td>").append(esc(entry.getKey())).append("</td>");
            html.append("<td>").append(entry.getValue()).append("</td>");
            html.append("<td>").append(impact).append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    // ── Monthly PnL Heatmap ───────────────────────────────────────────────

    private void appendMonthlyPnlHeatmap(StringBuilder html, List<StrategyVerificationResult> results) {
        // Collect all strategies with daily PnL data
        List<StrategyVerificationResult> withPnl = results.stream()
                .filter(r -> r.metrics() != null && r.metrics().dailyPnl() != null && !r.metrics().dailyPnl().isEmpty())
                .toList();

        if (withPnl.isEmpty()) return;

        html.append("<h2>📅 Monthly PnL Summary</h2>");

        // Aggregate monthly PnL per strategy
        html.append("<table><thead><tr><th>Strategy</th>");

        // Collect all months across all strategies
        java.util.TreeSet<String> allMonths = new java.util.TreeSet<>();
        for (StrategyVerificationResult r : withPnl) {
            for (String date : r.metrics().dailyPnl().keySet()) {
                allMonths.add(date.substring(0, 7)); // YYYY-MM
            }
        }
        for (String month : allMonths) {
            html.append("<th>").append(month).append("</th>");
        }
        html.append("<th>Total</th></tr></thead><tbody>");

        for (StrategyVerificationResult r : withPnl) {
            html.append("<tr><td>").append(esc(r.strategyType().displayName())).append("</td>");
            Map<String, BigDecimal> monthlyPnl = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : r.metrics().dailyPnl().entrySet()) {
                String month = entry.getKey().substring(0, 7);
                monthlyPnl.merge(month, entry.getValue(), BigDecimal::add);
            }
            BigDecimal total = BigDecimal.ZERO;
            for (String month : allMonths) {
                BigDecimal pnl = monthlyPnl.getOrDefault(month, BigDecimal.ZERO);
                total = total.add(pnl);
                String cls = pnl.signum() > 0 ? "green" : pnl.signum() < 0 ? "red" : "";
                html.append("<td class='").append(cls).append("'>").append(formatPnl(pnl)).append("</td>");
            }
            String totalCls = total.signum() >= 0 ? "green" : "red";
            html.append("<td class='").append(totalCls).append("'><b>").append(formatPnl(total)).append("</b></td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    // ── Strategy Comparison Table ─────────────────────────────────────────

    private void appendStrategyComparisonTable(StringBuilder html, List<StrategyVerificationResult> results) {
        html.append("<h2>Strategy Comparison</h2>");
        html.append("<table><thead><tr>");
        html.append("<th>Strategy</th><th>Status</th><th>Trades</th><th>Win Rate</th>");
        html.append("<th>PnL ₹</th><th>Expectancy</th><th>Profit Factor</th>");
        html.append("<th>Max DD</th><th>R:R</th><th>Signals</th><th>Rejected</th><th>Conversion</th>");
        html.append("</tr></thead><tbody>");
        for (StrategyVerificationResult r : results) {
            html.append("<tr>");
            html.append("<td>").append(esc(r.strategyType().displayName())).append("</td>");
            String statusClass = switch (r.status()) {
                case "ok" -> "status-ok";
                case "error" -> "status-error";
                default -> "status-nodata";
            };
            html.append("<td class='").append(statusClass).append("'>").append(r.status().toUpperCase()).append("</td>");
            if (r.metrics() != null) {
                BacktestMetrics m = r.metrics();
                html.append("<td>").append(m.totalTrades()).append("</td>");
                html.append("<td>").append(m.winRatePercent().setScale(1, java.math.RoundingMode.HALF_UP)).append("%</td>");
                String pnlClass = m.cumulativePnl().signum() >= 0 ? "green" : "red";
                html.append("<td class='").append(pnlClass).append("'>").append(formatPnl(m.cumulativePnl())).append("</td>");
                html.append("<td>").append(fmt(m.expectancy())).append("</td>");
                html.append("<td>").append(fmt(m.profitFactor())).append("</td>");
                html.append("<td>").append(fmt(m.maxDrawdown())).append("</td>");
                html.append("<td>").append(fmt(m.riskRewardRatio())).append("</td>");
                html.append("<td>").append(m.totalSignals()).append("</td>");
                html.append("<td>").append(m.rejectedSignals()).append("</td>");
                html.append("<td>").append(fmt(m.signalConversionPercent())).append("%</td>");
            } else {
                html.append("<td colspan='10' style='color:#6b7280'>").append(r.errorMessage() != null ? esc(r.errorMessage()) : "No data").append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    // ── Per-Strategy Detail Section ───────────────────────────────────────

    private void appendStrategyDetails(StringBuilder html, List<StrategyVerificationResult> results) {
        html.append("<h2>Per-Strategy Results</h2>");
        for (StrategyVerificationResult r : results) {
            html.append("<div class='strategy-card'>");
            html.append("<div class='strategy-header'>");
            html.append("<h3>").append(esc(r.strategyType().displayName()))
                    .append(" <small>(").append(r.strategyType().name()).append(")</small></h3>");
            String statusClass = switch (r.status()) {
                case "ok" -> "status-ok";
                case "error" -> "status-error";
                case "no-data" -> "status-nodata";
                default -> "status-skipped";
            };
            html.append("<span class='").append(statusClass).append("'>").append(r.status().toUpperCase()).append("</span>");
            html.append("</div>");

            // Signals
            html.append("<div class='metrics-grid'>");
            metric(html, "Entry Signals", String.valueOf(r.entrySignals()));
            metric(html, "Rejected Signals", String.valueOf(r.rejectedSignals()));
            if (!r.rejectionReasons().isEmpty()) {
                for (Map.Entry<String, Integer> entry : r.rejectionReasons().entrySet()) {
                    metric(html, "Rejected: " + entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            html.append("</div>");

            // Metrics
            if (r.metrics() != null) {
                BacktestMetrics m = r.metrics();
                html.append("<div class='metrics-grid'>");
                metric(html, "Trades", String.valueOf(m.totalTrades()));
                metric(html, "Win Rate", m.winRatePercent().setScale(1, RoundingMode.HALF_UP) + "%");
                metricPnl(html, "PnL", m.cumulativePnl());
                metric(html, "Expectancy", fmt(m.expectancy()));
                metric(html, "Profit Factor", fmt(m.profitFactor()));
                metric(html, "Max Drawdown", fmt(m.maxDrawdown()));
                metric(html, "Avg Win", fmt(m.averageWin()));
                metric(html, "Avg Loss", fmt(m.averageLoss()));
                metric(html, "Avg Hold (min)", fmt(m.avgHoldMinutes()));
                metric(html, "Max Consec Wins", String.valueOf(m.maxConsecutiveWins()));
                metric(html, "Max Consec Losses", String.valueOf(m.maxConsecutiveLosses()));
                metric(html, "Risk:Reward", fmt(m.riskRewardRatio()));
                metric(html, "Signal Conversion", fmt(m.signalConversionPercent()) + "%");
                html.append("</div>");
            }

            // Strategy-specific metrics
            if (!r.strategySpecificMetrics().isEmpty()) {
                html.append("<div class='metrics-grid'>");
                for (Map.Entry<String, String> entry : r.strategySpecificMetrics().entrySet()) {
                    metric(html, entry.getKey(), entry.getValue());
                }
                html.append("</div>");
            }

            // Error message
            if (r.errorMessage() != null && !r.errorMessage().isEmpty()) {
                html.append("<div style='color:#b91c1c;font-size:.82rem;margin-top:6px'>Error: ")
                        .append(esc(r.errorMessage())).append("</div>");
            }

            // Sample trades table
            if (r.sampleTrades() != null && !r.sampleTrades().isEmpty()) {
                appendSampleTradesTable(html, r.sampleTrades());
            }

            // Daily PnL breakdown
            if (r.metrics() != null && r.metrics().dailyPnl() != null && !r.metrics().dailyPnl().isEmpty()) {
                appendDailyPnlTable(html, r.metrics().dailyPnl());
            }

            html.append("</div>");
        }
    }

    private void appendSampleTradesTable(StringBuilder html, List<BacktestTrade> trades) {
        html.append("<table><thead><tr>");
        html.append("<th>Trade ID</th><th>Instrument</th><th>Entry Time</th><th>Exit Time</th>");
        html.append("<th>Qty</th><th>Entry ₹</th><th>Exit ₹</th><th>PnL ₹</th>");
        html.append("<th>Entry Reason</th><th>Exit Reason</th>");
        html.append("</tr></thead><tbody>");
        int limit = Math.min(trades.size(), 10);
        for (int i = 0; i < limit; i++) {
            BacktestTrade t = trades.get(i);
            html.append("<tr>");
            html.append("<td>").append(esc(t.tradeId())).append("</td>");
            html.append("<td>").append(esc(t.instrumentKey())).append("</td>");
            html.append("<td>").append(t.entryTime() != null ? IST_FMT.format(t.entryTime()) : "-").append("</td>");
            html.append("<td>").append(t.exitTime() != null ? IST_FMT.format(t.exitTime()) : "-").append("</td>");
            html.append("<td>").append(t.quantity()).append("</td>");
            html.append("<td>").append(fmt(t.entryPrice())).append("</td>");
            html.append("<td>").append(fmt(t.exitPrice())).append("</td>");
            String pnlClass = t.pnl() != null && t.pnl().signum() >= 0 ? "green" : "red";
            html.append("<td class='").append(pnlClass).append("'>").append(fmt(t.pnl())).append("</td>");
            html.append("<td>").append(esc(t.entryReason())).append("</td>");
            html.append("<td>").append(esc(t.exitReason())).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        if (trades.size() > 10) {
            html.append("<div style='font-size:.78rem;color:#6b7280;margin-top:4px'>Showing first 10 of ")
                    .append(trades.size()).append(" trades</div>");
        }
    }

    private void appendDailyPnlTable(StringBuilder html, Map<String, BigDecimal> dailyPnl) {
        html.append("<details style='margin-top:8px'><summary style='font-size:.82rem;cursor:pointer;color:#4b5563'>")
                .append("📊 Daily PnL Breakdown (").append(dailyPnl.size()).append(" days)</summary>");
        html.append("<table><thead><tr><th>Date</th><th>PnL ₹</th><th>Cumulative ₹</th></tr></thead><tbody>");
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : dailyPnl.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            String pnlClass = entry.getValue().signum() >= 0 ? "green" : "red";
            String cumClass = cumulative.signum() >= 0 ? "green" : "red";
            html.append("<tr><td>").append(entry.getKey()).append("</td>");
            html.append("<td class='").append(pnlClass).append("'>").append(formatPnl(entry.getValue())).append("</td>");
            html.append("<td class='").append(cumClass).append("'>").append(formatPnl(cumulative)).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table></details>");
    }


    // ── Execution Flow Coverage Section ───────────────────────────────────

    private void appendFlowCoverage(StringBuilder html, ExecutionFlowCoverage coverage) {
        html.append("<h2>Execution Flow Coverage</h2>");
        if (coverage == null || coverage.branches() == null || coverage.branches().isEmpty()) {
            html.append("<div style='font-size:.82rem;color:#6b7280'>No flow coverage data available.</div>");
            return;
        }

        int totalBranches = coverage.branches().size();
        long hitBranches = coverage.branches().values().stream().filter(BranchStatus::hit).count();
        html.append("<div style='font-size:.85rem;margin-bottom:10px'>")
                .append(hitBranches).append(" / ").append(totalBranches).append(" branches hit (")
                .append(totalBranches > 0 ? (hitBranches * 100 / totalBranches) : 0).append("%)</div>");

        html.append("<div class='flow-grid'>");
        for (Map.Entry<String, BranchStatus> entry : coverage.branches().entrySet()) {
            String name = entry.getKey();
            BranchStatus bs = entry.getValue();
            String icon = bs.hit() ? "✅" : "❌";
            String cls = bs.hit() ? "flow-hit" : "flow-miss";
            html.append("<div class='flow-item ").append(cls).append("'>");
            html.append("<span>").append(icon).append("</span>");
            html.append("<span><b>").append(esc(name)).append("</b>");
            if (bs.hit()) {
                html.append(" <small>(×").append(bs.hitCount());
                if (bs.firstHitDetail() != null) {
                    html.append(" — ").append(esc(truncate(bs.firstHitDetail(), 60)));
                }
                html.append(")</small>");
            }
            html.append("</span></div>");
        }
        html.append("</div>");
    }

    // ── Configuration Snapshot Section ────────────────────────────────────

    private void appendConfigSnapshot(StringBuilder html, ConfigSnapshot snapshot) {
        html.append("<h2>Configuration Snapshot</h2>");
        if (snapshot == null) {
            html.append("<div style='font-size:.82rem;color:#6b7280'>No configuration snapshot available.</div>");
            return;
        }

        // Trading Properties (infrastructure: mode, timezone, broker)
        if (snapshot.tradingProperties() != null) {
            TradingProperties tp = snapshot.tradingProperties();
            html.append("<div class='cfg-section'><h3>Trading Properties</h3><div class='cfg-grid'>");
            cfgItem(html, "Mode", String.valueOf(tp.mode()));
            cfgItem(html, "Live Trading", String.valueOf(tp.liveTradingEnabled()));
            cfgItem(html, "Timezone", String.valueOf(tp.timezone()));
            html.append("</div></div>");
        }

        // Global Config (entry/exit/risk from DB)
        if (snapshot.globalConfig() != null) {
            GlobalConfig gc = snapshot.globalConfig();

            // Entry
            html.append("<div class='cfg-section'><h3>Entry Filters (GlobalConfig)</h3><div class='cfg-grid'>");
            cfgItem(html, "Entry Timeframe", String.valueOf(gc.getTimeframe()));
            cfgItem(html, "Trend Timeframe", String.valueOf(gc.getTrendTimeframe()));
            cfgItem(html, "Enabled Options", gc.getEnabledOptionTypes());
            cfgItem(html, "VWAP Filter", String.valueOf(gc.isVwapFilterEnabled()));
            cfgItem(html, "Trend Filter", String.valueOf(gc.isTrendFilterEnabled()));
            cfgItem(html, "Volume Spike ×", fmt(gc.getVolumeSpikeMultiplier()));
            cfgItem(html, "Breakout Buffer %", fmt(gc.getBreakoutBufferPercent()));
            cfgItem(html, "Breakout Lookback", String.valueOf(gc.getBreakoutLookback()));
            cfgItem(html, "Volume Lookback", String.valueOf(gc.getVolumeLookback()));
            cfgItem(html, "Bullish Imbalance ×", fmt(gc.getBullishImbalanceThreshold()));
            cfgItem(html, "Bearish Imbalance ×", fmt(gc.getBearishImbalanceThreshold()));
            cfgItem(html, "Min Liquidity Vol", String.valueOf(gc.getMinLiquidityVolume()));
            cfgItem(html, "Max IV %", fmt(gc.getMaxIvPercent()));
            cfgItem(html, "Min Signal Score %", fmt(gc.getMinSignalScorePercent()));
            cfgItem(html, "CE OI Support", String.valueOf(gc.isCeOiSupportRequired()));
            cfgItem(html, "PE OI Support", String.valueOf(gc.isPeOiSupportRequired()));
            cfgItem(html, "CE OI Divergence", String.valueOf(gc.isCeOiDivergenceFilterEnabled()));
            cfgItem(html, "PE OI Divergence", String.valueOf(gc.isPeOiDivergenceFilterEnabled()));
            cfgItem(html, "OI Divergence ×", fmt(gc.getOiDivergenceMultiplier()));
            cfgItem(html, "OI Divergence Min Change", String.valueOf(gc.getOiDivergenceMinChange()));
            cfgItem(html, "CE Breakout Confirm Candles", String.valueOf(gc.getCeBreakoutConfirmationCandles()));
            cfgItem(html, "PE Breakout Confirm Candles", String.valueOf(gc.getPeBreakoutConfirmationCandles()));
            cfgItem(html, "Entry Start", gc.getEntryStartTime());
            cfgItem(html, "Entry Cutoff", gc.getEntryCutoffTime());
            cfgItem(html, "Allow First Minutes Entry", String.valueOf(gc.isAllowFirstMinutesEntry()));
            cfgItem(html, "No Entry First Minutes", String.valueOf(gc.getNoEntryFirstMinutes()));
            cfgItem(html, "RSI Filter", String.valueOf(gc.isRsiFilterEnabled()));
            cfgItem(html, "RSI Period", String.valueOf(gc.getRsiPeriod()));
            cfgItem(html, "RSI CE Buy Threshold", fmt(gc.getRsiCeBuyThreshold()));
            cfgItem(html, "RSI PE Sell Threshold", fmt(gc.getRsiPeSellThreshold()));
            html.append("</div></div>");

            // Exit
            html.append("<div class='cfg-section'><h3>Exit Rules (GlobalConfig)</h3><div class='cfg-grid'>");
            cfgItem(html, "SL %", fmt(gc.getStopLossPercent()));
            cfgItem(html, "Target %", fmt(gc.getTargetPercent()));
            cfgItem(html, "Trail Activation %", fmt(gc.getTrailingStopActivationPercent()));
            cfgItem(html, "Trail Gap %", fmt(gc.getTrailingGapPercent()));
            cfgItem(html, "Forced Exit", gc.getForcedExitTime());
            cfgItem(html, "Partial Profit Booking", String.valueOf(gc.isPartialProfitBookingEnabled()));
            cfgItem(html, "Max Hold (min)", String.valueOf(gc.getMaxHoldMinutes()));
            html.append("</div></div>");

            // Risk
            html.append("<div class='cfg-section'><h3>Risk Limits (GlobalConfig)</h3><div class='cfg-grid'>");
            cfgItem(html, "Total Capital", fmt(gc.getTotalCapital()));
            cfgItem(html, "Max Risk/Trade %", fmt(gc.getMaxRiskPerTradePercent()));
            cfgItem(html, "Max Daily Loss %", fmt(gc.getMaxDailyLossPercent()));
            cfgItem(html, "Max Trades/Day", String.valueOf(gc.getMaxTradesPerDay()));
            cfgItem(html, "Max Orders/Day", String.valueOf(gc.getMaxOrdersPerDay()));
            cfgItem(html, "Max Consec Losses", String.valueOf(gc.getMaxConsecutiveLosses()));
            cfgItem(html, "Max Open Trades", String.valueOf(gc.getMaxOpenTrades()));
            cfgItem(html, "Re-entry Min Price Move %", fmt(gc.getSameInstrumentReentryMinPriceMovePercent()));
            cfgItem(html, "Cooldown (min)", String.valueOf(gc.getCooldownMinutes()));
            cfgItem(html, "Daily Profit Target", fmt(gc.getDailyProfitTarget()));
            html.append("</div></div>");
        }

        // Per-strategy configs
        if (snapshot.strategyConfigs() != null && !snapshot.strategyConfigs().isEmpty()) {
            html.append("<div class='cfg-section'><h3>Per-Strategy Configs</h3>");
            html.append("<table><thead><tr>");
            html.append("<th>Strategy</th><th>Enabled</th><th>Lots</th><th>SL%</th><th>Target%</th>");
            html.append("<th>Max Hold</th><th>Spread Strikes</th><th>OTM Strikes</th>");
            html.append("<th>Min Premium</th><th>Max IV Rank</th><th>Trail Act%</th><th>Trail Gap%</th>");
            html.append("<th>Squareoff</th>");
            html.append("</tr></thead><tbody>");
            for (Map.Entry<StrategyType, StrategyConfig> entry : snapshot.strategyConfigs().entrySet()) {
                StrategyConfig sc = entry.getValue();
                html.append("<tr>");
                html.append("<td>").append(entry.getKey().displayName()).append("</td>");
                html.append("<td>").append(sc.isEnabled() ? "✅" : "❌").append("</td>");
                html.append("<td>").append(sc.getLots()).append("</td>");
                html.append("<td>").append(fmt(sc.getStopLossPercent())).append("</td>");
                html.append("<td>").append(fmt(sc.getTargetPercent())).append("</td>");
                html.append("<td>").append(sc.getMaxHoldMinutes()).append("</td>");
                html.append("<td>").append(sc.getSpreadStrikes()).append("</td>");
                html.append("<td>").append(sc.getOtmStrikes()).append("</td>");
                html.append("<td>").append(fmt(sc.getMinCombinedPremium())).append("</td>");
                html.append("<td>").append(fmt(sc.getMaxIvRankForBuying())).append("</td>");
                html.append("<td>").append(fmt(sc.getTrailingStopActivationPercent())).append("</td>");
                html.append("<td>").append(fmt(sc.getTrailingGapPercent())).append("</td>");
                html.append("<td>").append(sc.getSquareoffHour()).append(":").append(String.format("%02d", sc.getSquareoffMinute())).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table></div>");
        }

        // Config mismatches
        if (snapshot.configMismatches() != null && !snapshot.configMismatches().isEmpty()) {
            html.append("<div class='cfg-section'><h3>⚠️ Config Mismatches</h3><ul>");
            for (String mismatch : snapshot.configMismatches()) {
                html.append("<li style='font-size:.82rem'>").append(esc(mismatch)).append("</li>");
            }
            html.append("</ul></div>");
        }
    }


    // ── JSON Summary ──────────────────────────────────────────────────────

    @Override
    public Path generateJsonSummary(VerifyAllResult result, Path outputDirectory) {
        Path jsonPath = outputDirectory.resolve("verify-summary.json");
        try {
            Files.createDirectories(outputDirectory);
            JSON_MAPPER.writeValue(jsonPath.toFile(), result);
            log.info("Verify-all JSON summary written to {}", jsonPath);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write verify-all JSON summary", ex);
        }
        return jsonPath;
    }

    // ── Utility methods ──────────────────────────────────────────────────

    private void card(StringBuilder html, String label, String value) {
        card(html, label, value, null);
    }

    private void card(StringBuilder html, String label, String value, String colorClass) {
        html.append("<div class='card'><div class='label'>").append(esc(label)).append("</div>");
        html.append("<div class='value");
        if (colorClass != null) html.append(" ").append(colorClass);
        html.append("'>").append(esc(value)).append("</div></div>");
    }

    private void metric(StringBuilder html, String key, String value) {
        html.append("<div class='metric'><span class='mk'>").append(esc(key))
                .append("</span> <span class='mv'>").append(esc(value)).append("</span></div>");
    }

    private void metricPnl(StringBuilder html, String key, BigDecimal value) {
        String cls = value != null && value.signum() >= 0 ? "green" : "red";
        html.append("<div class='metric'><span class='mk'>").append(esc(key))
                .append("</span> <span class='mv ").append(cls).append("'>")
                .append(formatPnl(value)).append("</span></div>");
    }

    private void cfgItem(StringBuilder html, String key, String value) {
        html.append("<div class='cfg-item'><span class='ck'>").append(esc(key))
                .append(": </span><span class='cv'>").append(esc(value)).append("</span></div>");
    }

    private static String fmt(BigDecimal value) {
        if (value == null) return "-";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatPnl(BigDecimal value) {
        if (value == null) return "-";
        return "₹" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    private static String esc(String text) {
        if (text == null) return "-";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
