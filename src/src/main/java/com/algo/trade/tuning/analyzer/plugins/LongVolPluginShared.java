package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared long-vol analyzer sections for Long Straddle and Long Strangle. */
abstract class LongVolPluginSharedBase implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(LongVolPluginSharedBase.class);

    public abstract StrategyType strategy();

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(strategy())) {
            return List.of();
        }
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(combinedPremiumDrift(query));
        sections.add(ivRankVsRealized(query));
        return sections;
    }

    private AnalyzerSection combinedPremiumDrift(TuningEventQuery query) {
        try {
            List<Path> fwd = query.store().listEventFiles(
                    strategy(), TuningEventType.FORWARD_CHECKPOINT, query.fromDate(), query.toDate());
            if (fwd.isEmpty()) {
                return AnalyzerSection.htmlOnly("Combined premium drift",
                        "<p><em>No forward checkpoint data for " + strategy().displayName() + ".</em></p>");
            }
            String glob = globOf(fwd);
            String sql = ""
                    + "SELECT COUNT(*) AS rows, "
                    + "AVG(TRY_CAST(json_extract_string(attr_extra, '$.combinedPremiumDriftPct') AS DOUBLE)) AS avg_drift "
                    + "FROM read_csv_auto([" + glob + "], header=true)";
            List<Map<String, Object>> rows = query.store().query(sql);
            return AnalyzerSection.htmlOnly("Combined premium drift",
                    renderSummary(rows, "avg_drift", "Avg combined premium drift %"));
        } catch (TuningQueryException ex) {
            log.warn("[LongVolPlugin] combined premium drift failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Combined premium drift",
                    "<p><em>Query failed: " + escape(ex.getMessage()) + "</em></p>");
        }
    }

    private AnalyzerSection ivRankVsRealized(TuningEventQuery query) {
        try {
            List<Path> signals = query.store().listEventFiles(
                    strategy(), TuningEventType.SIGNAL, query.fromDate(), query.toDate());
            if (signals.isEmpty()) {
                return AnalyzerSection.htmlOnly("IV rank vs realized vol",
                        "<p><em>No signal data for " + strategy().displayName() + ".</em></p>");
            }
            String glob = globOf(signals);
            String sql = ""
                    + "SELECT AVG(TRY_CAST(json_extract_string(attr_extra, '$.ivRank') AS DOUBLE)) AS avg_iv, "
                    + "COUNT(*) AS signals "
                    + "FROM read_csv_auto([" + glob + "], header=true)";
            List<Map<String, Object>> rows = query.store().query(sql);
            return AnalyzerSection.htmlOnly("IV rank vs realized vol",
                    renderSummary(rows, "avg_iv", "Avg IV rank at entry"));
        } catch (TuningQueryException ex) {
            log.warn("[LongVolPlugin] iv rank section failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("IV rank vs realized vol",
                    "<p><em>Query failed: " + escape(ex.getMessage()) + "</em></p>");
        }
    }

    private static String globOf(List<Path> files) {
        return files.stream()
                .map(p -> "'" + p.toString().replace("\\", "/") + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("''");
    }

    private static String renderSummary(List<Map<String, Object>> rows, String valueKey, String label) {
        if (rows == null || rows.isEmpty()) {
            return "<p><em>No rows.</em></p>";
        }
        Map<String, Object> row = rows.getFirst();
        Object value = row.get(valueKey);
        Object count = row.containsKey("signals") ? row.get("signals") : row.get("rows");
        return "<p>" + label + ": <strong>"
                + (value != null ? String.format(Locale.ROOT, "%.2f", ((Number) value).doubleValue()) : "n/a")
                + "</strong> (n=" + count + ")</p>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("<", "&lt;");
    }
}

@Component
class LongVolPluginSharedStraddle extends LongVolPluginSharedBase {
    @Override public StrategyType strategy() { return StrategyType.LONG_STRADDLE; }
}

@Component
class LongVolPluginSharedStrangle extends LongVolPluginSharedBase {
    @Override public StrategyType strategy() { return StrategyType.LONG_STRANGLE; }
}
