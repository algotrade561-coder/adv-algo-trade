package com.algo.trade.commodity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Fetches Brent Crude Oil price from Yahoo Finance API every 60 seconds.
 * Feeds the price into OilPriceTracker for regime/momentum analysis.
 * Replaces the MCX Crude Oil WebSocket feed.
 *
 * Yahoo Finance ticker: BZ=F (Brent Crude Futures)
 * No API key required. Returns USD price.
 */
@Service
public class BrentCrudeService {

    private static final Logger log = LoggerFactory.getLogger(BrentCrudeService.class);
    private static final String YAHOO_URL = "https://query1.finance.yahoo.com/v8/finance/chart/BZ=F?interval=1m&range=1d";
    private static final double INR_PER_USD = 83.5; // approximate conversion

    private final OilPriceTracker oilPriceTracker;
    private final RestClient restClient;

    private volatile double lastPriceUSD = 0;
    private volatile double previousCloseUSD = 0;
    private volatile double dailyChangePct = 0;
    private volatile String regime = "UNKNOWN";
    private volatile Instant lastFetchTime = null;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public BrentCrudeService(OilPriceTracker oilPriceTracker) {
        this.oilPriceTracker = oilPriceTracker;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
        // Mark as Brent in the tracker
        oilPriceTracker.setInstrumentDetails(0, "BZ=F (Brent Crude)", null);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("brentCrude", "Brent Crude Oil price fetch (Yahoo Finance, 60s)", 60_000, this::fetchPrice);
        }
        // Initial fetch
        fetchPrice();
    }

    /**
     * Fetch Brent Crude price every 60 seconds from Yahoo Finance.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void fetchPrice() {
        try {
            String json = restClient.get()
                    .uri(YAHOO_URL)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                log.debug("[BrentCrude] Empty response from Yahoo Finance");
                return;
            }

            // Parse the regularMarketPrice from the JSON response
            // Structure: {"chart":{"result":[{"meta":{"regularMarketPrice":XX.XX}}]}}
            double price = parsePrice(json);
            if (price > 0) {
                lastPriceUSD = price;
                lastFetchTime = Instant.now();

                // Compute daily change from Yahoo's own previous close
                double prevClose = parsePreviousClose(json);
                if (prevClose > 0) {
                    previousCloseUSD = prevClose;
                    dailyChangePct = ((price - prevClose) / prevClose) * 100;
                }

                // Compute regime based on Brent thresholds
                if (price < 65) regime = "LOW";
                else if (price < 90) regime = "NORMAL";
                else if (price < 110) regime = "HIGH";
                else regime = "CRISIS";

                // Still feed OilPriceTracker for regime-aware strategies
                double priceINR = price * INR_PER_USD;
                oilPriceTracker.updatePrice(priceINR);
                if (prevClose > 0) {
                    oilPriceTracker.setPreviousDayClose(prevClose * INR_PER_USD);
                }
                double todayOpen = parseTodayOpen(json);
                if (todayOpen > 0) {
                    oilPriceTracker.setTodayOpen(todayOpen * INR_PER_USD);
                }

                log.debug("[BrentCrude] Price updated: ${} change={}% regime={}",
                        String.format("%.2f", price), String.format("%.2f", dailyChangePct), regime);
            }

            if (schedulerRegistry != null) schedulerRegistry.recordRun("brentCrude");
        } catch (Exception e) {
            log.debug("[BrentCrude] Fetch failed: {}", e.getMessage());
            if (schedulerRegistry != null) schedulerRegistry.recordError("brentCrude", e.getMessage());
        }
    }

    public double getLastPriceUSD() { return lastPriceUSD; }
    public double getDailyChangePct() { return dailyChangePct; }
    public String getRegime() { return regime; }
    public double getPreviousCloseUSD() { return previousCloseUSD; }
    public Instant getLastFetchTime() { return lastFetchTime; }
    public boolean isAvailable() { return lastPriceUSD > 0; }

    /**
     * Parse regularMarketPrice from Yahoo Finance chart JSON.
     */
    private double parsePrice(String json) {
        try {
            // Simple string parsing to avoid Jackson dependency for this one field
            int idx = json.indexOf("\"regularMarketPrice\":");
            if (idx < 0) return 0;
            int start = idx + "\"regularMarketPrice\":".length();
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            if (end < 0) return 0;
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parsePreviousClose(String json) {
        try {
            int idx = json.indexOf("\"chartPreviousClose\":");
            if (idx < 0) {
                idx = json.indexOf("\"previousClose\":");
                if (idx < 0) return 0;
                int start = idx + "\"previousClose\":".length();
                int end = json.indexOf(',', start);
                if (end < 0) end = json.indexOf('}', start);
                return Double.parseDouble(json.substring(start, end).trim());
            }
            int start = idx + "\"chartPreviousClose\":".length();
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseTodayOpen(String json) {
        try {
            // Look for "open" in the meta section
            int metaIdx = json.indexOf("\"meta\"");
            if (metaIdx < 0) return 0;
            int idx = json.indexOf("\"regularMarketDayOpen\":", metaIdx);
            if (idx < 0) return 0;
            int start = idx + "\"regularMarketDayOpen\":".length();
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
