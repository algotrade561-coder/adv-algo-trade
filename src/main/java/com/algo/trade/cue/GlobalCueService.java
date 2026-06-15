package com.algo.trade.cue;

import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.risk.MarketGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global Cue Service — fetches macro indicators for directional bias.
 *
 * Provides:
 * - GiftNifty/SGX Nifty direction (pre-market gap indicator)
 * - US futures sentiment (S&P 500, Nasdaq)
 * - USDINR trend (rupee weakness = bearish for equities)
 * - Crude oil direction (rising crude = bearish for India)
 * - VIX direction
 *
 * Exposes a consolidated bias signal:
 *   BULLISH  — majority of global cues are positive
 *   BEARISH  — majority of global cues are negative
 *   NEUTRAL  — mixed signals
 *
 * Data sources: Yahoo Finance API for USDINR + crude. Internal candle data for gap estimation.
 * Crude oil detail from {@link com.algo.trade.commodity.BrentCrudeService} provides deeper analysis.
 */
@Component
public class GlobalCueService {

    private static final Logger log = LoggerFactory.getLogger(GlobalCueService.class);

    private final LiveCandleBuilder candleBuilder;
    private final MarketGuard marketGuard;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @Value("${trading.global-cues.enabled:true}")
    private boolean enabled;

    @Value("${trading.global-cues.bullish-threshold:2}")
    private int bullishThreshold;

    @Value("${trading.global-cues.bearish-threshold:2}")
    private int bearishThreshold;

    public enum GlobalBias { BULLISH, BEARISH, NEUTRAL }

    public record GlobalCueSnapshot(
            GlobalBias bias,
            double giftNiftyGapPercent,
            String usDirection,
            String usdinrTrend,
            String crudeTrend,
            double vixDirection,
            int bullishCues,
            int bearishCues,
            String reasoning
    ) {}

    private final AtomicReference<GlobalCueSnapshot> latestSnapshot = new AtomicReference<>(
            new GlobalCueSnapshot(GlobalBias.NEUTRAL, 0, "FLAT", "STABLE", "STABLE", 0, 0, 0, "No data yet"));

    // Event override: set by NewsFeedService when major event detected
    private volatile String eventOverrideDirection = null;
    private volatile long eventOverrideTime = 0;
    private static final long EVENT_OVERRIDE_DURATION_MS = 30 * 60_000L; // 30 min

