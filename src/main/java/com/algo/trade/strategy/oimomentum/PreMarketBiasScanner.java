package com.algo.trade.strategy.oimomentum;

import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-market bias scanner — fetches Gift Nifty (SGX), US futures, and crude prices
 * before NSE opens (9:10 AM IST) and computes a directional bias score (0–100).
 *
 * <p>Data source: Yahoo Finance (unofficial API, no key needed). Fetches once per
 * morning and caches the result for the opening drive window.</p>
 *
 * <p>Score interpretation:
 * <ul>
 *   <li>60–100: bullish bias (gap up, global green)</li>
 *   <li>40–60: neutral</li>
 *   <li>0–40: bearish bias (gap down, global red)</li>
 * </ul>
 *
 * <p>Integration: OIMomentumStrategy reads {@link #getBiasScore()} during the opening
 * drive window (9:15–9:30) and adds it as a bonus/penalty to the bias engine.</p>
 */
@Component
public class PreMarketBiasScanner {

    private static final Logger log = LoggerFactory.getLogger(PreMarketBiasScanner.class);

    // Yahoo Finance quote URL (unofficial — no API key needed)
    private static final String YAHOO_QUOTE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(90); // valid until ~10:45 AM

    @Value("${oi-momentum.pre-market-bias.enabled:true}")
    private boolean enabled;

    @Value("${oi-momentum.pre-market-bias.gift-nifty-symbol:^NSEI}")
    private String giftNiftySymbol;

    @Value("${oi-momentum.pre-market-bias.nifty-previous-close:0}")
    private double niftyPreviousClose; // auto-populated from LiveInstrumentCache at EOD

    private final RestClient restClient;
    private final MarketGuard marketGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService telegramAlertService;

    // Cached results
    private volatile double biasScore = 50.0; // neutral default
    private volatile Instant lastFetchTime = null;
    private volatile String lastSummary = "";
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();

    public PreMarketBiasScanner(RestClient.Builder restClientBuilder, MarketGuard marketGuard) {
        this.restClient = restClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
        this.marketGuard = marketGuard;
    }

    /**
     * Scheduled at 9:10 AM IST — fetches pre-market data and computes bias.
     * Runs once per day; result cached for the opening drive window.
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanPreMarket() {
        if (!enabled) return;
        try {
            double score = 50.0; // neutral baseline
            StringBuilder summary = new StringBuilder();

            // 1. Gift Nifty / Nifty Futures gap
            double niftySpot = getNiftyPreviousClose();
            double giftNifty = fetchYahooPrice("^NSEI"); // Use NSEI as proxy (real Gift Nifty not on Yahoo)
            if (giftNifty > 0 && niftySpot > 0) {
                double gapPct = (giftNifty - niftySpot) / niftySpot * 100;
                lastPrices.put("NIFTY_GAP_PCT", gapPct);
                if (gapPct > 0.3) { score += 15; summary.append("GAP_UP(+").append(String.format("%.2f", gapPct)).append("%) "); }
                else if (gapPct > 0.1) { score += 8; summary.append("gap_up(+").append(String.format("%.2f", gapPct)).append("%) "); }
                else if (gapPct < -0.3) { score -= 15; summary.append("GAP_DOWN(").append(String.format("%.2f", gapPct)).append("%) "); }
                else if (gapPct < -0.1) { score -= 8; summary.append("gap_down(").append(String.format("%.2f", gapPct)).append("%) "); }
                else { summary.append("flat_gap "); }
            }

            // 2. US S&P 500 Futures
            double spFutures = fetchYahooPrice("ES=F");
            double spPrevClose = fetchYahooPreviousClose("ES=F");
            if (spFutures > 0 && spPrevClose > 0) {
                double usChangePct = (spFutures - spPrevClose) / spPrevClose * 100;
                lastPrices.put("US_FUTURES_CHG_PCT", usChangePct);
                if (usChangePct > 0.3) { score += 10; summary.append("US_GREEN(+").append(String.format("%.2f", usChangePct)).append("%) "); }
                else if (usChangePct < -0.3) { score -= 10; summary.append("US_RED(").append(String.format("%.2f", usChangePct)).append("%) "); }
                else { summary.append("US_flat "); }
            }

            // 3. Crude Oil (Brent)
            double crude = fetchYahooPrice("BZ=F");
            double crudePrev = fetchYahooPreviousClose("BZ=F");
            if (crude > 0 && crudePrev > 0) {
                double crudeChgPct = (crude - crudePrev) / crudePrev * 100;
                lastPrices.put("CRUDE_CHG_PCT", crudeChgPct);
                if (crudeChgPct < -1.0) { score += 5; summary.append("CRUDE_DOWN "); }
                else if (crudeChgPct > 1.0) { score -= 5; summary.append("CRUDE_UP "); }
            }

            // 4. India VIX (already available from MarketGuard)
            double vix = marketGuard.getCurrentVix();
            if (vix > 18) { score -= 5; summary.append("VIX_HIGH(").append(String.format("%.1f", vix)).append(") "); }
            else if (vix < 13) { score += 5; summary.append("VIX_LOW(").append(String.format("%.1f", vix)).append(") "); }

            // Clamp to 0-100
            biasScore = Math.max(0, Math.min(100, score));
            lastFetchTime = Instant.now();
            lastSummary = summary.toString().trim();

            String direction = biasScore > 60 ? "BULLISH" : biasScore < 40 ? "BEARISH" : "NEUTRAL";
            log.info("[PreMarketBias] Score: {} ({}) — {}", String.format("%.0f", biasScore), direction, lastSummary);

            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "📊 Pre-Market Bias: %.0f (%s)\n%s", biasScore, direction, lastSummary));
            }
        } catch (Exception e) {
            log.warn("[PreMarketBias] Scan failed (non-fatal): {}", e.getMessage());
            biasScore = 50.0; // neutral on failure
        }
    }

    /**
     * Get the current pre-market bias score (0–100). Returns 50 (neutral) if not yet scanned
     * or if the cached result is stale (> 90 min).
     */
    public double getBiasScore() {
        if (lastFetchTime == null || Duration.between(lastFetchTime, Instant.now()).compareTo(CACHE_TTL) > 0) {
            return 50.0; // neutral / stale
        }
        return biasScore;
    }

    /** True if the pre-market scan has fresh data for today. */
    public boolean hasFreshData() {
        return lastFetchTime != null && Duration.between(lastFetchTime, Instant.now()).compareTo(CACHE_TTL) <= 0;
    }

    /** Get the last scan summary (for logging/diagnostics). */
    public String getLastSummary() { return lastSummary; }

    /** Get direction as bias points for the OI Momentum scoring engine.
     *  Returns -10 to +10 (centered around 0). */
    public int getBiasBonus() {
        double score = getBiasScore();
        if (score >= 70) return 10;
        if (score >= 60) return 5;
        if (score <= 30) return -10;
        if (score <= 40) return -5;
        return 0; // neutral (40-60)
    }

    private double getNiftyPreviousClose() {
        // Try live instrument cache first (most accurate)
        if (liveInstrumentCache != null) {
            double spot = liveInstrumentCache.getFuturesPrice(
                    com.algo.trade.domain.IndexType.NIFTY);
            if (spot > 0) return spot; // use current as proxy before market (pre-open)
        }
        return niftyPreviousClose > 0 ? niftyPreviousClose : 24000; // fallback
    }

    /**
     * Fetch current price from Yahoo Finance (unofficial API).
     * Returns 0 on failure (caller handles gracefully).
     */
    private double fetchYahooPrice(String symbol) {
        try {
            String url = YAHOO_QUOTE_URL + symbol + "?interval=1m&range=1d";
            String body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            return parseCurrentPrice(body);
        } catch (Exception e) {
            log.debug("[PreMarketBias] Yahoo fetch failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    private double fetchYahooPreviousClose(String symbol) {
        try {
            String url = YAHOO_QUOTE_URL + symbol + "?interval=1d&range=2d";
            String body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            return parsePreviousClose(body);
        } catch (Exception e) {
            log.debug("[PreMarketBias] Yahoo prev-close fetch failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /** Extract regularMarketPrice from Yahoo chart JSON response. */
    private double parseCurrentPrice(String json) {
        if (json == null) return 0;
        // Look for "regularMarketPrice" in meta
        Pattern p = Pattern.compile("\"regularMarketPrice\":(\\d+\\.?\\d*)");
        Matcher m = p.matcher(json);
        if (m.find()) return Double.parseDouble(m.group(1));
        return 0;
    }

    /** Extract previousClose from Yahoo chart JSON response. */
    private double parsePreviousClose(String json) {
        if (json == null) return 0;
        Pattern p = Pattern.compile("\"previousClose\":(\\d+\\.?\\d*)");
        Matcher m = p.matcher(json);
        if (m.find()) return Double.parseDouble(m.group(1));
        // Fallback: chartPreviousClose
        p = Pattern.compile("\"chartPreviousClose\":(\\d+\\.?\\d*)");
        m = p.matcher(json);
        if (m.find()) return Double.parseDouble(m.group(1));
        return 0;
    }
}
