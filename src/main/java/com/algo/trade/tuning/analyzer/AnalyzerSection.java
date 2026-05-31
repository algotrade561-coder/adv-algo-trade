package com.algo.trade.tuning.analyzer;

import java.util.Map;
import java.util.Objects;

/**
 * One section of the EOD tuning report. Carries a human-readable title, an
 * already-rendered HTML body, and an optional structured-data view used by tests
 * and by potential exports to ML pipelines.
 *
 * <p>The {@link TuningAnalyzerCoordinator} (Phase 6) concatenates sections in
 * document order and wraps them in a navigable table of contents.</p>
 *
 * @param title section title, rendered as an {@code <h2>} in the report
 * @param htmlBody rendered HTML body — plugin is responsible for sanitization
 * @param data optional structured form of the same data; never {@code null} (use
 *             {@link Map#of()} for empty)
 */
public record AnalyzerSection(String title, String htmlBody, Map<String, Object> data) {

    public AnalyzerSection {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(htmlBody, "htmlBody");
        Objects.requireNonNull(data, "data (use Map.of() for empty)");
    }

    /** Convenience for sections that don't expose structured data. */
    public static AnalyzerSection htmlOnly(String title, String htmlBody) {
        return new AnalyzerSection(title, htmlBody, Map.of());
    }
}
