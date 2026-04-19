package com.kiteapioptions.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the Angular shell fresh while still allowing fingerprinted bundles to cache.
 */
@Component
public class StaticCacheControlFilter extends OncePerRequestFilter {

    private static final Pattern FINGERPRINTED_ASSET =
            Pattern.compile("^/(main|styles|polyfills)-[A-Z0-9]+\\.(js|css)$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isBlank() || "/".equals(path) || "/index.html".equals(path)
                || !path.substring(path.lastIndexOf('/') + 1).contains(".")) {
            response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        } else if (FINGERPRINTED_ASSET.matcher(path).matches()) {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
        filterChain.doFilter(request, response);
    }
}
