package com.kiteapioptions.controller;

import com.kiteapioptions.backtest.BacktestEngine;
import com.kiteapioptions.backtest.BacktestRunResult;
import com.kiteapioptions.persistence.BacktestResultEntity;
import com.kiteapioptions.persistence.BacktestResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final BacktestResultRepository backtestResultRepository;
    private final BacktestEngine backtestEngine;

    public BacktestController(BacktestResultRepository backtestResultRepository, BacktestEngine backtestEngine) {
        this.backtestResultRepository = backtestResultRepository;
        this.backtestEngine = backtestEngine;
    }

    @PostMapping("/backtest/run")
    public BacktestResultEntity run() {
        log.info("Backtest run endpoint called");
        BacktestRunResult result = backtestEngine.run();
        BacktestResultEntity entity = new BacktestResultEntity(result.id(), result.createdAt(),
                result.metrics().totalTrades(), result.metrics().winRatePercent(), result.metrics().expectancy(),
                result.metrics().maxDrawdown(), result.metrics().cumulativePnl(), result.outputDirectory().toString());
        BacktestResultEntity saved = backtestResultRepository.save(entity);
        log.info("Backtest run endpoint completed: id={}, totalTrades={}, cumulativePnl={}, outputDirectory={}",
                saved.getId(), saved.getTotalTrades(), saved.getCumulativePnl(), saved.getOutputPath());
        return saved;
    }

    @GetMapping("/backtest/results/{id}")
    public ResponseEntity<BacktestResultEntity> result(@PathVariable String id) {
        log.info("Backtest result requested: id={}", id);
        var result = backtestResultRepository.findById(id);
        if (result.isPresent()) {
            log.info("Backtest result found: id={}, totalTrades={}, cumulativePnl={}",
                    result.get().getId(), result.get().getTotalTrades(), result.get().getCumulativePnl());
            return ResponseEntity.ok(result.get());
        }
        log.warn("Backtest result not found: id={}", id);
        return ResponseEntity.notFound().build();
    }
}
