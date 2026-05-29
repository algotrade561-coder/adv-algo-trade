package com.algo.trade.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;

/**
 * Google OAuth2 authentication — only allows users whose email is in the app_users table.
 * Activated by setting auth.google.enabled=true in application.yml.
 * When disabled, all endpoints are open (local development).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${auth.google.enabled:false}")
    private String googleAuthEnabledRaw;

    private boolean isGoogleAuthEnabled() {
        return "true".equalsIgnoreCase(googleAuthEnabledRaw);
    }

    private final AppUserRepository userRepository;

    public SecurityConfig(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Force session cookie attributes for HTTPS behind ALB.
     */
    @Bean
    public org.springframework.boot.web.servlet.server.CookieSameSiteSupplier cookieSameSiteSupplier() {
        return org.springframework.boot.web.servlet.server.CookieSameSiteSupplier.ofNone();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!isGoogleAuthEnabled()) {
            // Auth disabled — allow everything (local development)
            log.info("Google auth DISABLED — all endpoints open");
            http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
            return http.build();
        }

        log.info("Google auth ENABLED — protecting all endpoints");
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Public: Kite OAuth callback (broker auth, not user auth)
                .requestMatchers("/auth/kite/**").permitAll()
                // Public: static resources (Angular app, CSS, JS)
                .requestMatchers("/", "/index.html", "/*.js", "/*.css", "/*.ico",
                        "/manifest.json", "/assets/**", "/3rdpartylicenses.txt",
                        "/prerendered-routes.json").permitAll()
                // Public: health check
                .requestMatchers("/actuator/health").permitAll()
                // Public: login/logout endpoints
                .requestMatchers("/login/**", "/oauth2/**", "/auth/login-failed").permitAll()
                // Auth user check — accessible to all but reads session if present
                .requestMatchers("/auth/user").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
                .failureUrl("/auth/login-failed")
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(oAuth2UserService())
                )
                .successHandler((request, response, authentication) -> {
                    // Double-check: verify email is in allowed users AFTER successful Google auth
                    if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
                        String email = oAuth2User.getAttribute("email");
                        if (email == null || !userRepository.existsByEmail(email.toLowerCase())) {
                            log.warn("Post-auth rejection: email {} not in allowed users — invalidating session", email);
                            request.getSession().invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            response.sendRedirect("/advalgotrade/auth/login-failed");
                            return;
                        }
                    }
                    response.sendRedirect("/advalgotrade/");
                })
            )
            // For AJAX/API calls: return 401 instead of redirect to Google
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String accept = request.getHeader("Accept");
                    String xhrHeader = request.getHeader("X-Requested-With");
                    boolean isApiCall = (accept != null && accept.contains("application/json"))
                            || "XMLHttpRequest".equals(xhrHeader)
                            || request.getRequestURI().matches(".*/(?:global-config|oi-momentum|trading|strategies|ml|analytics|diagnostics|monitoring|backtest|performance).*");
                    if (isApiCall) {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Authentication required\",\"loginUrl\":\"/advalgotrade/oauth2/authorization/google\"}");
                    } else {
                        response.sendRedirect("/advalgotrade/oauth2/authorization/google");
                    }
                })
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            )
            // For H2 console (if enabled)
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    /**
     * Custom OAuth2 user service — checks if the Google email is in the allowed users table.
     * If not found, throws an exception which Spring Security converts to a login failure.
     */
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        return request -> {
            OAuth2User oAuth2User = delegate.loadUser(request);
            String email = oAuth2User.getAttribute("email");
            String name = oAuth2User.getAttribute("name");
            String picture = oAuth2User.getAttribute("picture");

            if (email == null || email.isBlank()) {
                log.warn("Google login rejected: no email in OAuth2 response");
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("No email provided");
            }

            // Check if email is in allowed users table
            var userOpt = userRepository.findByEmail(email.toLowerCase());
            if (userOpt.isEmpty()) {
                log.warn("Google login REJECTED: email {} not in allowed users", email);
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                        new org.springframework.security.oauth2.core.OAuth2Error("access_denied",
                                "Email " + email + " is not authorized", null),
                        "Access denied: " + email + " is not authorized");
            }

            // Update user profile on each login
            AppUser user = userOpt.get();
            if (name != null && !name.isBlank()) user.setName(name);
            if (picture != null) user.setPictureUrl(picture);
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            log.info("Google login accepted: email={} name={} role={}", email, name, user.getRole());
            return oAuth2User;
        };
    }
}
