package com.algo.trade.multiuser.ip;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 3 reconciler + cost/orphan view (design §13). Joins the {@code user_ip_allocation}
 * table to live AWS state ({@code DescribeAddresses}) so EIPs that never appear in the
 * user-centric column — released-but-not-freed, left after an instance replacement, allocated
 * manually, or tied to a disabled/deleted user — are surfaced as a <b>cost leak</b>.
 *
 * <p><b>Read-only against AWS</b> for reconciliation (Describe* only), so it is safe to run
 * even with {@code ip-automation.enabled=false} (the Mock client returns an empty EIP list and
 * the view degrades to table-only). The only writes are local DB fixes (public-IP backfill,
 * drift rewrite) and, on boot, idempotent OS secondary-IP re-apply. EIP release is never
 * automatic — it is an explicit SUPERUSER action via {@link #releaseEip}.</p>
 */
@Service
public class IpReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(IpReconciliationService.class);

    /** AWS public-IPv4 charge since 2024-02-01: ~$0.005/hr ≈ $3.65/month per address (design §9). */
    private static final double EIP_MONTHLY_USD = 3.65;

    private final UserIpAllocationRepository repo;
    private final AppUserRepository users;
    private final UserBrokerConfigRepository brokerConfigRepo;
    private final Ec2NetworkClient ec2;
    private final OsSecondaryIpConfigurer osConfigurer;
    private final InstanceMetadataProvider metadata;

    @Value("${ip-automation.enabled:false}")
    private boolean automationEnabled;

    @Value("${ip-automation.max-eips:5}")
    private int maxEips;

    public IpReconciliationService(UserIpAllocationRepository repo, AppUserRepository users,
                                   UserBrokerConfigRepository brokerConfigRepo,
                                   Ec2NetworkClient ec2, OsSecondaryIpConfigurer osConfigurer,
                                   InstanceMetadataProvider metadata) {
        this.repo = repo;
        this.users = users;
        this.brokerConfigRepo = brokerConfigRepo;
        this.ec2 = ec2;
        this.osConfigurer = osConfigurer;
        this.metadata = metadata;
    }

    // ── triggers ────────────────────────────────────────────────────────────────────

    /** Daily scheduled reconcile (cron configurable). Moved 06:30 → 15:40 IST (2026-06-29): the box now stops
     *  15:50 / starts 08:45 (cost-saving stop/start schedule), so an early-morning 06:30 run would never fire.
     *  15:42 is post-close, inside the on-window, before the 15:50 stop (reconcile is a few API calls — seconds).
     *  Explicit Asia/Kolkata zone so the time is unambiguous regardless of JVM default. */
    @Scheduled(cron = "${ip-automation.reconcile-cron:0 42 15 * * *}", zone = "Asia/Kolkata")
    public void scheduledReconcile() {
        try {
            ReconciliationReport r = reconcile("scheduler");
            log.info("[IpReconcile] daily: {} mappings, {} removable (~${}/mo), {} drift",
                    r.mappings().size(), r.removableCount(), String.format("%.2f", r.removableMonthlyCostUsd()),
                    r.driftCount());
        } catch (Exception e) {
            log.warn("[IpReconcile] daily reconcile failed: {}", e.getMessage(), e);
        }
    }

    /** On boot: re-apply OS secondary IPs (lost across reboot, §6) then run a reconcile pass. */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        try {
            int reapplied = reapplyOsSecondaryIps("boot");
            log.info("[IpReconcile] boot: re-applied {} OS secondary IP(s)", reapplied);
            reconcile("boot");
        } catch (Exception e) {
            log.warn("[IpReconcile] boot reconcile failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    // ── core ────────────────────────────────────────────────────────────────────────

    /**
     * Build the reconciliation report and apply the safe local fixes (public-IP backfill +
     * drift rewrite). Never releases an EIP. Returns the report for the dashboard.
     */
    @Transactional
    public ReconciliationReport reconcile(String actor) {
        String ourEni = safe(metadata::primaryEniId);
        String ourInstance = safe(metadata::instanceId);
        String ourPrimaryIp = safe(metadata::primaryPrivateIp);

        // Resolve the primary/system account so the infra "Primary IP" row can show who owns
        // the box's default egress instead of a blank user (it has no user_ip_allocation row).
        UserBrokerConfig primaryCfg = brokerConfigRepo.findByPrimaryAccountTrue().orElse(null);
        Long primaryUserId = primaryCfg != null ? primaryCfg.getUserId() : null;
        AppUser primaryUser = primaryUserId != null ? users.findById(primaryUserId).orElse(null) : null;
        String primaryEmail = primaryUser != null ? primaryUser.getEmail() : null;
        boolean primaryEnabled = primaryUser != null && primaryUser.isEnabled();

        List<UserIpAllocation> rows = repo.findAll();
        Map<String, UserIpAllocation> byAlloc = new HashMap<>();
        Map<String, UserIpAllocation> byPrivate = new HashMap<>();
        for (UserIpAllocation r : rows) {
            if (notBlank(r.getEipAllocationId())) byAlloc.put(r.getEipAllocationId(), r);
            if (notBlank(r.getPrivateIp())) byPrivate.put(r.getPrivateIp(), r);
        }

        List<Ec2NetworkClient.EipInfo> eips;
        try { eips = ec2.describeAddresses(); }
        catch (Exception e) {
            log.warn("[IpReconcile] describeAddresses failed — table-only view: {}", e.getMessage());
            eips = List.of();
        }

        List<EipRow> out = new ArrayList<>();
        int removable = 0, drift = 0, oursEips = 0, externalEips = 0;
        java.util.Set<Long> rowsSeen = new java.util.HashSet<>();

        for (Ec2NetworkClient.EipInfo eip : eips) {
            UserIpAllocation row = eip.allocationId() != null ? byAlloc.get(eip.allocationId()) : null;
            if (row == null && eip.privateIp() != null) row = byPrivate.get(eip.privateIp());
            boolean onOurEni = ourEni != null && ourEni.equals(eip.networkInterfaceId());
            boolean onOurInstance = ourInstance != null && ourInstance.equals(eip.instanceId());
            boolean ours = row != null || ((onOurEni || onOurInstance) && !eip.serviceManaged());
            boolean isInfra = ourPrimaryIp != null && ourPrimaryIp.equals(eip.privateIp());

            String email = null;
            boolean userEnabled = false;
            boolean userDeleted = false;

            // Whenever this EIP maps to an allocation row, resolve the user, apply safe local
            // fixes (public-IP backfill, drift rewrite), and mark the row as seen.
            if (row != null) {
                rowsSeen.add(row.getId());
                Optional<AppUser> u = users.findById(row.getUserId());
                userEnabled = u.map(AppUser::isEnabled).orElse(false);
                if (u.isPresent()) {
                    email = u.get().getEmail();
                    // snapshot the live email so attribution survives a later user deletion
                    if (notBlank(email) && !email.equals(row.getUserEmail())) {
                        row.setUserEmail(email);
                        touch(row, actor, "reconcile: snapshotted user email");
                        repo.save(row);
                    }
                } else {
                    // app_users row gone (offboarded) — fall back to the last-known snapshot
                    email = row.getUserEmail();
                    userDeleted = true;
                }

                if (blank(row.getPublicIp()) && notBlank(eip.publicIp())) {
                    row.setPublicIp(eip.publicIp());
                    touch(row, actor, "reconcile: backfilled public IP");
                    repo.save(row);
                }
                boolean changed = false;
                if (notBlank(eip.instanceId()) && !eip.instanceId().equals(row.getInstanceId())) {
                    row.setInstanceId(eip.instanceId()); changed = true;
                }
                if (notBlank(eip.networkInterfaceId()) && !eip.networkInterfaceId().equals(row.getEniId())) {
                    row.setEniId(eip.networkInterfaceId()); changed = true;
                }
                if (changed) {
                    drift++;
                    touch(row, actor, "reconcile: drift rewrite (instance/ENI changed)");
                    repo.save(row);
                }
            }

            Disposition disp;
            if (isInfra) {
                // The instance's own primary IP (and the primary account's egress). Never a
                // per-user allocation; releasing it would strip the box's public IP.
                disp = Disposition.PROTECTED;
            } else if (!ours) {
                disp = Disposition.EXTERNAL;          // ALB/NAT or another stack — never our cost leak
            } else if (row != null) {
                boolean active = row.getStatus() == IpAllocationStatus.ACTIVE;
                disp = (active && userEnabled) ? Disposition.NECESSARY : Disposition.REMOVABLE;
            } else {
                disp = Disposition.REMOVABLE;         // on our ENI/instance but untracked — likely a leak
            }
            if (disp == Disposition.REMOVABLE) removable++;
            // every entry here is a live AWS EIP — split ours (incl. primary) vs external (ALB/NAT)
            if (disp == Disposition.EXTERNAL) externalEips++; else oursEips++;

            // For the infra/primary-IP row (no allocation), attribute it to the primary account
            // so the dashboard shows the owner + a SYSTEM label instead of blanks.
            Long rowUserId = row != null ? row.getUserId() : null;
            String rowEmail = email;
            boolean rowEnabled = userEnabled;
            if (disp == Disposition.PROTECTED && row == null && primaryUser != null) {
                rowUserId = primaryUserId;
                rowEmail = primaryEmail;
                rowEnabled = primaryEnabled;
            }

            out.add(new EipRow(
                    eip.allocationId(), eip.publicIp(), eip.privateIp(), eip.associationId(),
                    eip.networkInterfaceId(), eip.instanceId(),
                    rowUserId, rowEmail, rowEnabled, userDeleted,
                    row != null ? row.getStatus() : null,
                    row != null && row.isManuallyManaged(),
                    disp, changedReason(disp, row, eip, userDeleted)));
        }

        // Allocation rows with no live EIP (e.g. track-only/manual with no public IP, or stale).
        List<EipRow> tableOnly = new ArrayList<>();
        for (UserIpAllocation r : rows) {
            if (rowsSeen.contains(r.getId())) continue;
            if (r.getStatus() == IpAllocationStatus.RELEASED) continue;
            Optional<AppUser> u = users.findById(r.getUserId());
            boolean deleted = u.isEmpty();
            String email = u.map(AppUser::getEmail).orElse(r.getUserEmail());
            tableOnly.add(new EipRow(
                    r.getEipAllocationId(), r.getPublicIp(), r.getPrivateIp(), r.getEipAssociationId(),
                    r.getEniId(), r.getInstanceId(),
                    r.getUserId(), email, u.map(AppUser::isEnabled).orElse(false), deleted,
                    r.getStatus(), r.isManuallyManaged(),
                    Disposition.TABLE_ONLY, "no live EIP found in AWS"));
        }
        out.addAll(tableOnly);

        long failedOrReleasing = rows.stream()
                .filter(r -> r.getStatus() == IpAllocationStatus.FAILED || r.getStatus() == IpAllocationStatus.RELEASING)
                .count();

        return new ReconciliationReport(
                Instant.now(), automationEnabled, ourEni, ourInstance,
                out, removable, removable * EIP_MONTHLY_USD, drift, (int) failedOrReleasing,
                capacity(eips.size(), oursEips, externalEips));
    }

    /** Re-apply every ACTIVE allocation's secondary private IP to the OS interface (idempotent). */
    public int reapplyOsSecondaryIps(String actor) {
        int n = 0;
        int prefix = safePrefix();
        for (UserIpAllocation r : repo.findByStatus(IpAllocationStatus.ACTIVE)) {
            if (blank(r.getPrivateIp())) continue;
            try { osConfigurer.add(r.getPrivateIp(), prefix); n++; }
            catch (Exception e) { log.warn("[IpReconcile] OS re-apply failed for {}: {}", r.getPrivateIp(), e.getMessage()); }
        }
        return n;
    }

    /**
     * Explicit SUPERUSER-confirmed release of an EIP flagged removable (design §13.3 —
     * flag, never auto-delete). Disassociates if needed, releases the EIP, and marks any
     * matching allocation row RELEASED. Live only ({@code ip-automation.enabled=true}).
     */
    @Transactional
    public void releaseEip(String allocationId, String actor) {
        if (blank(allocationId)) throw new IllegalArgumentException("allocationId required");
        if (!automationEnabled) {
            throw new IllegalStateException("ip-automation.enabled=false — cannot release live AWS EIPs");
        }
        Ec2NetworkClient.EipInfo target = ec2.describeAddresses().stream()
                .filter(e -> allocationId.equals(e.allocationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no EIP with allocationId=" + allocationId));
        if (target.serviceManaged()) {
            throw new IllegalArgumentException("refusing to release an AWS service-managed (ALB/NAT) EIP");
        }
        String primaryIp = safe(metadata::primaryPrivateIp);
        if (primaryIp != null && primaryIp.equals(target.privateIp())) {
            throw new IllegalArgumentException(
                    "refusing to release the instance's primary IP (" + target.publicIp() + ") — it is the box's own address");
        }
        UserIpAllocation row = repo.findByEipAllocationId(allocationId).orElse(null);
        if (row != null && row.getStatus() == IpAllocationStatus.ACTIVE
                && users.findById(row.getUserId()).map(AppUser::isEnabled).orElse(false)) {
            throw new IllegalArgumentException("EIP is in use by an enabled user — disable/offboard that user first");
        }
        try {
            if (notBlank(target.associationId())) ec2.disassociateAddress(target.associationId());
        } catch (Exception e) {
            log.warn("[IpReconcile] disassociate during release failed (continuing): {}", e.getMessage());
        }
        ec2.releaseAddress(allocationId);
        if (row != null) {
            row.setStatus(IpAllocationStatus.RELEASED);
            touch(row, actor, "reconcile: SUPERUSER released orphaned EIP");
            repo.save(row);
        }
        log.warn("[IpReconcile] {} released EIP allocationId={} publicIp={}", actor, allocationId, target.publicIp());
    }

    /**
     * Capacity snapshot (design §13.2). {@code eipUsed} is the total EIPs in the region — every
     * one (ours, primary, and ALB/NAT) counts against the EC2-VPC EIP quota — so the headroom is
     * accurate. {@code managedEips}/{@code externalEips} break that total down so the user-facing
     * count isn't conflated with the load balancer's addresses.
     */
    private CapacityView capacity(int totalEips, int oursEips, int externalEips) {
        int activeAllocations = repo.findByStatus(IpAllocationStatus.ACTIVE).size();
        // When AWS reads are unavailable (mock / flag off), fall back to the table count.
        int used = totalEips > 0 ? totalEips : activeAllocations;
        return new CapacityView(maxEips, used, Math.max(0, maxEips - used),
                activeAllocations, oursEips, externalEips);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static String changedReason(Disposition d, UserIpAllocation row, Ec2NetworkClient.EipInfo eip,
                                        boolean userDeleted) {
        return switch (d) {
            case NECESSARY -> "in use by an active, enabled user";
            case REMOVABLE -> row == null ? "on our infra but no allocation row (untracked)"
                    : userDeleted ? "user deleted — EIP not freed (release failed/pending)"
                    : (row.getStatus() != IpAllocationStatus.ACTIVE
                        ? "allocation not ACTIVE (" + row.getStatus() + ")"
                        : "user disabled");
            case EXTERNAL -> "not owned by this stack (ALB/NAT or other) — left untouched";
            case TABLE_ONLY -> "no live EIP found in AWS";
            case PROTECTED -> "instance primary IP — infrastructure, never released";
        };
    }

    private int safePrefix() {
        try {
            String cidr = metadata.subnetCidr();
            return cidr != null ? Integer.parseInt(cidr.split("/")[1].trim()) : 24;
        } catch (Exception e) { return 24; }
    }

    private static void touch(UserIpAllocation row, String actor, String reason) {
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(actor);
        row.setUpdatedReason(reason);
    }

    private interface Sup { String get(); }
    private static String safe(Sup s) { try { return s.get(); } catch (Exception e) { return null; } }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    // ── DTOs ───────────────────────────────────────────────────────────────────────────

    public enum Disposition { NECESSARY, REMOVABLE, EXTERNAL, TABLE_ONLY, PROTECTED }

    /** One EIP (or table-only allocation) in the dashboard. */
    public record EipRow(String allocationId, String publicIp, String privateIp, String associationId,
                         String eniId, String instanceId,
                         Long userId, String email, boolean userEnabled, boolean userDeleted,
                         IpAllocationStatus status, boolean manuallyManaged,
                         Disposition disposition, String note) {}

    public record CapacityView(int eipMax, int eipUsed, int eipRemaining,
                               int activeAllocations, int managedEips, int externalEips) {}

    /** Full reconciliation report for {@code /admin/aws-ip}. */
    public record ReconciliationReport(Instant generatedAt, boolean automationEnabled,
                                       String ourEni, String ourInstance,
                                       List<EipRow> mappings, int removableCount,
                                       double removableMonthlyCostUsd, int driftCount,
                                       int failedOrReleasing, CapacityView capacity) {}
}
