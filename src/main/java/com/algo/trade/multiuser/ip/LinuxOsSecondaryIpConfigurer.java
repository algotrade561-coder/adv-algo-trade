package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Live Linux implementation (design §6). Shells out to {@code ip addr add/del}. Active only
 * when {@code ip-automation.enabled=true}.
 *
 * <p>The interface name comes from {@code ip-automation.network-interface} (do NOT hardcode
 * {@code eth0} — modern AMIs use {@code ens5}/{@code enp0s…}). The process must be allowed to
 * run {@code ip addr add|del} via a narrow sudoers entry (runbook); {@code sudo} usage is
 * toggled by {@code ip-automation.os.use-sudo} (default true).</p>
 */
@Component
@ConditionalOnProperty(name = "ip-automation.enabled", havingValue = "true")
public class LinuxOsSecondaryIpConfigurer implements OsSecondaryIpConfigurer {

    private static final Logger log = LoggerFactory.getLogger(LinuxOsSecondaryIpConfigurer.class);

    @Value("${ip-automation.network-interface:eth0}")
    private String iface;

    @Value("${ip-automation.os.use-sudo:true}")
    private boolean useSudo;

    @Override
    public void add(String privateIp, int prefixLength) {
        // "exists" is treated as success (idempotent re-apply, e.g. boot reconciler).
        run("add", privateIp, prefixLength, true);
    }

    @Override
    public void remove(String privateIp, int prefixLength) {
        run("del", privateIp, prefixLength, true);
    }

    private void run(String op, String privateIp, int prefixLength, boolean tolerateExisting) {
        String cidr = privateIp + "/" + prefixLength;
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (useSudo) cmd.add("sudo");
        cmd.add("ip");
        cmd.add("addr");
        cmd.add(op);
        cmd.add(cidr);
        cmd.add("dev");
        cmd.add(iface);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            int code = done ? p.exitValue() : -1;
            if (code == 0) {
                log.info("[OsSecondaryIp] {} {} dev {} ok", op, cidr, iface);
                return;
            }
            String lower = output.toLowerCase();
            if (tolerateExisting && (lower.contains("file exists") || lower.contains("cannot assign")
                    || lower.contains("does not exist") || lower.contains("no such"))) {
                log.info("[OsSecondaryIp] {} {} dev {} idempotent no-op ({})", op, cidr, iface, output.trim());
                return;
            }
            throw new IllegalStateException("ip addr " + op + " failed (exit=" + code + "): " + output.trim());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running ip addr " + op + " " + cidr, ie);
        } catch (Exception e) {
            throw new IllegalStateException("failed running ip addr " + op + " " + cidr + ": " + e.getMessage(), e);
        }
    }
}
