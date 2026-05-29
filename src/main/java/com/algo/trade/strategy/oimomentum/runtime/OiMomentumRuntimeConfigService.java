package com.algo.trade.strategy.oimomentum.runtime;

import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.strategy.oimomentum.OIMomentumConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bridges the DB-backed {@link OiMomentumRuntimeConfig} entity with the in-memory
 * Spring bean {@link OIMomentumConfig}.
 *
 * <ul>
 *   <li>On startup, loads (or seeds) the row and applies all values to OIMomentumConfig.</li>
 *   <li>On runtime update, persists to DB AND mutates OIMomentumConfig in-place so the
 *       strategy hot path sees the change on its next read (no cache; getters return
 *       the live field).</li>
 *   <li>Every change emits a WARN log and a Telegram alert for audit.</li>
 * </ul>
 */
@Service
public class OiMomentumRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(OiMomentumRuntimeConfigService.class);

    private final OiMomentumRuntimeConfigRepository repository;
    private final OIMomentumConfig oiMomentumConfig;

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    private volatile OiMomentumRuntimeConfig cached;

    public OiMomentumRuntimeConfigService(OiMomentumRuntimeConfigRepository repository,
                                            OIMomentumConfig oiMomentumConfig) {
        this.repository = repository;
        this.oiMomentumConfig = oiMomentumConfig;
    }

    @PostConstruct
    @Transactional
    void init() {
        var existing = repository.findById(1L);
        if (existing.isPresent()) {
            cached = existing.get();
            log.info("[OiMomentumRuntimeConfig] loaded from DB: v3Enabled={}, shadow={}, "
                    + "antiPyramid={}, expiryOtmCutoff={}, dailyLossMult={}, consecLossHalt={}, "
                    + "breakEvenTrigger={}",
                    cached.isV3Enabled(), cached.isV3ShadowMode(),
                    cached.isAntiPyramidEnabled(), cached.isExpiryOtmCutoffEnabled(),
                    cached.getDailyLossMultiplierOfAvgLoser(), cached.getConsecutiveLossHaltCount(),
                    cached.getBreakEvenTriggerPercent());
        } else {
            // Seed from current OIMomentumConfig (which Spring populated from YAML).
            OiMomentumRuntimeConfig seed = new OiMomentumRuntimeConfig();
            seed.setEnabled(oiMomentumConfig.isEnabled());
            seed.setPaperTrading(oiMomentumConfig.isPaperTrading());
            seed.setV3Enabled(oiMomentumConfig.isV3Enabled());
            seed.setV3ShadowMode(oiMomentumConfig.isV3ShadowMode());
            seed.setAntiPyramidEnabled(oiMomentumConfig.isAntiPyramidEnabled());
            seed.setAntiPyramidCooldownMinutes(oiMomentumConfig.getAntiPyramidCooldownMinutes());
            seed.setExpiryOtmCutoffEnabled(oiMomentumConfig.isExpiryOtmCutoffEnabled());
            seed.setExpiryOtmCutoffTime(oiMomentumConfig.getExpiryOtmCutoffTime());
            seed.setDailyLossLimitRupees(oiMomentumConfig.getDailyLossLimitRupees());
            seed.setDailyLossMultiplierOfAvgLoser(oiMomentumConfig.getDailyLossMultiplierOfAvgLoser());
            seed.setConsecutiveLossHaltCount(oiMomentumConfig.getConsecutiveLossHaltCount());
            seed.setBreakEvenTriggerPercent(oiMomentumConfig.getBreakEvenTriggerPercent());
            seed.setMaxTradesPerDay(oiMomentumConfig.getMaxTradesPerDay());
            seed.setUpdatedAt(Instant.now());
            seed.setUpdatedBy("yaml-seed");
            seed.setUpdatedReason("initial seed from YAML defaults");
            cached = repository.save(seed);
            log.info("[OiMomentumRuntimeConfig] seeded from YAML defaults");
        }
        // Apply cached state back to the in-memory OIMomentumConfig so anything
        // already reading the bean picks up DB-persisted overrides.
        applyToOiMomentumConfig(cached);
    }

    /** Read-only snapshot for UI / API. */
    public OiMomentumRuntimeConfig getCached() {
        return cached;
    }

    /**
     * Apply a partial update — every non-null field in {@code update} is persisted and
     * applied to the live OIMomentumConfig.
     *
     * @param updatedBy operator email (from OAuth) or "system"
     * @param reason short description, required (≥ 5 chars) so changes are auditable
     */
    @Transactional
    public synchronized OiMomentumRuntimeConfig apply(RuntimeConfigUpdate update,
                                                      String updatedBy, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("reason must be at least 5 characters");
        }
        OiMomentumRuntimeConfig row = repository.findById(1L).orElseGet(this::createDefaultRow);
        StringBuilder changes = new StringBuilder();
        if (update.enabled != null && update.enabled != row.isEnabled()) {
            row.setEnabled(update.enabled);
            changes.append("enabled=").append(update.enabled).append(" ");
        }
        if (update.paperTrading != null && update.paperTrading != row.isPaperTrading()) {
            row.setPaperTrading(update.paperTrading);
            changes.append("paperTrading=").append(update.paperTrading).append(" ");
        }
        if (update.v3Enabled != null && update.v3Enabled != row.isV3Enabled()) {
            row.setV3Enabled(update.v3Enabled);
            changes.append("v3Enabled=").append(update.v3Enabled).append(" ");
        }
        if (update.v3ShadowMode != null && update.v3ShadowMode != row.isV3ShadowMode()) {
            row.setV3ShadowMode(update.v3ShadowMode);
            changes.append("v3ShadowMode=").append(update.v3ShadowMode).append(" ");
        }
        if (update.antiPyramidEnabled != null
                && update.antiPyramidEnabled != row.isAntiPyramidEnabled()) {
            row.setAntiPyramidEnabled(update.antiPyramidEnabled);
            changes.append("antiPyramidEnabled=").append(update.antiPyramidEnabled).append(" ");
        }
        if (update.antiPyramidCooldownMinutes != null) {
            row.setAntiPyramidCooldownMinutes(update.antiPyramidCooldownMinutes);
            changes.append("antiPyramidCooldownMinutes=").append(update.antiPyramidCooldownMinutes).append(" ");
        }
        if (update.expiryOtmCutoffEnabled != null
                && update.expiryOtmCutoffEnabled != row.isExpiryOtmCutoffEnabled()) {
            row.setExpiryOtmCutoffEnabled(update.expiryOtmCutoffEnabled);
            changes.append("expiryOtmCutoffEnabled=").append(update.expiryOtmCutoffEnabled).append(" ");
        }
        if (update.expiryOtmCutoffTime != null) {
            row.setExpiryOtmCutoffTime(update.expiryOtmCutoffTime);
            changes.append("expiryOtmCutoffTime=").append(update.expiryOtmCutoffTime).append(" ");
        }
        if (update.dailyLossLimitRupees != null) {
            row.setDailyLossLimitRupees(update.dailyLossLimitRupees);
            changes.append("dailyLossLimitRupees=").append(update.dailyLossLimitRupees).append(" ");
        }
        if (update.dailyLossMultiplierOfAvgLoser != null) {
            row.setDailyLossMultiplierOfAvgLoser(update.dailyLossMultiplierOfAvgLoser);
            changes.append("dailyLossMultiplierOfAvgLoser=").append(update.dailyLossMultiplierOfAvgLoser).append(" ");
        }
        if (update.consecutiveLossHaltCount != null) {
            row.setConsecutiveLossHaltCount(update.consecutiveLossHaltCount);
            changes.append("consecutiveLossHaltCount=").append(update.consecutiveLossHaltCount).append(" ");
        }
        if (update.breakEvenTriggerPercent != null) {
            row.setBreakEvenTriggerPercent(update.breakEvenTriggerPercent);
            changes.append("breakEvenTriggerPercent=").append(update.breakEvenTriggerPercent).append(" ");
        }
        if (update.maxTradesPerDay != null) {
            row.setMaxTradesPerDay(update.maxTradesPerDay);
            changes.append("maxTradesPerDay=").append(update.maxTradesPerDay).append(" ");
        }
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy == null ? "anonymous" : updatedBy);
        row.setUpdatedReason(reason);
        cached = repository.save(row);
        applyToOiMomentumConfig(cached);

        String summary = changes.toString().trim();
        log.warn("[OiMomentumRuntimeConfig] UPDATED by={} reason='{}' changes='{}'",
                updatedBy, reason, summary);
        if (telegramAlertService != null && !summary.isEmpty()) {
            try {
                telegramAlertService.systemAlert(String.format(
                        "⚙️ OI Momentum config updated by %s%n  Changes: %s%n  Reason: %s",
                        updatedBy, summary, reason));
            } catch (Exception ex) {
                log.debug("[OiMomentumRuntimeConfig] telegram failed: {}", ex.getMessage());
            }
        }
        return cached;
    }

    /**
     * Kill switch — disables V3 + strategy and clears all the "scary" toggles. The
     * fastest way to stop OI Momentum trading via UI without thinking. Audited.
     */
    @Transactional
    public synchronized OiMomentumRuntimeConfig kill(String updatedBy, String reason) {
        RuntimeConfigUpdate u = new RuntimeConfigUpdate();
        u.enabled = false;
        u.v3Enabled = false;
        u.v3ShadowMode = false;
        return apply(u, updatedBy, reason == null ? "kill switch invoked" : reason);
    }

    private void applyToOiMomentumConfig(OiMomentumRuntimeConfig src) {
        oiMomentumConfig.setEnabled(src.isEnabled());
        oiMomentumConfig.setPaperTrading(src.isPaperTrading());
        oiMomentumConfig.setV3Enabled(src.isV3Enabled());
        oiMomentumConfig.setV3ShadowMode(src.isV3ShadowMode());
        oiMomentumConfig.setAntiPyramidEnabled(src.isAntiPyramidEnabled());
        oiMomentumConfig.setAntiPyramidCooldownMinutes(src.getAntiPyramidCooldownMinutes());
        oiMomentumConfig.setExpiryOtmCutoffEnabled(src.isExpiryOtmCutoffEnabled());
        oiMomentumConfig.setExpiryOtmCutoffTime(src.getExpiryOtmCutoffTime());
        oiMomentumConfig.setDailyLossLimitRupees(src.getDailyLossLimitRupees());
        oiMomentumConfig.setDailyLossMultiplierOfAvgLoser(src.getDailyLossMultiplierOfAvgLoser());
        oiMomentumConfig.setConsecutiveLossHaltCount(src.getConsecutiveLossHaltCount());
        oiMomentumConfig.setBreakEvenTriggerPercent(src.getBreakEvenTriggerPercent());
        oiMomentumConfig.setMaxTradesPerDay(src.getMaxTradesPerDay());
    }

    private OiMomentumRuntimeConfig createDefaultRow() {
        OiMomentumRuntimeConfig row = new OiMomentumRuntimeConfig();
        row.setUpdatedAt(Instant.now());
        return row;
    }

    /** Mutable DTO for partial updates. Any null field is left unchanged. */
    public static class RuntimeConfigUpdate {
        public Boolean enabled;
        public Boolean paperTrading;
        public Boolean v3Enabled;
        public Boolean v3ShadowMode;
        public Boolean antiPyramidEnabled;
        public Integer antiPyramidCooldownMinutes;
        public Boolean expiryOtmCutoffEnabled;
        public String  expiryOtmCutoffTime;
        public Double  dailyLossLimitRupees;
        public Double  dailyLossMultiplierOfAvgLoser;
        public Integer consecutiveLossHaltCount;
        public Double  breakEvenTriggerPercent;
        public Integer maxTradesPerDay;
    }
}
