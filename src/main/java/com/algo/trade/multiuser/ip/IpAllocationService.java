package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 1 (design §10, §11) — <b>track-only</b> orchestration of per-user source-IP
 * allocations. No AWS SDK calls: an operator performs the AWS CLI steps (manual runbook §1)
 * and then records the resulting IDs here, so the UI / notify / whitelist / link flow can be
 * exercised before the live SDK automation (Phase 2) is switched on.
 *
 * <p>All mutations are idempotent and go through {@link SourceIpLinker} so
 * {@code user_broker_config.source_ip} and the per-user clients stay in sync.</p>
 */
@Service
public class IpAllocationService {

    private static final Logger log = LoggerFactory.getLogger(IpAllocationService.class);

    private final UserIpAllocationRepository repo;
    private final SourceIpLinker linker;
    private final Ec2NetworkClient ec2;
    private final OsSecondaryIpConfigurer osConfigurer;
    private final InstanceMetadataProvider metadata;

    /** Master switch for live AWS automation (Phase 2). Off = track-only (Phase 1). */
    @Value("${ip-automation.enabled:false}")
    private boolean automationEnabled;

    /** Whether adding a user auto-fires provisioning (only acts when automation is also enabled). */
    @Value("${ip-automation.auto-provision-on-create:false}")
    private boolean autoProvisionOnCreate;

    /** Elastic IP quota for the region (default AWS limit is 5). Used for the UI headroom hint. */
    @Value("${ip-automation.max-eips:5}")
    private int maxEips;

    /** Secondary private IPs the ENI/instance type allows (0 = unknown until Phase 3 reconciler). */
    @Value("${ip-automation.eni-secondary-ip-capacity:0}")
    private int eniSecondaryCapacity;

