package com.algo.trade.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Google OAuth2 authentication — only allows users whose email is in the app_users table.
 * Activated by setting auth.google.enabled=true in application.yml.
 * When disabled, all endpoints are open (local development).
 *
 * Roles: USER (default), ADMIN, SUPERUSER. ADMIN+SUPERUSER can manage users via /admin/**.
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
     * Session cookie SameSite policy.
     *  - HTTPS behind ALB / live: SameSite=None (Chrome will accept because Secure=true is set in application-live.yml).
     *  - Local HTTP dev: SameSite=Lax — Chrome drops SameSite=None cookies on non-secure origins, which breaks
     *    the OAuth state cookie and the user can't complete login.
     *
     * Controlled by `auth.cookie.same-site-none` (default false → Lax). The live profile sets it true.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.cookie.same-site-none", havingValue = "true")
    public org.springframework.boot.web.servlet.server.CookieSameSiteSupplier cookieSameSiteSupplierNone() {
        return org.springframework.boot.web.servlet.server.CookieSameSiteSupplier.ofNone();
    }

    @Bean
    @ConditionalOnProperty(name = "auth.cookie.same-site-none", havingValue = "false", matchIfMissing = true)
    public org.springframework.boot.web.servlet.server.CookieSameSiteSupplier cookieSameSiteSupplierLax() {
        return org.springframework.boot.web.servlet.server.CookieSameSiteSupplier.ofLax();
    }

    /**
     * Programmatic Google ClientRegistrationRepository — used when Google auth is
     * on but spring.security.oauth2.client.registration.google isn't defined in
     * yml (e.g. the local profile). Values come from GOOGLE_CLIENT_ID /
     * GOOGLE_CLIENT_SECRET, which SecretsEnvironmentPostProcessor publishes from
     * data/trading-secrets.properties early in boot.
     *
     * @ConditionalOnMissingBean keeps Spring's yml-based auto-config working too
     * — the live profile's yml registration still wins if present.
     */
    @Bean
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    @ConditionalOnProperty(name = "auth.google.enabled", havingValue = "true")
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID:}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "auth.google.enabled=true but GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are not set. " +
                    "Add them to data/trading-secrets.properties (or env vars).");
        }
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "email", "profile")
                .build();
        log.info("Google ClientRegistration created programmatically (clientId={}…)",
                clientId.substring(0, Math.min(8, clientId.length())));
        return new InMemoryClientRegistrationRepository(google);
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
            .csrf(csrf -> csrf
                // Use cookie-based CSRF token — Angular HttpClient reads XSRF-TOKEN cookie
                // and sends it as X-XSRF-TOKEN header on mutating requests automatically.
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Required for SPA: make the token available as a request attribute for deferred loading
                .csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                // Exempt Kite OAuth callbacks (Zerodha redirects, no CSRF token available)
                // and the H2 console (its own login form POSTs carry no XSRF token → would 403).
                .ignoringRequestMatchers("/auth/kite/callback", "/auth/kite/callback/**",
                        "/me/broker/kite/callback", "/me/broker/kite/callback/**",
                        "/h2-console/**")
            )
            // SPA fix: Spring Security 6 defers CSRF token generation — the XSRF-TOKEN
            // cookie is only written when the token is actually read during a request.
            // Without this filter Angular never receives the cookie, can't send the
            // X-XSRF-TOKEN header, and EVERY POST/PUT/DELETE fails with 403.
            .addFilterAfter((request, response, chain) -> {
                Object token = ((jakarta.servlet.http.HttpServletRequest) request)
                        .getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
                if (token instanceof org.springframework.security.web.csrf.CsrfToken csrf) {
                    csrf.getToken(); // force deferred token resolution → cookie gets written
                }
                chain.doFilter(request, response);
            }, org.springframework.security.web.csrf.CsrfFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Public: Kite OAuth callbacks only (Zerodha redirects land here)
                .requestMatchers("/auth/kite/callback", "/auth/kite/callback/**").permitAll()
                // Legacy shared (file-based) login/session — superuser-only backend fallback, no UI
                .requestMatchers("/auth/kite/login", "/auth/kite/session").hasRole("SUPERUSER")
                .requestMatchers("/auth/kite/**").permitAll()
                // Public: static resources (Angular app, CSS, JS)
                .requestMatchers("/", "/index.html", "/*.js", "/*.css", "/*.ico",
                        "/manifest.json", "/assets/**", "/3rdpartylicenses.txt",
                        "/prerendered-routes.json").permitAll()
                // Public: health check
                .requestMatchers("/actuator/health").permitAll()
                // H2 database console — restricted to SUPERUSER (CSRF is exempted above and
                // frameOptions=sameOrigin below so the console's frames + login POST work).
                .requestMatchers("/h2-console/**").hasRole("SUPERUSER")
                // Public: login/logout endpoints
                .requestMatchers("/login/**", "/oauth2/**", "/auth/login-failed").permitAll()
                // Auth user check — accessible to all but reads session if present
                .requestMatchers("/auth/user").permitAll()
                // Admin endpoints — restricted to ADMIN + SUPERUSER
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SUPERUSER")
                // Self-service endpoints — any authenticated user
                .requestMatchers("/me/**").authenticated()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
                .failureUrl("/auth/login-failed")
                .userInfoEndpoint(userInfo -> userInfo
                    // Plain OAuth2 flow (no openid scope)
                    .userService(oAuth2UserService())
                    // OIDC flow — Google logins use THIS because scope includes "openid".
                    // Without it, the DB role authority is never attached and /admin/** 403s.
                    .oidcUserService(oidcUserService())
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
                            || request.getRequestURI().matches(".*/(?:global-config|oi-momentum|trading|strategies|ml|analytics|diagnostics|monitoring|reports|backtest|performance).*");
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
            // Headers: keep frame-options for the H2 console, but explicitly relax
            // Cross-Origin-Opener-Policy + set a Referrer-Policy that survives the
            // OAuth round-trip with Google. Spring Security 6 defaults COOP to
            // "same-origin", which can sever the opener reference and break the
            // post-callback navigation in some Chrome versions.
            .headers(headers -> headers
                    .frameOptions(frame -> frame.sameOrigin())
                    .crossOriginOpenerPolicy(coop -> coop.policy(
                            org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter
                                    .CrossOriginOpenerPolicy.UNSAFE_NONE))
                    .referrerPolicy(rp -> rp.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE))
            );

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
            AppUser user = validateAndUpdateUser(
                    oAuth2User.getAttribute("email"),
                    oAuth2User.getAttribute("name"),
                    oAuth2User.getAttribute("picture"));
            return new DefaultOAuth2User(withRoleAuthority(oAuth2User.getAuthorities(), user),
                    oAuth2User.getAttributes(), "email");
        };
    }

    /**
     * OIDC variant of the same check — Google logins go through THIS service because
     * the registration scope includes "openid". Must mirror oAuth2UserService(),
     * otherwise the ROLE_* authority from app_users is never attached to the session.
     */
    private OAuth2UserService<
            org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
            org.springframework.security.oauth2.core.oidc.user.OidcUser> oidcUserService() {
        var delegate = new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService();
        return request -> {
            var oidcUser = delegate.loadUser(request);
            AppUser user = validateAndUpdateUser(
                    oidcUser.getAttribute("email"),
                    oidcUser.getAttribute("name"),
                    oidcUser.getAttribute("picture"));
            return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                    withRoleAuthority(oidcUser.getAuthorities(), user),
                    oidcUser.getIdToken(), oidcUser.getUserInfo(), "email");
        };
    }

    /** Shared allow-list + enabled check; updates profile fields on each login. */
    private AppUser validateAndUpdateUser(String email, String name, String picture) {
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

        AppUser user = userOpt.get();
        if (!user.isEnabled()) {
            log.warn("Google login REJECTED: user {} is disabled", email);
            throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                    new org.springframework.security.oauth2.core.OAuth2Error("access_denied",
                            "User is disabled", null), "User is disabled");
        }
        if (name != null && !name.isBlank()) user.setName(name);
        if (picture != null) user.setPictureUrl(picture);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("Google login accepted: email={} name={} role={}", email, name, user.getRole());
        return user;
    }

    /** Attaches ROLE_* from app_users.role so hasRole / @PreAuthorize work. Maps legacy SUPER → SUPERUSER. */
    private Set<org.springframework.security.core.GrantedAuthority> withRoleAuthority(
            java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> base, AppUser user) {
        Set<org.springframework.security.core.GrantedAuthority> authorities = new HashSet<>(base);
        String role = user.getRole() == null ? "USER" : user.getRole().trim().toUpperCase();
        if ("SUPER".equals(role)) role = "SUPERUSER";
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        return authorities;
    }
}
