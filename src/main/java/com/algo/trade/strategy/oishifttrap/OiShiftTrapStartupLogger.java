package com.algo.trade.strategy.oishifttrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 2026-06-01 — dumps every effective OI Shift Trap config knob to the log once
 * Spring is fully up. Lets the operator confirm at a glance which thresholds
 * are actually active in this JVM (e.g., did the enhanced toggle deploy? did
 * the YAML override take?). Single log line per category; INFO level.
 */
@Component
public class OiShiftTrapStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapStartupLogger.class);

    private final OiShiftTrapConfig config;

    public OiShiftTrapStartupLogger(@Autowired(required = false) OiShiftTrapConfig config) {
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void dumpConfig() {
        if (config == null) {
            log.warn("[OiShiftTrap-Boot] OiShiftTrapConfig bean is NULL — strategy will use legacy defaults only.");
            return;
        }
        log.info("[OiShiftTrap-Boot] enhancementsEnabled={} (master toggle — when false, all Phase 1-4 features are no-op)",
                config.isEnhancementsEnabled());
        log.info("[OiShiftTrap-Boot] capture: captureEnabled={} snapshotWarmup={} evalEpisodeDedup={} forwardCheckpoints={} confirmShadow={} exitMaeMfe={}",
                config.isCaptureEnabled(), config.isChainSnapshotWarmupEnabled(),
                config.isEvalEpisodeDedupEnabled(), config.isForwardCheckpointsEnabled(),
                config.isConfirmationShadowEnabled(), config.isExitMaeMfeEnabled());
        log.info("[OiShiftTrap-Boot] score: base={} minConfidence={} trendLookback={}",
                config.getScoreBase(), config.getMinConfidenceScore(), config.getTrendLookbackCandles());
        log.info("[OiShiftTrap-Boot] thresholds: nonExpiryProx={}% nonExpiryImb={}x | expiryProx={}% expiryImb={}x",
                config.getNonExpiryProximityPercent(), config.getNonExpiryImbalanceRatio(),
                config.getExpiryDayProximityPercent(), config.getExpiryDayImbalanceRatio());
        log.info("[OiShiftTrap-Boot] paths: distantOiMaxStrikes={} imbalanceOnlyRatio={}x pendingTtl={}m crossIndexMinAgreement={}",
                config.getDistantOiMaxStrikes(), config.getImbalanceOnlyEntryRatio(),
                config.getPendingSignalTtlMinutes(), config.getCrossIndexMinAgreement());
        log.info("[OiShiftTrap-Boot] blockers: fakeBreakoutVolMult={} fakeBreakoutReversal={} oiAbsorptionWallOi={}",
                config.getFakeBreakoutVolumeMultiplier(), config.getFakeBreakoutReversalPercent(),
                config.getOiAbsorptionWallOiThreshold());
        log.info("[OiShiftTrap-Boot] exit: oiUnwindDrop={}% oiUnwindWindow={}m liquidityHuntProx={}%",
                config.getOiUnwindExitDropPercent(), config.getOiUnwindExitWindowMinutes(),
                config.getLiquidityHuntProximityPercent());
        log.info("[OiShiftTrap-Boot] alerts: imbalanceAlertRatio={}x cooldown={}m",
                config.getImbalanceAlertRatio(), config.getImbalanceAlertCooldownMinutes());
        if (!config.isEnhancementsEnabled()) {
            log.warn("[OiShiftTrap-Boot] enhancements-enabled is FALSE — strategy is running in LEGACY mode. " +
                    "To activate Phase 1-4 features, set oi-shift-trap.enhancements-enabled=true and restart.");
        }
    }
}
