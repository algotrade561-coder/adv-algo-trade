package com.algo.trade.controller;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.insights.AiAnalysisService;
import com.algo.trade.insights.BacktestAutoTuneService;
import com.algo.trade.persistence.AiRecommendationEntity;
import com.algo.trade.persistence.AiRecommendationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiInsightsController {

    private static final Logger log = LoggerFactory.getLogger(AiInsightsController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AiRecommendationRepository repository;
    private final AiAnalysisService analysisService;
    private final BacktestAutoTuneService autoTuneService;
    private final ObjectMapper objectMapper;

    public AiInsightsController(AiRecommendationRepository repository,
                                AiAnalysisService analysisService,
                                BacktestAutoTuneService autoTuneService,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.analysisService = analysisService;
        this.autoTuneService = autoTuneService;
        this.objectMapper = objectMapper;
    }

    /** Returns today's AI recommendations, or last 20 if none today. */
    @GetMapping("/recommendations")
    public List<Map<String, Object>> getRecommendations(
            @RequestParam(defaultValue = "today") String period) {

        List<AiRecommendationEntity> entities;
        if ("today".equals(period)) {
            Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
            Instant tomorrow = LocalDate.now(IST).plusDays(1).atStartOfDay(IST).toInstant();
            entities = repository.findByGeneratedAtBetweenOrderByGeneratedAtDesc(todayStart, tomorrow);
            if (entities.isEmpty()) {
                entities = repository.findTop20ByOrderByGeneratedAtDesc();
            }
        } else {
            entities = repository.findTop20ByOrderByGeneratedAtDesc();
        }

        return entities.stream().map(this::toDto).toList();
    }

    /** Downloads last 30 days of candle data and runs a 3×3 parameter grid backtest. */
    @PostMapping("/backtest/sync-and-tune")
    public ResponseEntity<Map<String, Object>> syncAndTune(
            @RequestParam(defaultValue = "NIFTY") String underlying) {
        log.info("Backtest auto-tune requested: underlying={}", underlying);
        try {
            UnderlyingSymbol symbol = UnderlyingSymbol.valueOf(underlying.toUpperCase());
            AiRecommendationEntity result = autoTuneService.runTune(symbol);
            return ResponseEntity.ok(toDto(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown underlying: " + underlying));
        } catch (Exception e) {
            log.warn("Backtest auto-tune failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Backtest tune failed: " + e.getMessage()));
        }
    }

    /** Triggers an immediate analysis run (manual). */
    @PostMapping("/recommendations/run")
    public ResponseEntity<Map<String, Object>> runNow() {
        log.info("Manual AI analysis run requested");
        try {
            AiRecommendationEntity result = analysisService.runAnalysis("MANUAL");
            return ResponseEntity.ok(toDto(result));
        } catch (Exception e) {
            log.warn("Manual AI analysis run failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    private Map<String, Object> toDto(AiRecommendationEntity e) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", e.getId());
        dto.put("generatedAt", e.getGeneratedAt().toString());
        dto.put("runType", e.getRunType());
        dto.put("marketContext", parseJson(e.getMarketContext()));
        dto.put("tradeSummary", parseJson(e.getTradeSummary()));
        dto.put("signalSummary", parseJson(e.getSignalSummary()));
        dto.put("findings", parseJsonList(e.getFindings()));
        dto.put("suggestions", parseJsonList(e.getSuggestions()));
        dto.put("overallAssessment", e.getOverallAssessment());
        return dto;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse AI recommendation JSON field: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse AI recommendation JSON list field: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
