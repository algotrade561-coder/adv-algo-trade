package com.algo.trade.commodity;

import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Service to identify and track MCX Crude Oil futures contract.
 *
 * Finds the nearest expiry MCX crude oil contract from Kite instruments
 * and provides the token for WebSocket subscription.
 */
@Service
public class McxCrudeOilService {

    private static final Logger log = LoggerFactory.getLogger(McxCrudeOilService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Standard MCX crude oil futures only — excludes CRUDEOILM (mini, 10-bbl lot). */
    private static final Pattern STANDARD_CRUDE_FUT = Pattern.compile("^CRUDEOIL\\d{2}[A-Z]{3}FUT$");

    private final InstrumentCache instrumentCache;
    private final OilPriceTracker oilPriceTracker;
    private final KiteWebSocketClient webSocketClient;
    private final MarketDataService marketDataService;

    public McxCrudeOilService(InstrumentCache instrumentCache,
                              OilPriceTracker oilPriceTracker,
                              KiteWebSocketClient webSocketClient,
                              MarketDataService marketDataService) {
        this.instrumentCache = instrumentCache;
        this.oilPriceTracker = oilPriceTracker;
        this.webSocketClient = webSocketClient;
        this.marketDataService = marketDataService;
    }

    /**
     * Attempt to find MCX crude oil contract at startup.
     * May fail if instruments aren't loaded yet — retry handles this.
     */
    @PostConstruct
    public void initialize() {
        log.info("[MCX Crude Oil] Initializing...");
        findAndSetCrudeOilContract();
    }

    /**
     * Retry finding the contract every 60 seconds until found.
     * Instruments are loaded after Kite login, which happens after @PostConstruct.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryIfNotFound() {
        if (oilPriceTracker.getInstrumentToken() > 0) return; // Already found
        log.info("[MCX Crude Oil] Retrying contract lookup (instruments may now be loaded)...");
        findAndSetCrudeOilContract();
    }

    /**
     * Find the nearest expiry MCX crude oil futures contract.
     * Picks the standard contract (lot=100) — mini (CRUDEOILM, lot=10) is filtered out.
     */
    public void findAndSetCrudeOilContract() {
        try {
            List<Instrument> allInstruments = instrumentCache.all();

            Optional<Instrument> crudeOil = allInstruments.stream()
                    .filter(i -> "MCX".equals(i.exchange()))
                    .filter(i -> STANDARD_CRUDE_FUT.matcher(i.tradingSymbol()).matches())
                    .filter(i -> i.expiry().isPresent())
                    .filter(i -> i.expiry().get().isAfter(LocalDate.now(IST).minusDays(1)))
                    .min(Comparator.comparing(i -> i.expiry().get()));

            if (crudeOil.isEmpty()) {
                log.warn("[MCX Crude Oil] No active contract found in instruments");
                return;
            }

            Instrument inst = crudeOil.get();
            oilPriceTracker.setInstrumentDetails(
                    inst.instrumentToken(),
                    inst.tradingSymbol(),
                    inst.expiry().get()
            );
            log.info("[MCX Crude Oil] Found contract: {} (token: {}, expiry: {})",
                    inst.tradingSymbol(), inst.instrumentToken(), inst.expiry().get());

            // Subscribe the live socket so ticks start flowing. Safe to call repeatedly —
            // KiteWebSocketClient.addSubscriptions de-dupes against the existing token set.
            try {
                webSocketClient.addSubscriptions(List.of(inst.instrumentToken()));
                log.info("[MCX Crude Oil] WebSocket subscription added for token {}", inst.instrumentToken());
            } catch (Exception e) {
                log.warn("[MCX Crude Oil] Failed to add WS subscription for token {}: {}",
                        inst.instrumentToken(), e.getMessage());
            }

            // Seed previous-day close + today's open so daily-change / overnight-gap work from
            // the first tick. Both anchors are derived from a single historical request.
            seedSessionAnchors(inst);
        } catch (Exception e) {
            log.error("[MCX Crude Oil] Failed to find contract: {}", e.getMessage(), e);
        }
    }

    /**
     * Pull recent 15-min candles and seed both:
     *  - the prior trading session's last close (for daily-change)
     *  - today's first candle open (for overnight-gap)
     * 7-day look-back so weekends/holidays don't kill the seed.
     */
    private void seedSessionAnchors(Instrument inst) {
        try {
            LocalDate today = LocalDate.now(IST);
            Instant from = today.minusDays(7).atStartOfDay(IST).toInstant();
            Instant to = Instant.now();
            var request = new HistoricalDataRequest(inst.instrumentKey(), from, to,
                    Timeframe.FIFTEEN_MINUTE, false);
            List<com.algo.trade.domain.Candle> candles = marketDataService.historicalCandles(request);
            if (candles.isEmpty()) {
                log.warn("[MCX Crude Oil] No previous-session candles returned for {}", inst.instrumentKey());
                return;
            }
            Instant todayStart = today.atStartOfDay(IST).toInstant();
            // Partition into yesterday's candles (anything before today 00:00 IST) and today's candles.
            com.algo.trade.domain.Candle lastBeforeToday = null;
            com.algo.trade.domain.Candle firstToday = null;
            for (com.algo.trade.domain.Candle c : candles) {
                if (c.timestamp().isBefore(todayStart)) {
                    lastBeforeToday = c; // candles are time-ordered, keep overwriting to land on the last
                } else if (firstToday == null) {
                    firstToday = c;
                }
            }
            if (lastBeforeToday != null) {
                oilPriceTracker.setPreviousDayClose(lastBeforeToday.close().doubleValue());
            } else {
                log.debug("[MCX Crude Oil] No prior-day candle found in 7-day window for {}", inst.instrumentKey());
            }
            if (firstToday != null) {
                oilPriceTracker.setTodayOpen(firstToday.open().doubleValue());
            } else {
                log.debug("[MCX Crude Oil] No today's candle yet for {} — todayOpen will seed on first tick",
                        inst.instrumentKey());
            }
        } catch (Exception e) {
            log.warn("[MCX Crude Oil] Failed to seed session anchors for {}: {}",
                    inst.instrumentKey(), e.getMessage());
        }
    }

    /**
     * Minute-cadence sampler for short-horizon shock detection. Fires every 60s; the tracker
     * trims its own ring to a 30-min window, so a steady-state ring carries ~30 samples.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sampleTick() {
        if (oilPriceTracker.getInstrumentToken() > 0 && oilPriceTracker.isDataAvailable()) {
            oilPriceTracker.sampleNow();
        }
    }

    /**
     * Get the instrument token for WebSocket subscription.
     */
    public long getCrudeOilToken() {
        return oilPriceTracker.getInstrumentToken();
    }

    /**
     * Check if crude oil contract is available.
     */
    public boolean isAvailable() {
        return oilPriceTracker.getInstrumentToken() > 0;
    }

    /**
     * Get current oil price snapshot.
     */
    public OilPriceTracker.OilPriceSnapshot getSnapshot() {
        return oilPriceTracker.getSnapshot();
    }
}
