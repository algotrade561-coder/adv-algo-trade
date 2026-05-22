package com.algo.trade.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.algo.trade.data.SnapshotFileWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyReportBundleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void collectsTodaySignalsLogsAndChainSnapshotsIntoZip() throws Exception {
        LocalDate today = LocalDate.of(2026, 5, 22);
        Instant midday = today.atTime(12, 0).atZone(DailyReportBundleService.IST).toInstant();
        Path signalsDir = tempDir.resolve("reports/entry-signals");
        Path logsDir = tempDir.resolve("logs/application");
        Path chainDir = tempDir.resolve("data/chain-snapshots").resolve(today.toString());
        Files.createDirectories(signalsDir);
        Files.createDirectories(logsDir);
        Files.createDirectories(chainDir);

        Path signalCsv = signalsDir.resolve("entry-signals.csv");
        Files.writeString(signalCsv, "a,b\n1,2\n");
        Files.setLastModifiedTime(signalCsv, FileTime.from(midday));
        String ymd = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path logFile = logsDir.resolve("algo-trade-" + ymd + "-120000.log");
        Files.writeString(logFile, "INFO test\n");
        Files.setLastModifiedTime(logFile, FileTime.from(midday));
        Path chainFile = chainDir.resolve("NIFTY_0915.json.gz");
        Files.write(chainFile, new byte[] {1, 2, 3});

        SnapshotFileWriter snapshotFileWriter =
                new SnapshotFileWriter(new ObjectMapper(), chainDir.getParent().toString());
        DailyReportBundleService service = new DailyReportBundleService(
                snapshotFileWriter,
                signalsDir.toString(),
                tempDir.resolve("logs").toString());

        var summary = service.summary(today);
        assertEquals(1, summary.signals().fileCount());
        assertEquals(1, summary.logs().fileCount());
        assertEquals(1, summary.chainSnapshots().fileCount());
        assertEquals(3, summary.totalFiles());

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        service.writeFullAnalysisZip(today, zipBytes);
        assertTrue(zipBytes.size() > 0);

        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes.toByteArray()))) {
            while (zis.getNextEntry() != null) {
                entries++;
            }
        }
        assertEquals(3, entries);
    }
}
