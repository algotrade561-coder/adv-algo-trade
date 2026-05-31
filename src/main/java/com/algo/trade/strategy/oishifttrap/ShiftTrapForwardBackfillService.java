package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.OiForwardReturnBackfillService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backfills forward checkpoint columns on trap forward CSV and forward returns on eval episodes.
 */
@Component
public class ShiftTrapForwardBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapForwardBackfillService.class);
    private static final Path EVALS = Path.of("reports", "entry-signals", "oi-shift-trap-evaluations.csv");

    private static final List<String> EVAL_FWD_COLS = List.of(
            "fwdSpot15m", "fwdSpot30m", "fwdSpot60m", "fwdAtmCe30m", "fwdAtmPe30m");

    private static final int[] CHECKPOINT_SECONDS = {30, 60, 180, 300, 600, 900, 1800};
    private static final String[] CHECKPOINT_LABELS = {"30s", "1m", "3m", "5m", "10m", "15m", "30m"};

    private final ShiftTrapChainSnapshotBuffer snapshotBuffer;
    private final com.algo.trade.strategy.oimomentum.OiMarketSnapshotBuffer oiSnapshotBuffer;

    public ShiftTrapForwardBackfillService(ShiftTrapChainSnapshotBuffer snapshotBuffer,
                                            com.algo.trade.strategy.oimomentum.OiMarketSnapshotBuffer oiSnapshotBuffer) {
        this.snapshotBuffer = snapshotBuffer;
        this.oiSnapshotBuffer = oiSnapshotBuffer;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 120_000)
    public void backfillAll() {
        try {
            backfillForwardCsv();
            backfillEvalCsv();
        } catch (Exception ex) {
            log.warn("[ShiftTrapForward] backfill failed: {}", ex.getMessage());
        }
    }

    void backfillForwardCsv() throws IOException {
        Path file = OiShiftTrapForwardRecorder.FILE;
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            return;
        }
        String[] header = OiForwardReturnBackfillService.parseCsvLine(lines.get(0));
        Map<String, Integer> colIndex = OiForwardReturnBackfillService.indexHeader(header);
        List<String> fwdCols = forwardCheckpointColumns();
        String[] extendedHeader = OiForwardReturnBackfillService.rebuildHeader(header, colIndex, fwdCols);
        List<String> newLines = new ArrayList<>();
        newLines.add(OiForwardReturnBackfillService.joinCsv(extendedHeader));
        boolean changed = !java.util.Arrays.equals(header, extendedHeader);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = OiForwardReturnBackfillService.padCols(
                    OiForwardReturnBackfillService.parseCsvLine(line), extendedHeader.length);
            Map<String, String> row = OiForwardReturnBackfillService.rowMap(extendedHeader, cols, colIndex);
            Instant signalAt = parseInstant(row.get("signalAt"));
            if (signalAt != null && isBlank(row.get("spot_30m"))) {
                Map<String, String> updates = fillCheckpoints(row, signalAt);
                if (!updates.isEmpty()) {
                    row.putAll(updates);
                    changed = true;
                }
            }
            newLines.add(OiForwardReturnBackfillService.joinCsv(
                    OiForwardReturnBackfillService.rowToArray(extendedHeader, row)));
        }
        if (changed) {
            writeAtomic(file, newLines);
        }
    }

    void backfillEvalCsv() throws IOException {
        if (!Files.isRegularFile(EVALS)) {
            return;
        }
        OiForwardReturnBackfillService.backfillCsv(EVALS, "episodeLastAt", "underlying", EVAL_FWD_COLS,
                (row, ts, indexName) -> {
                    if (ts == null || "true".equalsIgnoreCase(row.get("signalGenerated"))) {
                        return Map.of();
                    }
                    if (Duration.between(ts, Instant.now()).compareTo(Duration.ofMinutes(61)) < 0) {
                        return Map.of();
                    }
                    IndexType ix = parseIndex(indexName);
                    if (ix == null) {
                        return Map.of();
                    }
                    Map<String, String> out = new LinkedHashMap<>();
                    putSpotForward(out, ix, ts, 15, "fwdSpot15m");
                    putSpotForward(out, ix, ts, 30, "fwdSpot30m");
                    putSpotForward(out, ix, ts, 60, "fwdSpot60m");
                    String side = inferWouldBeSide(row);
                    oiSnapshotBuffer.nearest(ix, ts.plus(Duration.ofMinutes(30))).ifPresent(s -> {
                        if ("PE".equals(side)) {
                            out.put("fwdAtmPe30m", format(s.atmPeLast()));
                        } else {
                            out.put("fwdAtmCe30m", format(s.atmCeLast()));
                        }
                    });
                    return out;
                });
    }

    private Map<String, String> fillCheckpoints(Map<String, String> row, Instant signalAt) {
        if (Duration.between(signalAt, Instant.now()).compareTo(Duration.ofSeconds(35)) < 0) {
            return Map.of();
        }
        IndexType ix = parseIndex(row.get("underlying"));
        if (ix == null) {
            return Map.of();
        }
        int trapStrike = parseInt(row.get("trapStrike"));
        String trapSide = row.get("trapSide");
        double spotAtSignal = parseDouble(row.get("spotAtSignal"));
        long trappedAtSignal = parseLong(row.get("trappedOiAtSignal"));
        if (spotAtSignal <= 0) {
            spotAtSignal = parseDouble(row.get("spot"));
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < CHECKPOINT_SECONDS.length; i++) {
            int sec = CHECKPOINT_SECONDS[i];
            if (Duration.between(signalAt, Instant.now()).getSeconds() < sec + 5) {
                continue;
            }
            String label = CHECKPOINT_LABELS[i];
            if (!isBlank(row.get("spot_" + label))) {
                continue;
            }
            Instant target = signalAt.plusSeconds(sec);
            double finalSpotAtSignal = spotAtSignal;
            snapshotBuffer.nearest(ix, target).ifPresent(snap -> {
                ShiftTrapChainSnapshotBuffer.StrikeOi strike = snap.strikeOi(trapStrike);
                long trapped = "PE".equals(trapSide) ? strike.oiPe() : strike.oiCe();
                long opposite = "PE".equals(trapSide) ? strike.oiCe() : strike.oiPe();
                double imb = opposite > 0 ? trapped / (double) opposite : 0;
                out.put("spot_" + label, format(snap.spot()));
                if (finalSpotAtSignal > 0) {
                    out.put("spotMovePct_" + label,
                            format((snap.spot() - finalSpotAtSignal) / finalSpotAtSignal * 100.0));
                }
                out.put("trappedOi_" + label, String.valueOf(trapped));
                out.put("trappedOiDelta_" + label, String.valueOf(trapped - trappedAtSignal));
                out.put("oppositeOi_" + label, String.valueOf(opposite));
                out.put("imbalance_" + label, format(imb));
                out.put("atmCeLtp_" + label, format(snap.atmCeLast()));
                out.put("atmPeLtp_" + label, format(snap.atmPeLast()));
            });
        }
        return out;
    }

    private void putSpotForward(Map<String, String> out, IndexType ix, Instant base, int minutes, String col) {
        oiSnapshotBuffer.nearest(ix, base.plus(Duration.ofMinutes(minutes)))
                .ifPresent(s -> out.put(col, format(s.spot())));
    }

    private static List<String> forwardCheckpointColumns() {
        List<String> cols = new ArrayList<>();
        for (String cp : OiShiftTrapForwardRecorder.CHECKPOINTS) {
            cols.add("spot_" + cp);
            cols.add("spotMovePct_" + cp);
            cols.add("trappedOi_" + cp);
            cols.add("trappedOiDelta_" + cp);
            cols.add("oppositeOi_" + cp);
            cols.add("imbalance_" + cp);
            cols.add("atmCeLtp_" + cp);
            cols.add("atmPeLtp_" + cp);
        }
        return cols;
    }

    private static String inferWouldBeSide(Map<String, String> row) {
        int ceScore = (int) parseLong(row.get("bestCeScore"));
        int peScore = (int) parseLong(row.get("bestPeScore"));
        return peScore > ceScore ? "PE" : "CE";
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

    private static double parseDouble(String v) {
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(v.trim().split("\\.")[0]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int parseInt(String v) {
        return (int) parseLong(v);
    }

    private static void writeAtomic(Path path, List<String> lines) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, lines);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        log.info("[ShiftTrapForward] updated {} ({} rows)", path, lines.size() - 1);
    }
}
