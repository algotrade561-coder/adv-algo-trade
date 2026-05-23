package com.algo.trade.marketdata;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.Quote;
import com.algo.trade.monitoring.SchedulerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST fallback for OI data when WebSocket OI samples are absent for ATM strikes.
 *
 * Per-instrument staleness uses {@link OptionInstrument#getLastOiSampleMs()} (ring-buffer
 * sample time), not {@link OptionInstrument#getLastTickTimeMs()} — LTP-only ticks do not
 * mask quiet OI.
 */
@Component
public class OiRestFallbackService {

    public static final String TASK_NAME = "oiRestFallback";

    private static final Logger log = LoggerFactory.getLogger(OiRestFallbackService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private static final long WS_STALE_MS = 45_000L;
    private static final long INSTRUMENT_STALE_MS = 180_000L;
    private static final long MIN_INTERVAL_MS = 20_000L;

    private final KiteWebSocketClient wsClient;
    private final LiveInstrumentCache liveInstrumentCache;
    private final BrokerClient brokerClient;
    private final ExpiryCalendar expiryCalendar;
    private final SchedulerRegistry schedulerRegistry;
    private final OiRestFallbackEventLog eventLog;

    @Value("${oi-rest-fallback.enabled:true}")
    private boolean enabled;

    @Value("${oi-rest-fallback.strikes-each-side:5}")
    private int strikesEachSide;

    @Value("${oi-rest-fallback.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private List<String> enabledUnderlyings;

    private volatile boolean restFallbackActive = false;
    private volatile int restCallCount = 0;
    private volatile long lastRestCallMs = 0;
    private volatile String lastBatchSummary = "";
    private volatile Instant lastBatchAt = null;

    public OiRestFallbackService(KiteWebSocketClient wsClient,
                                  LiveInstrumentCache liveInstrumentCache,
                                  BrokerClient brokerClient,
                                  ExpiryCalendar expiryCalendar,
                                  SchedulerRegistry schedulerRegistry,
                                  OiRestFallbackEventLog eventLog) {
        this.wsClient             = wsClient;
        this.liveInstrumentCache  = liveInstrumentCache;
        this.brokerClient         = brokerClient;
        this.expiryCalendar       = expiryCalendar;
        this.schedulerRegistry    = schedulerRegistry;
        this.eventLog             = eventLog;
    }

    @PostConstruct
    void register() {
        schedulerRegistry.register(TASK_NAME,
                "OI REST fallback for stale ATM OI (30s, ATM±" + strikesEachSide + ")",
                30_000, this::checkAndFallback);
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 120_000)
    public void checkAndFallback() {
        try {
            runCheckAndFallback();
        } finally {
            schedulerRegistry.recordRun(TASK_NAME);
        }
    }

    private void runCheckAndFallback() {
        if (!enabled || !schedulerRegistry.isEnabled(TASK_NAME)) return;
        if (!isMarketHours()) return;
        if (!liveInstrumentCache.isReady()) return;

        if (System.currentTimeMillis() - lastRestCallMs < MIN_INTERVAL_MS) return;

        boolean wsStale = isWsConnectionStale();
        List<OptionInstrument> stale = findStaleAtmInstruments();
        boolean anyStale = wsStale || !stale.isEmpty();

        if (!anyStale) {
            if (restFallbackActive) {
                log.info("[OiRestFallback] WS OI flow restored — stopping REST fallback after {} call(s). "
                       + "ATM strikes are receiving live OI ticks.", restCallCount);
                eventLog.log("RESTORED", false, 0, 0, 0, 0, restCallCount,
                        "WS OI samples fresh for all monitored ATM strikes");
                restFallbackActive = false;
                restCallCount = 0;
            }
            return;
        }

        if (!restFallbackActive) {
            String reason = wsStale
                    ? "WS connection stale — no tick for >" + WS_STALE_MS / 1000 + "s"
                    : stale.size() + " ATM strike(s) have no OI sample for >"
                      + INSTRUMENT_STALE_MS / 60_000 + " min (WS alive but quiet OI)";
            log.warn("[OiRestFallback] Activating REST OI fallback. Reason: {}. "
                   + "Will poll /quote every 30s until WS recovers.", reason);
            eventLog.log("ACTIVATED", wsStale, stale.size(), 0, 0, 0, restCallCount, reason);
            restFallbackActive = true;
        }

        Set<String> keys = collectInstrumentKeys(wsStale, stale);
        if (keys.isEmpty()) {
            log.debug("[OiRestFallback] No instrument keys resolved — skipping this cycle");
            eventLog.log("SKIPPED", wsStale, stale.size(), 0, 0, 0, restCallCount, "no instrument keys");
            return;
        }

        try {
            lastRestCallMs = System.currentTimeMillis();
            Map<String, Quote> quotes = brokerClient.quotes(keys);
            restCallCount++;

            int updated = 0;
            for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
                Quote q = entry.getValue();
                if (q.openInterest() <= 0 && q.lastPrice().signum() <= 0) continue;
                String tradingSymbol = extractTradingSymbol(entry.getKey());
                liveInstrumentCache.applyRestQuoteData(
                        tradingSymbol,
                        q.lastPrice().doubleValue(),
                        q.openInterest());
                updated++;
            }

            lastBatchAt = Instant.now();
            lastBatchSummary = "requested=" + keys.size() + " received=" + quotes.size()
                    + " updated=" + updated + " wsStale=" + wsStale;
            log.info("[OiRestFallback] REST batch #{}: {}", restCallCount, lastBatchSummary);
            eventLog.log("BATCH_OK", wsStale, stale.size(), keys.size(), quotes.size(), updated,
                    restCallCount, lastBatchSummary);

        } catch (Exception e) {
            log.warn("[OiRestFallback] REST batch failed: {} — will retry next cycle", e.getMessage());
            eventLog.log("BATCH_FAIL", wsStale, stale.size(), keys.size(), 0, 0,
                    restCallCount, e.getMessage());
        }
    }

    private boolean isWsConnectionStale() {
        if (!wsClient.isConnected()) return true;
        Instant lastTick = wsClient.getLastTickTime();
        if (lastTick == null) return true;
        return Duration.between(lastTick, Instant.now()).toMillis() > WS_STALE_MS;
    }

    private List<OptionInstrument> findStaleAtmInstruments() {
        long nowMs = System.currentTimeMillis();
        List<OptionInstrument> stale = new ArrayList<>();

        for (String underlyingName : enabledUnderlyings) {
            try {
                IndexType indexType = IndexType.fromName(underlyingName);
                collectStaleAtmForIndex(indexType, nowMs, stale);
            } catch (Exception e) {
                log.debug("[OiRestFallback] Staleness check error for {}: {}", underlyingName, e.getMessage());
            }
        }
        return stale;
    }

    private void collectStaleAtmForIndex(IndexType indexType, long nowMs, List<OptionInstrument> stale) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);

        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int strike = atm + (i * interval);
            liveInstrumentCache.getOption(indexType, strike, "CE", expiry)
                    .filter(opt -> isOiSampleStale(opt, nowMs))
                    .ifPresent(stale::add);
            liveInstrumentCache.getOption(indexType, strike, "PE", expiry)
                    .filter(opt -> isOiSampleStale(opt, nowMs))
                    .ifPresent(stale::add);
        }
    }

    /**
     * Stale when no OI ring-buffer sample in {@link #INSTRUMENT_STALE_MS} — LTP ticks alone do not clear this.
     */
    private boolean isOiSampleStale(OptionInstrument opt, long nowMs) {
        long sampleMs = opt.getLastOiSampleMs();
        if (sampleMs <= 0) return true;
        return (nowMs - sampleMs) > INSTRUMENT_STALE_MS;
    }

    private Set<String> collectInstrumentKeys(boolean wsStale, List<OptionInstrument> staleInstruments) {
        if (wsStale) {
            Set<String> keys = new LinkedHashSet<>();
            for (String underlyingName : enabledUnderlyings) {
                try {
                    IndexType indexType = IndexType.fromName(underlyingName);
                    double spot = liveInstrumentCache.getFuturesPrice(indexType);
                    if (spot <= 0) continue;

                    int atm = indexType.roundToATM(spot);
                    int interval = indexType.strikeInterval();
                    LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);

                    for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
                        int strike = atm + (i * interval);
                        liveInstrumentCache.getOption(indexType, strike, "CE", expiry)
                                .map(o -> o.getExchange() + ":" + o.getTradingSymbol())
                                .ifPresent(keys::add);
                        liveInstrumentCache.getOption(indexType, strike, "PE", expiry)
                                .map(o -> o.getExchange() + ":" + o.getTradingSymbol())
                                .ifPresent(keys::add);
                    }
                } catch (Exception e) {
                    log.debug("[OiRestFallback] Key collection error for {}: {}", underlyingName, e.getMessage());
                }
            }
            return keys;
        }
        return staleInstruments.stream()
                .map(o -> o.getExchange() + ":" + o.getTradingSymbol())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String extractTradingSymbol(String instrumentKey) {
        int colon = instrumentKey.indexOf(':');
        return colon >= 0 ? instrumentKey.substring(colon + 1) : instrumentKey;
    }

    boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    // ── Diagnostics / tuning ──────────────────────────────────────────────────

    public boolean isRestFallbackActive() { return restFallbackActive; }

    public int getRestCallCount() { return restCallCount; }

    public Instant getLastBatchAt() { return lastBatchAt; }

    public String getLastBatchSummary() { return lastBatchSummary; }

    /** Age in seconds of the oldest ATM±N OI sample for this index (-1 if spot unavailable). */
    public long getMaxAtmOiSampleAgeSec(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return -1;

        long nowMs = System.currentTimeMillis();
        long maxAgeMs = 0;
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);

        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int strike = atm + (i * interval);
            maxAgeMs = Math.max(maxAgeMs, oiSampleAgeMs(liveInstrumentCache.getOption(indexType, strike, "CE", expiry), nowMs));
            maxAgeMs = Math.max(maxAgeMs, oiSampleAgeMs(liveInstrumentCache.getOption(indexType, strike, "PE", expiry), nowMs));
        }
        return maxAgeMs / 1000;
    }

    private static long oiSampleAgeMs(Optional<OptionInstrument> opt, long nowMs) {
        if (opt.isEmpty()) return 0;
        long sampleMs = opt.get().getLastOiSampleMs();
        if (sampleMs <= 0) return INSTRUMENT_STALE_MS + 1;
        return nowMs - sampleMs;
    }

    /** Seconds since last WS tick on any subscribed instrument (-1 if never). */
    public long getWsTickAgeSec() {
        Instant lastTick = wsClient.getLastTickTime();
        if (lastTick == null) return -1;
        return Duration.between(lastTick, Instant.now()).toSeconds();
    }
}
