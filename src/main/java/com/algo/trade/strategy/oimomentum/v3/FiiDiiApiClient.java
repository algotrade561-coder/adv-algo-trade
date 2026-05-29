package com.algo.trade.strategy.oimomentum.v3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * V3 OPERATOR — FII / DII data fetcher.
 *
 * <p>Replaces the manual properties file used by {@link V3ContextFeeder} with an automatic
 * HTTP fetch from a configurable endpoint. Defaults to NSE's public FII/DII activity API.</p>
 *
 * <p>Robust to network failures: every fetch returns an Optional. If the API is down,
 * the response is malformed, or the endpoint is blocked by network policy, the caller
 * falls back to the file-based feeder. Errors are throttled to one WARN per hour.</p>
 *
 * <p>Configurable via {@code oi-momentum.v3.*} properties so operators can swap
 * the endpoint (e.g. for a mirror) or disable the API path entirely.</p>
 */
@Component
public class FiiDiiApiClient {

    private static final Logger log = LoggerFactory.getLogger(FiiDiiApiClient.class);
    private static final Duration ERROR_LOG_COOLDOWN = Duration.ofHours(1);

    /** NSE's public FII/DII activity endpoint. Override via YAML if it changes. */
    @Value("${oi-momentum.v3.fii-dii-api-url:https://www.nseindia.com/api/fiidiiTradeReact}")
    private String apiUrl;

    /** Master switch — set false in environments where outbound HTTP is blocked. */
    @Value("${oi-momentum.v3.fii-dii-api-enabled:true}")
    private boolean enabled;

    /** Connection + read timeout in seconds. */
    @Value("${oi-momentum.v3.fii-dii-api-timeout-seconds:10}")
    private int timeoutSeconds = 10;

    /** Optional referrer URL — NSE often rejects requests without one. */
    @Value("${oi-momentum.v3.fii-dii-api-referrer:https://www.nseindia.com/reports/fii-dii}")
    private String referrer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Instant lastErrorAlert = Instant.EPOCH;

    /** Lazy-init HTTP client so the timeout config is honoured. */
    private volatile OkHttpClient httpClient;

    /**
     * Container for fetched data. Values are in crore rupees (NSE convention).
     * Positive = net long (buy > sell). Negative = net short.
     */
    public record FiiDiiData(double fiiNetCrore, double diiNetCrore, String sourceDate) {
        public boolean isMeaningful() {
            return Math.abs(fiiNetCrore) > 0.01 || Math.abs(diiNetCrore) > 0.01;
        }
    }

    /**
     * Fetch the latest FII / DII activity. Returns Optional.empty() on any failure.
     * Safe to call from a Spring scheduled job; non-blocking via a small client cache.
     */
    public Optional<FiiDiiData> fetchLatest() {
        if (!enabled) {
            log.debug("[FiiDiiApi] disabled via config");
            return Optional.empty();
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            log.debug("[FiiDiiApi] no URL configured");
            return Optional.empty();
        }
        try {
            String body = doHttpGet();
            if (body == null || body.isBlank()) return Optional.empty();
            return parseResponse(body);
        } catch (Exception ex) {
            throttledError("fetch failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    /** Performs the GET and returns the raw response body, or null on non-2xx / empty. */
    String doHttpGet() throws Exception {
        OkHttpClient client = httpClient;
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build();
            httpClient = client;
        }
        Request.Builder rb = new Request.Builder().url(apiUrl)
                // NSE rejects requests without a browser-shaped User-Agent.
                .addHeader("User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 AlgoTrade/1.0")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("X-Requested-With", "XMLHttpRequest");
        if (referrer != null && !referrer.isBlank()) {
            rb.addHeader("Referer", referrer);
        }
        Request request = rb.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throttledError("HTTP " + response.code() + " from " + apiUrl);
                return null;
            }
            return response.body() == null ? null : response.body().string();
        }
    }

    /**
     * Parse a JSON response into FiiDiiData. Lenient on shape — the NSE schema has
     * shifted in the past; we look for any structure that yields per-category buyValue
     * / sellValue / netValue numbers.
     *
     * <p>Expected NSE shape:</p>
     * <pre>
     * {
     *   "data": [
     *     {"category":"FII/FPI", "buyValue":"10234.5", "sellValue":"9876.3", "netValue":"358.2"},
     *     {"category":"DII",     "buyValue":"...",     "sellValue":"...",    "netValue":"..."}
     *   ]
     * }
     * </pre>
     *
     * <p>Also tolerates a top-level array, a "result" wrapper, and lower-case keys.</p>
     */
    Optional<FiiDiiData> parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.has("data") ? root.get("data")
                    : root.has("result") ? root.get("result")
                    : root;
            if (arr == null || !arr.isArray()) return Optional.empty();

            double fiiNet = Double.NaN;
            double diiNet = Double.NaN;
            String date = "";

            for (JsonNode row : arr) {
                String category = textOrEmpty(row, "category", "Category", "CATEGORY");
                if (category.isBlank()) continue;
                String norm = category.trim().toUpperCase();
                double net = doubleOrNaN(row, "netValue", "net_value", "Net", "NET");
                if (Double.isNaN(net)) {
                    double buy = doubleOrNaN(row, "buyValue", "buy_value", "Buy");
                    double sell = doubleOrNaN(row, "sellValue", "sell_value", "Sell");
                    if (!Double.isNaN(buy) && !Double.isNaN(sell)) net = buy - sell;
                }
                if (Double.isNaN(net)) continue;
                if (norm.contains("FII") || norm.contains("FPI")) fiiNet = net;
                else if (norm.contains("DII")) diiNet = net;
                if (date.isBlank()) date = textOrEmpty(row, "date", "Date", "DATE");
            }
            if (Double.isNaN(fiiNet) && Double.isNaN(diiNet)) return Optional.empty();
            return Optional.of(new FiiDiiData(
                    Double.isNaN(fiiNet) ? 0 : fiiNet,
                    Double.isNaN(diiNet) ? 0 : diiNet,
                    date));
        } catch (Exception ex) {
            throttledError("parse failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String textOrEmpty(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return "";
    }

    private static double doubleOrNaN(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v == null || v.isNull()) continue;
            String s = v.asText().trim().replace(",", "");
            if (s.isEmpty()) continue;
            try { return Double.parseDouble(s); }
            catch (NumberFormatException ignore) {}
        }
        return Double.NaN;
    }

    private void throttledError(String msg) {
        Instant now = Instant.now();
        if (Duration.between(lastErrorAlert, now).compareTo(ERROR_LOG_COOLDOWN) >= 0) {
            log.warn("[FiiDiiApi] {} (further errors throttled for 1h)", msg);
            lastErrorAlert = now;
        } else {
            log.debug("[FiiDiiApi] {}", msg);
        }
    }
}
