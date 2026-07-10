package com.algo.trade.auth;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserTradingStateManager;
import com.algo.trade.multiuser.ip.IpAllocationService;
import com.algo.trade.multiuser.ip.UserIpAllocationRepository;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ordered, safe user offboarding (design §5.4). Replaces the old "just delete the row" path,
 * which orphaned the broker config (API key/token) and the AWS Elastic IP, and never checked
 * for open positions.
 *
 * <p><b>Open-position safety:</b> this service refuses to delete a user who has any OPEN
 * trade. It deliberately does <b>not</b> auto-square-off, because that means placing live exit
 * orders on the user's behalf — the operator must flatten the book first, then re-run the
 * delete. ({@code force} is accepted for API symmetry but never bypasses this gate.)</p>
 *
 * <p>Cleanup order: stop trading → invalidate session/clients → release IP → delete broker
 * config → delete user. The allocation row is kept (marked RELEASED) for audit history.</p>
 */
@Service
public class UserOffboardingService {

    private static final Logger log = LoggerFactory.getLogger(UserOffboardingService.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository brokerRepository;
    private final TradeRepository tradeRepository;
    private final UserBrokerSessionManager sessionManager;
    private final UserTradingStateManager tradingStateManager;
    private final IpAllocationService ipService;
    private final UserIpAllocationRepository allocationRepository;

    @Autowired(required = false)
    private com.algo.trade.multiuser.SourceIpRoutingRequestFactory sourceIpFactory;
    @Autowired(required = false)
    private com.algo.trade.multiuser.UserWebSocketManager userWebSocketManager;

    public UserOffboardingService(AppUserRepository userRepository,
                                  UserBrokerConfigRepository brokerRepository,
                                  TradeRepository tradeRepository,
                                  UserBrokerSessionManager sessionManager,
                                  UserTradingStateManager tradingStateManager,
                                  IpAllocationService ipService,
                                  UserIpAllocationRepository allocationRepository) {
        this.userRepository = userRepository;
        this.brokerRepository = brokerRepository;
        this.tradeRepository = tradeRepository;
        this.sessionManager = sessionManager;
        this.tradingStateManager = tradingStateManager;
        this.ipService = ipService;
        this.allocationRepository = allocationRepository;
    }

    public record Result(boolean deleted, String message) {}

    /**
     * @param force accepted for API symmetry; does NOT override the open-position gate.
     * @throws OpenPositionsException if the user still has OPEN trades.
     */
    @Transactional
    public Result offboard(Long userId, boolean force, String actor) {
        AppUser u = userRepository.findById(userId).orElse(null);
        if (u == null) {
            // Idempotent: re-running on an already-deleted user is a no-op success.
            return new Result(true, "user already absent");
        }

        // 1. Safety gate — never delete a user with live exposure. No auto-squareoff.
        List<?> open = tradeRepository.findByUserIdAndStatus(userId, TradeStatus.OPEN);
        if (!open.isEmpty()) {
            String msg = "User " + u.getEmail() + " has " + open.size()
                    + " OPEN position(s). Square off the book first, then delete. "
                    + "(Automatic exit orders are intentionally not placed on the user's behalf.)";
            log.warn("[Offboard] refused for userId={}: {}", userId, msg);
            throw new OpenPositionsException(msg);
        }

        // 2. Stop trading so nothing re-enters mid-offboard.
        try { tradingStateManager.getState(userId).hardHalt("offboarding"); }
        catch (Exception e) { log.warn("[Offboard] hardHalt failed for userId={} (continuing): {}", userId, e.getMessage()); }

        // 3. Invalidate token + per-user clients + WS so no in-flight call binds a releasing IP.
        try { sessionManager.invalidateToken(userId, "offboarding"); } catch (Exception ignored) {}
        try { sessionManager.invalidateHttpClient(userId); } catch (Exception ignored) {}
        if (sourceIpFactory != null) { try { sourceIpFactory.invalidate(userId); } catch (Exception ignored) {} }
        if (userWebSocketManager != null) { try { userWebSocketManager.disconnectUser(userId); } catch (Exception ignored) {} }

        // 4. Release the IP (manual vs automated branch handled inside the service). Best-effort:
        //    a release failure must not block the local delete (reconciler flags any cost leak).
        if (allocationRepository.existsByUserId(userId)) {
            // Snapshot the email onto the allocation first, so a leaked EIP can still be
            // attributed to this user in the reconciler after the app_users row is gone.
            allocationRepository.findByUserId(userId).ifPresent(a -> {
                if (a.getUserEmail() == null && u.getEmail() != null) {
                    a.setUserEmail(u.getEmail());
                    allocationRepository.save(a);
                }
            });
            try { ipService.release(userId, actor); }
            catch (Exception e) { log.warn("[Offboard] IP release failed for userId={} (continuing; reconciler will retry): {}", userId, e.getMessage()); }
        }

        // 5. Delete the broker config so credentials don't linger.
        brokerRepository.findByUserId(userId).ifPresent(cfg -> {
            try { brokerRepository.delete(cfg); }
            catch (Exception e) { log.warn("[Offboard] broker-config delete failed for userId={}: {}", userId, e.getMessage()); }
        });
        // (The allocation row is kept, marked RELEASED, for audit history — design §5.4 step 5.)

        // 6. Finally delete the user.
        userRepository.delete(u);
        log.info("[Offboard] user id={} email={} fully offboarded by {}", userId, u.getEmail(), actor);
        return new Result(true, "offboarded " + u.getEmail());
    }

    /** Thrown when a user still has OPEN positions — surfaced as 409 by the controller. */
    public static class OpenPositionsException extends RuntimeException {
        public OpenPositionsException(String message) { super(message); }
    }
}
