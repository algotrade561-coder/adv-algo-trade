package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.service.HistoricalVixIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Admin endpoint to seed the {@code iv_samples} table with India VIX history
 * pulled from Yahoo Finance. Designed to be invoked once per environment to
 * prime {@link com.algo.trade.indicator.IVRankTracker} with a real historical
 * distribution. After the table is populated, {@code IVRankTracker.getIVRank}
 * returns a true percentile-based rank instead of the VIX-bucket proxy.
 *
 * <h3>Usage</h3>
 * <pre>
 * curl -X POST 'http://localhost:8080/admin/iv-samples/seed-india-vix?years=5'
 * curl -X POST 'http://localhost:8080/admin/iv-samples/seed-india-vix?years=3&amp;indices=NIFTY,BANKNIFTY'
 * </pre>
 *
 * <p>Idempotent — re-running with the same window updates rows in place rather
 * than duplicating, so it's safe to call multiple times.</p>
 */
@RestController
public class HistoricalVixController {

    private static final Logger log = LoggerFactory.getLogger(HistoricalVixController.class);

    private final HistoricalVixIngestService ingestService;
    private final com.algo.trade.persistence.IVSampleRepository ivSampleRepository;

    /** Lazy to avoid forcing a hard dep chain — only used for the post-seed cache reload. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.indicator.IVRankTracker ivRankTracker;

    public HistoricalVixController(HistoricalVixIngestService ingestService,
                                   com.algo.trade.persistence.IVSampleRepository ivSampleRepository) {
        this.ingestService = ingestService;
        this.ivSampleRepository = ivSampleRepository;
    }

    /**
     * <b>Reset + reseed</b> the {@code iv_samples} history on ONE consistent measure (2026-07-06 IV-drift fix).
     * The old series was POLLUTED — an India-VIX seed (~12–14) mixed with the pre-fix, biased bot ATM-IV
     * (~8.7 on the trading/252 clock), which collapsed IV-rank toward 0 and mislabeled the regime as "buy
     * premium". This purges every sample for the target indices (DB + in-memory) and re-seeds purely from
     * India VIX, so seed and future daily samples share the same calendar/365 measure that the IV-calc fix now
     * produces. Destructive — ADMIN/SUPERUSER only. After this, the corrected bot IV (≈ Sensibull) is what
     * gets appended daily, so the series stays consistent.
     *
     * <pre>POST /admin/iv-samples/reseed-india-vix?years=5&amp;indices=NIFTY,BANKNIFTY,SENSEX</pre>
     */
    @PostMapping("/admin/iv-samples/reseed-india-vix")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','SUPERUSER')")
    public ResponseEntity<?> reseedIndiaVix(
            @RequestParam(name = "years", defaultValue = "5") int years,
            @RequestParam(name = "indices", required = false) String indicesCsv) {
        try {
            List<IndexType> indices = parseIndices(indicesCsv);
            List<IndexType> targets = indices.isEmpty() ? List.of(IndexType.values()) : indices;
            // 1) Purge the polluted series (DB + in-memory) for every target index.
            Map<String, Long> purged = new java.util.LinkedHashMap<>();
            for (IndexType idx : targets) {
                long deleted = ivSampleRepository.deleteByIndexType(idx.name());
                purged.put(idx.name(), deleted);
                if (ivRankTracker != null) ivRankTracker.clearHistory(idx);
            }
            log.warn("[VixIngest] RESEED purge: deleted per-index rows {} before reseeding {} years", purged, years);
            // 2) Re-seed cleanly from India VIX on the corrected measure.
            HistoricalVixIngestService.IngestResult result = ingestService.ingest(years, indices);
            // 3) Reload the tracker so the fresh series is live without a restart.
            boolean reloaded = false;
            if (ivRankTracker != null) {
                try { ivRankTracker.reloadFromDb(); reloaded = true; }
                catch (Exception ex) { log.warn("[VixIngest] reload after reseed failed (non-fatal): {}", ex.getMessage()); }
            }
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "purgedRowsByIndex", purged,
                    "rowsFromYahoo", result.rowsFromYahoo(),
                    "indicesPopulated", result.indicesPopulated(),
                    "inserted", result.inserted(),
                    "updated", result.updated(),
                    "firstDate", String.valueOf(result.firstDate()),
                    "lastDate", String.valueOf(result.lastDate()),
                    "ivRankTrackerReloaded", reloaded));
        } catch (IllegalArgumentException ex) {
            log.warn("[VixIngest] reseed rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("[VixIngest] reseed download failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("[VixIngest] reseed failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/admin/iv-samples/seed-india-vix")
    public ResponseEntity<?> seedIndiaVix(
            @RequestParam(name = "years", defaultValue = "5") int years,
            @RequestParam(name = "indices", required = false) String indicesCsv) {
        try {
            List<IndexType> indices = parseIndices(indicesCsv);
            log.info("[VixIngest] seed requested: years={}, indices={}", years, indices);
            HistoricalVixIngestService.IngestResult result = ingestService.ingest(years, indices);
            // 4 Jun 2026: also reload IVRankTracker so this endpoint can recover
            // a running session without restart (e.g. when the in-memory cache
            // is empty because @PostConstruct ran before the table was populated).
            boolean reloaded = false;
            if (ivRankTracker != null) {
                try {
                    ivRankTracker.reloadFromDb();
                    reloaded = true;
                    log.info("[VixIngest] IVRankTracker reloaded after manual seed");
                } catch (Exception ex) {
                    log.warn("[VixIngest] IVRankTracker reload after manual seed failed (non-fatal): {}",
                            ex.getMessage());
                }
            }
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "rowsFromYahoo", result.rowsFromYahoo(),
                    "indicesPopulated", result.indicesPopulated(),
                    "inserted", result.inserted(),
                    "updated", result.updated(),
                    "skipped", result.skipped(),
                    "firstDate", String.valueOf(result.firstDate()),
                    "lastDate", String.valueOf(result.lastDate()),
                    "ivRankTrackerReloaded", reloaded));
        } catch (IllegalArgumentException ex) {
            // Some library validators (Hibernate / Spring proxies) throw
            // IllegalArgumentException with cryptic messages. Log the full
            // stack so the actual cause is visible.
            log.warn("[VixIngest] IllegalArgumentException — full trace follows: {}",
                    ex.getMessage(), ex);
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error",
                            "message", ex.getMessage(),
                            "errorClass", ex.getClass().getName(),
                            "stackTop", ex.getStackTrace().length > 0
                                    ? ex.getStackTrace()[0].toString() : ""));
        } catch (IOException ex) {
            log.warn("[VixIngest] download failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("[VixIngest] failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /**
     * Export the entire iv_samples table as a CSV download.
     * Usage: GET /admin/iv-samples/export.csv
     * Response headers set so browser saves as iv_samples.csv.
     */
    @GetMapping(value = "/admin/iv-samples/export.csv",
                produces = "text/csv")
    public ResponseEntity<?> exportCsv() {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ingestService.exportCsv(new OutputStreamWriter(buf, StandardCharsets.UTF_8));
            byte[] data = buf.toByteArray();
            log.info("[VixIngest] CSV export: {} bytes", data.length);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"iv_samples.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(new InputStreamResource(new ByteArrayInputStream(data)));
        } catch (IOException ex) {
            log.warn("[VixIngest] CSV export failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /**
     * Import iv_samples from an uploaded CSV.
     * Usage: POST /admin/iv-samples/import (multipart form-data, field name "file")
     * CSV format: indexType,sampleDate,iv  (header row required)
     * Idempotent — existing (indexType, sampleDate) rows are updated; new ones inserted.
     */
    @PostMapping(value = "/admin/iv-samples/import",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importCsv(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "file is empty"));
        }
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            log.info("[VixIngest] CSV import: filename={}, size={} bytes",
                    file.getOriginalFilename(), file.getSize());
            HistoricalVixIngestService.IngestResult result = ingestService.importCsv(reader);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "rowsFromCsv", result.rowsFromYahoo(),
                    "inserted", result.inserted(),
                    "updated", result.updated(),
                    "skipped", result.skipped(),
                    "firstDate", String.valueOf(result.firstDate()),
                    "lastDate", String.valueOf(result.lastDate())));
        } catch (IllegalArgumentException ex) {
            log.warn("[VixIngest] CSV import rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("[VixIngest] CSV import IO failure: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    private static List<IndexType> parseIndices(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<IndexType> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String t = token.trim().toUpperCase();
            if (t.isEmpty()) continue;
            out.add(IndexType.valueOf(t));
        }
        return out;
    }
}