    /** Off-request executor for auto-provision on user create (AWS calls take seconds). */
    private final java.util.concurrent.ExecutorService provisionExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ip-provision");
                t.setDaemon(true);
                return t;
            });

    public IpAllocationService(UserIpAllocationRepository repo, SourceIpLinker linker,
                               Ec2NetworkClient ec2, OsSecondaryIpConfigurer osConfigurer,
                               InstanceMetadataProvider metadata) {
        this.repo = repo;
        this.linker = linker;
        this.ec2 = ec2;
        this.osConfigurer = osConfigurer;
        this.metadata = metadata;
    }

    public boolean isAutomationEnabled() { return automationEnabled; }

    public Optional<UserIpAllocation> get(Long userId) {
        return repo.findByUserId(userId);
    }

    /**
     * Record a (manually performed) provision. Track-only: persists the IDs the operator
     * supplies and mirrors the private IP into {@code source_ip}. Never calls AWS.
     *
     * @throws IllegalArgumentException if the private IP is already held by another user.
     */
    @Transactional
    public UserIpAllocation recordProvision(Long userId, ProvisionData d, String actor) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        String privateIp = trimToNull(d.privateIp());

        // Collision guard (§2.1 / §5.3): a private IP must not be shared across users.
        if (privateIp != null) {
            repo.findByPrivateIp(privateIp).ifPresent(other -> {
                if (!userId.equals(other.getUserId())) {
                    throw new IllegalArgumentException(
                            "Private IP " + privateIp + " is already allocated to userId=" + other.getUserId());
                }
            });
        }

        UserIpAllocation row = repo.findByUserId(userId).orElseGet(() -> {
            UserIpAllocation r = new UserIpAllocation();
            r.setUserId(userId);
            r.setCreatedAt(Instant.now());
            return r;
        });

        if (privateIp != null) row.setPrivateIp(privateIp);
        if (trimToNull(d.publicIp()) != null) row.setPublicIp(d.publicIp().trim());
        if (trimToNull(d.eniId()) != null) row.setEniId(d.eniId().trim());
        if (trimToNull(d.eipAllocationId()) != null) row.setEipAllocationId(d.eipAllocationId().trim());
        if (trimToNull(d.eipAssociationId()) != null) row.setEipAssociationId(d.eipAssociationId().trim());
        if (trimToNull(d.instanceId()) != null) row.setInstanceId(d.instanceId().trim());

        // A recorded manual provision with a usable private IP is treated as ACTIVE; otherwise
        // it is still being set up.
        row.setStatus(row.getPrivateIp() != null ? IpAllocationStatus.ACTIVE : IpAllocationStatus.PENDING);
        row.setLastError(null);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(actor);
        row.setUpdatedReason("track-only provision (Phase 1)");
        repo.save(row);

        if (row.getPrivateIp() != null) {
            linker.applyAllocatedIp(userId, row.getPrivateIp(), actor);
        }
        log.info("[IpAllocation] user={} track-only provision recorded by {}: private={} public={} status={}",
                userId, actor, row.getPrivateIp(), row.getPublicIp(), row.getStatus());
        return row;
    }

    // ── Phase 2: live AWS automation (gated by ip-automation.enabled) ──────────────────

    /** Fire-and-forget provision off the request thread (used by the user-create hook, §7). */
    public void autoProvisionAsync(Long userId, String actor) {
        if (!automationEnabled || !autoProvisionOnCreate) {
            log.info("[IpAllocation] auto-provision skipped for userId={} (enabled={}, auto-provision-on-create={})",
                    userId, automationEnabled, autoProvisionOnCreate);
            return;
        }
        if (repo.findByUserId(userId).map(a -> a.getStatus() == IpAllocationStatus.ACTIVE).orElse(false)) {
            log.info("[IpAllocation] auto-provision skipped for userId={} (already ACTIVE)", userId);
            return;
        }
        provisionExecutor.submit(() -> {
            try { provision(userId, actor); }
            catch (Exception e) { log.warn("[IpAllocation] async provision failed for userId={}: {}", userId, e.getMessage(), e); }
        });
    }

    /**
     * Live provision sequence (design §5.3). Each step persists state; on any failure the
     * compensating release undoes whatever was created so retries are clean. Requires
     * {@code ip-automation.enabled=true}.
     */
    @Transactional
    public UserIpAllocation provision(Long userId, String actor) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        if (!automationEnabled) {
            throw new IllegalStateException("ip-automation.enabled=false — live provisioning is off (use track-only recordProvision)");
        }
        String eni = require(metadata.primaryEniId(), "primary ENI id (set ip-automation.eni-id or run on EC2)");
        String subnetCidr = require(metadata.subnetCidr(), "subnet CIDR (set ip-automation.subnet-cidr or run on EC2)");
        int prefix = parsePrefix(subnetCidr);

        UserIpAllocation row = repo.findByUserId(userId).orElseGet(() -> {
            UserIpAllocation r = new UserIpAllocation();
            r.setUserId(userId);
            r.setCreatedAt(Instant.now());
            return r;
        });
        row.setStatus(IpAllocationStatus.PENDING);
        row.setEniId(eni);
        row.setInstanceId(metadata.instanceId());
        row.setLastError(null);
        touch(row, actor, "live provision started");
        repo.save(row);

        // Reuse the row's private IP ONLY if it's in the CURRENT subnet. A stale IP from a prior
        // allocation in a different subnet/region (e.g. tamilselvam's old Sydney 172.31.16.0/20 address
        // after the Mumbai migration) is NOT reusable — AWS rejects assignPrivateIp with "Address does not
        // fall within the subnet's address range". In that case pick a fresh in-subnet IP.
        String existingIp = row.getPrivateIp();
        String privateIp = (existingIp != null && inSubnet(existingIp, subnetCidr))
                ? existingIp
                : pickFreePrivateIp(eni, subnetCidr);
        try {
            // 1. assign secondary private IP
            ec2.assignPrivateIp(eni, privateIp);
            row.setPrivateIp(privateIp);
            touch(row, actor, "private IP assigned"); repo.save(row);

            // 2. OS-configure so socket.bind() works
            osConfigurer.add(privateIp, prefix);
            row.setStatus(IpAllocationStatus.OS_CONFIGURED);
            touch(row, actor, "OS configured"); repo.save(row);

            // 3. allocate EIP
            Ec2NetworkClient.AllocatedEip eip = ec2.allocateAddress();
            row.setEipAllocationId(eip.allocationId());
            row.setPublicIp(eip.publicIp());
            touch(row, actor, "EIP allocated"); repo.save(row);

            // 4. associate EIP with the private IP
            String assoc = ec2.associateAddress(eip.allocationId(), eni, privateIp);
            row.setEipAssociationId(assoc);
            row.setStatus(IpAllocationStatus.ASSOCIATED);
            touch(row, actor, "EIP associated"); repo.save(row);

            // 5. mirror into source_ip + invalidate clients (full chain via linker)
            linker.applyAllocatedIp(userId, privateIp, actor);

            // 6. active
            row.setStatus(IpAllocationStatus.ACTIVE);
            touch(row, actor, "provisioned"); repo.save(row);
            log.info("[IpAllocation] user={} provisioned: private={} public={} eni={}",
                    userId, privateIp, row.getPublicIp(), eni);
            return row;
        } catch (Exception ex) {
            log.warn("[IpAllocation] provision failed for userId={} — running compensating release: {}", userId, ex.getMessage(), ex);
            compensate(row, prefix);
            row.setStatus(IpAllocationStatus.FAILED);
            row.setLastError(truncate(ex.getMessage(), 1024));
            touch(row, actor, "provision failed (compensated)"); repo.save(row);
            throw new IllegalStateException("provision failed for userId=" + userId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Release. When automation is enabled and the row was automation-created
     * ({@code eipAllocationId != null}), performs the live AWS teardown
     * (Disassociate → Release → Unassign → OS remove). Otherwise it is track-only: clears the
     * binding and marks the row {@code RELEASED} without touching AWS (manual/Phase-1 rows;
     * design §2.1 / §5.4).
     */
    @Transactional
    public UserIpAllocation release(Long userId, String actor) {
        UserIpAllocation row = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("no allocation for userId=" + userId));
        linker.clearSourceIp(userId, actor);

        boolean live = automationEnabled && !row.isManuallyManaged();
        if (live) {
            row.setStatus(IpAllocationStatus.RELEASING);
            touch(row, actor, "live release started"); repo.save(row);
            int prefix = safePrefix();
            compensate(row, prefix);
            row.setStatus(IpAllocationStatus.RELEASED);
            row.setUpdatedReason("live release (AWS resources freed)");
        } else {
            row.setStatus(IpAllocationStatus.RELEASED);
            row.setUpdatedReason(row.isManuallyManaged()
                    ? "track-only release (manual — AWS left intact)"
                    : "track-only release (automation off — reclaim AWS manually)");
        }
        touch(row, actor, row.getUpdatedReason());
        repo.save(row);
        log.info("[IpAllocation] user={} released (live={}) by {}", userId, live, actor);
        return row;
    }

    /** Best-effort teardown of whatever AWS/OS resources the row references. Never throws. */
    private void compensate(UserIpAllocation row, int prefix) {
        try { if (row.getEipAssociationId() != null) ec2.disassociateAddress(row.getEipAssociationId()); }
        catch (Exception e) { log.warn("[IpAllocation] disassociate failed (continuing): {}", e.getMessage()); }
        try { if (row.getEipAllocationId() != null) ec2.releaseAddress(row.getEipAllocationId()); }
        catch (Exception e) { log.warn("[IpAllocation] releaseAddress failed (continuing): {}", e.getMessage()); }
        try { if (row.getPrivateIp() != null && row.getEniId() != null) ec2.unassignPrivateIp(row.getEniId(), row.getPrivateIp()); }
        catch (Exception e) { log.warn("[IpAllocation] unassign failed (continuing): {}", e.getMessage()); }
        try { if (row.getPrivateIp() != null) osConfigurer.remove(row.getPrivateIp(), prefix); }
        catch (Exception e) { log.warn("[IpAllocation] OS remove failed (continuing): {}", e.getMessage()); }
    }

    @Transactional
    public UserIpAllocation setWhitelisted(Long userId, boolean whitelisted, String actor) {
        UserIpAllocation row = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("no allocation for userId=" + userId));
        row.setWhitelistedWithBroker(whitelisted);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(actor);
        row.setUpdatedReason("whitelist flag " + (whitelisted ? "set" : "cleared"));
        repo.save(row);
        log.info("[IpAllocation] user={} whitelistedWithBroker={} by {}", userId, whitelisted, actor);
        return row;
    }

    /**
     * Best-effort capacity headroom from the allocation table (live AWS counts arrive with the
     * Phase 3 reconciler). Each active user consumes one secondary private IP + one EIP.
     */
    public Capacity capacity() {
        int active = repo.findByStatus(IpAllocationStatus.ACTIVE).size();
        int eipRemaining = Math.max(0, maxEips - active);
        Integer eniRemaining = eniSecondaryCapacity > 0 ? Math.max(0, eniSecondaryCapacity - active) : null;
        return new Capacity(active, maxEips, eipRemaining, eniSecondaryCapacity, eniRemaining);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ── provisioning helpers ──────────────────────────────────────────────────────────

    /**
     * Pick a free secondary private IP in the subnet — not already used by any allocation row
     * (manual or automated) and not live on the ENI (DescribeNetworkInterfaces). Skips the
     * AWS-reserved first four and last addresses of the subnet (§5.3 / §2.1).
     */
    private String pickFreePrivateIp(String eniId, String subnetCidr) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (UserIpAllocation a : repo.findAll()) {
            if (a.getPrivateIp() != null) used.add(a.getPrivateIp());
        }
        try { used.addAll(ec2.describePrivateIps(eniId)); }
        catch (Exception e) { log.warn("[IpAllocation] describePrivateIps failed during pick (continuing with table-only): {}", e.getMessage()); }

        String[] parts = subnetCidr.split("/");
        long base = ipToLong(parts[0]);
        int prefix = Integer.parseInt(parts[1].trim());
        long size = 1L << (32 - prefix);
        long network = base & (0xFFFFFFFFL << (32 - prefix));
        // AWS reserves network+0..+3 and the broadcast (network+size-1).
        for (long off = 4; off < size - 1; off++) {
            String candidate = longToIp(network + off);
            if (!used.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("no free private IP available in subnet " + subnetCidr);
    }

    private static int parsePrefix(String cidr) {
        String[] p = cidr.split("/");
        return Integer.parseInt(p[1].trim());
    }

    /**
     * True if {@code ip} falls inside the {@code cidr} block. Guards {@link #provision} against reusing a
     * stale private IP recorded for a different subnet/region (e.g. a pre-Mumbai-migration Sydney address),
     * which AWS rejects as "Address does not fall within the subnet's address range". Any parse problem is
     * treated as "not in subnet" so the caller falls back to picking a fresh IP.
     */
    private static boolean inSubnet(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            long base = ipToLong(parts[0]);
            int prefix = Integer.parseInt(parts[1].trim());
            long mask = 0xFFFFFFFFL << (32 - prefix);
            return (ipToLong(ip) & mask) == (base & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private int safePrefix() {
        try { return parsePrefix(metadata.subnetCidr()); }
        catch (Exception e) { return 24; }
    }

    private static long ipToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        long v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | (Long.parseLong(o[i]) & 0xFF);
        return v & 0xFFFFFFFFL;
    }

    private static String longToIp(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required value: " + what);
        }
        return value;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void touch(UserIpAllocation row, String actor, String reason) {
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(actor);
        row.setUpdatedReason(reason);
    }

    /** Operator-recorded provision inputs (track-only). Only {@code privateIp} is essential. */
    public record ProvisionData(String privateIp, String publicIp, String eniId,
                                String eipAllocationId, String eipAssociationId, String instanceId) {}

    /** Capacity headroom for the UI fail-fast hint. {@code eniSecondaryRemaining} is null if unknown. */
    public record Capacity(int activeAllocations, int eipMax, int eipRemaining,
                           int eniSecondaryCapacity, Integer eniSecondaryRemaining) {}
}
