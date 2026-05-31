package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.BucketDimension;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CalendarSpreadCaptureAdapter extends AbstractSpreadCaptureAdapter {
    @Override public StrategyType strategy() { return StrategyType.CALENDAR_SPREAD; }
    @Override public List<BucketDimension> bucketDimensions() {
        return List.of(
                numericRange("thetaDelta", "thetaDelta", List.of(0.01, 0.02, 0.05, 0.10)).getFirst(),
                numericRange("expiryGap", "expiryGap", List.of(7.0, 14.0, 21.0, 28.0)).getFirst());
    }
}
