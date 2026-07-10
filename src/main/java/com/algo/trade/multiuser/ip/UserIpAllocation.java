package com.algo.trade.multiuser.ip;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Per-user source-IP allocation lifecycle + audit record (SEBI static-IP rule, Apr 2026).
 * See {@code docs/USER_SOURCE_IP_AUTOMATION_DESIGN.md} §4.
 *
 * <p>{@code user_broker_config.source_ip} remains the runtime binding value read by the
 * egress factories; this row is the provisioning/lifecycle source of truth.</p>
 *
 * <p><b>Ownership:</b> {@code eipAllocationId == null} means a <b>manually-managed</b> IP
 * adopted at backfill — the automation must never auto-release it (design §2.1).</p>
 */
@Entity
@Table(name = "user_ip_allocation",
       uniqueConstraints = @UniqueConstraint(name = "uq_user_ip_allocation_user", columnNames = "user_id"))
public class UserIpAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** One active allocation per user. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Secondary private IP on the ENI; mirrored into user_broker_config.source_ip. */
    @Column(name = "private_ip", length = 45)
    private String privateIp;

    /** Elastic (public) IP the user whitelists with the broker. Null until known. */
    @Column(name = "public_ip", length = 45)
    private String publicIp;

    /**
     * Last-known email of the owning user, snapshotted so the reconciler can still attribute an
     * EIP after the {@code app_users} row is deleted (offboarding keeps this row for audit).
     */
    @Column(name = "user_email", length = 200)
    private String userEmail;

    /** Network interface the private IP is attached to. */
    @Column(name = "eni_id", length = 64)
    private String eniId;

    /** AWS EIP allocation id. NULL = manually-managed (never auto-released). */
    @Column(name = "eip_allocation_id", length = 64)
    private String eipAllocationId;

    /** AWS EIP association id. */
    @Column(name = "eip_association_id", length = 64)
    private String eipAssociationId;

    /** EC2 instance currently hosting the IP. */
    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'ACTIVE'")
    private IpAllocationStatus status = IpAllocationStatus.ACTIVE;

    /** Operator-confirmed the public IP is registered in the Kite developer console. */
    @Column(name = "whitelisted_with_broker", nullable = false)
    @ColumnDefault("false")
    private boolean whitelistedWithBroker = false;

    /** When the public IP was sent to the user via Telegram (idempotency for §5.6). */
    @Column(name = "ip_notified_at")
    private Instant ipNotifiedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    @Column(name = "updated_reason", length = 512)
    private String updatedReason;

    public UserIpAllocation() {}

    /** True for adopted manual IPs that the automation must never release. */
    @Transient
    public boolean isManuallyManaged() {
        return eipAllocationId == null || eipAllocationId.isBlank();
    }

    // ── getters / setters ──
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPrivateIp() { return privateIp; }
    public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }
    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public String getEniId() { return eniId; }
    public void setEniId(String eniId) { this.eniId = eniId; }
    public String getEipAllocationId() { return eipAllocationId; }
    public void setEipAllocationId(String eipAllocationId) { this.eipAllocationId = eipAllocationId; }
    public String getEipAssociationId() { return eipAssociationId; }
    public void setEipAssociationId(String eipAssociationId) { this.eipAssociationId = eipAssociationId; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public IpAllocationStatus getStatus() { return status; }
    public void setStatus(IpAllocationStatus status) { this.status = status; }
    public boolean isWhitelistedWithBroker() { return whitelistedWithBroker; }
    public void setWhitelistedWithBroker(boolean v) { this.whitelistedWithBroker = v; }
    public Instant getIpNotifiedAt() { return ipNotifiedAt; }
    public void setIpNotifiedAt(Instant ipNotifiedAt) { this.ipNotifiedAt = ipNotifiedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public String getUpdatedReason() { return updatedReason; }
    public void setUpdatedReason(String updatedReason) { this.updatedReason = updatedReason; }
}
