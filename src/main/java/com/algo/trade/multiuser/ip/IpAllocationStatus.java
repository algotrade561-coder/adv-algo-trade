package com.algo.trade.multiuser.ip;

/**
 * Lifecycle of a per-user source-IP allocation (see USER_SOURCE_IP_AUTOMATION_DESIGN.md).
 *
 * <p>Phase 0 only ever produces {@link #ACTIVE} (for adopted manual IPs). The remaining
 * states are used once the AWS SDK automation (Phase 2) and reconciler (Phase 3) land.</p>
 */
public enum IpAllocationStatus {
    /** Row created; provisioning not yet started. */
    PENDING,
    /** Secondary private IP added to the OS interface. */
    OS_CONFIGURED,
    /** Elastic IP associated with the private IP in AWS. */
    ASSOCIATED,
    /** Fully provisioned and bound — the user can trade (once whitelisted in Kite). */
    ACTIVE,
    /** A provisioning/release step failed; see {@code lastError}. Reconciler retries. */
    FAILED,
    /** Release in progress. */
    RELEASING,
    /** Released / decommissioned (kept for audit history). */
    RELEASED
}