    public GlobalCueService(LiveCandleBuilder candleBuilder, MarketGuard marketGuard, ObjectMapper objectMapper) {
        this.candleBuilder = candleBuilder;
        this.marketGuard = marketGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Refresh global cues every 5 minutes during market hours.
     */
    @Scheduled(fixedDelay = 300_000)
    public void refreshCues() {
        if (!enabled) return;
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 10)) || now.isAfter(LocalTime.of(15, 30))) return;

        try {
            int bullish = 0, bearish = 0;
            StringBuilder reasoning = new StringBuilder();

            // 1. GiftNifty Gap (approximated from NIFTY candle data)
            double gapPercent = estimateGiftNiftyGap();
            if (gapPercent > 0.2) { bullish++; reasoning.append("Gap UP ").append(String.format("%.2f", gapPercent)).append("%; "); }
            else if (gapPercent < -0.2) { bearish++; reasoning.append("Gap DOWN ").append(String.format("%.2f", gapPercent)).append("%; "); }

            // 2. US Direction (correlated from overnight NIFTY movement)
            String usDir = estimateUSDirection();
            if ("UP".equals(usDir)) { bullish++; reasoning.append("US UP; "); }
            else if ("DOWN".equals(usDir)) { bearish++; reasoning.append("US DOWN; "); }

            // 3. USDINR Trend
            String usdinr = estimateUSDINRTrend();
            if ("WEAKENING".equals(usdinr)) { bullish++; reasoning.append("INR strengthening; "); }
            else if ("STRENGTHENING".equals(usdinr)) { bearish++; reasoning.append("INR weakening; "); }

            // 4. Crude Oil Trend
            String crude = estimateCrudeTrend();
            if ("FALLING".equals(crude)) { bullish++; reasoning.append("Crude falling; "); }
            else if ("RISING".equals(crude)) { bearish++; reasoning.append("Crude rising; "); }

            // 5. VIX Direction
            double vixDir = getVIXDirection();
            if (vixDir < -0.5) { bullish++; reasoning.append("VIX falling; "); }
            else if (vixDir > 1.0) { bearish++; reasoning.append("VIX rising; "); }

            // Determine bias
            GlobalBias bias;
            if (bullish >= bullishThreshold && bullish > bearish) bias = GlobalBias.BULLISH;
            else if (bearish >= bearishThreshold && bearish > bullish) bias = GlobalBias.BEARISH;
            else bias = GlobalBias.NEUTRAL;

            GlobalCueSnapshot snapshot = new GlobalCueSnapshot(
                    bias, gapPercent, usDir, usdinr, crude, vixDir,
                    bullish, bearish, reasoning.toString());

            GlobalCueSnapshot prev = latestSnapshot.get();
            latestSnapshot.set(snapshot);

            if (prev.bias != bias) {
                log.info("[GlobalCues] Bias changed: {} → {} (bull={} bear={}) — {}",
                        prev.bias, bias, bullish, bearish, reasoning);
            }

        } catch (Exception e) {
            log.debug("[GlobalCues] Refresh error: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public GlobalBias getBias() {
        if (isEventOverrideActive()) {
            return "BULLISH".equals(eventOverrideDirection) ? GlobalBias.BULLISH : GlobalBias.BEARISH;
        }
        return latestSnapshot.get().bias;
    }

    public boolean isGlobalBullish() { return getBias() == GlobalBias.BULLISH; }
    public boolean isGlobalBearish() { return getBias() == GlobalBias.BEARISH; }
    public GlobalCueSnapshot getSnapshot() { return latestSnapshot.get(); }

    /**
     * Check if global cues support a given direction.
     * Returns true if bias is aligned OR neutral (don't block on neutral).
     */
    public boolean supportsDirection(String direction) {
        GlobalBias bias = getBias();
        if (bias == GlobalBias.NEUTRAL) return true;
        return ("BULLISH".equals(direction) && bias == GlobalBias.BULLISH)
                || ("BEARISH".equals(direction) && bias == GlobalBias.BEARISH);
    }

    /**
     * Set event override — forces global bias in a direction for 30 minutes.
     */
    public void setEventOverride(String direction, String eventDescription) {
        this.eventOverrideDirection = direction;
        this.eventOverrideTime = System.currentTimeMillis();
        log.warn("[GlobalCues] EVENT OVERRIDE set: {} — {} (active for 30 min)", direction, eventDescription);
    }

    public boolean isEventOverrideActive() {
        if (eventOverrideDirection == null) return false;
        if (System.currentTimeMillis() - eventOverrideTime > EVENT_OVERRIDE_DURATION_MS) {
            eventOverrideDirection = null;
            return false;
        }
        return true;
    }

    public String getEventOverrideDirection() {
        return isEventOverrideActive() ? eventOverrideDirection : null;
    }

    public String getBiasBreakdown() {
        GlobalCueSnapshot snap = latestSnapshot.get();
        return String.format("GlobalBias=%s [gap=%.2f%% US=%s INR=%s crude=%s VIX=%.1f] bull=%d bear=%d",
                snap.bias(), snap.giftNiftyGapPercent(), snap.usDirection(),
                snap.usdinrTrend(), snap.crudeTrend(), snap.vixDirection(),
                snap.bullishCues(), snap.bearishCues());
    }

    // ── Estimation Methods ────────────────────────────────────────────────────

    private double estimateGiftNiftyGap() {
        // NIFTY token = 256265
        List<Candle> candles = candleBuilder.getHistory(256265L, Timeframe.FIFTEEN_MINUTE);
        if (candles.size() < 6) return 0;
        double prevClose = candles.get(candles.size() - 6).close().doubleValue();
        double todayOpen = candles.get(candles.size() - 5).open().doubleValue();
        if (prevClose <= 0) return 0;
        return ((todayOpen - prevClose) / prevClose) * 100;
    }

    private String estimateUSDirection() {
        double gap = estimateGiftNiftyGap();
        if (gap > 0.3) return "UP";
        if (gap < -0.3) return "DOWN";
        return "FLAT";
    }

    private String estimateUSDINRTrend() {
        String result = fetchYahooChange("INR=X", 0.2);
        if (result != null) return result;
        result = fetchYahooChange("USDINR=X", 0.2);
        if (result != null) return result;
        return "STABLE";
    }

    private String estimateCrudeTrend() {
        String result = fetchYahooChange("BZ=F", 1.0);
        if (result != null) return result;
        result = fetchYahooChange("CL=F", 1.0);
        if (result != null) return result;
        return "STABLE";
    }

    /**
     * Fetch 2-day price change from Yahoo Finance for a given symbol.
     */
    private String fetchYahooChange(String symbol, double thresholdPct) {
        try {
            Request request = new Request.Builder()
                    .url("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?interval=1d&range=2d")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    var json = objectMapper.readTree(body);
                    var closes = json.path("chart").path("result").get(0)
                            .path("indicators").path("quote").get(0).path("close");
                    if (closes.isArray() && closes.size() >= 2) {
                        double prevClose = closes.get(closes.size() - 2).asDouble();
                        double currentClose = closes.get(closes.size() - 1).asDouble();
                        if (prevClose > 0) {
                            double changePct = ((currentClose - prevClose) / prevClose) * 100;
                            if (changePct > thresholdPct) return symbol.contains("INR") ? "STRENGTHENING" : "RISING";
                            if (changePct < -thresholdPct) return symbol.contains("INR") ? "WEAKENING" : "FALLING";
                            return "STABLE";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[GlobalCues] Fetch failed for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private double getVIXDirection() {
        double currentVix = marketGuard.getCurrentVix();
        if (currentVix > 16) return 1.0;
        if (currentVix < 12) return -1.0;
        return 0;
    }
}
