package com.kiteapioptions.controller;

import com.kiteapioptions.backtest.BacktestEngine;
import com.kiteapioptions.backtest.BacktestRunResult;
import com.kiteapioptions.persistence.BacktestResultEntity;
import com.kiteapioptions.persistence.BacktestResultRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BacktestController {

    private final BacktestResultRepository backtestResultRepository;
    private final BacktestEngine backtestEngine;

    public BacktestController(BacktestResultRepository backtestResultRepository, BacktestEngine backtestEngine) {
        this.backtestResultRepository = backtestResultRepository;
        this.backtestEngine = backtestEngine;
    }

    @PostMapping("/backtest/run")
    public BacktestResultEntity run() {
        BacktestRunResult result = backtestEngine.run();
        BacktestResultEntity entity = new BacktestResultEntity(result.id(), result.createdAt(),
                result.metrics().totalTrades(), result.metrics().winRatePercent(), result.metrics().expectancy(),
                result.metrics().maxDrawdown(), result.metrics().cumulativePnl(), result.outputDirectory().toString());
        return backtestResultRepository.save(entity);
    }

    @GetMapping("/backtest/results/{id}")
    public ResponseEntity<BacktestResultEntity> result(@PathVariable String id) {
        return backtestResultRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
