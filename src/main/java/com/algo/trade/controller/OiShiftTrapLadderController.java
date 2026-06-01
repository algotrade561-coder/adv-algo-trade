package com.algo.trade.controller;

import com.algo.trade.strategy.oishifttrap.OiShiftTrapLadderConfig;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapLadderConfigService;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapLadderConfigService.RuntimeConfigUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for the OI Shift Trap limit-ladder runtime settings.
 *
 * <ul>
 *   <li>{@code GET  /oi-shift-trap/ladder} — current config + live ladder count</li>
 *   <li>{@code POST /oi-shift-trap/ladder} — partial update (reason required)</li>
 * </ul>
 */
@RestController
@RequestMapping("/oi-shift-trap/ladder")
public class OiShiftTrapLadderController {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapLadderController.class);

    private final OiShiftTrapLadderConfigService configService;

    public OiShiftTrapLadderController(OiShiftTrapLadderConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> dto = toDto(configService.getCached());
        dto.put("activeLadders", 0);
        return dto;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateRequest body,
                                                       @AuthenticationPrincipal OidcUser principal) {
        try {
            String operatorEmail = resolveOperator(principal);
            OiShiftTrapLadderConfig updated = configService.apply(
                    body.toUpdate(), operatorEmail, body.reason);
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException ex) {
            log.warn("OI Shift Trap ladder update rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private static Map<String, Object> toDto(OiShiftTrapLadderConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null) return m;
        m.put("ladderMode", c.getLadderMode());
        m.put("tier1Discount", c.getTier1Discount());
        m.put("tier2Discount", c.getTier2Discount());
        m.put("tier3Discount", c.getTier3Discount());
        m.put("ladderWindowMin", c.getLadderWindowMin());
        m.put("ladderOpScoreArmFloor", c.getLadderOpScoreArmFloor());
        m.put("ladderOpScoreCancelDelta", c.getLadderOpScoreCancelDelta());
        m.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        m.put("updatedBy", c.getUpdatedBy());
        m.put("updatedReason", c.getUpdatedReason());
        return m;
    }

    private static String resolveOperator(OidcUser principal) {
        if (principal == null) return "anonymous";
        String email = principal.getEmail();
        return email == null || email.isBlank() ? principal.getSubject() : email;
    }

    /** Request body. {@code reason} is mandatory (&gt;=5 chars) for the audit log. */
    public static class UpdateRequest {
        public String ladderMode;
        public Double tier1Discount;
        public Double tier2Discount;
        public Double tier3Discount;
        public Integer ladderWindowMin;
        public Integer ladderOpScoreArmFloor;
        public Integer ladderOpScoreCancelDelta;
        public String reason;

        public RuntimeConfigUpdate toUpdate() {
            RuntimeConfigUpdate u = new RuntimeConfigUpdate();
            u.ladderMode = ladderMode;
            u.tier1Discount = tier1Discount;
            u.tier2Discount = tier2Discount;
            u.tier3Discount = tier3Discount;
            u.ladderWindowMin = ladderWindowMin;
            u.ladderOpScoreArmFloor = ladderOpScoreArmFloor;
            u.ladderOpScoreCancelDelta = ladderOpScoreCancelDelta;
            return u;
        }
    }
}
