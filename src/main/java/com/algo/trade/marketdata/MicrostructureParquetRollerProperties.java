package com.algo.trade.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage / retention config for {@link MicrostructureParquetRoller}.
 *
 * <pre>
 * atm-microstructure:
 *   roller:
 *     enabled: true
 *     csv-dir: data/tuning
 *     csv-prefix: atm-microstructure-
 *     archive-base-dir: data/tuning/microstructure-archive
 *     delete-csv-after-roll: true
 *     retention-days: 180          # ~6 months
 *     compression: zstd            # zstd | snappy | gzip
 * </pre>
 *
 * <p>This is a <b>separate</b> roller from {@code tuning.roller} — it only handles the
 * high-frequency ATM microstructure CSVs written by {@link AtmMicrostructureRecorder}.
 * It never touches the tuning-event archive.</p>
 */
@ConfigurationProperties(prefix = "atm-microstructure.roller")
public class MicrostructureParquetRollerProperties {

    private boolean enabled = true;
    private String csvDir = "data/tuning";
    private String csvPrefix = "atm-microstructure-";
    private String archiveBaseDir = "data/tuning/microstructure-archive";
    private boolean deleteCsvAfterRoll = true;
    /** ~6 months. Archived Parquet (and orphaned CSVs) older than this are deleted. */
    private int retentionDays = 180;
    /** DuckDB Parquet codec. zstd is ~2.5x smaller than gzip CSV for this data. */
    private String compression = "zstd";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getCsvDir() { return csvDir; }
    public void setCsvDir(String v) { this.csvDir = v; }

    public String getCsvPrefix() { return csvPrefix; }
    public void setCsvPrefix(String v) { this.csvPrefix = v; }

    public String getArchiveBaseDir() { return archiveBaseDir; }
    public void setArchiveBaseDir(String v) { this.archiveBaseDir = v; }

    public boolean isDeleteCsvAfterRoll() { return deleteCsvAfterRoll; }
    public void setDeleteCsvAfterRoll(boolean v) { this.deleteCsvAfterRoll = v; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }

    public String getCompression() { return compression; }
    public void setCompression(String v) { this.compression = v; }
}
