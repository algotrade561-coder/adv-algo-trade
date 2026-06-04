package com.algo.trade.service;

import com.algo.trade.domain.IndexType;
import com.algo.trade.persistence.IVSampleEntity;
import com.algo.trade.persistence.IVSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot ingest of India VIX history from Yahoo Finance into the
 * {@code iv_samples} table.
 *
 * <p>Yahoo Finance's ^INDIAVIX CSV download endpoint returns a CSV with columns:
 * {@code Date,Open,High,Low,Close,Adj Close,Volume}. We persist one row per
 * trading day using the Close value as the daily IV proxy.</p>
 *
 * <p>The same India VIX series is written under all three index keys
 * (NIFTY, BANKNIFTY, SENSEX) by default, with a configurable scale factor
 * per index so BANKNIFTY's structurally higher IV is approximated rather than
 * silently equated to NIFTY. The scale factors are empirical defaults; the
 * caller can override per-index via the {@code scaleFactors} parameter.</p>
 *
 * <p>Idempotent: rows are written via
 * {@link IVSampleRepository#findByIndexTypeAndSampleDate} so re-running with
 * the same date range updates existing values rather than duplicating.</p>
 */
@Service
public class HistoricalVixIngestService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalVixIngestService.class);

    /**
     * Yahoo's v8 chart JSON endpoint for India VIX history. The v7 CSV download
     * endpoint started requiring an authenticated crumb cookie in 2024 and now
     * returns 401 for unauthenticated requests. The v8 chart API still serves
     * historical OHLC publicly with no auth.
     */
    private static final String YAHOO_CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%%5EINDIAVIX"
            + "?period1=%d&period2=%d&interval=1d&events=history";

    /**
     * Per-index scale factors applied to the India VIX close. India VIX is
     * derived from NIFTY ATM straddle pricing; BANKNIFTY and SENSEX both
     * historically realize higher IV than NIFTY. These are conservative
     * empirical multipliers — recalibrate against actual ATM straddle data
     * once the {@code IVRankTracker} has collected its own per-index history.
     */
    private static final double NIFTY_SCALE = 1.00;
    private static final double BANKNIFTY_SCALE = 1.15;
    private static final double SENSEX_SCALE = 1.05;

    private final IVSampleRepository repository;
    private final HttpClient httpClient;

    /** Injected lazily to avoid circular dependency (IVRankTracker is in a
     *  different package and we only need it for post-seed cache refresh). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.indicator.IVRankTracker ivRankTracker;

    /** Auto-seed on startup if the iv_samples table is empty. Default ON. Disable
     *  with {@code tuning.iv-sample-bootstrap.enabled=false}. */
    @Value("${tuning.iv-sample-bootstrap.enabled:true}")
    private boolean autoSeedEnabled = true;

    /** Years of India VIX history to fetch when auto-seeding. Default 5. */
    @Value("${tuning.iv-sample-bootstrap.years:5}")
    private int autoSeedYears = 5;

    public HistoricalVixIngestService(IVSampleRepository repository) {
        this.repository = repository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Auto-bootstrap: when the application is fully started, check whether the
     * {@code iv_samples} table is empty. If so, pull
     * {@code tuning.iv-sample-bootstrap.years} (default 5) of India VIX history
     * from Yahoo Finance and populate the table. If the table already has data
     * we leave it alone — re-seeding is an explicit operator action via
     * {@code POST /advalgotrade/admin/iv-samples/seed-india-vix}.
     *
     * <p>Runs after the app is fully started (so Spring beans, scheduling, etc.
     * are all up) and is best-effort: any Yahoo outage or network failure is
     * logged at WARN and silently swallowed so app startup is never blocked.
     * The IVRankTracker has a VIX-bucket proxy fallback that keeps the system
     * usable even when this seed fails.</p>
     *
     * <p>Network call runs on a background daemon thread so a slow Yahoo
     * response can't block the ApplicationReadyEvent listener chain. The
     * seed completes in the background and {@code IVRankTracker} picks up
     * the new samples on its next restart (samples are loaded once in
     * its {@code @PostConstruct}).</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoSeedIfEmpty() {
        if (!autoSeedEnabled) {
            log.info("[VixIngest] auto-seed disabled by config (tuning.iv-sample-bootstrap.enabled=false)");
            return;
        }
        long existing;
        try {
            existing = repository.count();
        } catch (Exception ex) {
            log.warn("[VixIngest] auto-seed: count() failed, skipping bootstrap: {}", ex.getMessage());
            return;
        }
        if (existing > 0) {
            log.info("[VixIngest] auto-seed: iv_samples table already has {} rows — leaving as-is", existing);
            return;
        }
        log.warn("[VixIngest] auto-seed: iv_samples is EMPTY → scheduling background fetch of {} years "
                + "of India VIX from Yahoo Finance", autoSeedYears);
        Thread t = new Thread(() -> {
            try {
                IngestResult result = ingest(autoSeedYears, null);
                log.warn("[VixIngest] auto-seed COMPLETE: {}", result);
                // 4 Jun 2026: trigger an immediate IVRankTracker reload so the
                // tracker picks up the new samples without requiring a restart.
                if (ivRankTracker != null) {
                    try {
                        ivRankTracker.reloadFromDb();
                        log.warn("[VixIngest] IVRankTracker reloaded — IV rank now backed by {} years of history",
                                autoSeedYears);
                    } catch (Exception ex) {
                        log.warn("[VixIngest] IVRankTracker reload failed (non-fatal — next restart will pick up): {}",
                                ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.warn("[VixIngest] auto-seed FAILED — IVRankTracker will fall back to VIX-bucket proxy "
                        + "until the operator runs POST /admin/iv-samples/seed-india-vix manually. Cause: {}",
                        ex.getMessage());
            }
        }, "iv-sample-autoseed");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Fetch India VIX history from Yahoo Finance for the given lookback window
     * and persist into {@code iv_samples} for all three indices.
     *
     * @param years            how many calendar years back to fetch (1-10)
     * @param indices          which indices to populate; if null/empty, all
     *                         three are written
     * @return ingest result summary
     * @throws IOException     on network failure or malformed CSV
     */
    @Transactional
    public IngestResult ingest(int years, List<IndexType> indices) throws IOException {
        if (years < 1 || years > 10) {
            throw new IllegalArgumentException("years must be 1..10, got " + years);
        }
        List<IndexType> targets = (indices == null || indices.isEmpty())
                ? List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)
                : indices;

        long endEpoch = ZonedDateTime.now(ZoneId.of("UTC")).toEpochSecond();
        long startEpoch = ZonedDateTime.now(ZoneId.of("UTC"))
                .minusYears(years).toEpochSecond();
        String url = String.format(YAHOO_CHART_URL, startEpoch, endEpoch);

        log.info("[VixIngest] Fetching India VIX history from Yahoo Finance: years={}, "
                + "indices={}, url={}", years, targets, url);

        List<DailyClose> closes = fetchCsv(url);
        log.info("[VixIngest] Received {} daily VIX rows from Yahoo Finance", closes.size());

        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        for (IndexType ix : targets) {
            double scale = scaleFor(ix);
            for (DailyClose dc : closes) {
                double iv = dc.close() * scale;
                if (iv <= 0 || iv > 200) {
                    // Yahoo occasionally serves null rows as 0 / "null" → 0.
                    skipped.incrementAndGet();
                    continue;
                }
                Optional<IVSampleEntity> existing =
                        repository.findByIndexTypeAndSampleDate(ix.name(), dc.date());
                if (existing.isPresent()) {
                    IVSampleEntity row = existing.get();
                    if (Math.abs(row.getIv() - iv) > 0.0001) {
                        row.setIv(iv);
                        repository.save(row);
                        updated.incrementAndGet();
                    }
                } else {
                    repository.save(new IVSampleEntity(ix.name(), dc.date(), iv));
                    inserted.incrementAndGet();
                }
            }
        }

        IngestResult res = new IngestResult(
                closes.size(), targets.size(),
                inserted.get(), updated.get(), skipped.get(),
                closes.isEmpty() ? null : closes.get(0).date(),
                closes.isEmpty() ? null : closes.get(closes.size() - 1).date());
        log.info("[VixIngest] Done: {}", res);
        return res;
    }

    private double scaleFor(IndexType ix) {
        switch (ix) {
            case NIFTY:     return NIFTY_SCALE;
            case BANKNIFTY: return BANKNIFTY_SCALE;
            case SENSEX:    return SENSEX_SCALE;
            default:        return NIFTY_SCALE;
        }
    }

    /**
     * Pull and parse the Yahoo Finance v8 chart JSON. Structure is:
     * <pre>
     * { "chart": { "result": [ {
     *     "timestamp": [unix_seconds, ...],
     *     "indicators": { "quote": [ { "close": [double, ...] } ] }
     * } ] } }
     * </pre>
     * Days with no trading have {@code null} in the close array — skipped.
     */
    private List<DailyClose> fetchCsv(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0 Safari/537.36")
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();

        HttpResponse<java.io.InputStream> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("VIX download interrupted", ie);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Yahoo VIX download HTTP " + resp.statusCode()
                    + " — body suppressed");
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root;
        try (java.io.InputStream in = resp.body()) {
            root = mapper.readTree(in);
        }

        com.fasterxml.jackson.databind.JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            String err = root.path("chart").path("error").toString();
            throw new IOException("Yahoo VIX response had no result: " + err);
        }
        com.fasterxml.jackson.databind.JsonNode r0 = result.get(0);
        com.fasterxml.jackson.databind.JsonNode timestamps = r0.path("timestamp");
        com.fasterxml.jackson.databind.JsonNode closes = r0.path("indicators")
                .path("quote").path(0).path("close");
        if (!timestamps.isArray() || !closes.isArray()
                || timestamps.size() != closes.size()) {
            throw new IOException("Yahoo VIX JSON malformed: timestamps="
                    + timestamps.size() + " closes=" + closes.size());
        }
        List<DailyClose> out = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode c = closes.get(i);
            if (c == null || c.isNull()) continue;
            double close = c.asDouble(0);
            if (close <= 0) continue;
            long epochSec = timestamps.get(i).asLong();
            LocalDate date = java.time.Instant.ofEpochSecond(epochSec)
                    .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
            out.add(new DailyClose(date, close));
        }
        return out;
    }

    /**
     * Stream every {@code iv_samples} row as CSV: {@code indexType,sampleDate,iv}.
     * Caller is expected to wrap the {@link java.io.Writer} (e.g. servlet response).
     */
    public void exportCsv(java.io.Writer writer) throws IOException {
        java.io.BufferedWriter bw = new java.io.BufferedWriter(writer);
        bw.write("indexType,sampleDate,iv");
        bw.newLine();
        for (IndexType ix : IndexType.values()) {
            var rows = repository.findByIndexTypeOrderBySampleDateAsc(ix.name());
            for (IVSampleEntity row : rows) {
                bw.write(row.getIndexType());
                bw.write(',');
                bw.write(row.getSampleDate().toString());
                bw.write(',');
                bw.write(String.format("%.4f", row.getIv()));
                bw.newLine();
            }
        }
        bw.flush();
    }

    /**
     * Import CSV (same format as {@link #exportCsv}) into {@code iv_samples}.
     * Idempotent — re-running with the same rows updates existing values in place.
     *
     * @param reader CSV input (caller manages the stream)
     * @return ingest result summary
     */
    @Transactional
    public IngestResult importCsv(java.io.Reader reader) throws IOException {
        java.io.BufferedReader br = new java.io.BufferedReader(reader);
        String header = br.readLine();
        if (header == null
                || !header.toLowerCase().replace(" ", "").startsWith("indextype,sampledate,iv")) {
            throw new IllegalArgumentException(
                    "expected CSV header 'indexType,sampleDate,iv' — got: " + header);
        }
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        LocalDate first = null, last = null;
        int rows = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] cols = line.split(",");
            if (cols.length < 3) { skipped.incrementAndGet(); continue; }
            try {
                String ixName = cols[0].trim();
                LocalDate date = LocalDate.parse(cols[1].trim());
                double iv = Double.parseDouble(cols[2].trim());
                if (iv <= 0 || iv > 200) { skipped.incrementAndGet(); continue; }
                if (first == null || date.isBefore(first)) first = date;
                if (last == null || date.isAfter(last)) last = date;
                rows++;
                Optional<IVSampleEntity> existing =
                        repository.findByIndexTypeAndSampleDate(ixName, date);
                if (existing.isPresent()) {
                    IVSampleEntity row = existing.get();
                    if (Math.abs(row.getIv() - iv) > 0.0001) {
                        row.setIv(iv);
                        repository.save(row);
                        updated.incrementAndGet();
                    }
                } else {
                    repository.save(new IVSampleEntity(ixName, date, iv));
                    inserted.incrementAndGet();
                }
            } catch (Exception e) {
                skipped.incrementAndGet();
            }
        }
        IngestResult res = new IngestResult(rows, 3, inserted.get(), updated.get(),
                skipped.get(), first, last);
        log.info("[VixIngest] CSV import done: {}", res);
        return res;
    }

    /** Result summary returned by the admin endpoint. */
    public record IngestResult(
            int rowsFromYahoo,
            int indicesPopulated,
            int inserted,
            int updated,
            int skipped,
            LocalDate firstDate,
            LocalDate lastDate
    ) { }

    private record DailyClose(LocalDate date, double close) { }
}
