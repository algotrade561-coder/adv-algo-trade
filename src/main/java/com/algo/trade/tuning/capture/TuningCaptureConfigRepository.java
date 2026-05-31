package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuningCaptureConfigRepository
        extends JpaRepository<TuningCaptureConfigEntity, StrategyType> {
}
