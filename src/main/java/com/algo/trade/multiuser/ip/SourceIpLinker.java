package com.algo.trade.multiuser.ip;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.multiuser.SourceIpRoutingRequestFactory;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserWebSocketManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Bidirectional, idempotent reconciler between {@link UserIpAllocation#getPrivateIp()} and
 * {@code user_broker_config.source_ip} (design §7.1). Keyed by {@code userId}; safe to call
 * in any order and repeatedly, so the order of "Add User → provision IP → save broker creds"
 * never matters.
 *
 * <p>Whenever it actually changes {@code source_ip} it runs the <b>same invalidate chain</b>
 * as {@code MyBrokerController} (§2.0): REST routing factory + per-user OkHttp client +
 * WebSocket bounce, so the new bind address takes effect without a restart. If the value is
 * unchanged it is a no-op (no client churn).</p>
 */
@Service
public class SourceIpLinker {

    private static final Logger log = LoggerFactory.getLogger(SourceIpLinker.class);

    private final UserBrokerConfigRepository brokerRepository;
    private final UserIpAllocationRepository allocationRepository;
    private final UserBrokerSessionManager sessionManager;

    /** Optional — present only when source-IP routing is active. */
    @Autowired(required = false)
    private SourceIpRoutingRequestFactory sourceIpFactory;

    /** Optional — bounce the user's market-data WebSocket so it reconnects from the new IP. */
    @Autowired(required = false)
    private UserWebSocketManager userWebSocketManager;

    public SourceIpLinker(UserBrokerConfigRepository brokerRepository,
                          UserIpAllocationRepository allocationRepository,
                          UserBrokerSessionManager sessionManager) {
        this.brokerRepository = brokerRepository;
        this.allocationRepository = allocationRepository;
        this.sessionManager = sessionManager;
    }

    /**
     * Allocation → broker config. Writes {@code privateIp} into the user's {@code source_ip}
     * when it differs, then runs the invalidate chain. No-op if the broker config doesn't
     * exist yet (it will be linked on first save via {@link #linkOnBrokerConfigSave(Long)})
     * or the value is already correct.
     *
     * @return true if {@code source_ip} was changed.
     */
    @Transactional
    public boolean applyAllocatedIp(Long userId, String privateIp, String actor) {
        if (userId == null || privateIp == null || privateIp.isBlank()) return false;
        UserBrokerConfig cfg = brokerRepository.findByUserId(userId).orElse(null);
        if (cfg == null) {
            log.info("[SourceIpLinker] user={} has no broker config yet — source_ip will be linked on first save", userId);
            return false;
        }
        return writeSourceIp(cfg, privateIp.trim(), userId, actor);
    }

    /**
     * Broker config save → allocation. If an {@code ACTIVE}/known allocation with a private IP
     * exists and the config's {@code source_ip} is still blank, populate it (the "broker
     * config created after the IP" case, §7.1). Deliberately does <b>not</b> override a
     * non-blank operator/user-entered value.
     *
     * @return true if {@code source_ip} was changed.
     */
    @Transactional
    public boolean linkOnBrokerConfigSave(Long userId) {
        if (userId == null) return false;
        UserIpAllocation alloc = allocationRepository.findByUserId(userId).orElse(null);
        if (alloc == null || alloc.getPrivateIp() == null || alloc.getPrivateIp().isBlank()) return false;
        UserBrokerConfig cfg = brokerRepository.findByUserId(userId).orElse(null);
        if (cfg == null) return false;
        String current = cfg.getSourceIp();
        if (current != null && !current.isBlank()) {
            // Respect an existing value (manual entry / already linked) — never clobber it here.
            return false;
        }
        return writeSourceIp(cfg, alloc.getPrivateIp().trim(), userId, "link-on-save");
    }

    /**
     * Clear the user's {@code source_ip} (used on release/offboard) and run the invalidate
     * chain so no in-flight client keeps binding to an IP that is going away.
     *
     * @return true if {@code source_ip} was cleared.
     */
    @Transactional
    public boolean clearSourceIp(Long userId, String actor) {
        if (userId == null) return false;
        UserBrokerConfig cfg = brokerRepository.findByUserId(userId).orElse(null);
        if (cfg == null) return false;
        String current = cfg.getSourceIp();
        if (current == null || current.isBlank()) return false;
        return writeSourceIp(cfg, null, userId, actor);
    }

    /** Sets source_ip (null clears it), persists, and runs the invalidate chain if changed. */
    private boolean writeSourceIp(UserBrokerConfig cfg, String newIp, Long userId, String actor) {
        String oldIp = cfg.getSourceIp();
        if (Objects.equals(oldIp, newIp)) return false;
        cfg.setSourceIp(newIp);
        brokerRepository.save(cfg);
        runInvalidateChain(userId);
        log.info("[SourceIpLinker] user={} source_ip '{}' -> '{}' (by={}) — invalidated REST+OkHttp clients and bounced WS",
                userId, oldIp, newIp, actor);
        return true;
    }

    /** REST routing factory + per-user OkHttp client + WebSocket bounce (mirrors §2.0 fix). */
    private void runInvalidateChain(Long userId) {
        if (sourceIpFactory != null) sourceIpFactory.invalidate(userId);
        sessionManager.invalidateHttpClient(userId);
        if (userWebSocketManager != null) userWebSocketManager.disconnectUser(userId);
    }
}
