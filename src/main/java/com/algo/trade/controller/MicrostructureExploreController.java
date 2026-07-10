package com.algo.trade.controller;

import com.algo.trade.marketdata.MicrostructureParquetRollerProperties;
import com.algo.trade.tuning.store.TuningEventStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * READ-ONLY explorer over the high-frequency ATM microstructure capture.
 *
 * <p>Backed entirely by DuckDB ({@link TuningEventStore}) querying the Parquet archive
 * (written by {@code MicrostructureParquetRoller}) for past dates and the live daily CSV
 * for today. <b>All aggregation/bucketing happens in SQL</b> so payloads stay tiny — the
 * browser never receives raw per-second rows.</p>
 *
 * <p>This controller has <b>no</b> trading side effects: no order path, no strategy state,
 * no writes. It exists to validate the captured data and the shadow early-detection
 * signals (Workstream C/D of {@code docs/MICROSTRUCTURE-EARLY-DETECTION-PLAN.md}).</p>
 *
 * <p>All user inputs are sanitised (index allow-list, date parsed to {@link LocalDate},
 * numeric bounds, optionType ∈ {CE,PE}) before any SQL string is built.</p>
 */
@RestController
@RequestMapping("/microstructure")
public class MicrostructureExploreController {

    private static final Logger log = LoggerFactory.getLogger(MicrostructureExploreController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern INDEX_OK = Pattern.compile("[A-Z0-9]{1,16}");
    private static final Pattern CSV_DATE = Pattern.compile(".*?(\\d{4}-\\d{2}-\\d{2})\\.csv");

    private final TuningEventStore store;
    private final MicrostructureParquetRollerProperties props;

    public MicrostructureExploreController(TuningEventStore store,
                                           MicrostructureParquetRollerProperties props) {
        this.store = store;
        this.props = props;
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    /** Dates for which microstructure data exists (archive day-dirs ∪ live CSVs). */
    @GetMapping("/days")
    public List<String> days() {
        TreeSet<String> out = new TreeSet<>();
        // Live CSVs
        Path csvDir = Path.of(props.getCsvDir());
        if (Files.isDirectory(csvDir)) {
            try (Stream<Path> s = Files.list(csvDir)) {
                s.map(p -> p.getFileName().toString())
                 .filter(n -> n.startsWith(props.getCsvPrefix()) && n.endsWith(".csv"))
                 .map(n -> { var m = CSV_DATE.matcher(n); return m.matches() ? m.group(1) : null; })
                 .filter(java.util.Objects::nonNull)
                 .forEach(out::add);
            } catch (Exception ex) {
                log.debug("[MicroExplore] days csv scan failed: {}", ex.getMessage());
            }
        }
        // Archived day-dirs
        Path archive = Path.of(props.getArchiveBaseDir());
        if (Files.isDirectory(archive)) {
            try (Stream<Path> walk = Files.walk(archive)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isDirectory(p) && p.getFileName().toString().startsWith("day=")) {
                        LocalDate d = dayDirDate(p);
                        if (d != null) out.add(d.toString());
                    }
                }
            } catch (Exception ex) {
                log.debug("[MicroExplore] days archive scan failed: {}", ex.getMessage());
            }
        }
        return new ArrayList<>(out).reversed();
    }

    /** Distinct strikes captured for an index on a date. */
    @GetMapping("/strikes")
    public ResponseEntity<?> strikes(@RequestParam String index, @RequestParam String date) {
        String idx = safeIndex(index);
        LocalDate d = safeDate(date);
        if (idx == null || d == null) return ResponseEntity.badRequest().body(err("bad index/date"));
        String src = ticksCte(idx, d);
        if (src == null) return ResponseEntity.ok(List.of());
        String sql = src + " SELECT DISTINCT strike FROM ticks ORDER BY strike";
        return ResponseEntity.ok(queryColumn(sql, "strike"));
    }

    // ── Lenses ──────────────────────────────────────────────────────────────────

