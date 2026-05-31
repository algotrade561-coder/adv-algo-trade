package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.adapter.TuningCaptureAdapter;
import com.algo.trade.tuning.adapter.TuningCaptureAdapterRegistry;
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
    private final TuningCaptureAdapterRegistry adapterRegistry;

    public TuningAnalyzerCoordinator(TuningEventStore store,
                                       List<TuningAnalyzerPlugin> plugins,
                                       TuningCaptureAdapterRegistry adapterRegistry) {
        this.store = store;
        this.plugins = plugins;
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

        // Second pass: only emit per-strategy sections for the ones that have data.
        for (StrategyType strategy : withData) {
            TuningCaptureAdapter adapter = adapterRegistry.find(strategy).orElse(null);
            if (adapter != null) {
                report.addSections(standardBreakdowns(query, strategy));
            } else {
                report.addSection(AnalyzerSection.htmlOnly(strategy.displayName(),
                        "<p><em>No capture adapter registered for this strategy.</em></p>"));
            }
            for (TuningAnalyzerPlugin plugin : plugins) {
                if (plugin.strategy() != strategy) {
                    continue;
                }
                try {
                    report.addSections(plugin.customSections(query));
                } catch (Exception ex) {
                    log.warn("[TuningAnalyzer] plugin {} failed: {}",
                            plugin.getClass().getSimpleName(), ex.getMessage());
                    report.addSection(AnalyzerSection.htmlOnly(
                            plugin.getClass().getSimpleName(),
                            "<p class=\"error\">Plugin failed: " + escape(ex.getMessage()) + "</p>"));
                }
            }
        }
        return report;
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
            if (!store.listEventFiles(strategy, type, from, to).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<AnalyzerSection> standardBreakdowns(TuningEventQuery query, StrategyType strategy) {
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(StandardBreakdownSections.byDay(query, strategy));
        sections.add(StandardBreakdownSections.byIndex(query, strategy));
        sections.add(StandardBreakdownSections.signalSummary(query, strategy));
        sections.add(StandardBreakdownSections.exitSummary(query, strategy));
        sections.add(StandardBreakdownSections.fillRatio(query, strategy));
        return sections;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
