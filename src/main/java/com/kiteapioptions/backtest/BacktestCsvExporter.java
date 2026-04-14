package com.kiteapioptions.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes backtest metrics, trades, and equity curve CSV outputs.
 */
public class BacktestCsvExporter {

    public void export(BacktestRunResult result) throws IOException {
        Files.createDirectories(result.outputDirectory());
        Files.writeString(result.tradesCsv(), tradesCsv(result));
        Files.writeString(result.metricsCsv(), metricsCsv(result));
        Files.writeString(result.equityCurveCsv(), equityCurveCsv(result));
        Files.writeString(result.reportHtml(), html(result));
    }

    private String tradesCsv(BacktestRunResult result) {
        StringBuilder csv = new StringBuilder("tradeId,instrumentKey,entryTime,exitTime,quantity,entryPrice,exitPrice,pnl,entryReason,exitReason\n");
        for (BacktestTrade trade : result.trades()) {
            csv.append(trade.tradeId()).append(',')
                    .append(trade.instrumentKey()).append(',')
                    .append(trade.entryTime()).append(',')
                    .append(trade.exitTime()).append(',')
                    .append(trade.quantity()).append(',')
                    .append(trade.entryPrice()).append(',')
                    .append(trade.exitPrice()).append(',')
                    .append(trade.pnl()).append(',')
                    .append(escape(trade.entryReason())).append(',')
                    .append(escape(trade.exitReason())).append('\n');
        }
        return csv.toString();
    }

    private String metricsCsv(BacktestRunResult result) {
        BacktestMetrics metrics = result.metrics();
        return "metric,value\n"
                + "totalTrades," + metrics.totalTrades() + '\n'
                + "winRatePercent," + metrics.winRatePercent() + '\n'
                + "averageWin," + metrics.averageWin() + '\n'
                + "averageLoss," + metrics.averageLoss() + '\n'
                + "expectancy," + metrics.expectancy() + '\n'
                + "maxDrawdown," + metrics.maxDrawdown() + '\n'
                + "cumulativePnl," + metrics.cumulativePnl() + '\n';
    }

    private String equityCurveCsv(BacktestRunResult result) {
        StringBuilder csv = new StringBuilder("timestamp,equity,drawdown\n");
        for (EquityCurvePoint point : result.equityCurve()) {
            csv.append(point.timestamp()).append(',')
                    .append(point.equity()).append(',')
                    .append(point.drawdown()).append('\n');
        }
        return csv.toString();
    }

    private String escape(String value) {
        return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"';
    }

