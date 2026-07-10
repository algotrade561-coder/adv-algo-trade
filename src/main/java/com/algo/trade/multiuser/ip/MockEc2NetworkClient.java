package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No-AWS implementation used when {@code ip-automation.enabled} is false (default) or absent.
 * Generates deterministic fake ids so the provision/release flow can be exercised end-to-end
 * (UI, linking, DB lifecycle) without touching AWS. Never makes a network call.
 */
@Component
@ConditionalOnProperty(name = "ip-automation.enabled", havingValue = "false", matchIfMissing = true)
public class MockEc2NetworkClient implements Ec2NetworkClient {

    private static final Logger log = LoggerFactory.getLogger(MockEc2NetworkClient.class);
    private final AtomicInteger seq = new AtomicInteger(1);
    private final List<String> assigned = new ArrayList<>();

    @Override
    public synchronized void assignPrivateIp(String eniId, String privateIp) {
        assigned.add(privateIp);
        log.info("[MockEc2] (no-op) assign private IP {} to {}", privateIp, eniId);
    }

    @Override
    public synchronized void unassignPrivateIp(String eniId, String privateIp) {
        assigned.remove(privateIp);
        log.info("[MockEc2] (no-op) unassign private IP {} from {}", privateIp, eniId);
    }

    @Override
    public AllocatedEip allocateAddress() {
        int n = seq.getAndIncrement();
        AllocatedEip e = new AllocatedEip("eipalloc-mock" + n, "203.0.113." + (n % 254 + 1));
        log.info("[MockEc2] (no-op) allocate EIP {}", e);
        return e;
    }

    @Override
    public String associateAddress(String allocationId, String eniId, String privateIp) {
        return "eipassoc-mock" + seq.getAndIncrement();
    }

    @Override public void disassociateAddress(String associationId) {
        log.info("[MockEc2] (no-op) disassociate {}", associationId);
    }

    @Override public void releaseAddress(String allocationId) {
        log.info("[MockEc2] (no-op) release {}", allocationId);
    }

    @Override public synchronized List<String> describePrivateIps(String eniId) {
        return new ArrayList<>(assigned);
    }

    @Override public List<EipInfo> describeAddresses() {
        // No AWS in mock mode — the reconciler falls back to a table-only view.
        return List.of();
    }
}
