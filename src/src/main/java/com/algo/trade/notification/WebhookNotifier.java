package com.algo.trade.notification;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Webhook notifier — sends alerts to Discord, Slack, or any webhook URL.
 *
 * Configure in application.yml:
 *   trading.alerts.webhook.url: https://discord.com/api/webhooks/...
 *   trading.alerts.webhook.enabled: true
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    @Value("${trading.alerts.webhook.url:}")
    private String webhookUrl;

    @Value("${trading.alerts.webhook.enabled:false}")
    private boolean enabled;

    private final OkHttpClient httpClient = new OkHttpClient();

    public void send(String message) {
        if (!enabled || webhookUrl == null || webhookUrl.isBlank()) return;

        try {
            String json;
            if (webhookUrl.contains("discord")) {
                json = "{\"content\":\"" + escapeJson(message) + "\"}";
            } else {
                json = "{\"text\":\"" + escapeJson(message) + "\"}";
            }

            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder().url(webhookUrl).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.debug("[Webhook] Failed: {}", e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            log.debug("[Webhook] Error: {}", e.getMessage());
        }
    }

    public void tradeAlert(String strategy, String side, String symbol, int qty, double price) {
        send(String.format("📊 %s %s %s x%d @ ₹%.2f", strategy, side, symbol, qty, price));
    }

    public void riskAlert(String message) {
        send("⚠️ " + message);
    }

    public void dailySummary(double pnl, int trades, double winRate) {
        send(String.format("📈 Daily Summary — P&L: ₹%.2f | Trades: %d | Win Rate: %.1f%%", pnl, trades, winRate));
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
