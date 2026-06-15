package com.algo.trade.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ML model loader and scorer — used exclusively for shadow/observation scoring.
 * Does NOT influence any trading decisions. The shadow recorder calls this to
 * compute what the ML model would score, and records it alongside the system score.
 */
@Component
public class MlSignalScorer {

    private static final Logger log = LoggerFactory.getLogger(MlSignalScorer.class);

    /** Path where the trained model JSON is stored (hot-reloadable). */
    private static final Path MODEL_PATH = Path.of("data/ml/signal-model.json");
    private static final String CLASSPATH_MODEL = "ml/signal-model.json";

    private final AtomicReference<GradientBoostedTreeModel> model = new AtomicReference<>();

    @PostConstruct
    public void init() {
        boolean loaded = loadModel();
        if (!loaded) {
            log.warn("ML model NOT loaded at startup — shadow scoring will be inactive. " +
                    "Checked file: {} and classpath: {}", MODEL_PATH, CLASSPATH_MODEL);
        }
    }

    /**
     * Load or reload the model from disk. Called at startup and via REST endpoint.
     * @return true if model loaded successfully
     */
    public boolean loadModel() {
        // Try file system first (allows hot-reload without redeploy)
        if (Files.exists(MODEL_PATH)) {
            try {
                GradientBoostedTreeModel loaded = GradientBoostedTreeModel.loadFromFile(MODEL_PATH);
                model.set(loaded);
                log.info("ML signal model loaded from file: {} trees", loaded.treeCount());
                return true;
            } catch (IOException e) {
                log.warn("Failed to load ML model from {}: {}", MODEL_PATH, e.getMessage());
            }
        }

        // Fall back to classpath (bundled bootstrap model)
        try {
            GradientBoostedTreeModel loaded = GradientBoostedTreeModel.loadFromClasspath(CLASSPATH_MODEL);
            model.set(loaded);
            log.info("ML signal model loaded from classpath: {} trees", loaded.treeCount());
            return true;
        } catch (IOException e) {
            log.info("No ML signal model available — shadow scoring disabled");
            return false;
        }
    }

    /** Get the loaded model (used by MlShadowRecorder). */
    GradientBoostedTreeModel getModel() { return model.get(); }

    public boolean isModelLoaded() { return model.get() != null; }

    public int treeCount() {
        GradientBoostedTreeModel m = model.get();
        return m != null ? m.treeCount() : 0;
    }

    public MlScorerStatus status() {
        GradientBoostedTreeModel m = model.get();
        return new MlScorerStatus(
                m != null,
                m != null ? m.treeCount() : 0,
                Files.exists(MODEL_PATH) ? MODEL_PATH.toString() : "classpath:" + CLASSPATH_MODEL
        );
    }

    public record MlScorerStatus(
            boolean modelLoaded,
            int treeCount,
            String modelPath
    ) {}
}
