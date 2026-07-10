package com.algo.trade.auth;

import com.algo.trade.auth.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Per-user broker configuration — stores each user's Zerodha Kite credentials
 * and session state. Each user has their own API key, secret, and access token.
 */
@Entity
@Table(name = "user_broker_config")
public class UserBrokerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String brokerName = "ZERODHA";

    /** Broker-side client/login id (e.g. Zerodha user id "SX0602"). Captured at Kite token exchange. */
    @Column(name = "broker_client_id", length = 64)
    private String brokerClientId;

    /**
     * LOCAL source IP this user's broker API calls must originate from (SEBI static-IP rule:
     * one whitelisted static IP per user, effective Apr 2026). This is the PRIVATE secondary
     * IP on the EC2 ENI whose associated Elastic IP the user registered at developers.kite.trade.
     * Null = use the default (primary) interface.
     */
    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(nullable = false, length = 512)
    private String apiKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 1024)
    private String apiSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    private String accessToken;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 1024)
    private String requestToken;

    private Instant tokenIssuedAt;
    private Instant tokenExpiresAt;

    /** Whether this user's trading is active (admin can disable) */
    private boolean tradingEnabled = true;

    /** Per-user capital allocation */
    private double totalCapital = 60000;

    /** Per-user daily loss limit (₹) */
    private double dailyMaxLoss = 5000;

    /** Per-user max open positions */
    private int maxOpenPositions = 3;

    /**
     * Legacy broker-row field — lot sizing uses the user's assigned risk profile
     * ({@link com.algo.trade.config.usersettings.RiskProfile}) via {@code TradingConfigResolver},
     * not this column. Kept for API/DB compatibility; do not use for entry lot caps.
     */
    private int maxLotsPerTrade = 2;

    /** Per-user Telegram config */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 1024)
    private String telegramBotToken;

    @Column(length = 64)
    private String telegramChatId;

    /**
     * When the one-time Kite setup instructions (redirect URL + public IP to whitelist) were
     * sent to the user over Telegram. Set the first time the user links Telegram; used as an
     * idempotency guard so the instructions are sent exactly once. Null = not yet sent.
     */
    @Column(name = "kite_setup_sent_at")
    private Instant kiteSetupSentAt;

    /** Per-user webhook URL (Discord/Slack) */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    private String webhookUrl;

    /** Designates this user's API key as the primary account used for market analysis (WS feed) */
    private boolean primaryAccount = false;

    /** WebSocket connection state */
    private boolean wsConnected = false;
    private Instant lastWsConnectTime;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }

    @PreUpdate
    protected void onUpdate() { updatedAt = Instant.now(); }

    // ── Constructors ──
    protected UserBrokerConfig() {}

    public UserBrokerConfig(Long userId, String apiKey, String apiSecret) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    // ── Getters & Setters ──
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBrokerName() { return brokerName; }
    public String getBrokerClientId() { return brokerClientId; }
    public void setBrokerClientId(String brokerClientId) { this.brokerClientId = brokerClientId; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }
    public Instant getTokenIssuedAt() { return tokenIssuedAt; }
    public void setTokenIssuedAt(Instant tokenIssuedAt) { this.tokenIssuedAt = tokenIssuedAt; }
    public Instant getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(Instant tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public boolean isTradingEnabled() { return tradingEnabled; }
    public void setTradingEnabled(boolean tradingEnabled) { this.tradingEnabled = tradingEnabled; }
    public double getTotalCapital() { return totalCapital; }
    public void setTotalCapital(double totalCapital) { this.totalCapital = totalCapital; }
    public double getDailyMaxLoss() { return dailyMaxLoss; }
    public void setDailyMaxLoss(double dailyMaxLoss) { this.dailyMaxLoss = dailyMaxLoss; }
    public int getMaxOpenPositions() { return maxOpenPositions; }
    public void setMaxOpenPositions(int maxOpenPositions) { this.maxOpenPositions = maxOpenPositions; }
    public int getMaxLotsPerTrade() { return maxLotsPerTrade; }
    public void setMaxLotsPerTrade(int maxLotsPerTrade) { this.maxLotsPerTrade = maxLotsPerTrade; }
    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }
    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
    public Instant getKiteSetupSentAt() { return kiteSetupSentAt; }
    public void setKiteSetupSentAt(Instant kiteSetupSentAt) { this.kiteSetupSentAt = kiteSetupSentAt; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public boolean isPrimaryAccount() { return primaryAccount; }
    public void setPrimaryAccount(boolean primaryAccount) { this.primaryAccount = primaryAccount; }
    public boolean isWsConnected() { return wsConnected; }
    public void setWsConnected(boolean wsConnected) { this.wsConnected = wsConnected; }
    public Instant getLastWsConnectTime() { return lastWsConnectTime; }
    public void setLastWsConnectTime(Instant t) { this.lastWsConnectTime = t; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean hasValidToken() {
        return accessToken != null && !accessToken.isBlank()
                && (tokenExpiresAt == null || tokenExpiresAt.isAfter(Instant.now()));
    }
}
