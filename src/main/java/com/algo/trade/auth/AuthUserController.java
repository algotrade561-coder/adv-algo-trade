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
            return ResponseEntity.ok(Map.of("authenticated", true, "email", "local", "name", "Local User"));
        }
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                return ResponseEntity.ok(Map.of("authenticated", false));
            }
            // Don't access OAuth2User attributes — they cause ClassCastException on some JVMs
            // Just confirm the user is authenticated
            String name = auth.getName(); // returns the sub (Google user ID)
            // Try to get email from the AppUser table instead
            var users = userRepository.findAll();
            String email = users.stream()
                    .filter(u -> u.getLastLoginAt() != null)
                    .max(java.util.Comparator.comparing(AppUser::getLastLoginAt))
                    .map(AppUser::getEmail)
                    .orElse("authenticated");
            String displayName = users.stream()
                    .filter(u -> u.getEmail().equals(email))
                    .map(AppUser::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse(name);

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("authenticated", true);
            result.put("email", email);
            result.put("name", displayName);
            result.put("picture", "");
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
