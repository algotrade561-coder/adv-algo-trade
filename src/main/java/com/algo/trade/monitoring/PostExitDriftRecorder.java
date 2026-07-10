package com.algo.trade.monitoring;

import com.algo.trade.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Post-exit drift capture (#3 of the 2026-06-12 tuning-data additions).
 *
 * After every trade exit, samples the option price at +5/+15/+30 minutes and appends
 * to data/tuning/post-exit-drift-&lt;date&gt;.csv. Answers "was the exit premature?"
 * offline — e.g. how often OI_FLIP_REVERSE exits are followed by the move continuing
 * in the trade's original direction (the 2026-06-12 churn pattern).
 *
 * CSV: exitTime,tradeId,instrument,strategy,reason,exitPrice,driftMin,price,pctVsExit
 */
@Component
public class PostExitDriftRecorder {

    private static final Logger log = LoggerFactory.getLogger(PostExitDriftRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int[] CHECKPOINT_MINUTES = {5, 15, 30};

    private final MarketDataService marketDataService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "post-exit-drift"); t.setDaemon(true); return t; });

    public PostExitDriftRecorder(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /** Best-effort, never throws — called from ExecutionEngine close paths. */
    public void record(String tradeId, String instrumentKey, String strategyType,
                       String reason, BigDecimal exitPrice) {
        if (instrumentKey == null || exitPrice == null || exitPrice.signum() <= 0) return;
        String exitTime = LocalTime.now(IST).toString().substring(0, 8);
        for (int min : CHECKPOINT_MINUTES) {
            scheduler.schedule(() -> sample(exitTime, tradeId, instrumentKey,
                    strategyType, reason, exitPrice, min), min, TimeUnit.MINUTES);
        }
    }

    private void sample(String exitTime, String tradeId, String instrumentKey,
                        String strategyType, String reason, BigDecimal exitPrice, int min) {
        try {
            // Market closed → skip silently
            if (LocalTime.now(IST).isAfter(LocalTime.of(15, 31))) return;
            var quote = marketDataService.quote(instrumentKey).orElse(null);
            if (quote == null || quote.lastPrice() == null) return;
            double px = quote.lastPrice().doubleValue();
            double pct = (px - exitPrice.doubleValue()) / exitPrice.doubleValue() * 100.0;
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("post-exit-drift-" + LocalDate.now(IST) + ".csv");
            boolean newFile = !Files.exists(file);
            try (FileWriter w = new FileWriter(file.toFile(), true)) {
                if (newFile) w.write("exitTime,tradeId,instrument,strategy,reason,exitPrice,driftMin,price,pctVsExit\n");
                w.write(String.format("%s,%s,%s,%s,\"%s\",%.2f,%d,%.2f,%.2f%n",
                        exitTime, tradeId, instrumentKey, strategyType,
                        reason == null ? "" : reason.replace('"', '\''),
                        exitPrice.doubleValue(), min, px, pct));
            }
        } catch (Exception e) {
            log.debug("[PostExitDrift] sample failed for {}: {}", tradeId, e.getMessage());
        }
    }
}
