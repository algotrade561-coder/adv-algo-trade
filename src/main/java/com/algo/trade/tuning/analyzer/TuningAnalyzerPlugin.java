package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import java.util.List;

/**
 * Per-strategy analyzer plugin. Implements the strategy-specific custom report
 * sections — the parts of the EOD HTML that don't fit the standard auto-breakdowns
 * (by-day, by-index, by-bucket, by-exit-reason, MAE distribution, slippage, fill
 * ratio, confirmation effectiveness, late-entry simulation).
 *
 * <h2>What a plugin produces</h2>
 * Zero or more {@link AnalyzerSection}s. The coordinator inserts them into the
 * report after the strategy's standard sections, in the order returned.
 *
 * <h2>What a plugin does NOT do</h2>
 * Standard breakdowns (by-day, by-bucket, etc.) come for free from the coordinator
 * — based on the {@link com.algo.trade.tuning.adapter.TuningCaptureAdapter}'s
 * declared {@code BucketDimension}s. Plugins only contribute custom sections that
 * require strategy-specific logic (e.g. matrix-case × bias-score heatmap for OI
 * Momentum; confirmation-effectiveness combinatorics for OI Shift Trap).
 *
 * <h2>Discovery</h2>
 * Implementations are Spring {@code @Component}s, discovered by the coordinator
 * via {@code List<TuningAnalyzerPlugin>} injection. There can be multiple plugins
 * per strategy (rare); coordinator concatenates their sections.
 */
public interface TuningAnalyzerPlugin {

    /** Strategy this plugin contributes custom sections for. */
    StrategyType strategy();

    /**
     * Render order. Plugins with a <b>negative</b> order render <em>before</em> the standard breakdowns
     * (use this for a data-health header that must be read first); plugins with order ≥ 0 render after the
     * standard breakdowns, ascending. Default 100 = "after, in injection order".
     */
    default int order() {
        return 100;
    }

    /**
     * Returns custom sections for the given query. May return an empty list if no
     * matching data exists in the query window — caller treats this as "no custom
     * sections needed."
     *
     * <p>Implementations should fail safely: any internal exception should be
     * caught and turned into an inline-error section so a single bad plugin
     * doesn't fail the whole report. The coordinator wraps with a 60s section
     * timeout (see design § 12.3).</p>
     */
    List<AnalyzerSection> customSections(TuningEventQuery query);
}
