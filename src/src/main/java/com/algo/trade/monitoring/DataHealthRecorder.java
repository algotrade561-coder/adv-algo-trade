package com.algo.trade.monitoring;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.PcrCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Daily per-index data-health snapshot (#6 of the 2026-06-12 tuning-data additions).
 *
 * At 15:31 IST writes one row per index to data/health/data-health-&lt;date&gt;.csv:
 * spot present, 1-min candle count, 5-min candle count, PCR sample count.
 * Any ZERO on an index you intend to trade is logged as a WARN — this is the
 * automated version of the BANKNIFTY "range30m=0.00% all day" discovery: the
 * momentum tracker had no data and nobody noticed until a manual log dig.
 */
@Component
public class DataHealthRecorder {

    private static final Logger log = LoggerFactory.getLogger(DataHealthRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache liveInstrumentCache;
    private final PcrCalculator pcrCalculator;

    public DataHealthRecorder(LiveCandleBuilder candleBuilder,
                              LiveInstrumentCache liveInstrumentCache,
                              PcrCalculator pcrCalculator) {
        this.candleBuilder = candleBuilder;
        this.liveInstrumentCache = liveInstrumentCache;
        this.pcrCalculator = pcrCalculator;
    }

    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void writeDailyHealthRow() {
        try {
            Path dir = Path.of("data", "health");
            Files.createDirectories(dir);
            Path file = dir.resolve("data-health-" + LocalDate.now(IST) + ".csv");
            boolean newFile = !Files.exists(file);
            try (FileWriter w = new FileWriter(file.toFile(), true)) {
                if (newFile) w.write("date,index,spot,candles1m,candles5m,pcrSamples\n");
                for (IndexType idx : IndexType.values()) {
                    double spot = liveInstrumentCache.getFuturesPrice(idx);
                    int c1 = safeCount(idx, Timeframe.ONE_MINUTE);
                    int c5 = safeCount(idx, Timeframe.FIVE_MINUTE);
                    int pcrN = pcrCalculator.getIntradaySeries(idx).size();
                    w.write(String.format("%s,%s,%.2f,%d,%d,%d%n",
                            LocalDate.now(IST), idx, spot, c1, c5, pcrN));
                    if (spot <= 0 || c1 == 0 || c5 == 0) {
                        log.warn("[DataHealth] {} had MISSING data today: spot={} candles1m={} candles5m={} pcrSamples={} — "
                                + "strategies were blind on this index", idx, spot, c1, c5, pcrN);
                    }
                }
            }
            log.info("[DataHealth] Daily data-health row written for all indices.");
        } catch (Exception e) {
            log.warn("[DataHealth] write failed: {}", e.getMessage());
        }
    }

    private int safeCount(IndexType idx, Timeframe tf) {
        try { return candleBuilder.getHistory(idx.spotToken(), tf).size(); }
        catch (Exception e) { return -1; }
    }
}
