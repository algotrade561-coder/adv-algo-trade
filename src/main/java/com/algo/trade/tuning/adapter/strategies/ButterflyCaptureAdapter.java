package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ButterflyCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.BUTTERFLY; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("wingDistance", "wingDistance", List.of(50.0, 100.0, 150.0)).getFirst(),
                numericRange("netDebit", "netDebit", List.of(10.0, 25.0, 50.0, 100.0)).getFirst());
    }
}