    private String html(BacktestRunResult result) {
        BacktestMetrics m = result.metrics();
        String pnlColor = m.cumulativePnl().signum() >= 0 ? "#16a34a" : "#dc2626";

        // equity curve data for Chart.js
        StringBuilder labels = new StringBuilder();
        StringBuilder equityData = new StringBuilder();
        StringBuilder drawdownData = new StringBuilder();
        for (EquityCurvePoint p : result.equityCurve()) {
            labels.append("'").append(p.timestamp().toString().replace("T", " ").substring(0, 16)).append("',");
            equityData.append(p.equity()).append(",");
            drawdownData.append(p.drawdown().negate()).append(",");
        }

        // daily pnl rows
        StringBuilder dailyRows = new StringBuilder();
        m.dailyPnl().forEach((date, pnl) -> {
            String color = pnl.signum() >= 0 ? "#16a34a" : "#dc2626";
            dailyRows.append("<tr><td>").append(htmlEscape(date)).append("</td><td style='color:").append(color)
                    .append("'>").append(formatMoney(pnl)).append("</td></tr>");
        });

        // trades rows
        StringBuilder tradeRows = new StringBuilder();
        for (BacktestTrade t : result.trades()) {
            String color = t.pnl().signum() >= 0 ? "#16a34a" : "#dc2626";
            tradeRows.append("<tr>")
                    .append("<td>").append(t.entryTime().toString().replace("T", " ").substring(0, 16)).append("</td>")
                    .append("<td>").append(t.exitTime().toString().replace("T", " ").substring(0, 16)).append("</td>")
                    .append("<td>").append(htmlEscape(t.instrumentKey())).append("</td>")
                    .append("<td>").append(t.quantity()).append("</td>")
                    .append("<td>").append(formatMoney(t.entryPrice())).append("</td>")
                    .append("<td>").append(formatMoney(t.exitPrice())).append("</td>")
                    .append("<td style='color:").append(color).append("'>").append(formatMoney(t.pnl())).append("</td>")
                    .append("<td>").append(htmlEscape(t.exitReason())).append("</td>")
                    .append("</tr>");
        }

        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Backtest Report " + htmlEscape(result.id()) + "</title>"
                + "<script src='https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js'></script>"
                + "<style>"
                + "body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#f8fafc;color:#1e293b}"
                + "h1{font-size:1.4rem;margin-bottom:4px}h2{font-size:1rem;margin:24px 0 8px;color:#475569}"
                + ".meta{font-size:.8rem;color:#64748b;margin-bottom:24px}"
                + ".cards{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:24px}"
                + ".card{background:#fff;border-radius:8px;padding:16px 20px;min-width:140px;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + ".card .label{font-size:.75rem;color:#64748b;margin-bottom:4px}"
                + ".card .value{font-size:1.3rem;font-weight:600}"
                + ".chart-wrap{background:#fff;border-radius:8px;padding:16px;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:24px}"
                + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:24px}"
                + "th{background:#f1f5f9;padding:8px 12px;text-align:left;font-size:.78rem;color:#475569}"
                + "td{padding:7px 12px;font-size:.82rem;border-top:1px solid #f1f5f9}"
                + "tr:hover td{background:#f8fafc}"
                + "</style></head><body>"
                + "<h1>Backtest Report</h1>"
                + "<div class='meta'>ID: " + htmlEscape(result.id()) + " &nbsp;|&nbsp; Generated: " + result.createdAt() + "</div>"
                + "<div class='cards'>"
                + card("Total Trades", String.valueOf(m.totalTrades()), null)
                + card("Win Rate", m.winRatePercent().setScale(1, java.math.RoundingMode.HALF_UP) + "%", null)
                + card("Cumulative PnL", "Rs " + formatMoney(m.cumulativePnl()), pnlColor)
                + card("Avg Win", "Rs " + formatMoney(m.averageWin()), "#16a34a")
                + card("Avg Loss", "Rs " + formatMoney(m.averageLoss()), "#dc2626")
                + card("Expectancy", "Rs " + formatMoney(m.expectancy()), null)
                + card("Max Drawdown", "Rs " + formatMoney(m.maxDrawdown()), "#dc2626")
                + "</div>"
                + "<h2>Equity Curve &amp; Drawdown</h2>"
                + "<div class='chart-wrap'><canvas id='chart' height='90'></canvas></div>"
                + "<h2>Daily PnL</h2>"
                + "<table><thead><tr><th>Date</th><th>PnL (Rs)</th></tr></thead><tbody>" + dailyRows + "</tbody></table>"
                + "<h2>Trades</h2>"
                + "<table><thead><tr><th>Entry Time</th><th>Exit Time</th><th>Instrument</th><th>Qty</th><th>Entry Rs</th><th>Exit Rs</th><th>PnL Rs</th><th>Exit Reason</th></tr></thead><tbody>"
                + tradeRows + "</tbody></table>"
                + "<script>"
                + "new Chart(document.getElementById('chart'),{type:'line',data:{labels:[" + labels + "],"
                + "datasets:[{label:'Equity',data:[" + equityData + "],borderColor:'#2563eb',backgroundColor:'rgba(37,99,235,.08)',fill:true,tension:.3,pointRadius:2},"
                + "{label:'Drawdown',data:[" + drawdownData + "],borderColor:'#dc2626',backgroundColor:'rgba(220,38,38,.06)',fill:true,tension:.3,pointRadius:2}]},"
                + "options:{responsive:true,interaction:{mode:'index',intersect:false},plugins:{legend:{position:'top'}},scales:{y:{ticks:{callback:v=>'Rs '+v}}}}})"
                + "</script></body></html>";
    }

    private String card(String label, String value, String color) {
        String style = color != null ? " style='color:" + color + "'" : "";
        return "<div class='card'><div class='label'>" + htmlEscape(label) + "</div><div class='value'" + style + ">"
                + htmlEscape(value) + "</div></div>";
    }

    private String formatMoney(java.math.BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
