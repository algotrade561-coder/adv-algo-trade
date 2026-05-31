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
            seed.setRecordEveryReject(oiMomentumConfig.isRecordEveryReject());
            seed.setRejectSampleIntervalSeconds(oiMomentumConfig.getRejectSampleIntervalSeconds());
            seed.setMatrixRejectSampleIntervalSeconds(oiMomentumConfig.getMatrixRejectSampleIntervalSeconds());
            seed.setSummaryRejectTopN(oiMomentumConfig.getSummaryRejectTopN());
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
        if (update.legacyTimeOfDayModeEnabled != null
                && update.legacyTimeOfDayModeEnabled != row.isLegacyTimeOfDayModeEnabled()) {
            row.setLegacyTimeOfDayModeEnabled(update.legacyTimeOfDayModeEnabled);
            changes.append("legacyTimeOfDayModeEnabled=").append(update.legacyTimeOfDayModeEnabled).append(" ");
        }
        if (update.case0Enabled != null
                && update.case0Enabled != row.isCase0Enabled()) {
            row.setCase0Enabled(update.case0Enabled);
            changes.append("case0Enabled=").append(update.case0Enabled).append(" ");
        }
        if (update.case0ShadowMode != null
                && update.case0ShadowMode != row.isCase0ShadowMode()) {
            row.setCase0ShadowMode(update.case0ShadowMode);
            changes.append("case0ShadowMode=").append(update.case0ShadowMode).append(" ");
        }
        if (update.case0OpScoreThreshold != null) {
            if (update.case0OpScoreThreshold < 50 || update.case0OpScoreThreshold > 100) {
                throw new IllegalArgumentException("case0OpScoreThreshold must be in [50,100]");
            }
            row.setCase0OpScoreThreshold(update.case0OpScoreThreshold);
            changes.append("case0OpScoreThreshold=").append(update.case0OpScoreThreshold).append(" ");
        }
        if (update.case0CoilMaxPct != null) {
            if (update.case0CoilMaxPct <= 0 || update.case0CoilMaxPct > 1.0) {
                throw new IllegalArgumentException("case0CoilMaxPct must be in (0,1.0]");
            }
            row.setCase0CoilMaxPct(update.case0CoilMaxPct);
            changes.append("case0CoilMaxPct=").append(update.case0CoilMaxPct).append(" ");
        }
        if (update.case0PcrSlopeMinAbs != null) {
            if (update.case0PcrSlopeMinAbs < 0) {
                throw new IllegalArgumentException("case0PcrSlopeMinAbs must be >= 0");
            }
            row.setCase0PcrSlopeMinAbs(update.case0PcrSlopeMinAbs);
            changes.append("case0PcrSlopeMinAbs=").append(update.case0PcrSlopeMinAbs).append(" ");
        }
        if (update.case4WatchlistBonusEnabled != null
                && update.case4WatchlistBonusEnabled != row.isCase4WatchlistBonusEnabled()) {
            row.setCase4WatchlistBonusEnabled(update.case4WatchlistBonusEnabled);
            changes.append("case4WatchlistBonusEnabled=").append(update.case4WatchlistBonusEnabled).append(" ");
        }
        // R3 — Adaptive CASE 0 for low-VIX
        if (update.case0LowVixEnabled != null
                && update.case0LowVixEnabled != row.isCase0LowVixEnabled()) {
            row.setCase0LowVixEnabled(update.case0LowVixEnabled);
            changes.append("case0LowVixEnabled=").append(update.case0LowVixEnabled).append(" ");
        }
        if (update.case0LowVixVixThreshold != null) {
            row.setCase0LowVixVixThreshold(update.case0LowVixVixThreshold);
            changes.append("case0LowVixVixThreshold=").append(update.case0LowVixVixThreshold).append(" ");
        }
        if (update.case0LowVixOpScoreThreshold != null) {
            if (update.case0LowVixOpScoreThreshold < 30 || update.case0LowVixOpScoreThreshold > 100) {
                throw new IllegalArgumentException("case0LowVixOpScoreThreshold must be in [30,100]");
            }
            row.setCase0LowVixOpScoreThreshold(update.case0LowVixOpScoreThreshold);
            changes.append("case0LowVixOpScoreThreshold=").append(update.case0LowVixOpScoreThreshold).append(" ");
        }
        if (update.case0LowVixCoilMaxPct != null) {
            row.setCase0LowVixCoilMaxPct(update.case0LowVixCoilMaxPct);
            changes.append("case0LowVixCoilMaxPct=").append(update.case0LowVixCoilMaxPct).append(" ");
        }
        // R2 — Range-edge fade
        if (update.rangeEdgeFadeEnabled != null
                && update.rangeEdgeFadeEnabled != row.isRangeEdgeFadeEnabled()) {
            row.setRangeEdgeFadeEnabled(update.rangeEdgeFadeEnabled);
            changes.append("rangeEdgeFadeEnabled=").append(update.rangeEdgeFadeEnabled).append(" ");
        }
        if (update.rangeEdgeFadeRangeMaxPct != null) {
            row.setRangeEdgeFadeRangeMaxPct(update.rangeEdgeFadeRangeMaxPct);
            changes.append("rangeEdgeFadeRangeMaxPct=").append(update.rangeEdgeFadeRangeMaxPct).append(" ");
        }
        if (update.rangeEdgeFadeEdgePct != null) {
            row.setRangeEdgeFadeEdgePct(update.rangeEdgeFadeEdgePct);
            changes.append("rangeEdgeFadeEdgePct=").append(update.rangeEdgeFadeEdgePct).append(" ");
        }
        if (update.rangeEdgeFadeOiBuildMin != null) {
            row.setRangeEdgeFadeOiBuildMin(update.rangeEdgeFadeOiBuildMin);
            changes.append("rangeEdgeFadeOiBuildMin=").append(update.rangeEdgeFadeOiBuildMin).append(" ");
        }
        // Theta-decay gate
        if (update.thetaDecayCheckEnabled != null
                && update.thetaDecayCheckEnabled != row.isThetaDecayCheckEnabled()) {
            row.setThetaDecayCheckEnabled(update.thetaDecayCheckEnabled);
            changes.append("thetaDecayCheckEnabled=").append(update.thetaDecayCheckEnabled).append(" ");
        }
        if (update.thetaDecayMaxCostPct != null) {
            row.setThetaDecayMaxCostPct(update.thetaDecayMaxCostPct);
            changes.append("thetaDecayMaxCostPct=").append(update.thetaDecayMaxCostPct).append(" ");
        }
        if (update.recordEveryReject != null && update.recordEveryReject != row.isRecordEveryReject()) {
            row.setRecordEveryReject(update.recordEveryReject);
            changes.append("recordEveryReject=").append(update.recordEveryReject).append(" ");
        }
        if (update.rejectSampleIntervalSeconds != null) {
            validateInterval(update.rejectSampleIntervalSeconds, "rejectSampleIntervalSeconds");
            row.setRejectSampleIntervalSeconds(update.rejectSampleIntervalSeconds);
            changes.append("rejectSampleIntervalSeconds=").append(update.rejectSampleIntervalSeconds).append(" ");
        }
        if (update.matrixRejectSampleIntervalSeconds != null) {
            validateInterval(update.matrixRejectSampleIntervalSeconds, "matrixRejectSampleIntervalSeconds");
            row.setMatrixRejectSampleIntervalSeconds(update.matrixRejectSampleIntervalSeconds);
            changes.append("matrixRejectSampleIntervalSeconds=")
                    .append(update.matrixRejectSampleIntervalSeconds).append(" ");
        }
        if (update.summaryRejectTopN != null) {
            if (update.summaryRejectTopN < 1 || update.summaryRejectTopN > 50) {
                throw new IllegalArgumentException("summaryRejectTopN must be in [1,50]");
            }
            row.setSummaryRejectTopN(update.summaryRejectTopN);
            changes.append("summaryRejectTopN=").append(update.summaryRejectTopN).append(" ");
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
        oiMomentumConfig.setLegacyTimeOfDayModeEnabled(src.isLegacyTimeOfDayModeEnabled());
        oiMomentumConfig.setCase0Enabled(src.isCase0Enabled());
        oiMomentumConfig.setCase0ShadowMode(src.isCase0ShadowMode());
        oiMomentumConfig.setCase0OpScoreThreshold(src.getCase0OpScoreThreshold());
        oiMomentumConfig.setCase0CoilMaxPct(src.getCase0CoilMaxPct());
        oiMomentumConfig.setCase0PcrSlopeMinAbs(src.getCase0PcrSlopeMinAbs());
        oiMomentumConfig.setCase4WatchlistBonusEnabled(src.isCase4WatchlistBonusEnabled());
        // R3 — Adaptive CASE 0 for low-VIX
        oiMomentumConfig.setCase0LowVixEnabled(src.isCase0LowVixEnabled());
        oiMomentumConfig.setCase0LowVixVixThreshold(src.getCase0LowVixVixThreshold());
        oiMomentumConfig.setCase0LowVixOpScoreThreshold(src.getCase0LowVixOpScoreThreshold());
        oiMomentumConfig.setCase0LowVixCoilMaxPct(src.getCase0LowVixCoilMaxPct());
        // R2 — Range-edge fade
        oiMomentumConfig.setRangeEdgeFadeEnabled(src.isRangeEdgeFadeEnabled());
        oiMomentumConfig.setRangeEdgeFadeRangeMaxPct(src.getRangeEdgeFadeRangeMaxPct());
        oiMomentumConfig.setRangeEdgeFadeEdgePct(src.getRangeEdgeFadeEdgePct());
        oiMomentumConfig.setRangeEdgeFadeOiBuildMin(src.getRangeEdgeFadeOiBuildMin());
        // Theta-decay gate
        oiMomentumConfig.setThetaDecayCheckEnabled(src.isThetaDecayCheckEnabled());
        oiMomentumConfig.setThetaDecayMaxCostPct(src.getThetaDecayMaxCostPct());
        oiMomentumConfig.setRecordEveryReject(src.isRecordEveryReject());
        oiMomentumConfig.setRejectSampleIntervalSeconds(src.getRejectSampleIntervalSeconds());
        oiMomentumConfig.setMatrixRejectSampleIntervalSeconds(src.getMatrixRejectSampleIntervalSeconds());
        oiMomentumConfig.setSummaryRejectTopN(src.getSummaryRejectTopN());
    }

    private static void validateInterval(int seconds, String field) {
        if (seconds < 1 || seconds > 300) {
            throw new IllegalArgumentException(field + " must be in [1,300]");
        }
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
        // Legacy enhancements (29 May 2026 — see OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md)
        public Boolean legacyTimeOfDayModeEnabled;
        public Boolean case0Enabled;
        public Boolean case0ShadowMode;
        public Integer case0OpScoreThreshold;
        public Double  case0CoilMaxPct;
        public Double  case0PcrSlopeMinAbs;
        public Boolean case4WatchlistBonusEnabled;
        // R3 — Adaptive CASE 0 for low-VIX
        public Boolean case0LowVixEnabled;
        public Double  case0LowVixVixThreshold;
        public Integer case0LowVixOpScoreThreshold;
        public Double  case0LowVixCoilMaxPct;
        // R2 — Range-edge fade
        public Boolean rangeEdgeFadeEnabled;
        public Double  rangeEdgeFadeRangeMaxPct;
        public Double  rangeEdgeFadeEdgePct;
        public Integer rangeEdgeFadeOiBuildMin;
        // Theta-decay gate
        public Boolean thetaDecayCheckEnabled;
        public Double  thetaDecayMaxCostPct;
        // P4 instrumentation
        public Boolean recordEveryReject;
        public Integer rejectSampleIntervalSeconds;
        public Integer matrixRejectSampleIntervalSeconds;
        public Integer summaryRejectTopN;
    }
}
