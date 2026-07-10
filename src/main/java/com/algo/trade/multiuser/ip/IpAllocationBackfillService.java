package com.algo.trade.multiuser.ip;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Phase 0 (USER_SOURCE_IP_AUTOMATION_DESIGN.md §10): on startup, adopt every existing
 * manually-created {@code user_broker_config.source_ip} into {@link UserIpAllocation} as a
 * read-only {@code ACTIVE} row with {@code eipAllocationId = null} (= manually managed,
 * never auto-released — design §2.1).
 *
 * <p><b>No AWS calls. No trading-path changes.</b> Pure DB adoption, idempotent: rows that
 * already exist are skipped, so it is safe to run on every boot. The public IP is left null
 * (we only have the private source IP today); the Phase 2 reconciler will fill it from
 * {@code DescribeAddresses}.</p>
 */
@Component
@Order(100)
public class IpAllocationBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IpAllocationBackfillService.class);

    private final UserBrokerConfigRepository brokerConfigRepository;
    private final UserIpAllocationRepository allocationRepository;

    public IpAllocationBackfillService(UserBrokerConfigRepository brokerConfigRepository,
                                       UserIpAllocationRepository allocationRepository) {
        this.brokerConfigRepository = brokerConfigRepository;
        this.allocationRepository = allocationRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int adopted = 0, skipped = 0;
            for (UserBrokerConfig cfg : brokerConfigRepository.findAll()) {
                String sourceIp = cfg.getSourceIp();
                if (sourceIp == null || sourceIp.isBlank()) continue;
                Long userId = cfg.getUserId();
                if (userId == null) continue;
                if (allocationRepository.existsByUserId(userId)) {
                    skipped++;
                    continue;
                }
                UserIpAllocation row = new UserIpAllocation();
                row.setUserId(userId);
                row.setPrivateIp(sourceIp.trim());
                // Manually managed: no AWS ids; never auto-released. Public IP unknown here.
                row.setEipAllocationId(null);
                row.setStatus(IpAllocationStatus.ACTIVE);
                // These IPs are already live and trading, so treat them as whitelisted;
                // operator can correct via the UI if needed.
                row.setWhitelistedWithBroker(true);
                row.setCreatedAt(Instant.now());
                row.setUpdatedAt(Instant.now());
                row.setUpdatedBy("backfill-adopt");
                row.setUpdatedReason("adopted existing manual source_ip (Phase 0)");
                allocationRepository.save(row);
                adopted++;
                log.info("[IpAllocation] Adopted manual source_ip for userId={} privateIp={} (read-only, never auto-released)",
                        userId, sourceIp);
            }
            if (adopted > 0 || skipped > 0) {
                log.info("[IpAllocation] Backfill complete: adopted={}, alreadyPresent={}", adopted, skipped);
            } else {
                log.info("[IpAllocation] Backfill: no existing source_ip values to adopt");
            }
        } catch (Exception e) {
            // Best-effort — must never block application startup.
            log.warn("[IpAllocation] Backfill skipped due to error (non-fatal): {}", e.getMessage(), e);
        }
    }
}
