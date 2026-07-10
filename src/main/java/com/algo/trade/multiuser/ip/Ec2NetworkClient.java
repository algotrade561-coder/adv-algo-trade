package com.algo.trade.multiuser.ip;

import java.util.List;

/**
 * Thin, mockable seam over the AWS EC2 SDK calls the source-IP automation needs (design §5.2).
 * The live implementation is {@code AwsEc2NetworkClient}; tests / flag-off runs get
 * {@code MockEc2NetworkClient}. All methods are expected to be idempotent-friendly: callers
 * persist state between steps and may retry.
 */
public interface Ec2NetworkClient {

    /** AssignPrivateIpAddresses — attach a specific secondary private IP to the ENI. */
    void assignPrivateIp(String eniId, String privateIp);

    /** UnassignPrivateIpAddresses — detach a secondary private IP from the ENI. */
    void unassignPrivateIp(String eniId, String privateIp);

    /** AllocateAddress(domain=vpc) — allocate a new Elastic IP. */
    AllocatedEip allocateAddress();

    /** AssociateAddress — bind an EIP allocation to a private IP on the ENI; returns associationId. */
    String associateAddress(String allocationId, String eniId, String privateIp);

    /** DisassociateAddress. */
    void disassociateAddress(String associationId);

    /** ReleaseAddress — free the EIP allocation. */
    void releaseAddress(String allocationId);

    /** DescribeNetworkInterfaces — live list of private IPs currently on the ENI (collision check). */
    List<String> describePrivateIps(String eniId);

    /**
     * DescribeAddresses — every Elastic IP in the region with its mapping (design §13.1).
     * Used by the reconciler to spot orphaned/cost-leaking EIPs that never appear in the
     * user-centric view. Read-only; safe to call even when provisioning is off.
     */
    List<EipInfo> describeAddresses();

    /** Result of {@link #allocateAddress()}. */
    record AllocatedEip(String allocationId, String publicIp) {}

    /**
     * One Elastic IP as reported by DescribeAddresses. {@code associationId}/{@code privateIp}/
     * {@code networkInterfaceId}/{@code instanceId} are null when the EIP is unassociated.
     * {@code serviceManaged} is true for AWS-managed EIPs (e.g. ALB/NAT) we must never touch.
     */
    record EipInfo(String allocationId, String publicIp, String associationId,
                   String privateIp, String networkInterfaceId, String instanceId,
                   boolean serviceManaged) {}
}
