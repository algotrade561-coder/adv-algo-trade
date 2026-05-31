package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backfills forward-return columns on reject, V3 skip, and spike-episode CSVs
 * using {@link OiMarketSnapshotBuffer}.
 */
@Component
public class OiForwardReturnBackfillService {

    private static final Logger log = LoggerFactory.getLogger(OiForwardReturnBackfillService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Path REJECTS = Path.of("reports", "entry-signals", "oi-momentum-rejects.csv");
    private static final Path V3_DIR = Path.of("data", "v3-decisions");
    private static final Path SPIKE_DIR = Path.of("data", "spike-episodes");

    private static final List<String> REJECT_FWD_COLS = List.of(
            "fwdSpot15m", "fwdSpot30m", "fwdSpot60m", "fwdAtmCe30m", "fwdAtmPe30m");

    private static final List<String> V3_FWD_COLS = List.of(
            "fwdSpot15m", "fwdSpot30m", "fwdSpot60m", "fwdAtmCe30m", "fwdAtmPe30m");

    private static final List<String> SPIKE_FWD_COLS = List.of("fwdSpot5m", "fwdSpot15m", "fwdSpot30m");

    private final OiMarketSnapshotBuffer snapshotBuffer;

    public OiForwardReturnBackfillService(OiMarketSnapshotBuffer snapshotBuffer) {
        this.snapshotBuffer = snapshotBuffer;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 120_000)
    public void backfillAll() {
        try {
            backfillRejectCsv();
            backfillV3Csvs();
            backfillSpikeCsvs();
        } catch (Exception ex) {
            log.warn("[OiForwardReturn] backfill failed: {}", ex.getMessage());
        }
    }

    void backfillRejectCsv() throws IOException {
        if (!Files.isRegularFile(REJECTS)) {
            return;
        }
        backfillCsv(REJECTS, "timestamp", "indexType", REJECT_FWD_COLS,
                (row, ts, indexName) -> {
                    IndexType ix = parseIndex(indexName);
                    if (ix == null) {
                        return Map.of();
                    }
                    return forwardMap(ix, ts, List.of(15, 30, 60), true);
                });
    }

    void backfillV3Csvs() throws IOException {
        if (!Files.isDirectory(V3_DIR)) {
            return;
        }
        for (Path file : listRecentCsv(V3_DIR, 3)) {
            backfillCsv(file, "timestamp", "indexType", V3_FWD_COLS, (row, ts, indexName) -> {
                String verdict = row.getOrDefault("verdict", "");
                if (verdict == null || verdict.isBlank() || "ENTER".equals(verdict)) {
                    return Map.of();
                }
                if (!isBlank(row.get("fwdSpot60m"))) {
                    return Map.of();
                }
                IndexType ix = parseIndex(indexName);
                if (ix == null) {
                    return Map.of();
                }
                return forwardMap(ix, ts, List.of(15, 30, 60), true);
            });
        }
    }

    void backfillSpikeCsvs() throws IOException {
        if (!Files.isDirectory(SPIKE_DIR)) {
            return;
        }
        for (Path file : listRecentCsv(SPIKE_DIR, 3)) {
            backfillCsv(file, "firstAt", "indexType", SPIKE_FWD_COLS, (row, ts, indexName) -> {
                if (!isBlank(row.get("fwdSpot30m"))) {
                    return Map.of();
                }
                IndexType ix = parseIndex(indexName);
                if (ix == null) {
                    return Map.of();
                }
                Map<String, String> out = new LinkedHashMap<>();
                putSpotForward(out, ix, ts, 5, "fwdSpot5m");
                putSpotForward(out, ix, ts, 15, "fwdSpot15m");
                putSpotForward(out, ix, ts, 30, "fwdSpot30m");
                return out;
            });
        }
    }

    private Map<String, String> forwardMap(IndexType ix, Instant base, List<Integer> spotMinutes,
                                           boolean includePremiums) {
        if (base == null || Duration.between(base, Instant.now()).compareTo(Duration.ofMinutes(61)) < 0) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int m : spotMinutes) {
            putSpotForward(out, ix, base, m, "fwdSpot" + m + "m");
        }
        if (includePremiums) {
            snapshotBuffer.nearest(ix, base.plus(Duration.ofMinutes(30)))
                    .ifPresent(s -> {
                        out.put("fwdAtmCe30m", format(s.atmCeLast()));
                        out.put("fwdAtmPe30m", format(s.atmPeLast()));
                    });
        }
        return out;
    }

