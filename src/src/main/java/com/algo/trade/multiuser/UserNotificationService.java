package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Per-user notification service — sends alerts using the CURRENT USER's
 * Telegram/webhook config, falling back to global config if not set.
 *
 * Each user can have their own:
 * - Telegram bot token + chat ID
 * - Webhook URL (Discord/Slack)
 *
 * Usage:
 *   UserContext.setUserId(userId);
 *   userNotificationService.sendTradeAlert("Entry: NIFTY 24500 CE @ ₹180");
 */
@Service
public class UserNotificationService {

    private static final Logger log = LoggerFactory.getLogger(UserNotificationService.class);

    private final UserBrokerConfigRepository configRepository;

    /** Global fallback Telegram config (from application.yml or env) */
    @Value("${TELEGRAM_BOT_TOKEN:}")
    private String globalBotToken;

    @Value("${TELEGRAM_CHAT_ID:}")
    private String globalChatId;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    public UserNotificationService(UserBrokerConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * Send a trade alert to the current user's configured notification channel.
     */
    public void sendTradeAlert(String message) {
        sendNotification("🔔 Trade Alert", message);
    }

    /**
     * Send a risk alert (halt, kill switch, daily limit) to the current user.
     */
    public void sendRiskAlert(String message) {
        sendNotification("⚠️ Risk Alert", message);
    }

    /**
     * Send daily summary text to the current user.
     */
    public void sendDailySummary(String text) {
        sendNotification("📊 Daily Summary", text);
    }

    /**
     * Send to the user's OWN channel only — no fallback to the global Telegram chat.
     * Used by TelegramAlertService's per-user fan-out: if the user hasn't linked
     * Telegram (no chat_id) and has no webhook, this is a silent no-op, preventing
     * duplicate copies in the global chat.
     */
    public void sendToUserOwnChannelOnly(Long userId, String message) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) return;
        String chatId = config.getTelegramChatId();
        if (chatId != null && !chatId.isBlank()) {
            // Per-user chat: user's own bot token if set, otherwise the shared/global bot
            sendTelegram(resolveToken(config), chatId, message);
        }
        sendWebhook(config.getWebhookUrl(), message);
    }

    /**
     * Send a notification to a specific user (bypasses UserContext).
     */
    public void sendToUser(Long userId, String prefix, String message) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        String botToken = resolveToken(config);
        String chatId = resolveChatId(config);
        String webhookUrl = config != null ? config.getWebhookUrl() : null;

        String fullMessage = prefix + "\n" + message;
        sendTelegram(botToken, chatId, fullMessage);
        sendWebhook(webhookUrl, fullMessage);
    }

    private void sendNotification(String prefix, String message) {
        Long userId = UserContext.getUserId();
        sendToUser(userId, prefix, message);
    }

    private String resolveToken(UserBrokerConfig config) {
        if (config != null && config.getTelegramBotToken() != null && !config.getTelegramBotToken().isBlank()) {
            return config.getTelegramBotToken();
        }
        return globalBotToken;
    }

    private String resolveChatId(UserBrokerConfig config) {
        if (config != null && config.getTelegramChatId() != null && !config.getTelegramChatId().isBlank()) {
            return config.getTelegramChatId();
        }
        return globalChatId;
    }

    private void sendTelegram(String botToken, String chatId, String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            log.debug("[UserNotify] Telegram not configured — skipping message");
            return;
        }

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", message)
                .add("parse_mode", "HTML")
                .build();

        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[UserNotify] Telegram send failed: status={}", response.code());
            }
        } catch (Exception e) {
            log.warn("[UserNotify] Telegram send error: {}", e.getMessage());
        }
    }

    private void sendWebhook(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String json = "{\"content\":\"" + escapeJson(message) + "\"}";
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder().url(webhookUrl).post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[UserNotify] Webhook send failed: url={}, status={}", webhookUrl, response.code());
            }
        } catch (Exception e) {
            log.warn("[UserNotify] Webhook send error: {}", e.getMessage());
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
