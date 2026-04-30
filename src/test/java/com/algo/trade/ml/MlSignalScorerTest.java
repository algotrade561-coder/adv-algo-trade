package com.algo.trade.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MlSignalScorerTest {

    @Test
    void loadsBootstrapModelOnInit() {
        MlSignalScorer scorer = new MlSignalScorer();
        scorer.init();

        assertTrue(scorer.isModelLoaded());
        assertTrue(scorer.treeCount() > 0);
    }

    @Test
    void getModelReturnsLoadedModel() {
        MlSignalScorer scorer = new MlSignalScorer();
        scorer.init();

        GradientBoostedTreeModel model = scorer.getModel();
        assertNotNull(model);
        assertEquals(5, model.treeCount());
    }

    @Test
    void noModelWhenNotInitialized() {
        MlSignalScorer scorer = new MlSignalScorer();
        // Don't call init()

        assertFalse(scorer.isModelLoaded());
        assertNull(scorer.getModel());
        assertEquals(0, scorer.treeCount());
    }

    @Test
    void statusReportsCorrectly() {
        MlSignalScorer scorer = new MlSignalScorer();
        scorer.init();

        MlSignalScorer.MlScorerStatus status = scorer.status();
        assertTrue(status.modelLoaded());
        assertTrue(status.treeCount() > 0);
        assertNotNull(status.modelPath());
    }

    @Test
    void reloadModelWorks() {
        MlSignalScorer scorer = new MlSignalScorer();
        assertFalse(scorer.isModelLoaded());

        boolean loaded = scorer.loadModel();
        assertTrue(loaded);
        assertTrue(scorer.isModelLoaded());
    }
}
