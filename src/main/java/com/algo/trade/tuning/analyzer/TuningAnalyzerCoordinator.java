package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.adapter.TuningCaptureAdapter;
import com.algo.trade.tuning.adapter.TuningCaptureAdapterRegistry;
import com.algo.trade.tuning.analyzer.core.CaptureHealthSection;
import com.algo.trade.tuning.analyzer.core.CrossStrategyScorecard;
import com.algo.trade.tuning.analyzer.core.StandardBreakdownSections;
import com.algo.trade.tuning.store.TuningEventStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Phase 6 — orchestrates unified tuning reports in-process on the trading JVM. */
@Component
public class TuningAnalyzerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TuningAnalyzerCoordinator.class);

    private final TuningEventStore store;
    private final List<TuningAnalyzerPlugin> plugins;
    private final List<GenericStrategyAnalyzerPlugin> genericPlugins;
    private final TuningCaptureAdapterRegistry adapterRegistry;

    public TuningAnalyzerCoordinator(TuningEventStore store,
                                       List<TuningAnalyzerPlugin> plugins,
                                       List<GenericStrategyAnalyzerPlugin> genericPlugins,
                                       TuningCaptureAdapterRegistry adapterRegistry) {
        this.store = store;
        this.plugins = plugins;
        this.genericPlugins = genericPlugins;
        this.adapterRegistry = adapterRegistry;
    }

    public TuningReport analyze(LocalDate from, LocalDate to, Set<StrategyType> strategies) {
        List<StrategyType> ordered = strategies.stream().sorted(Comparator.comparing(Enum::name)).toList();
        TuningEventQuery query = new TuningEventQuery(from, to, Set.copyOf(ordered), store);
        TuningReport report = new TuningReport(from, to,
                ordered.stream().map(Enum::name).collect(Collectors.toList()), List.of());

        // First pass: partition strategies into "has data" and "empty" so we can
        // skip the per-strategy noise for the empty ones and surface them in a
        // single summary block at the top instead.
        List<StrategyType> withData = new ArrayList<>();
        List<StrategyType> empty = new ArrayList<>();
        for (StrategyType strategy : ordered) {
            if (hasAnyEvents(strategy, from, to)) {
                withData.add(strategy);
            } else {
                empty.add(strategy);
            }
        }

        if (!empty.isEmpty()) {
            String list = empty.stream()
                    .map(StrategyType::displayName)
                    .collect(Collectors.joining(", "));
            report.addSection(AnalyzerSection.htmlOnly(
                    "Strategies with no captured events",
                    "<p><em>" + empty.size() + " of " + ordered.size()
                            + " strategies had no events in this window — skipped from the per-strategy breakdowns below.</em></p>"
                            + "<p>" + escape(list) + "</p>"));
        }

        // Cross-strategy scorecard — the one-glance "tune each strategy from here" table, up top.
        if (!withData.isEmpty()) {
            try {
                report.addSection(CrossStrategyScorecard.section(query, withData));
            } catch (Exception ex) {
                log.warn("[TuningAnalyzer] scorecard failed (non-fatal): {}", ex.getMessage());
            }
            // Capture-health — the report showing its own data quality (coverage / degraded checkpoints)
            // so the numbers below are read with the right trust. (#180)
            try {
                report.addSection(CaptureHealthSection.section(query, withData));
            } catch (Exception ex) {
                log.warn("[TuningAnalyzer] capture-health failed (non-fatal): {}", ex.getMessage());
            }
        }

        // Per-strategy sections — banner first ("## " sentinel), then standard
        // breakdowns and custom plugin sections, each title prefixed with the
        // strategy display name for self-identification. The "## " prefix is
        // detected by TuningReport.renderHtml() to render the banner with a
        // larger styled header and a dashed divider between strategies.
        for (StrategyType strategy : withData) {
            String name = strategy.displayName();
            report.addSection(AnalyzerSection.htmlOnly(
                    "## " + name,
                    "<p class=\"strategy-subtitle\">Captured events in window — see sections below.</p>"));

            // Plugins for this strategy, ordered: negative order() renders BEFORE the standard breakdowns
            // (e.g. the data-health header), order >= 0 renders after — both ascending.
            List<TuningAnalyzerPlugin> mine = plugins.stream()
                    .filter(p -> p.strategy() == strategy)
                    .sorted(Comparator.comparingInt(TuningAnalyzerPlugin::order))
                    .toList();
            List<TuningAnalyzerPlugin> pre = mine.stream().filter(p -> p.order() < 0).toList();
            List<TuningAnalyzerPlugin> post = mine.stream().filter(p -> p.order() >= 0).toList();

            // Generic (cross-strategy) plugins run for EVERY strategy, same order convention.
            List<GenericStrategyAnalyzerPlugin> genPre = genericPlugins.stream()
                    .filter(p -> p.order() < 0)
                    .sorted(Comparator.comparingInt(GenericStrategyAnalyzerPlugin::order)).toList();
            List<GenericStrategyAnalyzerPlugin> genPost = genericPlugins.stream()
                    .filter(p -> p.order() >= 0)
                    .sorted(Comparator.comparingInt(GenericStrategyAnalyzerPlugin::order)).toList();

            for (TuningAnalyzerPlugin plugin : pre) {
                runPlugin(plugin, query, name, report);
            }
            for (GenericStrategyAnalyzerPlugin plugin : genPre) {
                runGenericPlugin(plugin, query, strategy, name, report);
            }
            TuningCaptureAdapter adapter = adapterRegistry.find(strategy).orElse(null);
            if (adapter != null) {
                for (AnalyzerSection s : standardBreakdowns(query, strategy)) {
                    report.addSection(prefix(name, s));
                }
            } else {
                report.addSection(AnalyzerSection.htmlOnly(name + " — adapter",
                        "<p><em>No capture adapter registered for this strategy.</em></p>"));
            }
            for (TuningAnalyzerPlugin plugin : post) {
                runPlugin(plugin, query, name, report);
            }
            for (GenericStrategyAnalyzerPlugin plugin : genPost) {
                runGenericPlugin(plugin, query, strategy, name, report);
            }
        }
        return report;
    }

    /** Runs one plugin's sections into the report, prefixed + fail-safe (a bad plugin can't break the report). */
    private void runPlugin(TuningAnalyzerPlugin plugin, TuningEventQuery query, String name, TuningReport report) {
        try {
            for (AnalyzerSection s : plugin.customSections(query)) {
                report.addSection(prefix(name, s));
            }
        } catch (Exception ex) {
            log.warn("[TuningAnalyzer] plugin {} failed: {}",
                    plugin.getClass().getSimpleName(), ex.getMessage());
            report.addSection(AnalyzerSection.htmlOnly(
                    name + " — " + plugin.getClass().getSimpleName(),
                    "<p class=\"error\">Plugin failed: " + escape(ex.getMessage()) + "</p>"));
        }
    }

    /** Runs one generic (cross-strategy) plugin's sections for a strategy — prefixed + fail-safe. */
    private void runGenericPlugin(GenericStrategyAnalyzerPlugin plugin, TuningEventQuery query,
                                  StrategyType strategy, String name, TuningReport report) {
        try {
            for (AnalyzerSection s : plugin.customSections(query, strategy)) {
                report.addSection(prefix(name, s));
            }
        } catch (Exception ex) {
            log.warn("[TuningAnalyzer] generic plugin {} failed for {}: {}",
                    plugin.getClass().getSimpleName(), strategy, ex.getMessage());
            report.addSection(AnalyzerSection.htmlOnly(
                    name + " — " + plugin.getClass().getSimpleName(),
                    "<p class=\"error\">Plugin failed: " + escape(ex.getMessage()) + "</p>"));
        }
    }

    /** Prepend the strategy display name to a section's title so it's self-identifying. */
    private static AnalyzerSection prefix(String strategyName, AnalyzerSection s) {
        return new AnalyzerSection(strategyName + " — " + s.title(), s.htmlBody(), s.data());
    }

    /**
     * Returns true if the strategy has at least one event file of any type in the
     * window — cheap directory listing, no DuckDB query.
     */
    private boolean hasAnyEvents(StrategyType strategy, LocalDate from, LocalDate to) {
        for (TuningEventType type : EnumSet.of(
                TuningEventType.EVALUATION, TuningEventType.SIGNAL,
                TuningEventType.EXECUTION, TuningEventType.EXIT,
                TuningEventType.FORWARD_CHECKPOINT, TuningEventType.SHADOW_GATE,
                TuningEventType.LEG)) {
            // Check BOTH the recent CSVs and the rolled Parquet archive — for an older date range the CSVs
            // were rolled+deleted, so a CSV-only check wrongly reports "no events" and skips the strategy.
            if (!store.listEventFiles(strategy, type, from, to).isEmpty()
                    || !store.listArchiveFiles(strategy, type, from, to).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Universal per-strategy breakdowns. Ordered from highest-level to
     * deepest so the report reads top-down. Custom plugins emit additional
     * sections after these via {@link TuningAnalyzerPlugin#customSections}.
     */
    private List<AnalyzerSection> standardBreakdowns(TuningEventQuery query, StrategyType strategy) {
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(StandardBreakdownSections.overview(query, strategy));
        sections.add(StandardBreakdownSections.byDay(query, strategy));
        sections.add(StandardBreakdownSections.byIndex(query, strategy));
        sections.add(StandardBreakdownSections.topBlockers(query, strategy));
        sections.add(StandardBreakdownSections.blockerByIndex(query, strategy));
        sections.add(StandardBreakdownSections.byHourOfDay(query, strategy));
        sections.add(StandardBreakdownSections.episodeSizeDistribution(query, strategy));
        sections.add(StandardBreakdownSections.signalSummary(query, strategy));
        sections.add(StandardBreakdownSections.exitSummary(query, strategy));
        sections.add(StandardBreakdownSections.fillRatio(query, strategy));
        return sections;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
