package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BullCallSpreadCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.BULL_CALL_SPREAD; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("netDebit", "netDebit", List.of(50.0, 100.0, 200.0, 500.0)).getFirst(),
                numericRange("emaGap", "emaGap", List.of(0.1, 0.2, 0.5, 1.0)).getFirst());
    }
}
