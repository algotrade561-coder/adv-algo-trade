package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SUPERUSER-only AWS / Source-IP management surface (design §13.2/§13.4). Resource-centric
 * view that complements the user-centric Source-IP column on User Management: it reconciles
 * the {@code user_ip_allocation} table against live AWS to surface orphaned, cost-leaking EIPs.
 *
 * <ul>
 *   <li>{@code GET  /admin/aws-ip/reconcile} — full reconciliation report (read-only AWS).</li>
 *   <li>{@code GET  /admin/aws-ip/capacity}  — EIP quota / headroom snapshot.</li>
 *   <li>{@code POST /admin/aws-ip/release}   — SUPERUSER-confirmed release of a removable EIP.</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/aws-ip")
@PreAuthorize("hasRole('SUPERUSER')")
public class AwsIpController {

    private static final Logger log = LoggerFactory.getLogger(AwsIpController.class);

    private final IpReconciliationService reconciler;

    public AwsIpController(IpReconciliationService reconciler) {
        this.reconciler = reconciler;
    }

    /** Reconcile table vs live AWS and return the report (also applies safe local fixes). */
    @GetMapping("/reconcile")
    public IpReconciliationService.ReconciliationReport reconcile(Authentication auth) {
        return reconciler.reconcile(auth.getName());
    }

    /** Capacity snapshot only (cheap; reuses a reconcile pass). */
    @GetMapping("/capacity")
    public IpReconciliationService.CapacityView capacity(Authentication auth) {
        return reconciler.reconcile(auth.getName()).capacity();
    }

    /** SUPERUSER-confirmed release of a removable/orphaned EIP (design §13.3 — never automatic). */
    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody ReleaseRequest req, Authentication auth) {
        if (req == null || req.allocationId() == null || req.allocationId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "allocationId required"));
        }
        try {
            reconciler.releaseEip(req.allocationId().trim(), auth.getName());
            log.warn("[AwsIp] {} released EIP {}", auth.getName(), req.allocationId());
            return ResponseEntity.ok(Map.of("released", req.allocationId()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
        }
    }

    public record ReleaseRequest(String allocationId) {}
}
