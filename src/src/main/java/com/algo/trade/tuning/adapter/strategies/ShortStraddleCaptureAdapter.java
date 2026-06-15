package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ShortStraddleCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.SHORT_STRADDLE; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("ivRank", "ivRank", List.of(30.0, 50.0, 70.0, 90.0)).getFirst(),
                numericRange("combinedCredit", "combinedCredit", List.of(100.0, 200.0, 400.0, 800.0)).getFirst());
    }
}
