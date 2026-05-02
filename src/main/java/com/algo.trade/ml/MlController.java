package com.algo.trade.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for ML signal scoring observation and management.
 */
@RestController
@RequestMapping("/ml")
public class MlController {

    private static final Logger log = LoggerFactory.getLogger(MlController.class);

    private final MlSignalScorer scorer;
    private final MlShadowRecorder shadowRecorder;
    private final TrainingDataCollector trainingDataCollector;
    private final MlVirtualTradeTracker virtualTradeTracker;
    private final MlExitShadowRecorder exitShadowRecorder;

    public MlController(MlSignalScorer scorer,
                         MlShadowRecorder shadowRecorder,
                         TrainingDataCollector trainingDataCollector,
                         MlVirtualTradeTracker virtualTradeTracker,
                         MlExitShadowRecorder exitShadowRecorder) {
        this.scorer = scorer;
        this.shadowRecorder = shadowRecorder;
        this.trainingDataCollector = trainingDataCollector;
        this.virtualTradeTracker = virtualTradeTracker;
        this.exitShadowRecorder = exitShadowRecorder;
    }

    /** Get current ML scorer status. */
    @GetMapping("/status")
    public MlSignalScorer.MlScorerStatus status() {
        return scorer.status();
    }

    /** Get ML shadow comparison summary for the UI. */
    @GetMapping("/shadow")
    public MlShadowRecorder.MlShadowSummary shadow(@RequestParam(defaultValue = "TODAY") String period) {
        return shadowRecorder.getSummary(period);
    }

    /** Reload the model from disk (after retraining). */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadModel() {
        boolean success = scorer.loadModel();
        return ResponseEntity.ok(Map.of(
                "success", success,
                "status", scorer.status()
        ));
    }

    /** Generate training data from entry-signals.csv + trade outcomes. */
    @PostMapping("/training-data")
    public ResponseEntity<?> generateTrainingData() {
        try {
            TrainingDataCollector.TrainingResult result = trainingDataCollector.generateTrainingData();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Training data generation failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Get ML virtual trades summary — trades ML would have taken but system skipped. */
    @GetMapping("/virtual-trades")
    public MlVirtualTradeTracker.VirtualTradeSummary virtualTrades() {
        return virtualTradeTracker.getSummary();
    }

    /** Get ML exit shadow summary — exit evaluation observations. */
    @GetMapping("/exit-shadow")
    public MlExitShadowRecorder.ExitShadowSummary exitShadow(@RequestParam(defaultValue = "TODAY") String period) {
        return exitShadowRecorder.getSummary(period);
    }
}
