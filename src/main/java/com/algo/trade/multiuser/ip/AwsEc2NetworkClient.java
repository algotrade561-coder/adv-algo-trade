package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;

/**
 * Live AWS EC2 implementation (design §5.1/§5.2). Active only when
 * {@code ip-automation.enabled=true}. Credentials are resolved by the SDK's default provider
 * chain — on the instance this is the attached IAM role via IMDSv2, so no static keys exist.
 *
 * <p>The IAM role must allow: AllocateAddress, ReleaseAddress, AssociateAddress,
 * DisassociateAddress, AssignPrivateIpAddresses, UnassignPrivateIpAddresses,
 * DescribeNetworkInterfaces, DescribeAddresses (runbook §0a).</p>
 */
@Component
@ConditionalOnProperty(name = "ip-automation.enabled", havingValue = "true")
public class AwsEc2NetworkClient implements Ec2NetworkClient {

    private static final Logger log = LoggerFactory.getLogger(AwsEc2NetworkClient.class);

    private final Ec2Client ec2;

    public AwsEc2NetworkClient(InstanceMetadataProvider metadata) {
        String region = metadata.region();
        this.ec2 = (region != null && !region.isBlank())
                ? Ec2Client.builder().region(Region.of(region)).build()
                : Ec2Client.builder().build();
        log.info("[AwsEc2NetworkClient] initialised (region={})", region);
    }

    @Override
    public void assignPrivateIp(String eniId, String privateIp) {
        ec2.assignPrivateIpAddresses(AssignPrivateIpAddressesRequest.builder()
                .networkInterfaceId(eniId)
                .privateIpAddresses(privateIp)
                .build());
        log.info("[AwsEc2] assigned private IP {} to {}", privateIp, eniId);
    }

    @Override
    public void unassignPrivateIp(String eniId, String privateIp) {
        ec2.unassignPrivateIpAddresses(UnassignPrivateIpAddressesRequest.builder()
                .networkInterfaceId(eniId)
                .privateIpAddresses(privateIp)
                .build());
        log.info("[AwsEc2] unassigned private IP {} from {}", privateIp, eniId);
    }

    @Override
    public AllocatedEip allocateAddress() {
        AllocateAddressResponse r = ec2.allocateAddress(AllocateAddressRequest.builder()
                .domain(DomainType.VPC)
                .build());
        log.info("[AwsEc2] allocated EIP {} ({})", r.publicIp(), r.allocationId());
        return new AllocatedEip(r.allocationId(), r.publicIp());
    }

    @Override
    public String associateAddress(String allocationId, String eniId, String privateIp) {
        AssociateAddressResponse r = ec2.associateAddress(AssociateAddressRequest.builder()
                .allocationId(allocationId)
                .networkInterfaceId(eniId)
                .privateIpAddress(privateIp)
                .build());
        log.info("[AwsEc2] associated EIP alloc={} to {} on {} (assoc={})",
                allocationId, privateIp, eniId, r.associationId());
        return r.associationId();
    }

    @Override
    public void disassociateAddress(String associationId) {
        ec2.disassociateAddress(DisassociateAddressRequest.builder()
                .associationId(associationId)
                .build());
        log.info("[AwsEc2] disassociated EIP assoc={}", associationId);
    }

    @Override
    public void releaseAddress(String allocationId) {
        ec2.releaseAddress(ReleaseAddressRequest.builder()
                .allocationId(allocationId)
                .build());
        log.info("[AwsEc2] released EIP alloc={}", allocationId);
    }

    @Override
    public List<String> describePrivateIps(String eniId) {
        DescribeNetworkInterfacesResponse r = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .networkInterfaceIds(eniId)
                        .build());
        if (r.networkInterfaces().isEmpty()) return List.of();
        return r.networkInterfaces().get(0).privateIpAddresses().stream()
                .map(NetworkInterfacePrivateIpAddress::privateIpAddress)
                .toList();
    }

    @Override
    public List<EipInfo> describeAddresses() {
        DescribeAddressesResponse r = ec2.describeAddresses(DescribeAddressesRequest.builder().build());
        return r.addresses().stream()
                .map(a -> new EipInfo(
                        a.allocationId(),
                        a.publicIp(),
                        a.associationId(),
                        a.privateIpAddress(),
                        a.networkInterfaceId(),
                        a.instanceId(),
                        // ALB/NAT-managed EIPs are owned by an AWS service account, not ours.
                        // Final ownership is decided by the reconciler (ENI/table match); this is a hint.
                        isServiceManaged(a)))
                .toList();
    }

    /**
     * Hint that an EIP is AWS-service-managed (ALB/NAT): its network-interface owner is an
     * AWS service account ("amazon-elb", "amazon-aws", …) rather than this account's id.
     */
    private static boolean isServiceManaged(Address a) {
        String owner = a.networkInterfaceOwnerId();
        return owner != null && owner.startsWith("amazon");
    }
}
