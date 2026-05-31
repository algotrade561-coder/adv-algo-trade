package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LongStraddleCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.LONG_STRADDLE; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("ivRank", "ivRank", List.of(0.0, 20.0, 40.0, 60.0)).getFirst(),
                numericRange("combinedPremium", "combinedPremium", List.of(100.0, 200.0, 400.0, 800.0)).getFirst());
    }
}
