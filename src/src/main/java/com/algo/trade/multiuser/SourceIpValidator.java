package com.algo.trade.multiuser;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * Validates a per-user outbound source IP before it is stored or used to bind a
 * Zerodha REST call.
 *
 * <p>Background (incident 2026-06-08): a secondary user's {@code sourceIp} was
 * written with the PRIMARY box's addresses ({@code 172.31.31.140}) and even a
 * public Elastic IP ({@code 15.134.120.186}). Binding outbound calls to the wrong
 * address makes them egress from a non-whitelisted IP, and Zerodha rejects them
 * with {@code 403 "Incorrect api_key or access_token"} — an error that looks like
 * a bad token but is really an IP/whitelist mismatch.
 *
 * <p>A valid per-user source IP must be:
 * <ol>
 *   <li>a syntactically valid IP literal,</li>
 *   <li>actually assigned to an UP local network interface (a public Elastic IP is
 *       NAT'd and never on the NIC, so it can never be a valid bind source), and</li>
 *   <li>NOT the host's default-route address — that one is the shared/primary egress
 *       used when a user has no {@code sourceIp}, so it must not be pinned to a
 *       secondary user.</li>
 * </ol>
 * Cross-user uniqueness (no two users sharing one egress IP) is enforced by the
 * caller, which has access to the other users' configs.
 */
public final class SourceIpValidator {

    private SourceIpValidator() {}

    /**
     * Backward-compatible overload — treats the IP as belonging to a NON-primary user,
     * so the host's default-egress address is still rejected (reserved for the primary).
     *
     * @return null if {@code ip} is an acceptable bind source on this host, otherwise
     *         a human-readable reason it was rejected.
     */
    public static String reasonIfInvalid(String ip) {
        return reasonIfInvalid(ip, false);
    }

    /**
     * @param isPrimaryAccount when {@code true}, the account is allowed to pin the host's
     *        default-egress address as its own source IP. Previously the default egress was
     *        reserved for "the primary" by being left blank; to make a primary-account
     *        SWITCH seamless, every account — including whichever one is currently primary —
     *        may now carry its OWN explicit whitelisted IP in the table, and entry/exit
     *        binds to it via {@link SourceIpRoutingRequestFactory}. Cross-user uniqueness
     *        (no two users sharing one egress IP) is still enforced by the caller, so at
     *        most one account can hold the default-egress address.
     * @return null if {@code ip} is an acceptable bind source on this host, otherwise
     *         a human-readable reason it was rejected.
     */
    public static String reasonIfInvalid(String ip, boolean isPrimaryAccount) {
        if (ip == null || ip.isBlank()) {
            return null; // blank == "use default interface", handled by caller
        }
        String trimmed = ip.trim();
        InetAddress target;
        try {
            target = InetAddress.getByName(trimmed);
        } catch (Exception e) {
            return "'" + trimmed + "' is not a valid IP address";
        }
        if (!isLocalInterfaceAddress(target)) {
            return "Source IP " + trimmed + " is not assigned to any active network "
                    + "interface on this server (a public/Elastic IP cannot be a bind "
                    + "source — use the matching private IP, e.g. 172.31.x.x).";
        }
        // The default-egress address is only off-limits to SECONDARY users — pinning it to a
        // secondary would route their orders out the wrong interface. The primary account is
        // explicitly allowed to claim it as its own whitelisted source IP.
        if (!isPrimaryAccount) {
            String egress = defaultEgressIp();
            if (egress != null && egress.equals(trimmed)) {
                return "Source IP " + trimmed + " is this server's default egress address, "
                        + "reserved for the primary account. Assign a distinct secondary IP "
                        + "to this user, or leave it blank to use the default.";
            }
        }
        return null;
    }

    /** True if {@code addr} is bound to an UP local interface. */
    public static boolean isLocalInterfaceAddress(InetAddress addr) {
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(addr);
            return ni != null && ni.isUp();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The host's default-route source address — the local IP the OS would use to
     * reach the internet. Uses a connected (but un-sent) UDP socket, so no packets
     * leave the box. Returns null if it cannot be determined.
     */
    public static String defaultEgressIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("1.1.1.1"), 53);
            InetAddress local = s.getLocalAddress();
            return (local == null || local.isAnyLocalAddress()) ? null : local.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
