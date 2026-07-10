package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Feature-impact section — measures whether each LIVE entry adjustment actually earns its keep, by reading
 * the {@code regime=} / {@code boosts=[agg:..,rev:..,lad:..,gam:..]} tags the strategy now stamps onto every
 * trade's {@code entryReason}, joined to the trade's realized outcome.
 *
 * <p>Two breakdowns over executed+exited OI-Momentum trades in the window:</p>
 * <ul>
 *   <li><b>Per detector</b> — for each bonus source (aggregator / reversal / ladder / gamma): trades where it
 *       fired (bonus &gt; 0) vs not, with win-rate and avg realized %. If "fired" isn't better than "not",
 *       that detector isn't adding edge and its bonus should be cut.</li>
 *   <li><b>Per regime</b> — win-rate + avg realized % per dynamic-gate regime, so you can see which regimes
 *       are worth trading (complements the regime breakdown, but on REALIZED trade P&L).</li>
 * </ul>
 *
 * <p>Reads the trades table directly (the tags live on {@code entryReason}); fail-safe if the repo is absent.</p>
 */
@Component
public class FeatureImpactAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(FeatureImpactAnalyzerPlugin.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern REGIME = Pattern.compile("regime=([A-Z_]+)");
    private static final Pattern BOOSTS = Pattern.compile("boosts=\\[agg:(\\d+),rev:(\\d+),lad:(\\d+),gam:(\\d+)\\]");
    private static final Pattern LIQ = Pattern.compile("liqMult=([0-9.]+)");
    private static final String[] FEATURES = {"aggregator", "reversal", "ladder", "gamma"};

    @Autowired(required = false)
    private TradeRepository tradeRepository;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    /** After feature-predictiveness / decision-record — it's an outcome-attribution view. */
    @Override
    public int order() {
        return 82;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Feature impact (per-detector & per-regime realized outcome)";
        if (tradeRepository == null) {
            return AnalyzerSection.htmlOnly(title, "<p><em>Trade repository unavailable.</em></p>");
        }
        try {
            Instant from = query.fromDate() != null ? query.fromDate().atStartOfDay(IST).toInstant() : Instant.EPOCH;
            Instant to = query.toDate() != null ? query.toDate().plusDays(1).atStartOfDay(IST).toInstant() : Instant.now();

            List<TradeEntity> trades = new ArrayList<>();
            for (TradeEntity t : tradeRepository.findAll()) {
                if (t.getExitTime() == null || t.getRealizedPnl() == null) continue;        // only resolved trades
                if (t.getExitTime().isBefore(from) || t.getExitTime().isAfter(to)) continue;
                if (!"OI_MOMENTUM".equalsIgnoreCase(String.valueOf(t.getStrategyType()))) continue;
                if (t.isPaperTrade()) continue;
                trades.add(t);
            }
            if (trades.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No resolved OI-Momentum trades in this window yet. Populates once trades close "
                        + "with the regime/boosts tags (post-deploy).</em></p>");
            }

            // ── Per-detector: fired vs not ──
            // featureStats[feature][firedFlag] = {n, wins, sumPct}
            Map<String, double[][]> det = new LinkedHashMap<>();
            for (String f : FEATURES) det.put(f, new double[2][3]); // [fired?][n,wins,sumPct]
            det.put("liquidity-sizing", new double[2][3]); // fired = lots were reduced for thin liquidity
            Map<String, double[]> byRegime = new LinkedHashMap<>(); // regime → {n, wins, sumPct}

            for (TradeEntity t : trades) {
                String reason = t.getEntryReason() == null ? "" : t.getEntryReason();
                double pct = realizedPct(t);
                boolean win = t.getRealizedPnl().signum() > 0;

                int[] boosts = parseBoosts(reason);   // [agg,rev,lad,gam]
                for (int i = 0; i < FEATURES.length; i++) {
                    int fired = boosts[i] > 0 ? 1 : 0;
                    double[] cell = det.get(FEATURES[i])[fired];
                    cell[0]++; if (win) cell[1]++; cell[2] += pct;
                }
                // Liquidity sizing: "fired" = lots were reduced for thin depth (liqMult < 1.0 present).
                int liqFired = liquiditySized(reason) ? 1 : 0;
                double[] liqCell = det.get("liquidity-sizing")[liqFired];
                liqCell[0]++; if (win) liqCell[1]++; liqCell[2] += pct;

                String regime = parseRegime(reason);
                double[] r = byRegime.computeIfAbsent(regime, k -> new double[3]);
                r[0]++; if (win) r[1]++; r[2] += pct;
            }

            StringBuilder h = new StringBuilder(1024);
            h.append("<p>Realized outcome of each LIVE entry adjustment, from the <code>regime=</code> / "
                    + "<code>boosts=[…]</code> tags on <code>entryReason</code>. "
                    + "\"fired\" = that detector contributed bias on the trade. If fired isn't beating not-fired, "
                    + "the detector isn't earning its bonus.</p>");

            h.append("<h4>Per detector</h4><table><thead><tr><th>detector</th><th>state</th><th>trades</th>"
                    + "<th>win%</th><th>avg realized %</th></tr></thead><tbody>");
            for (String f : FEATURES) {
                double[][] s = det.get(f);
                appendRow(h, f, "fired", s[1]);
                appendRow(h, f, "not", s[0]);
            }
            double[][] liq = det.get("liquidity-sizing");
            appendRow(h, "liquidity-sizing", "sized-down", liq[1]);
            appendRow(h, "liquidity-sizing", "full-size", liq[0]);
            h.append("</tbody></table>");

            h.append("<h4>Per regime</h4><table><thead><tr><th>regime</th><th>trades</th><th>win%</th>"
                    + "<th>avg realized %</th></tr></thead><tbody>");
            for (var e : byRegime.entrySet()) {
                double[] r = e.getValue();
                h.append("<tr><td>").append(escape(e.getKey())).append("</td><td>").append((int) r[0]).append("</td><td>")
                        .append(pctStr(r[0] > 0 ? 100.0 * r[1] / r[0] : 0)).append("</td><td>")
                        .append(pctStr(r[0] > 0 ? r[2] / r[0] : 0)).append("</td></tr>");
            }
            h.append("</tbody></table>");
            return AnalyzerSection.htmlOnly(title, h.toString());
        } catch (Exception ex) {
            log.warn("[FeatureImpact] section failed: {}", ex.toString());
            return AnalyzerSection.htmlOnly(title, "<p class='error'>Section failed: " + escape(String.valueOf(ex.getMessage())) + "</p>");
        }
    }

    private static void appendRow(StringBuilder h, String feature, String state, double[] s) {
        long n = (long) s[0];
        double winPct = n > 0 ? 100.0 * s[1] / n : 0;
        double avgPct = n > 0 ? s[2] / n : 0;
        h.append("<tr><td>").append(escape(feature)).append("</td><td>").append(state).append("</td><td>")
                .append(n).append("</td><td>").append(pctStr(winPct)).append("</td><td>")
                .append(pctStr(avgPct)).append("</td></tr>");
    }

    private static double realizedPct(TradeEntity t) {
        try {
            double entryVal = t.getEntryPrice().doubleValue() * t.getQuantity();
            return entryVal > 0 ? t.getRealizedPnl().doubleValue() / entryVal * 100.0 : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static int[] parseBoosts(String reason) {
        Matcher m = BOOSTS.matcher(reason);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
        }
        return new int[]{0, 0, 0, 0};
    }

    private static String parseRegime(String reason) {
        Matcher m = REGIME.matcher(reason);
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    /** True if the trade's lots were reduced for thin liquidity (a {@code liqMult=<1.0} tag is present). */
    private static boolean liquiditySized(String reason) {
        Matcher m = LIQ.matcher(reason);
        if (!m.find()) return false;
        try {
            return Double.parseDouble(m.group(1)) < 1.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String pctStr(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
