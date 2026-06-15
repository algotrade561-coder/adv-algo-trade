package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SyntheticFuturesCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.SYNTHETIC_FUTURES; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("emaDivergence", "emaDivergence", List.of(0.1, 0.2, 0.5, 1.0)).getFirst(),
                new BucketDimension("direction", "direction", List.of(), BandStyle.CATEGORICAL));
    }
}
