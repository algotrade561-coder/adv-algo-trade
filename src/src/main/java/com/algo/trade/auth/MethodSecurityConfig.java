package com.algo.trade.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables @PreAuthorize ONLY when Google auth is enabled (i.e. production).
 *
 * In local dev (auth.google.enabled=false), there is no authenticated principal,
 * so @PreAuthorize("hasRole(...)") would always fail with "Access Denied".
 * Disabling method security entirely in dev keeps admin endpoints reachable
 * without granting the anonymous principal any role.
 */
@Configuration
@ConditionalOnProperty(name = "auth.google.enabled", havingValue = "true")
@EnableMethodSecurity
public class MethodSecurityConfig {
}
