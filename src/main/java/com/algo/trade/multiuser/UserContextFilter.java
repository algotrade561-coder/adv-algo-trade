package com.algo.trade.multiuser;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Servlet filter that extracts the authenticated user's ID and sets it
 * in {@link UserContext} for the duration of the request.
 *
 * When Google Auth is disabled (local dev), uses DEFAULT_USER_ID.
 * When authenticated, looks up the AppUser by email and sets their ID.
 */
@Component
@Order(1)
public class UserContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);
    private static final String SESSION_USER_ID_KEY = "algo.userId";

    private final AppUserRepository userRepository;

    public UserContextFilter(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            Long userId = resolveUserId((HttpServletRequest) request);
            UserContext.setUserId(userId);
            // MDC: add userId to all log messages for per-user tracing
            org.slf4j.MDC.put("userId", String.valueOf(userId));
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            org.slf4j.MDC.remove("userId");
        }
    }

    private Long resolveUserId(HttpServletRequest request) {
        // Try cached session attribute first (fast path)
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object cached = session.getAttribute(SESSION_USER_ID_KEY);
            if (cached instanceof Long cachedId) {
                return cachedId;
            }
        }

        // Extract from Spring Security authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return UserContext.DEFAULT_USER_ID;
        }

        if (auth.getPrincipal() instanceof OAuth2User oAuth2User) {
            String email = oAuth2User.getAttribute("email");
            if (email != null) {
                Optional<AppUser> user = userRepository.findByEmail(email.toLowerCase());
                if (user.isPresent()) {
                    Long userId = user.get().getId();
                    // Cache in session for subsequent requests
                    if (session == null) session = request.getSession(true);
                    session.setAttribute(SESSION_USER_ID_KEY, userId);
                    UserContext.setUserEmail(email);
                    return userId;
                }
            }
        }

        return UserContext.DEFAULT_USER_ID;
    }
}
