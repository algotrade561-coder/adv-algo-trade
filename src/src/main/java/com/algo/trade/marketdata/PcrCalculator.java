package com.algo.trade.marketdata;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.Quote;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Computes Put-Call Ratio from the FULL option chain OI — not just the
 * ATM ± N subscribed strikes — PER INDEX (NIFTY, BANKNIFTY, SENSEX).
 * Uses a periodic REST batch quote to fetch OI for strikes without WS ticks.
 *
 * Runs every 2 minutes during market hours. The NIFTY result is pushed to
 * MarketGuard (legacy global PCR consumer); all per-index results are cached
 * and recorded into an INTRADAY SERIES (today only, cleared at midnight) for
 * the PCR daily chart (/api/pcr/intraday).
 *
 * This matches how brokers/NSE compute PCR: total put OI / total call OI
 * across ALL strikes for the expiry.
 */
@Component
public class PcrCalculator implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(PcrCalculator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Indices for which PCR is computed each cycle. */
    public static final List<IndexType> TRACKED_INDICES =
            List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX);

    private final LiveInstrumentCache liveInstrumentCache;
    private final BrokerClient brokerClient;
    private final MarketGuard marketGuard;
    private final ExpiryCalendar expiryCalendar;

    /** Latest PCR per index. */
    private final Map<IndexType, Double> latestPcrByIndex = new ConcurrentHashMap<>();

    /** Today's intraday PCR samples per index (one point per 2-min compute cycle). */
    private final Map<IndexType, CopyOnWriteArrayList<PcrPoint>> intradaySeries = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public PcrCalculator(LiveInstrumentCache liveInstrumentCache,
                         BrokerClient brokerClient,
                         MarketGuard marketGuard,
                         ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.brokerClient = brokerClient;
        this.marketGuard = marketGuard;
        this.expiryCalendar = expiryCalendar;
    }

    /** A single intraday PCR sample. */
    public record PcrPoint(String time, double pcr) {}

    /**
     * Legacy accessor — returns NIFTY full-chain PCR (the original behavior).
     * Prefer {@link #getPcr(IndexType)} for index-aware consumers.
     */
    public double getPcr() {
        return getPcr(IndexType.NIFTY);
    }

    /** Latest full-chain PCR for a specific index (0 if not yet computed). */
    public double getPcr(IndexType indexType) {
        return latestPcrByIndex.getOrDefault(indexType, 0.0);
    }

    /** Today's intraday PCR series for one index (oldest → newest). */
    public List<PcrPoint> getIntradaySeries(IndexType indexType) {
        return List.copyOf(intradaySeries.getOrDefault(indexType, new CopyOnWriteArrayList<>()));
    }

    /** Today's intraday PCR series for all tracked indices. */
    public Map<String, List<PcrPoint>> getAllIntradaySeries() {
        Map<String, List<PcrPoint>> out = new java.util.LinkedHashMap<>();
        for (IndexType idx : TRACKED_INDICES) {
            out.put(idx.name(), getIntradaySeries(idx));
        }
        return out;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) schedulerRegistry.register("pcrCalculator", "Full-chain PCR per index (2min)", 120_000, this::compute);
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    public void compute() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("pcrCalculator")) return;
        if (!isMarketHours()) return;
        if (!liveInstrumentCache.isReady()) return;

        for (IndexType indexType : TRACKED_INDICES) {
            try {
                double pcr = computeFullChainPcr(indexType);
                if (pcr > 0) {
                    latestPcrByIndex.put(indexType, pcr);
                    recordIntradayPoint(indexType, pcr);
                    if (indexType == IndexType.NIFTY) {
                        // Legacy global consumer — MarketGuard keys its PCR gates off NIFTY
                        marketGuard.updatePcr(pcr);
                    }
                    log.info("[PcrCalculator] Full-chain {} PCR: {}", indexType, String.format("%.3f", pcr));
                }
            } catch (Exception e) {
                log.warn("[PcrCalculator] {} failed: {}", indexType, e.getMessage());
                if (errorEventService != null) errorEventService.medium("PcrCalculator", indexType + " PCR calculation failed: " + e.getMessage());
            }
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("pcrCalculator");
    }

    private void recordIntradayPoint(IndexType indexType, double pcr) {
        LocalTime now = LocalTime.now(IST);
        // Chart window: 09:00–15:30 (data realistically starts ~09:17)
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(15, 30))) return;
        intradaySeries.computeIfAbsent(indexType, k -> new CopyOnWriteArrayList<>())
                .add(new PcrPoint(String.format("%02d:%02d", now.getHour(), now.getMinute()), round3(pcr)));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Clears today's series + latest values at midnight (DailyResetService). */
    @Override
    public void resetDaily() {
        intradaySeries.clear();
        latestPcrByIndex.clear();
        log.info("[PcrCalculator] Daily intraday PCR series reset.");
    }

    private double computeFullChainPcr(IndexType indexType) {
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        List<OptionInstrument> fullChain = liveInstrumentCache.getStrikeChain(indexType, expiry);
        if (fullChain.isEmpty()) {
            log.debug("[PcrCalculator] No instruments in cache for {} expiry {}", indexType, expiry);
            return 0;
        }

        // First try: use WebSocket OI for subscribed instruments
        long wsCallOi = 0, wsPutOi = 0;
        List<OptionInstrument> noOi = new java.util.ArrayList<>();

        for (OptionInstrument opt : fullChain) {
            if (opt.getOpenInterest() > 0) {
                if ("CE".equals(opt.getOptionType())) wsCallOi += opt.getOpenInterest();
                else wsPutOi += opt.getOpenInterest();
            } else {
                noOi.add(opt);
            }
        }

        // Fetch OI via REST for instruments not receiving WebSocket ticks
        long restCallOi = 0, restPutOi = 0;
        if (!noOi.isEmpty()) {
            // Build instrument keys for REST batch quote
            // Kite quote API accepts "EXCHANGE:TRADINGSYMBOL" format
            List<String> keys = noOi.stream()
                    .map(o -> o.getExchange() + ":" + o.getTradingSymbol())
                    .toList();

            // Batch in chunks of 200 to stay within Kite API limits
            for (int i = 0; i < keys.size(); i += 200) {
                List<String> batch = keys.subList(i, Math.min(i + 200, keys.size()));
                try {
                    Map<String, Quote> quotes = brokerClient.quotes(batch);
                    for (int j = 0; j < batch.size(); j++) {
                        Quote q = quotes.get(batch.get(j));
                        if (q == null || q.openInterest() <= 0) continue;
                        OptionInstrument opt = noOi.get(i + j);
                        if ("CE".equals(opt.getOptionType())) restCallOi += q.openInterest();
                        else restPutOi += q.openInterest();
                    }
                } catch (Exception e) {
                    log.debug("[PcrCalculator] REST batch failed for chunk {}: {}", i, e.getMessage());
                }
            }
        }

        long totalCallOi = wsCallOi + restCallOi;
        long totalPutOi = wsPutOi + restPutOi;

        log.debug("[PcrCalculator] {} expiry={} | WS: call={} put={} | REST: call={} put={} | Total: call={} put={}",
                indexType, expiry, wsCallOi, wsPutOi, restCallOi, restPutOi, totalCallOi, totalPutOi);

        return totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0;
    }

    private boolean isMarketHours() {
        var now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 35));
    }
}
