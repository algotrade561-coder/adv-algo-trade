package com.algo.trade.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards Angular client-side routes to {@code index.html} so bookmarked and
 * deep-linked URLs work when served from Spring static resources.
 *
 * <p>REST APIs are unaffected — {@code @RestController} mappings (e.g.
 * {@code /reports/tuning/jobs}) are matched before this controller.</p>
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/",
            "/dashboard",
            "/strategies",
            "/settings",
            "/tuning-capture",
            "/tuning/explore",
            "/tuning/reports/**",
            "/tuning/strategy/**",
            "/index-config",
            "/execution",
            "/monitoring",
            "/entry-signals",
            "/rejected-signals",
            "/reports",
            "/auth",
            "/diagnostics"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
