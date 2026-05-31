package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared short-vol analyzer sections for Short Straddle and Short Strangle. */
abstract class ShortVolPluginSharedBase implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(ShortVolPluginSharedBase.class);

    public abstract StrategyType strategy();

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(strategy())) {
            return List.of();
        }
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(thetaCapture(query));
        sections.add(ivRankFloor(query));
        return sections;
    }

    private AnalyzerSection thetaCapture(TuningEventQuery query) {
        try {
            List<Path> exits = query.store().listEventFiles(
                    strategy(), TuningEventType.EXIT, query.fromDate(), query.toDate());
            if (exits.isEmpty()) {
                return AnalyzerSection.htmlOnly("Theta capture analysis",
                        "<p><em>No exit data for " + strategy().displayName() + ".</em></p>");
            }
            String glob = globOf(exits);
            String sql = ""
                    + "SELECT COUNT(*) AS exits, "
                    + "AVG(realizedPnlPct / NULLIF(holdSec / 3600.0, 0)) AS theta_rate "
                    + "FROM read_csv_auto([" + glob + "], header=true) "
                    + "WHERE holdSec > 0";
            List<Map<String, Object>> rows = query.store().query(sql);
            return AnalyzerSection.htmlOnly("Theta capture analysis", renderRate(rows));
        } catch (TuningQueryException ex) {
            log.warn("[ShortVolPlugin] theta capture failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Theta capture analysis",
                    "<p><em>Query failed: " + escape(ex.getMessage()) + "</em></p>");
        }
    }

    private AnalyzerSection ivRankFloor(TuningEventQuery query) {
        try {
            List<Path> signals = query.store().listEventFiles(
                    strategy(), TuningEventType.SIGNAL, query.fromDate(), query.toDate());
            if (signals.isEmpty()) {
                return AnalyzerSection.htmlOnly("IV rank floor performance",
                        "<p><em>No signal data for " + strategy().displayName() + ".</em></p>");
            }
            String glob = globOf(signals);
            String sql = ""
                    + "SELECT AVG(TRY_CAST(json_extract_string(attr_extra, '$.ivRank') AS DOUBLE)) AS avg_iv, "
                    + "COUNT(*) AS signals "
                    + "FROM read_csv_auto([" + glob + "], header=true)";
            List<Map<String, Object>> rows = query.store().query(sql);
            Object avg = rows.isEmpty() ? null : rows.getFirst().get("avg_iv");
            Object n = rows.isEmpty() ? 0 : rows.getFirst().get("signals");
            return AnalyzerSection.htmlOnly("IV rank floor performance",
                    "<p>Avg IV rank at entry: <strong>"
                            + (avg != null ? String.format(Locale.ROOT, "%.1f", ((Number) avg).doubleValue()) : "n/a")
                            + "</strong> (n=" + n + ")</p>");
        } catch (TuningQueryException ex) {
            log.warn("[ShortVolPlugin] iv rank floor failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("IV rank floor performance",
                    "<p><em>Query failed: " + escape(ex.getMessage()) + "</em></p>");
        }
    }

    private static String globOf(List<Path> files) {
        return files.stream()
                .map(p -> "'" + p.toString().replace("\\", "/") + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("''");
    }

    private static String renderRate(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "<p><em>No rows.</em></p>";
        }
        Map<String, Object> row = rows.getFirst();
        Object rate = row.get("theta_rate");
        Object n = row.get("exits");
        return "<p>Avg PnL%/hour: <strong>"
                + (rate != null ? String.format(Locale.ROOT, "%.2f", ((Number) rate).doubleValue()) : "n/a")
                + "</strong> (n=" + n + ")</p>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("<", "&lt;");
    }
}

@Component
class ShortVolPluginSharedStraddle extends ShortVolPluginSharedBase {
    @Override public StrategyType strategy() { return StrategyType.SHORT_STRADDLE; }
}

@Component
class ShortVolPluginSharedStrangle extends ShortVolPluginSharedBase {
    @Override public StrategyType strategy() { return StrategyType.SHORT_STRANGLE; }
}
