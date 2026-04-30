package com.algo.trade.ml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GradientBoostedTreeModelTest {

    @Test
    void loadAndPredictBootstrapModel() throws IOException {
        GradientBoostedTreeModel model = GradientBoostedTreeModel.loadFromClasspath("ml/signal-model.json");
        assertNotNull(model);
        assertEquals(5, model.treeCount());
        assertEquals(30, model.featureNames().length);
    }

    @Test
    void highRuleScoreWithGoodFiltersProducesHighConfidence() throws IOException {
        GradientBoostedTreeModel model = GradientBoostedTreeModel.loadFromClasspath("ml/signal-model.json");

        // Feature vector: good signal — high rule score, vwap passed, volume spike, OI passed, breakout passed
        double[] features = new double[30];
        features[23] = 85;  // ruleBasedScore (high)
        features[11] = 1;   // vwapPassed
        features[12] = 1;   // breakoutPassed
        features[13] = 1;   // volumeSpike
        features[14] = 1;   // oiPassed
        features[10] = 30;  // ivRank (moderate)
        features[4] = 20;   // optionImpliedVolatility (low)
        features[28] = 16;  // vixLevel (normal)
        features[29] = 3;   // daysToExpiry

        double prob = model.predictProbability(features);
        int score = model.predictConfidenceScore(features);

        assertTrue(prob > 0.5, "Good signal should have >50% probability, got " + prob);
        assertTrue(score > 50, "Good signal should score >50, got " + score);
    }

    @Test
    void lowRuleScoreWithFailedFiltersProducesLowConfidence() throws IOException {
        GradientBoostedTreeModel model = GradientBoostedTreeModel.loadFromClasspath("ml/signal-model.json");

        // Feature vector: bad signal — low rule score, nothing passed
        double[] features = new double[30];
        features[23] = 15;  // ruleBasedScore (low)
        features[11] = 0;   // vwapPassed = false
        features[12] = 0;   // breakoutPassed = false
        features[13] = 0;   // volumeSpike = false
        features[14] = 0;   // oiPassed = false
        features[10] = 80;  // ivRank (high — bad for buying)
        features[4] = 50;   // optionImpliedVolatility (high)

        double prob = model.predictProbability(features);
        int score = model.predictConfidenceScore(features);

        assertTrue(prob < 0.5, "Bad signal should have <50% probability, got " + prob);
        assertTrue(score < 50, "Bad signal should score <50, got " + score);
    }

    @Test
    void predictConfidenceScoreIsInRange() throws IOException {
        GradientBoostedTreeModel model = GradientBoostedTreeModel.loadFromClasspath("ml/signal-model.json");

        // Test with various inputs — score should always be 0–100
        for (int i = 0; i < 100; i++) {
            double[] features = new double[30];
            features[23] = i; // varying rule score
            int score = model.predictConfidenceScore(features);
            assertTrue(score >= 0 && score <= 100,
                    "Score should be 0-100, got " + score + " for ruleScore=" + i);
        }
    }

    @Test
    void loadFromStreamWithMinimalModel() throws IOException {
        String json = """
                {
                  "featureNames": ["f1", "f2"],
                  "learningRate": 0.1,
                  "baseScore": 0.0,
                  "trees": [
                    {
                      "featureIndex": 0,
                      "threshold": 5.0,
                      "left": { "leafValue": -1.0 },
                      "right": { "leafValue": 1.0 }
                    }
                  ]
                }
                """;
        GradientBoostedTreeModel model = GradientBoostedTreeModel.loadFromStream(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, model.treeCount());

        // f1 = 3 (< 5) → left → leaf -1.0 → sigmoid(0 + 0.1 * -1.0) = sigmoid(-0.1)
        double probLow = model.predictProbability(new double[]{3.0, 0.0});
        assertTrue(probLow < 0.5);

        // f1 = 10 (>= 5) → right → leaf 1.0 → sigmoid(0 + 0.1 * 1.0) = sigmoid(0.1)
        double probHigh = model.predictProbability(new double[]{10.0, 0.0});
        assertTrue(probHigh > 0.5);
    }
}
