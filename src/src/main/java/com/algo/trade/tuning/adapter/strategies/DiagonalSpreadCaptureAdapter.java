package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DiagonalSpreadCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.DIAGONAL_SPREAD; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("direction", "direction", List.of(), BandStyle.CATEGORICAL),
                numericRange("thetaDelta", "thetaDelta", List.of(0.01, 0.02, 0.05, 0.10)).getFirst());
    }
}
