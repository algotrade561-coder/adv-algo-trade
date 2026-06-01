package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.notification.TelegramAlertService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the single-row {@link OiShiftTrapLadderConfig}. Loads/seeds on init,
 * partial updates emit an audit log entry.
 */
@Service
public class OiShiftTrapLadderConfigService {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapLadderConfigService.class);

    private final OiShiftTrapLadderConfigRepository repository;

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    private volatile OiShiftTrapLadderConfig cached;

    public OiShiftTrapLadderConfigService(OiShiftTrapLadderConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    void init() {
        var existing = repository.findById(1L);
        if (existing.isPresent()) {
            cached = existing.get();
            log.info("[OiShiftTrapLadder] loaded: mode={} tiers=[{}, {}, {}] window={}m",
                    cached.getLadderMode(),
                    cached.getTier1Discount(), cached.getTier2Discount(), cached.getTier3Discount(),
                    cached.getLadderWindowMin());
        } else {
            OiShiftTrapLadderConfig seed = new OiShiftTrapLadderConfig();
            seed.setUpdatedAt(Instant.now());
            seed.setUpdatedBy("yaml-seed");
            seed.setUpdatedReason("initial seed");
            cached = repository.save(seed);
            log.info("[OiShiftTrapLadder] seeded defaults (mode={})", cached.getLadderMode());
        }
    }

    public OiShiftTrapLadderConfig getCached() {
        return cached;
    }

    @Transactional
    public synchronized OiShiftTrapLadderConfig apply(RuntimeConfigUpdate u,
                                                      String updatedBy, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("reason must be at least 5 characters");
        }
        OiShiftTrapLadderConfig row = repository.findById(1L).orElseGet(this::createDefault);
        StringBuilder changes = new StringBuilder();

        if (u.ladderMode != null) {
            String mode = u.ladderMode.trim().toUpperCase();
            if (!mode.equals("OFF") && !mode.equals("SHADOW") && !mode.equals("LIVE")) {
                throw new IllegalArgumentException("ladderMode must be OFF, SHADOW, or LIVE");
            }
            row.setLadderMode(mode);
            changes.append("ladderMode=").append(mode).append(' ');
        }
        if (u.tier1Discount != null) {
            requireDiscount(u.tier1Discount, "tier1Discount");
            row.setTier1Discount(u.tier1Discount);
            changes.append("tier1Discount=").append(u.tier1Discount).append(' ');
        }
        if (u.tier2Discount != null) {
            requireDiscount(u.tier2Discount, "tier2Discount");
            row.setTier2Discount(u.tier2Discount);
            changes.append("tier2Discount=").append(u.tier2Discount).append(' ');
        }
        if (u.tier3Discount != null) {
            requireDiscount(u.tier3Discount, "tier3Discount");
            row.setTier3Discount(u.tier3Discount);
            changes.append("tier3Discount=").append(u.tier3Discount).append(' ');
        }
        // Tier ordering check (only when tiers are coherent)
        if (row.getTier1Discount() >= row.getTier2Discount()
                || row.getTier2Discount() >= row.getTier3Discount()) {
            throw new IllegalArgumentException(
                    "tier discounts must be strictly increasing (tier1 < tier2 < tier3)");
        }
        if (u.ladderWindowMin != null) {
            if (u.ladderWindowMin < 1 || u.ladderWindowMin > 180) {
                throw new IllegalArgumentException("ladderWindowMin must be in [1,180]");
            }
            row.setLadderWindowMin(u.ladderWindowMin);
            changes.append("ladderWindowMin=").append(u.ladderWindowMin).append(' ');
        }
        if (u.ladderOpScoreArmFloor != null) {
            if (u.ladderOpScoreArmFloor < 0 || u.ladderOpScoreArmFloor > 100) {
                throw new IllegalArgumentException("ladderOpScoreArmFloor must be in [0,100]");
            }
            row.setLadderOpScoreArmFloor(u.ladderOpScoreArmFloor);
            changes.append("ladderOpScoreArmFloor=").append(u.ladderOpScoreArmFloor).append(' ');
        }
        if (u.ladderOpScoreCancelDelta != null) {
            if (u.ladderOpScoreCancelDelta < 0 || u.ladderOpScoreCancelDelta > 100) {
                throw new IllegalArgumentException("ladderOpScoreCancelDelta must be in [0,100]");
            }
            row.setLadderOpScoreCancelDelta(u.ladderOpScoreCancelDelta);
            changes.append("ladderOpScoreCancelDelta=").append(u.ladderOpScoreCancelDelta).append(' ');
        }

        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy == null ? "anonymous" : updatedBy);
        row.setUpdatedReason(reason);
        cached = repository.save(row);

        String summary = changes.toString().trim();
        log.warn("[OiShiftTrapLadder] UPDATED by={} reason='{}' changes='{}'",
                updatedBy, reason, summary);
        if (telegramAlertService != null && !summary.isEmpty()) {
            try {
                telegramAlertService.systemAlert(String.format(
                        "OI Shift Trap ladder config updated by %s%n  Changes: %s%n  Reason: %s",
                        updatedBy, summary, reason));
            } catch (Exception ex) {
                log.debug("[OiShiftTrapLadder] telegram failed: {}", ex.getMessage());
            }
        }
        return cached;
    }

    private OiShiftTrapLadderConfig createDefault() {
        OiShiftTrapLadderConfig row = new OiShiftTrapLadderConfig();
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private static void requireDiscount(double v, String name) {
        if (v <= 0 || v >= 1.0) {
            throw new IllegalArgumentException(name + " must be in (0,1) — e.g. 0.03 for 3%");
        }
    }

    /** Partial-update DTO — any null field is left unchanged. */
    public static class RuntimeConfigUpdate {
        public String ladderMode;
        public Double tier1Discount;
        public Double tier2Discount;
        public Double tier3Discount;
        public Integer ladderWindowMin;
        public Integer ladderOpScoreArmFloor;
        public Integer ladderOpScoreCancelDelta;
    }
}
