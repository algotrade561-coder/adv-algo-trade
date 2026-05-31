package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JadeLizardCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.JADE_LIZARD; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("ivRank", "ivRank", List.of(40.0, 50.0, 60.0, 70.0)).getFirst(),
                numericRange("hedgeDistance", "hedgeDistance", List.of(50.0, 100.0, 150.0, 200.0)).getFirst(),
                numericRange("netCredit", "netCredit", List.of(20.0, 50.0, 100.0, 200.0)).getFirst());
    }
}
