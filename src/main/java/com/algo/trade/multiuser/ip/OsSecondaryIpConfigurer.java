package com.algo.trade.multiuser.ip;

/**
 * Adds/removes a secondary private IP on the OS network interface (design §6). AWS assigning
 * the IP to the ENI is not enough — Linux must also know about it or {@code socket.bind()}
 * fails with "Cannot assign requested address".
 */
public interface OsSecondaryIpConfigurer {

    /** {@code ip addr add <privateIp>/<prefix> dev <iface>} — idempotent. */
    void add(String privateIp, int prefixLength);

    /** {@code ip addr del <privateIp>/<prefix> dev <iface>} — idempotent. */
    void remove(String privateIp, int prefixLength);
}
