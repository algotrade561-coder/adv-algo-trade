package com.algo.trade.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Returns the currently authenticated user info for the Angular UI.
 */
@RestController
@RequestMapping("/auth")
public class AuthUserController {

    private final AppUserRepository userRepository;

    public AuthUserController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @org.springframework.beans.factory.annotation.Value("${auth.google.enabled:false}")
    private String googleAuthEnabledRaw;

    private boolean isGoogleAuthEnabled() {
        return "true".equalsIgnoreCase(googleAuthEnabledRaw);
    }

    @GetMapping("/user")
    public ResponseEntity<?> currentUser(jakarta.servlet.http.HttpServletRequest request) {
        if (!isGoogleAuthEnabled()) {
            // Local dev — return SUPERUSER so all admin pages are reachable
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "email", "local",
                    "name", "Local User",
                    "role", "SUPERUSER",
                    "userId", 0));
        }
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                return ResponseEntity.ok(Map.of("authenticated", false));
            }
            // Pull email + name directly from the OAuth2User principal — this is the
            // ACTUAL logged-in user, not whoever last logged in globally.
            String email = null, displayName = null, picture = null;
            Object principal = auth.getPrincipal();
            if (principal instanceof org.springframework.security.oauth2.core.user.OAuth2User o) {
                email       = o.getAttribute("email");
                displayName = o.getAttribute("name");
                picture     = o.getAttribute("picture");
            }
            if (email == null || email.isBlank()) email = auth.getName();
            String emailKey = email == null ? null : email.toLowerCase();

            AppUser dbUser = emailKey == null ? null : userRepository.findByEmail(emailKey).orElse(null);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("authenticated", true);
            result.put("email", emailKey);
            result.put("name", displayName != null ? displayName : (dbUser != null ? dbUser.getName() : email));
            result.put("picture", picture != null ? picture : "");
            result.put("role", dbUser != null ? dbUser.getRole() : "USER");
            result.put("userId", dbUser != null ? dbUser.getId() : null);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("authenticated", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/login-failed")
    public ResponseEntity<?> loginFailed() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "Access denied",
                "message", "You are not authorized to access this application. Contact the administrator."
        ));
    }
}