    /**
     * Headline lens: per-bucket LTP, OI level, and per-bucket traded-volume delta for the
     * CE and PE of a chosen strike (the OI buildup/unwinding view).
     */
    @GetMapping("/strike-timeline")
    public ResponseEntity<?> strikeTimeline(@RequestParam String index,
                                            @RequestParam String date,
                                            @RequestParam int strike,
                                            @RequestParam(defaultValue = "60") int bucketSec) {
        String idx = safeIndex(index);
        LocalDate d = safeDate(date);
        int b = clampBucket(bucketSec);
        if (idx == null || d == null) return ResponseEntity.badRequest().body(err("bad index/date"));
        String src = ticksCte(idx, d);
        if (src == null) return ResponseEntity.ok(List.of());
        String sql = src +
            " , bucketed AS (" +
            "   SELECT (exchangeTsEpochSec / " + b + ") * " + b + " AS bucketTs, optionType," +
            "          last(ltp ORDER BY exchangeTsEpochSec) AS ltp," +
            "          last(oi ORDER BY exchangeTsEpochSec) AS oi," +
            "          max(cumVolume) AS cumVol" +
            "   FROM ticks WHERE strike = " + strike +
            "   GROUP BY bucketTs, optionType" +
            " )" +
            " SELECT bucketTs," +
            "   max(CASE WHEN optionType='CE' THEN ltp END) AS ceLtp," +
            "   max(CASE WHEN optionType='PE' THEN ltp END) AS peLtp," +
            "   max(CASE WHEN optionType='CE' THEN oi END)  AS ceOi," +
            "   max(CASE WHEN optionType='PE' THEN oi END)  AS peOi," +
            "   max(CASE WHEN optionType='CE' THEN cumVol END) - " +
            "     lag(max(CASE WHEN optionType='CE' THEN cumVol END)) OVER (ORDER BY bucketTs) AS ceVolDelta," +
            "   max(CASE WHEN optionType='PE' THEN cumVol END) - " +
            "     lag(max(CASE WHEN optionType='PE' THEN cumVol END)) OVER (ORDER BY bucketTs) AS peVolDelta" +
            " FROM bucketed GROUP BY bucketTs ORDER BY bucketTs";
        return ResponseEntity.ok(safeQuery(sql));
    }

    /**
     * Liquidity / quote-pressure lens: per-bucket average spread (bps of mid), microprice
     * drift vs mid, and bid queue-imbalance, for one option leg.
     */
    @GetMapping("/liquidity")
    public ResponseEntity<?> liquidity(@RequestParam String index,
                                       @RequestParam String date,
                                       @RequestParam int strike,
                                       @RequestParam(defaultValue = "CE") String optionType,
                                       @RequestParam(defaultValue = "60") int bucketSec) {
        String idx = safeIndex(index);
        LocalDate d = safeDate(date);
        int b = clampBucket(bucketSec);
        String ot = "PE".equalsIgnoreCase(optionType) ? "PE" : "CE";
        if (idx == null || d == null) return ResponseEntity.badRequest().body(err("bad index/date"));
        String src = ticksCte(idx, d);
        if (src == null) return ResponseEntity.ok(List.of());
        String sql = src +
            " SELECT (exchangeTsEpochSec / " + b + ") * " + b + " AS bucketTs," +
            "   avg(CASE WHEN (bestBid+bestAsk) > 0 THEN (bestAsk-bestBid)/((bestBid+bestAsk)/2.0)*10000 END) AS spreadBps," +
            "   avg(CASE WHEN (bidQty+askQty) > 0 THEN (bestBid*askQty + bestAsk*bidQty)/(bidQty+askQty) - (bestBid+bestAsk)/2.0 END) AS micropriceDrift," +
            "   avg(CASE WHEN (bidQty+askQty) > 0 THEN bidQty::DOUBLE/(bidQty+askQty) END) AS bidImbalance" +
            " FROM ticks WHERE strike = " + strike + " AND optionType = '" + ot + "'" +
            " GROUP BY bucketTs ORDER BY bucketTs";
        return ResponseEntity.ok(safeQuery(sql));
    }

    /** GATE-0: observed OI refresh cadence for an index/date (median seconds between OI changes). */
    @GetMapping("/oi-cadence")
    public ResponseEntity<?> oiCadence(@RequestParam String index, @RequestParam String date) {
        String idx = safeIndex(index);
        LocalDate d = safeDate(date);
        if (idx == null || d == null) return ResponseEntity.badRequest().body(err("bad index/date"));
        String src = ticksCte(idx, d);
        if (src == null) return ResponseEntity.ok(Map.of());
        String sql = src +
            " , chg AS (" +
            "   SELECT tradingSymbol, exchangeTsEpochSec AS ts," +
            "     exchangeTsEpochSec - lag(exchangeTsEpochSec) OVER (PARTITION BY tradingSymbol ORDER BY exchangeTsEpochSec) AS gap" +
            "   FROM (SELECT tradingSymbol, exchangeTsEpochSec, oi," +
            "                lag(oi) OVER (PARTITION BY tradingSymbol ORDER BY exchangeTsEpochSec) AS prevOi FROM ticks)" +
            "   WHERE oi IS DISTINCT FROM prevOi" +
            " )" +
            " SELECT median(gap) AS medianGapSec, avg(gap) AS meanGapSec, count(*) AS oiChangeEvents" +
            " FROM chg WHERE gap IS NOT NULL AND gap > 0";
        List<Map<String, Object>> r = safeQuery(sql);
        return ResponseEntity.ok(r.isEmpty() ? Map.of() : r.get(0));
    }

