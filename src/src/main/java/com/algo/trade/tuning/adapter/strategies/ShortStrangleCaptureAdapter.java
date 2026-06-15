package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ShortStrangleCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.SHORT_STRANGLE; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("ivRank", "ivRank", List.of(50.0, 70.0, 90.0)).getFirst(),
                numericRange("otmDistance", "otmDistance", List.of(2.0, 3.0, 5.0)).getFirst(),
                numericRange("combinedCredit", "combinedCredit", List.of(50.0, 100.0, 200.0, 400.0)).getFirst());
    }
}