    private void putSpotForward(Map<String, String> out, IndexType ix, Instant base, int minutes, String col) {
        snapshotBuffer.nearest(ix, base.plus(Duration.ofMinutes(minutes)))
                .ifPresent(s -> out.put(col, format(s.spot())));
    }

    @FunctionalInterface
    interface RowBackfill {
        Map<String, String> fill(Map<String, String> row, Instant ts, String indexName);
    }

    static void backfillCsv(Path path, String tsCol, String indexCol, List<String> fwdCols,
                            RowBackfill backfill) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            return;
        }
        String[] header = parseCsvLine(lines.get(0));
        Map<String, Integer> colIndex = indexHeader(header);
        String[] extendedHeader = rebuildHeader(header, colIndex, fwdCols);
        List<String> newLines = new ArrayList<>();
        newLines.add(joinCsv(extendedHeader));
        boolean anyRowChanged = !java.util.Arrays.equals(header, extendedHeader);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = padCols(parseCsvLine(line), extendedHeader.length);
            Map<String, String> row = rowMap(extendedHeader, cols, colIndex);
            Instant ts = parseInstant(row.get(tsCol));
            String indexName = row.getOrDefault(indexCol, "");
            String lastFwdCol = fwdCols.get(fwdCols.size() - 1);
            if (ts != null && isBlank(row.get(lastFwdCol))) {
                Map<String, String> updates = backfill.fill(row, ts, indexName);
                if (!updates.isEmpty()) {
                    row.putAll(updates);
                    anyRowChanged = true;
                }
            }
            newLines.add(joinCsv(rowToArray(extendedHeader, row)));
        }
        if (anyRowChanged) {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, newLines);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("[OiForwardReturn] updated {} ({} rows)", path, newLines.size() - 1);
        }
    }

    private static List<Path> listRecentCsv(Path dir, int limit) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted((a, b) -> b.getFileName().compareTo(a.getFileName()))
                    .limit(limit)
                    .toList();
        }
    }

    private static IndexType parseIndex(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return IndexType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String format(double v) {
        if (v == 0) {
            return "";
        }
        return String.format("%.2f", v);
    }

    static Map<String, Integer> indexHeader(String[] header) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].trim(), i);
        }
        return map;
    }

    static boolean ensureColumns(String[] header, Map<String, Integer> colIndex, List<String> cols) {
        boolean changed = false;
        for (String c : cols) {
            if (!colIndex.containsKey(c)) {
                changed = true;
            }
        }
        return changed;
    }

    static String[] rebuildHeader(String[] original, Map<String, Integer> colIndex, List<String> extra) {
        List<String> out = new ArrayList<>();
        for (String h : original) {
            out.add(h);
        }
        for (String c : extra) {
            if (!colIndex.containsKey(c)) {
                out.add(c);
                colIndex.put(c, out.size() - 1);
            }
        }
        return out.toArray(new String[0]);
    }

    static Map<String, String> rowMap(String[] header, String[] cols, Map<String, Integer> colIndex) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String h : header) {
            Integer idx = colIndex.get(h);
            row.put(h, idx != null && idx < cols.length ? cols[idx] : "");
        }
        return row;
    }

    static String[] rowToArray(String[] header, Map<String, String> row) {
        String[] out = new String[header.length];
        for (int i = 0; i < header.length; i++) {
            out[i] = row.getOrDefault(header[i], "");
        }
        return out;
    }

    static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String[] padCols(String[] cols, int len) {
        if (cols.length >= len) {
            return cols;
        }
        String[] out = new String[len];
        System.arraycopy(cols, 0, out, 0, cols.length);
        return out;
    }

    static String joinCsv(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = cols[i] == null ? "" : cols[i];
            if (v.contains(",") || v.contains("\"")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
