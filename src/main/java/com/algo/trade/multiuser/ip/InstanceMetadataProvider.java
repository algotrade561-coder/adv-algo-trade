package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resolves this EC2 instance's network identity (instance id, primary ENI, subnet CIDR,
 * region) from IMDSv2, with config overrides so it also works off-box for tests.
 *
 * <p>All values can be pinned via {@code ip-automation.*} properties; anything left blank is
 * fetched lazily from {@code http://169.254.169.254} using a token (IMDSv2). Fetch failures
 * are non-fatal — they just leave the value null, and {@link IpAllocationService} surfaces a
 * clear error if it needs one that is missing.</p>
 */
@Component
public class InstanceMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetadataProvider.class);
    private static final String IMDS = "http://169.254.169.254";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Value("${ip-automation.instance-id:}")     private String cfgInstanceId;
    @Value("${ip-automation.eni-id:}")          private String cfgEniId;
    @Value("${ip-automation.subnet-cidr:}")     private String cfgSubnetCidr;
    @Value("${ip-automation.region:}")          private String cfgRegion;
    @Value("${ip-automation.primary-private-ip:}") private String cfgPrimaryPrivateIp;
    @Value("${ip-automation.primary-public-ip:}")  private String cfgPrimaryPublicIp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private volatile String instanceId;
    private volatile String eniId;
    private volatile String subnetCidr;
    private volatile String region;
    private volatile String primaryPrivateIp;
    private volatile String primaryPublicIp;
    private volatile boolean fetched = false;

    public String instanceId() { ensure(); return firstNonBlank(cfgInstanceId, instanceId); }
    public String primaryEniId() { ensure(); return firstNonBlank(cfgEniId, eniId); }
    public String subnetCidr() { ensure(); return firstNonBlank(cfgSubnetCidr, subnetCidr); }
    public String region() { ensure(); return firstNonBlank(cfgRegion, region); }

    /** The instance's primary private IPv4 (IMDS {@code local-ipv4}). The EIP on this IP is the
     *  box's own address / the primary account's egress — never a releasable per-user allocation. */
    public String primaryPrivateIp() { ensure(); return firstNonBlank(cfgPrimaryPrivateIp, primaryPrivateIp); }

    /** The instance's primary public IPv4 (IMDS {@code public-ipv4}) — the Elastic IP on the
     *  primary private IP, i.e. the box's own / primary account's egress address. May be null
     *  off-box; pin via {@code ip-automation.primary-public-ip}. */
    public String primaryPublicIp() { ensure(); return firstNonBlank(cfgPrimaryPublicIp, primaryPublicIp); }

    private synchronized void ensure() {
        if (fetched) return;
        fetched = true;
        // Skip the IMDS round-trip entirely if everything is pinned in config.
        if (notBlank(cfgInstanceId) && notBlank(cfgEniId) && notBlank(cfgSubnetCidr)
                && notBlank(cfgRegion) && notBlank(cfgPrimaryPrivateIp)) {
            return;
        }
        try {
            String token = put(IMDS + "/latest/api/token");
            instanceId = get(IMDS + "/latest/meta-data/instance-id", token);
            region = get(IMDS + "/latest/meta-data/placement/region", token);
            primaryPrivateIp = get(IMDS + "/latest/meta-data/local-ipv4", token);
            primaryPublicIp = get(IMDS + "/latest/meta-data/public-ipv4", token);
            String mac = get(IMDS + "/latest/meta-data/mac", token);
            if (notBlank(mac)) {
                String base = IMDS + "/latest/meta-data/network/interfaces/macs/" + mac.trim() + "/";
                eniId = get(base + "interface-id", token);
                subnetCidr = get(base + "subnet-ipv4-cidr-block", token);
            }
            log.info("[InstanceMetadata] resolved instanceId={} eni={} subnet={} region={} primaryPrivateIp={}",
                    instanceId, eniId, subnetCidr, region, primaryPrivateIp);
        } catch (Exception e) {
            log.warn("[InstanceMetadata] IMDS lookup failed (non-fatal — use ip-automation.* overrides off-box): {}",
                    e.getMessage());
        }
    }

    private String put(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body().trim() : null;
    }

    private String get(String url, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
        if (notBlank(token)) b.header("X-aws-ec2-metadata-token", token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body().trim() : null;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String firstNonBlank(String a, String b) { return notBlank(a) ? a : b; }
}
