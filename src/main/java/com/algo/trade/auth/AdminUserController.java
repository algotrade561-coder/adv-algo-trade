package com.algo.trade.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only user management. ADMIN and SUPERUSER can list / add / edit / disable users.
 * Role hierarchy enforced server-side:
 *   - SUPERUSER can create ADMIN, USER (and other SUPERUSER if needed).
 *   - ADMIN can only create USER.
 *   - Neither can edit themselves into a higher role.
 */
@RestController
@RequestMapping("/admin/app-users")
@PreAuthorize("hasAnyRole('ADMIN','SUPERUSER')")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    private static final List<String> VALID_ROLES = List.of("USER", "ADMIN", "SUPERUSER");

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository brokerRepository;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.algo.trade.multiuser.ip.IpAllocationService ipService;
    private final com.algo.trade.multiuser.ip.InstanceMetadataProvider metadata;
    private final UserOffboardingService offboardingService;
    private final com.algo.trade.config.usersettings.UserTradingSettingsRepository userSettingsRepo;

    public AdminUserController(AppUserRepository userRepository,
                                UserBrokerConfigRepository brokerRepository,
                                org.springframework.context.ApplicationEventPublisher events,
                                com.algo.trade.multiuser.ip.IpAllocationService ipService,
                                com.algo.trade.multiuser.ip.InstanceMetadataProvider metadata,
                                UserOffboardingService offboardingService,
                                com.algo.trade.config.usersettings.UserTradingSettingsRepository userSettingsRepo) {
        this.userRepository = userRepository;
        this.brokerRepository = brokerRepository;
        this.events = events;
        this.ipService = ipService;
        this.metadata = metadata;
        this.offboardingService = offboardingService;
        this.userSettingsRepo = userSettingsRepo;
    }

    /** SUPERUSER only — designate which user's API key feeds the shared market-data session. */
    @PostMapping("/{id}/primary-account")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<?> setPrimary(@PathVariable Long id, @RequestBody Map<String, Boolean> body,
                                         Authentication auth) {
        boolean make = Boolean.TRUE.equals(body.get("primary"));
        // Exclusive: clear any other primary, then set/unset this one.
        var configs = brokerRepository.findAll();
        for (UserBrokerConfig c : configs) {
            if (c.getUserId().equals(id)) continue;
            if (c.isPrimaryAccount()) {
                c.setPrimaryAccount(false);
                brokerRepository.save(c);
            }
        }
        UserBrokerConfig target = brokerRepository.findByUserId(id).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "user has no broker config yet"));
        }
        target.setPrimaryAccount(make);
        brokerRepository.save(target);
        log.info("[Admin] {} set userId={} primaryAccount={}", auth.getName(), id, make);
        if (make) {
            events.publishEvent(new MyBrokerController.PrimaryAccountChangedEvent(id));
        }
        return ResponseEntity.ok(Map.of("userId", id, "primaryAccount", make));
    }

    @GetMapping
    public List<UserView> list() {
        return userRepository.findAll().stream().map(u -> {
            boolean primary = brokerRepository.findByUserId(u.getId())
                    .map(UserBrokerConfig::isPrimaryAccount).orElse(false);
            IpAllocationView ip = ipService.get(u.getId()).map(IpAllocationView::of).orElse(null);
            // The primary/system account has no per-user allocation row — it egresses from the
            // instance's primary IP. Surface that IP so the Source IP column isn't blank.
            String sysPub = null, sysPriv = null;
            if (ip == null && primary) {
                sysPub = metadata.primaryPublicIp();
                sysPriv = metadata.primaryPrivateIp();
            }
            String riskProfile = userSettingsRepo.findByUserId(u.getId())
                    .map(com.algo.trade.config.usersettings.UserTradingSettings::getRiskProfile)
                    .orElse("BALANCED");
            return UserView.of(u, primary, ip, sysPub, sysPriv, riskProfile);
        }).toList();
    }

    // ── Per-user source-IP provisioning (design §8). Phase 1 = track-only (no AWS SDK). ──

    /** Current allocation for a user (for polling), or 404 if none. */
    @GetMapping("/{id}/source-ip")
    public ResponseEntity<?> getSourceIp(@PathVariable Long id) {
        return ipService.get(id)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(IpAllocationView.of(a)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("status", "NONE")));
    }

    /** Record a (manually performed) provision — track-only, no AWS calls. */
    @PostMapping("/{id}/source-ip:provision")
    public ResponseEntity<?> provisionSourceIp(@PathVariable Long id,
                                               @RequestBody(required = false) ProvisionIpRequest req,
                                               Authentication auth) {
        if (userRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        if (isPrimaryAccount(id)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "Primary account egresses from the instance's primary IP — it does not get a per-user "
                    + "Elastic IP. Whitelist the instance's primary public IP in Kite for this account instead."));
        }
        ProvisionIpRequest r = req == null
                ? new ProvisionIpRequest(null, null, null, null, null, null) : req;
        try {
            var alloc = ipService.recordProvision(id,
                    new com.algo.trade.multiuser.ip.IpAllocationService.ProvisionData(
                            r.privateIp(), r.publicIp(), r.eniId(),
                            r.eipAllocationId(), r.eipAssociationId(), r.instanceId()),
                    auth.getName());
            log.info("[Admin] {} recorded source-IP provision for userId={}", auth.getName(), id);
            return ResponseEntity.ok(IpAllocationView.of(alloc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Release a user's allocation. SUPERUSER only. Track-only in Phase 1 (AWS untouched). */
    @PostMapping("/{id}/source-ip:release")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<?> releaseSourceIp(@PathVariable Long id, Authentication auth) {
        try {
            var alloc = ipService.release(id, auth.getName());
            log.info("[Admin] {} released source-IP for userId={}", auth.getName(), id);
            return ResponseEntity.ok(IpAllocationView.of(alloc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Live AWS auto-allocate — allocate a fresh Elastic IP, assign it to a new secondary private IP on this
     * instance's ENI, OS-configure it, and bind it as the user's source IP. Used from User Management to
     * automate getting a new public IP for an existing user (e.g. after the old one was released), instead of
     * doing it by hand in the AWS console. SUPERUSER only; requires {@code ip-automation.enabled=true}. The
     * returned {@code publicIp} still has to be whitelisted in the broker (the one step that can't be automated).
     */
    @PostMapping("/{id}/source-ip:allocate")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<?> allocateSourceIp(@PathVariable Long id, Authentication auth) {
        if (userRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        if (isPrimaryAccount(id)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "Primary account egresses from the instance's primary IP — it does not get a per-user "
                    + "Elastic IP. Whitelist the instance's primary public IP in Kite for this account instead."));
        }
        try {
            var alloc = ipService.provision(id, auth.getName());
            log.info("[Admin] {} live-allocated source-IP for userId={} (public={})",
                    auth.getName(), id, alloc.getPublicIp());
            return ResponseEntity.ok(IpAllocationView.of(alloc));
        } catch (IllegalStateException ex) {
            // automation disabled / no capacity / provision failed (already compensated by the service)
            return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Flip the "whitelisted in Kite" gate (the manual step that can't be automated). */
    @PatchMapping("/{id}/source-ip")
    public ResponseEntity<?> patchSourceIp(@PathVariable Long id,
                                           @RequestBody Map<String, Boolean> body, Authentication auth) {
        boolean whitelisted = Boolean.TRUE.equals(body.get("whitelistedWithBroker"));
        try {
            var alloc = ipService.setWhitelisted(id, whitelisted, auth.getName());
            return ResponseEntity.ok(IpAllocationView.of(alloc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** EIP / ENI headroom for the provisioning fail-fast hint. */
    @GetMapping("/source-ip/capacity")
    public com.algo.trade.multiuser.ip.IpAllocationService.Capacity sourceIpCapacity() {
        return ipService.capacity();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateUserRequest req, Authentication auth) {
        if (req.email() == null || req.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email required"));
        }
        String email = req.email().toLowerCase().trim();
        String role = normalizeRole(req.role());
        if (!VALID_ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role: " + role));
        }
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.status(409).body(Map.of("error", "user already exists"));
        }
        // ADMIN cannot create ADMIN or SUPERUSER
        if (isAdminOnly(auth) && !"USER".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "ADMIN can only create USER role"));
        }

        AppUser u = new AppUser(email, role);
        u.setName(req.name());
        u.setEnabled(req.enabled() == null ? true : req.enabled());
        userRepository.save(u);
        log.info("[Admin] {} created user {} role={}", auth.getName(), email, role);
        // Auto-provision a source IP for the new user (async; no-op unless ip-automation.enabled). §7
        ipService.autoProvisionAsync(u.getId(), auth.getName());
        return ResponseEntity.ok(UserView.of(u));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpdateUserRequest req, Authentication auth) {
        AppUser u = userRepository.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();

        if (req.name() != null) u.setName(req.name());
        if (req.enabled() != null) u.setEnabled(req.enabled());
        if (req.role() != null) {
            String role = normalizeRole(req.role());
            if (!VALID_ROLES.contains(role)) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid role: " + role));
            }
            if (isAdminOnly(auth) && !"USER".equals(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "ADMIN can only assign USER role"));
            }
            u.setRole(role);
        }
        userRepository.save(u);
        log.info("[Admin] {} updated user id={} role={} enabled={}", auth.getName(), id, u.getRole(), u.isEnabled());
        String profile = userSettingsRepo.findByUserId(u.getId())
                .map(com.algo.trade.config.usersettings.UserTradingSettings::getRiskProfile)
                .orElse("BALANCED");
        return ResponseEntity.ok(UserView.of(u, false, null, null, null, profile));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    Authentication auth) {
        AppUser u = userRepository.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        if (isAdminOnly(auth) && !"USER".equals(u.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "ADMIN cannot delete non-USER"));
        }
        // Ordered, safe offboarding: open-position gate → stop → invalidate → release IP →
        // delete broker config → delete user (design §5.4).
        try {
            var result = offboardingService.offboard(id, force, auth.getName());
            log.info("[Admin] {} deleted user id={} email={}", auth.getName(), id, u.getEmail());
            return ResponseEntity.ok(Map.of("deleted", result.deleted(), "message", result.message()));
        } catch (UserOffboardingService.OpenPositionsException ex) {
            return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
        }
    }

    /** Primary account uses the instance's primary public IP, so it never gets a per-user EIP. */
    private boolean isPrimaryAccount(Long userId) {
        return brokerRepository.findByUserId(userId)
                .map(UserBrokerConfig::isPrimaryAccount).orElse(false);
    }

    private boolean isAdminOnly(Authentication auth) {
        boolean isSuper = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPERUSER".equals(a.getAuthority()));
        return !isSuper;
    }

    private String normalizeRole(String role) {
        return role == null ? "USER" : role.toUpperCase().trim();
    }

    // ── DTOs ──
    public record CreateUserRequest(String email, String name, String role, Boolean enabled) {}
    public record UpdateUserRequest(String name, String role, Boolean enabled) {}
    public record ProvisionIpRequest(String privateIp, String publicIp, String eniId,
                                     String eipAllocationId, String eipAssociationId, String instanceId) {}

    public record UserView(Long id, String email, String name, String role, boolean enabled,
                           boolean primaryAccount, Instant createdAt, Instant lastLoginAt,
                           IpAllocationView ipAllocation,
                           String systemPublicIp, String systemPrivateIp, String riskProfile) {
        public static UserView of(AppUser u) { return of(u, false, null, null, null, "BALANCED"); }
        public static UserView of(AppUser u, boolean primary) { return of(u, primary, null, null, null, "BALANCED"); }
        public static UserView of(AppUser u, boolean primary, IpAllocationView ip) {
            return of(u, primary, ip, null, null, "BALANCED");
        }
        public static UserView of(AppUser u, boolean primary, IpAllocationView ip,
                                  String systemPublicIp, String systemPrivateIp) {
            return of(u, primary, ip, systemPublicIp, systemPrivateIp, "BALANCED");
        }
        public static UserView of(AppUser u, boolean primary, IpAllocationView ip,
                                  String systemPublicIp, String systemPrivateIp, String riskProfile) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole(),
                    u.isEnabled(), primary, u.getCreatedAt(), u.getLastLoginAt(), ip,
                    systemPublicIp, systemPrivateIp, riskProfile);
        }
    }

    /** Admin-facing view of a source-IP allocation (admin sees both private and public IPs). */
    public record IpAllocationView(String privateIp, String publicIp, String eniId, String status,
                                   boolean whitelistedWithBroker, boolean manuallyManaged,
                                   String lastError) {
        public static IpAllocationView of(com.algo.trade.multiuser.ip.UserIpAllocation a) {
            return new IpAllocationView(
                    a.getPrivateIp(), a.getPublicIp(), a.getEniId(),
                    a.getStatus() == null ? null : a.getStatus().name(),
                    a.isWhitelistedWithBroker(), a.isManuallyManaged(), a.getLastError());
        }
    }
}
