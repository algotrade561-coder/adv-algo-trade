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

    public AdminUserController(AppUserRepository userRepository,
                                UserBrokerConfigRepository brokerRepository,
                                org.springframework.context.ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.brokerRepository = brokerRepository;
        this.events = events;
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
            return UserView.of(u, primary);
        }).toList();
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
        return ResponseEntity.ok(UserView.of(u));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        AppUser u = userRepository.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        if (isAdminOnly(auth) && !"USER".equals(u.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "ADMIN cannot delete non-USER"));
        }
        userRepository.delete(u);
        log.info("[Admin] {} deleted user id={} email={}", auth.getName(), id, u.getEmail());
        return ResponseEntity.ok(Map.of("deleted", true));
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

    public record UserView(Long id, String email, String name, String role, boolean enabled,
                           boolean primaryAccount, Instant createdAt, Instant lastLoginAt) {
        public static UserView of(AppUser u) { return of(u, false); }
        public static UserView of(AppUser u, boolean primary) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole(),
                    u.isEnabled(), primary, u.getCreatedAt(), u.getLastLoginAt());
        }
    }
}
