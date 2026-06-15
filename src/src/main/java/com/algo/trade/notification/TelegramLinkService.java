package com.algo.trade.notification;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * OTP-based Telegram linking — one shared bot serves every user.
 *
 * Flow:
 *  1. UI calls POST /me/broker/telegram/start-link → server returns OTP + deep link.
 *  2. User clicks https://t.me/<bot>?start=<OTP> → Telegram opens the bot and sends "/start <OTP>".
 *  3. Background long-poll worker calls getUpdates, sees the message, matches the OTP,
 *     stores the chat_id on UserBrokerConfig.telegramChatId for that user, sends a
 *     "✅ Linked!" confirmation back to the user via Telegram.
 *  4. UI polls GET /me/broker/telegram/link-status to know when linked.
 *
 * Per-user bot tokens are NOT used — every user gets alerts via the shared bot,
 * routed by their chat_id.
 */
@Service
public class TelegramLinkService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkService.class);

    @Value("${TELEGRAM_BOT_TOKEN:}")
    private String botToken;

    @Value("${TELEGRAM_BOT_USERNAME:}")
    private String botUsername;

    private final UserBrokerConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "telegram-link-poll"); t.setDaemon(true); return t; });

    /** otp → pending entry */
    private final ConcurrentHashMap<String, PendingLink> pending = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final long OTP_TTL_MS = 10 * 60_000;
    private volatile long lastUpdateId = 0;
    private volatile boolean started = false;

    public TelegramLinkService(UserBrokerConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (botToken == null || botToken.isBlank()) {
            log.warn("[TelegramLink] TELEGRAM_BOT_TOKEN not set — Telegram linking disabled.");
            return;
        }
        // Normalize: users often configure the username WITH a leading @ (e.g. "@MyBot").
        // Telegram deep-links (https://t.me/<username>) must NOT contain @ — a stray @
        // breaks the link entirely. Strip it here so both forms work.
        if (botUsername != null) {
            botUsername = botUsername.trim().replaceFirst("^@+", "");
        }
        if (botUsername == null || botUsername.isBlank()) {
            log.warn("[TelegramLink] TELEGRAM_BOT_USERNAME not set — deep-link will be missing.");
        }
        started = true;
        // If a webhook is registered, getUpdates returns 409 Conflict every time and
        // we'd silently never see /start <OTP>. Clear any stale webhook first.
        deleteWebhook();
        // First poll runs after 2s, then every 2s — long-poll itself waits up to 25s
        poller.scheduleWithFixedDelay(this::pollSafe, 2, 2, TimeUnit.SECONDS);
        log.info("[TelegramLink] Polling started for bot @{}", botUsername);
    }

    private void deleteWebhook() {
        String url = "https://api.telegram.org/bot" + botToken + "/deleteWebhook?drop_pending_updates=true";
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (r.isSuccessful()) log.info("[TelegramLink] deleteWebhook ok — long-poll mode active");
            else log.warn("[TelegramLink] deleteWebhook returned status={}", r.code());
        } catch (Exception e) {
            log.warn("[TelegramLink] deleteWebhook failed: {} — verify TELEGRAM_BOT_TOKEN is valid", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
    }

    /** UI calls this to start linking a specific user. Returns the OTP + deep link. */
    public LinkChallenge startLink(Long userId) {
        if (!started) throw new IllegalStateException("Telegram bot not configured");
        // Reuse outstanding OTP if user already has one alive
        for (Map.Entry<String, PendingLink> e : pending.entrySet()) {
            if (e.getValue().userId.equals(userId) && !e.getValue().isExpired()) {
                return toChallenge(e.getKey(), e.getValue());
            }
        }
        String otp = String.format("%06d", rng.nextInt(1_000_000));
        // Make collisions unlikely — regenerate if active OTP collides
        while (pending.containsKey(otp)) otp = String.format("%06d", rng.nextInt(1_000_000));
        PendingLink link = new PendingLink(userId, Instant.now().plusMillis(OTP_TTL_MS));
        pending.put(otp, link);
        log.info("[TelegramLink] Issued OTP for userId={} (expires in 10m)", userId);
        return toChallenge(otp, link);
    }

    /** UI poll: is this user linked yet? */
    public LinkStatus status(Long userId) {
        UserBrokerConfig c = configRepository.findByUserId(userId).orElse(null);
        boolean linked = c != null && c.getTelegramChatId() != null && !c.getTelegramChatId().isBlank();
        String pendingOtp = pending.entrySet().stream()
                .filter(e -> e.getValue().userId.equals(userId) && !e.getValue().isExpired())
                .map(Map.Entry::getKey).findFirst().orElse(null);
        return new LinkStatus(linked, c != null ? c.getTelegramChatId() : null, pendingOtp, botUsername);
    }

    /** Cancel any pending OTPs for a user (e.g. on Unlink). */
    public void cancelLink(Long userId) {
        pending.values().removeIf(v -> v.userId.equals(userId));
    }

    /** Removes the stored chat_id so the user is no longer alerted. */
    public void unlink(Long userId) {
        cancelLink(userId);
        configRepository.findByUserId(userId).ifPresent(c -> {
            c.setTelegramChatId(null);
            configRepository.save(c);
            log.info("[TelegramLink] Unlinked userId={}", userId);
        });
    }

    /** Send a free-form alert to a user via the shared bot. */
    public boolean sendToUser(Long userId, String text) {
        UserBrokerConfig c = configRepository.findByUserId(userId).orElse(null);
        if (c == null || c.getTelegramChatId() == null) return false;
        return sendTelegram(c.getTelegramChatId(), text);
    }

    // ── internals ──

    private void pollSafe() {
        try { poll(); } catch (Exception e) { log.warn("[TelegramLink] poll error: {}", e.getMessage()); }
        evictExpired();
    }

    private void poll() throws Exception {
        String url = "https://api.telegram.org/bot" + botToken
                + "/getUpdates?timeout=25&offset=" + (lastUpdateId + 1);
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.body() == null) return;
            String bodyText = resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[TelegramLink] getUpdates HTTP {} body={}", resp.code(), bodyText);
                return;
            }
            JsonNode root = objectMapper.readTree(bodyText);
            if (!root.path("ok").asBoolean()) {
                log.warn("[TelegramLink] getUpdates not ok: {}", root.path("description").asText());
                return;
            }
            for (JsonNode update : root.path("result")) {
                long updateId = update.path("update_id").asLong();
                if (updateId > lastUpdateId) lastUpdateId = updateId;
                JsonNode msg = update.path("message");
                if (msg.isMissingNode()) continue;
                String text = msg.path("text").asText("");
                long chatId = msg.path("chat").path("id").asLong();
                if (chatId == 0L || text.isBlank()) continue;
                if (text.startsWith("/start")) handleStart(chatId, text.trim());
                else if (text.startsWith("/unlink")) handleUnlink(chatId);
            }
        }
    }

    private void handleStart(long chatId, String text) {
        // Accept "/start <OTP>" or just "<OTP>" or "/start" without arg
        String[] parts = text.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        if (arg.isBlank()) {
            sendTelegram(String.valueOf(chatId),
                    "👋 Welcome to Algo Trade alerts.\n\nOpen the app → My Broker → Link Telegram to get a 6-digit code, then send /start <code> here.");
            return;
        }
        PendingLink link = pending.remove(arg);
        if (link == null || link.isExpired()) {
            sendTelegram(String.valueOf(chatId), "❌ That code is invalid or expired. Please request a fresh code in the app.");
            return;
        }
        UserBrokerConfig c = configRepository.findByUserId(link.userId).orElse(null);
        if (c == null) {
            // No broker config row yet → create a minimal one so we can store the chat_id
            c = new UserBrokerConfig(link.userId, "", "");
        }
        c.setTelegramChatId(String.valueOf(chatId));
        configRepository.save(c);
        log.info("[TelegramLink] ✅ userId={} linked to chatId={}", link.userId, chatId);
        sendTelegram(String.valueOf(chatId),
                "✅ Linked! You'll now receive entry, exit, and risk alerts here. Send /unlink any time to stop.");
    }

    private void handleUnlink(long chatId) {
        // Find user by chat_id and clear it
        configRepository.findAll().stream()
                .filter(c -> String.valueOf(chatId).equals(c.getTelegramChatId()))
                .findFirst().ifPresent(c -> {
                    c.setTelegramChatId(null);
                    configRepository.save(c);
                    log.info("[TelegramLink] User {} unlinked via /unlink", c.getUserId());
                    sendTelegram(String.valueOf(chatId), "🔕 Unlinked. You will no longer receive alerts.");
                });
    }

    private boolean sendTelegram(String chatId, String text) {
        if (botToken == null || botToken.isBlank()) return false;
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        FormBody body = new FormBody.Builder()
                .add("chat_id", chatId).add("text", text).build();
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response r = http.newCall(req).execute()) { return r.isSuccessful(); }
        catch (Exception e) { log.warn("[TelegramLink] send failed: {}", e.getMessage()); return false; }
    }

    private void evictExpired() {
        pending.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private LinkChallenge toChallenge(String otp, PendingLink link) {
        String deepLink = (botUsername == null || botUsername.isBlank()) ? null
                : "https://t.me/" + botUsername + "?start=" + otp;
        return new LinkChallenge(otp, deepLink, botUsername, link.expiresAt);
    }

    // ── DTOs ──
    public record LinkChallenge(String otp, String deepLink, String botUsername, Instant expiresAt) {}
    public record LinkStatus(boolean linked, String chatId, String pendingOtp, String botUsername) {}

    private static class PendingLink {
        final Long userId;
        final Instant expiresAt;
        PendingLink(Long userId, Instant expiresAt) { this.userId = userId; this.expiresAt = expiresAt; }
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }
}
