package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LongStrangleCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.LONG_STRANGLE; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("ivRank", "ivRank", List.of(0.0, 20.0, 40.0, 60.0)).getFirst(),
                numericRange("otmDistance", "otmDistance", List.of(2.0, 3.0, 5.0)).getFirst());
    }
}
