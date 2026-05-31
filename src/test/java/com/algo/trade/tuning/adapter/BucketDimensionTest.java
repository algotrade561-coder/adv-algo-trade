package com.algo.trade.tuning.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2, Commit 1 — BucketDimension band math. Numeric bands map values to readable
 * labels; CATEGORICAL and BOOLEAN dimensions don't require band edges.
 */
class BucketDimensionTest {

    @Test
    void numericRangeBanding() {
        BucketDimension dim = new BucketDimension(
                "score", "score", List.of(50.0, 60.0, 70.0, 80.0, 90.0), BandStyle.NUMERIC_RANGE);

        assertThat(dim.bandFor(45)).isEqualTo("<50");
        assertThat(dim.bandFor(50)).isEqualTo("50–59");
        assertThat(dim.bandFor(72)).isEqualTo("70–79");
        assertThat(dim.bandFor(89.5)).isEqualTo("80–89");
        assertThat(dim.bandFor(90)).isEqualTo("90+");
        assertThat(dim.bandFor(120)).isEqualTo("90+");
    }

    @Test
    void categoricalDimensionDoesNotRequireBandEdges() {
        BucketDimension dim = new BucketDimension("entryCase", "entryCase", null, BandStyle.CATEGORICAL);
        assertThat(dim.style()).isEqualTo(BandStyle.CATEGORICAL);
        assertThat(dim.bandEdges()).isNull();
    }

    @Test
    void booleanDimensionDoesNotRequireBandEdges() {
        BucketDimension dim = new BucketDimension("reversal", "reversal", null, BandStyle.BOOLEAN);
        assertThat(dim.style()).isEqualTo(BandStyle.BOOLEAN);
    }

    @Test
    void numericRangeRejectsTooFewEdges() {
        assertThatThrownBy(() -> new BucketDimension("x", "x", List.of(50.0), BandStyle.NUMERIC_RANGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("≥ 2 band edges");
        assertThatThrownBy(() -> new BucketDimension("x", "x", null, BandStyle.NUMERIC_RANGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericRangeRejectsUnsortedEdges() {
        assertThatThrownBy(() -> new BucketDimension("x", "x",
                List.of(50.0, 60.0, 55.0), BandStyle.NUMERIC_RANGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ascending");
    }

    @Test
    void numericRangeRejectsEqualEdges() {
        assertThatThrownBy(() -> new BucketDimension("x", "x",
                List.of(50.0, 50.0, 60.0), BandStyle.NUMERIC_RANGE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
