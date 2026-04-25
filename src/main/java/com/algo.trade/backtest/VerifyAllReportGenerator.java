package com.algo.trade.backtest;

import java.nio.file.Path;

/**
 * Generates HTML and JSON reports for a verify-all run.
 * Implementation will be provided in task 6.
 */
public interface VerifyAllReportGenerator {

    /**
     * Generate the HTML suite report.
     *
     * @param result          the complete verification result
     * @param outputDirectory directory to write the report into
     * @return path to the generated HTML file
     */
    Path generateHtmlReport(VerifyAllResult result, Path outputDirectory);

    /**
     * Generate the JSON summary.
     *
     * @param result          the complete verification result
     * @param outputDirectory directory to write the summary into
     * @return path to the generated JSON file
     */
    Path generateJsonSummary(VerifyAllResult result, Path outputDirectory);
}
