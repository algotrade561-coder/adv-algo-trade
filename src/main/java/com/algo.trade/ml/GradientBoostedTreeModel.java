package com.algo.trade.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java inference engine for gradient-boosted decision trees.
 * Loads model weights from a JSON file exported by the Python training script.
 *
 * <p>Model JSON format:</p>
 * <pre>
 * {
 *   "featureNames": ["f1", "f2", ...],
 *   "learningRate": 0.1,
 *   "baseScore": 0.5,
 *   "trees": [
 *     {
 *       "featureIndex": 3,
 *       "threshold": 0.5,
 *       "left": { "leafValue": -0.12 },
 *       "right": {
 *         "featureIndex": 7,
 *         "threshold": 1000,
 *         "left": { "leafValue": 0.05 },
 *         "right": { "leafValue": 0.22 }
 *       }
 *     },
 *     ...
 *   ]
 * }
 * </pre>
 */
public class GradientBoostedTreeModel {

    private static final Logger log = LoggerFactory.getLogger(GradientBoostedTreeModel.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<TreeNode> trees;
    private final double learningRate;
    private final double baseScore;
    private final String[] featureNames;

    private GradientBoostedTreeModel(List<TreeNode> trees, double learningRate,
                                      double baseScore, String[] featureNames) {
        this.trees = trees;
        this.learningRate = learningRate;
        this.baseScore = baseScore;
        this.featureNames = featureNames;
    }

    /**
     * Predict probability (0–1) that this signal will be profitable.
     * Uses sigmoid on the raw score from the ensemble.
     */
    public double predictProbability(double[] features) {
        double rawScore = baseScore;
        for (TreeNode tree : trees) {
            rawScore += learningRate * tree.predict(features);
        }
        return sigmoid(rawScore);
    }

    /**
     * Predict and return a confidence score 0–100 compatible with the existing system.
     */
    public int predictConfidenceScore(double[] features) {
        double prob = predictProbability(features);
        return (int) Math.round(prob * 100);
    }

    public int treeCount() { return trees.size(); }
    public String[] featureNames() { return featureNames; }

    // ── Loading ──────────────────────────────────────────────────────────────

    public static GradientBoostedTreeModel loadFromFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadFromStream(is);
        }
    }

    public static GradientBoostedTreeModel loadFromClasspath(String resource) throws IOException {
        // Try multiple classloaders — Spring Boot's fat JAR uses a nested classloader
        InputStream found = GradientBoostedTreeModel.class.getClassLoader().getResourceAsStream(resource);
        if (found == null) {
            found = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        }
        if (found == null) {
            found = GradientBoostedTreeModel.class.getResourceAsStream("/" + resource);
        }
        if (found == null) throw new IOException("Classpath resource not found: " + resource);
        try (InputStream is = found) {
            return loadFromStream(is);
        }
    }

    public static GradientBoostedTreeModel loadFromStream(InputStream is) throws IOException {
        JsonNode root = mapper.readTree(is);

        double lr = root.path("learningRate").asDouble(0.1);
        double base = root.path("baseScore").asDouble(0.0);

        JsonNode namesNode = root.path("featureNames");
        String[] names = new String[namesNode.size()];
        for (int i = 0; i < namesNode.size(); i++) {
            names[i] = namesNode.get(i).asText();
        }

        JsonNode treesNode = root.path("trees");
        List<TreeNode> trees = new ArrayList<>();
        for (JsonNode treeJson : treesNode) {
            trees.add(parseNode(treeJson));
        }

        log.info("Loaded ML model: {} trees, learningRate={}, baseScore={}, features={}",
                trees.size(), lr, base, names.length);
        return new GradientBoostedTreeModel(trees, lr, base, names);
    }

    private static TreeNode parseNode(JsonNode json) {
        if (json.has("leafValue")) {
            return new LeafNode(json.get("leafValue").asDouble());
        }
        int featureIndex = json.get("featureIndex").asInt();
        double threshold = json.get("threshold").asDouble();
        TreeNode left = parseNode(json.get("left"));
        TreeNode right = parseNode(json.get("right"));
        return new SplitNode(featureIndex, threshold, left, right);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // ── Tree node types ─────────────────────────────────────────────────────

    sealed interface TreeNode {
        double predict(double[] features);
    }

    record LeafNode(double value) implements TreeNode {
        @Override
        public double predict(double[] features) { return value; }
    }

    record SplitNode(int featureIndex, double threshold,
                     TreeNode left, TreeNode right) implements TreeNode {
        @Override
        public double predict(double[] features) {
            if (featureIndex < 0 || featureIndex >= features.length) return 0;
            return features[featureIndex] < threshold
                    ? left.predict(features)
                    : right.predict(features);
        }
    }
}
