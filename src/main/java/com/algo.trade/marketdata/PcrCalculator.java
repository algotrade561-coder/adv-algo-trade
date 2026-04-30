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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Computes Put-Call Ratio from the FULL option chain OI — not just the
 * ATM ± N subscribed strikes. Uses a periodic REST batch quote to fetch
 * OI for all strikes of the current weekly expiry.
 *
 * Runs every 2 minutes during market hours. The result is pushed to
 * MarketGuard and cached for the /market API endpoint.
 *
 * This matches how brokers/NSE compute PCR: total put OI / total call OI
 * across ALL strikes for the expiry.
 */
@Component
public class PcrCalculator {

    private static final Logger log = LoggerFactory.getLogger(PcrCalculator.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final BrokerClient brokerClient;
    private final MarketGuard marketGuard;
    private final ExpiryCalendar expiryCalendar;

    private final AtomicReference<Double> latestPcr = new AtomicReference<>(0.0);

    public PcrCalculator(LiveInstrumentCache liveInstrumentCache,
                         BrokerClient brokerClient,
                         MarketGuard marketGuard,
                         ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.brokerClient = brokerClient;
        this.marketGuard = marketGuard;
        this.expiryCalendar = expiryCalendar;
    }

    /** Returns the latest computed full-chain PCR. */
    public double getPcr() {
        return latestPcr.get();
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    public void compute() {
        if (!isMarketHours()) return;
        if (!liveInstrumentCache.isReady()) return;

        try {
            double pcr = computeFullChainPcr(IndexType.NIFTY);
            if (pcr > 0) {
                latestPcr.set(pcr);
                marketGuard.updatePcr(pcr);
                log.info("[PcrCalculator] Full-chain NIFTY PCR: {}", String.format("%.3f", pcr));
            }
        } catch (Exception e) {
            log.warn("[PcrCalculator] Failed: {}", e.getMessage());
        }
    }

    private double computeFullChainPcr(IndexType indexType) {
        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
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
        var ist = java.time.ZoneId.of("Asia/Kolkata");
        var now = java.time.LocalTime.now(ist);
        return now.isAfter(java.time.LocalTime.of(9, 16)) && now.isBefore(java.time.LocalTime.of(15, 35));
    }
}