    /** Virtual early-detection signals logged by the shadow detector for an index/date. */
    @GetMapping("/shadow-signals")
    public ResponseEntity<?> shadowSignals(@RequestParam String index, @RequestParam String date) {
        String idx = safeIndex(index);
        LocalDate d = safeDate(date);
        if (idx == null || d == null) return ResponseEntity.badRequest().body(err("bad index/date"));
        Path csv = Path.of(props.getCsvDir()).resolve("early-detection-shadow-" + d + ".csv");
        if (!Files.isRegularFile(csv)) return ResponseEntity.ok(List.of());
        String sql = "SELECT * FROM read_csv_auto('" + sq(csv.toAbsolutePath().toString())
                + "', header=true) WHERE index = '" + idx + "' ORDER BY recvEpochMs";
        return ResponseEntity.ok(safeQuery(sql));
    }

    // ── SQL source builder ──────────────────────────────────────────────────────

    /**
     * Builds the leading {@code WITH ticks AS (...)} CTE: deduped per
     * (tradingSymbol, exchangeTsEpochSec), filtered to the index, unioning whichever of
     * the Parquet archive and the live CSV exist for the date. Returns null if neither
     * source exists (caller returns an empty result).
     */
    private String ticksCte(String idx, LocalDate date) {
        List<String> sources = new ArrayList<>();
        Path dayDir = Path.of(props.getArchiveBaseDir())
                .resolve("year=" + date.getYear())
                .resolve("month=" + String.format(Locale.ROOT, "%02d", date.getMonthValue()))
                .resolve("day=" + String.format(Locale.ROOT, "%02d", date.getDayOfMonth()));
        if (Files.isDirectory(dayDir) && dirHasParquet(dayDir)) {
            sources.add("SELECT recvEpochMs, exchangeTsEpochSec, index, strike, optionType, tradingSymbol,"
                    + " ltp, bestBid, bestAsk, bidQty, askQty, cumVolume, oi"
                    + " FROM read_parquet('" + sq(dayDir.toAbsolutePath() + "/**/*.parquet")
                    + "', hive_partitioning=true, union_by_name=true)");
        }
        Path csv = Path.of(props.getCsvDir()).resolve(props.getCsvPrefix() + date + ".csv");
        if (Files.isRegularFile(csv)) {
            sources.add("SELECT recvEpochMs, exchangeTsEpochSec, index, strike, optionType, tradingSymbol,"
                    + " ltp, bestBid, bestAsk, bidQty, askQty, cumVolume, oi"
                    + " FROM read_csv_auto('" + sq(csv.toAbsolutePath().toString())
                    + "', header=true, union_by_name=true)");
        }
        if (sources.isEmpty()) return null;
        String union = String.join(" UNION ALL BY NAME ", sources);
        return "WITH raw AS (" + union + "), "
                + "ranked AS (SELECT *, row_number() OVER (PARTITION BY tradingSymbol, exchangeTsEpochSec"
                + " ORDER BY recvEpochMs DESC) AS rn FROM raw"
                + " WHERE index = '" + idx + "' AND exchangeTsEpochSec > 0), "
                + "ticks AS (SELECT * FROM ranked WHERE rn = 1) ";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> safeQuery(String sql) {
        try {
            return store.query(sql);
        } catch (Exception ex) {
            log.warn("[MicroExplore] query failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<Object> queryColumn(String sql, String col) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : safeQuery(sql)) out.add(row.get(col));
        return out;
    }

    private boolean dirHasParquet(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        } catch (Exception ex) {
            return false;
        }
    }

    private static LocalDate dayDirDate(Path dayDir) {
        int y = -1, m = -1, d = -1;
        for (Path p : dayDir) {
            String s = p.toString();
            if (s.startsWith("year=")) y = parseInt(s.substring(5));
            else if (s.startsWith("month=")) m = parseInt(s.substring(6));
            else if (s.startsWith("day=")) d = parseInt(s.substring(4));
        }
        if (y < 0 || m < 0 || d < 0) return null;
        try { return LocalDate.of(y, m, d); } catch (Exception ex) { return null; }
    }

    private static String safeIndex(String index) {
        if (index == null) return null;
        String u = index.trim().toUpperCase(Locale.ROOT);
        return INDEX_OK.matcher(u).matches() ? u : null;
    }

    private static LocalDate safeDate(String date) {
        try { return LocalDate.parse(date.trim()); } catch (Exception ex) { return null; }
    }

    private static int clampBucket(int bucketSec) {
        if (bucketSec < 1) return 1;
        return Math.min(bucketSec, 3600);
    }

    private static String sq(String s) { return s.replace("'", "''"); }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return -1; }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
