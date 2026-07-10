package com.algo.trade.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the daily Edge Research briefs (separate from the tuning report). The Python orchestrator
 * ({@code tools/edge-study/run-research.sh}) writes {@code reports/research/<date>/research-report.html} +
 * {@code summary.json}; this controller lists those dated reports and serves the HTML/JSON, plus an async
 * "run now" trigger. Filesystem-backed (no DB/entity) — the reports ARE files, so listing = directory scan.
 */
@RestController
@RequestMapping("/reports/research")
public class ResearchReportController {

    private static final Logger log = LoggerFactory.getLogger(ResearchReportController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}"); // also blocks path traversal
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${research.output-dir:reports/research}")
    private String outputDir;

    @Value("${research.script:tools/edge-study/run-research.sh}")
    private String script;

    /** Most-recent research briefs (newest first), each with summary metadata for the table. */
    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(@RequestParam(defaultValue = "30") int limit) {
        Path root = Path.of(outputDir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> DATE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(Math.max(1, limit))
                    .map(this::toJob)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[research] list failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> toJob(Path dir) {
        String date = dir.getFileName().toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", date);
        m.put("date", date);
        Path htmlFile = dir.resolve("research-report.html");
        Path summaryFile = dir.resolve("summary.json");
        m.put("hasHtml", Files.exists(htmlFile));
        if (Files.exists(summaryFile)) {
            try {
                Map<?, ?> json = MAPPER.readValue(Files.readString(summaryFile), Map.class);
                m.put("coverage", json.get("coverage"));
                m.put("counts", json.get("counts"));
                m.put("verdict", json.get("verdict"));
                m.put("generatedAt", json.get("generatedAt"));
                if (json.containsKey("error")) m.put("error", json.get("error"));
            } catch (Exception ex) {
                log.debug("[research] summary parse failed for {}: {}", date, ex.getMessage());
            }
        }
        try {
            Path stamp = Files.exists(htmlFile) ? htmlFile : dir;
            m.put("updatedAt", Files.getLastModifiedTime(stamp).toInstant().toString());
        } catch (IOException ignored) {
            m.put("updatedAt", Instant.now().toString());
        }
        return m;
    }

    @GetMapping(value = "/jobs/{date}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable String date) {
        if (!DATE.matcher(date).matches()) {
            return ResponseEntity.badRequest().build();
        }
        Path p = Path.of(outputDir, date, "research-report.html");
        if (!Files.exists(p)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(Files.readString(p));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("read error: " + e.getMessage());
        }
    }

    @GetMapping(value = "/jobs/{date}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> summary(@PathVariable String date) {
        if (!DATE.matcher(date).matches()) {
            return ResponseEntity.badRequest().body("{}");
        }
        Path p = Path.of(outputDir, date, "summary.json");
        if (!Files.exists(p)) {
            return ResponseEntity.ok("{}");
        }
        try {
            return ResponseEntity.ok(Files.readString(p));
        } catch (IOException e) {
            return ResponseEntity.ok("{}");
        }
    }

    /**
     * Fire-and-forget manual run for a date (default today IST). The orchestrator is long-running, so we don't
     * block — the brief appears in the jobs list when it finishes. Returns immediately with STARTED.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam(required = false) String date) {
        String d = (date != null && DATE.matcher(date).matches()) ? date : LocalDate.now(IST).toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", script);
            pb.environment().put("RESEARCH_DATE", d);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.start(); // detached; writes its own per-day run.log
            log.info("[research] manual run triggered for {}", d);
            return ResponseEntity.accepted().body(Map.of("date", d, "status", "STARTED"));
        } catch (IOException e) {
            log.warn("[research] manual run failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
