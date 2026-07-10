package com.algo.trade.research;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SHADOW detector for the strongest research-watchlist edge: <b>NIFTY leads SENSEX</b> (cross-index spot
 * momentum in a trend regime; day-level t≈2.5, ~73% positive days in the daily edge study). This is the
 * research→shadow step — it runs LIVE and logs what it WOULD trade, but <b>places no orders</b>.
 *
 * <p>Order-free + fully isolated: runs only on the Spring scheduler thread (never the WS tick thread — that
 * starvation was the 2026-07-01 candle-close bug), reads only cached values (no broker / historical / DB
 * calls), and flushes its CSV off the eval thread. It cannot affect live trading.</p>
 *
 * <p>Each fire: when NIFTY's ~15-min spot momentum is non-zero AND NIFTY is in an intraday trend (trailing
 * ~30-min range &gt; threshold), record a VIRTUAL SENSEX ATM option entry in NIFTY's direction at the real
 * live premium, then close it after the horizon at the real live premium. Writes one completed virtual
 * trade per row to {@code data/tuning/crossindex-shadow-<date>.csv} for the offline study to score. Gives
 * an honest, out-of-sample, tradeable-fidelity record before anything is promoted to real orders.</p>
 */
@Component
public class CrossIndexShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(CrossIndexShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MKT_OPEN = LocalTime.of(9, 20);
    private static final LocalTime MKT_LAST_ENTRY = LocalTime.of(15, 0);   // no new virtual entries after 15:00
    private static final LocalTime MKT_CLOSE = LocalTime.of(15, 30);
    private static final String HEADER =
            "entryEpochMs,exitEpochMs,direction,side,sensexAtm,entryPrem,exitPrem,holdMin,"
            + "grossPct,niftySpotEntry,sensexSpotEntry,niftyMomPast,regime\n";

    @Value("${crossindex-shadow.enabled:true}")
    private boolean enabled = true;
    @Value("${crossindex-shadow.momentum-lookback-min:15}")
    private int lookbackMin = 15;
    @Value("${crossindex-shadow.regime-range-pct:0.20}")
    private double regimeRangePct = 0.20;
    @Value("${crossindex-shadow.horizon-min:30}")
    private int horizonMin = 30;
    @Value("${crossindex-shadow.cooldown-min:15}")
    private int cooldownMin = 15;

    private final LiveInstrumentCache cache;
    private final ExpiryCalendar expiryCalendar;

    // Owned exclusively by evaluate() (fixedDelay serialises it — never concurrent with itself).
    private final Deque<double[]> niftySpot = new ArrayDeque<>();   // [epochSec, spot]
    private final List<Pending> pending = new ArrayList<>();
    private volatile Instant lastFireAt;
    // Cross-thread-safe hand-off to the flusher.
    private final ConcurrentLinkedQueue<String> csvBuf = new ConcurrentLinkedQueue<>();

    private record Pending(Instant entryAt, int direction, String side, int sensexAtm, LocalDate expiry,
                           double entryPrem, double niftySpot, double sensexSpot, double niftyPast, String regime) {}

    public CrossIndexShadowRecorder(LiveInstrumentCache cache, ExpiryCalendar expiryCalendar) {
        this.cache = cache;
        this.expiryCalendar = expiryCalendar;
    }

    @Scheduled(fixedDelayString = "${crossindex-shadow.eval-ms:60000}", initialDelay = 45_000)
    public void evaluate() {
        if (!enabled) return;
        try {
            Instant now = Instant.now();
            LocalTime ist = now.atZone(IST).toLocalTime();

            double nSpot = cache.getFuturesPrice(IndexType.NIFTY);
            if (nSpot > 0 && !ist.isBefore(MKT_OPEN) && ist.isBefore(MKT_CLOSE)) {
                niftySpot.addLast(new double[]{now.getEpochSecond(), nSpot});
            }
            long keepSec = Math.max(lookbackMin, 30) * 60L;
            while (!niftySpot.isEmpty() && now.getEpochSecond() - niftySpot.peekFirst()[0] > keepSec) {
                niftySpot.pollFirst();
            }

            closeMatured(now);   // always run so virtual trades close even after the entry window

            if (nSpot <= 0 || ist.isBefore(MKT_OPEN) || !ist.isBefore(MKT_LAST_ENTRY)) return;
            Double past = spotAgo(now, lookbackMin);
            if (past == null) return;
            int dir = Double.compare(nSpot, past);   // +1 bull / 0 / -1 bear
            String regime = intradayRegime();
            if (dir == 0 || !"trend".equals(regime)) return;
            if (lastFireAt != null && Duration.between(lastFireAt, now).toMinutes() < cooldownMin) return;

            double sSpot = cache.getFuturesPrice(IndexType.SENSEX);
            if (sSpot <= 0) return;
            int atm = IndexType.SENSEX.roundToATM(sSpot);
            String side = dir > 0 ? "CE" : "PE";
            LocalDate expiry = expiryCalendar.getCurrentExpiry(IndexType.SENSEX);
            double prem = cache.getOption(IndexType.SENSEX, atm, side, expiry)
                    .map(OptionInstrument::getLastPrice).orElse(0.0);
            if (prem <= 0) return;

            pending.add(new Pending(now, dir, side, atm, expiry, prem, nSpot, sSpot, past, regime));
            lastFireAt = now;
            savePending();   // #3: survive restarts so the virtual trade can mature to a CSV row
            log.info("[XIdxShadow] VIRTUAL NIFTY->SENSEX {} {} atm={} prem={} (nifty {} vs {}m-ago {}) — NO ORDER",
                    side, dir > 0 ? "BULL" : "BEAR", atm, String.format("%.2f", prem),
                    String.format("%.0f", nSpot), lookbackMin, String.format("%.0f", past));
        } catch (Exception e) {
            log.debug("[XIdxShadow] eval skipped: {}", e.toString());
        }
    }

    private void closeMatured(Instant now) {
        Iterator<Pending> it = pending.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Pending p = it.next();
            long held = Duration.between(p.entryAt(), now).toMinutes();
            if (held < horizonMin) continue;
            double exit = cache.getOption(IndexType.SENSEX, p.sensexAtm(), p.side(), p.expiry())
                    .map(OptionInstrument::getLastPrice).orElse(0.0);
            it.remove();
            changed = true;
            if (exit <= 0 || p.entryPrem() <= 0) continue;
            double grossPct = (exit - p.entryPrem()) / p.entryPrem() * 100.0;
            csvBuf.add(p.entryAt().toEpochMilli() + "," + now.toEpochMilli() + "," + p.direction() + ","
                    + p.side() + "," + p.sensexAtm() + "," + fmt(p.entryPrem()) + "," + fmt(exit) + ","
                    + held + "," + fmt(grossPct) + "," + fmt(p.niftySpot()) + "," + fmt(p.sensexSpot()) + ","
                    + fmt(p.niftyPast()) + "," + p.regime() + "\n");
            log.info("[XIdxShadow] VIRTUAL closed {} atm={} entry={} exit={} gross={}% held={}m",
                    p.side(), p.sensexAtm(), fmt(p.entryPrem()), fmt(exit), fmt(grossPct), held);
        }
        if (changed) savePending();
    }

    // ── Restart persistence (#3): the in-memory `pending` list was wiped on every restart, so on a box that
    // restarts intra-session no virtual trade ever survived its 30-min horizon → the CSV never got a row.
    // Persist pending virtual trades to a sidecar and reload on startup. (2026-07-02)
    private static final Path PENDING_FILE = Path.of("data/tuning", "crossindex-shadow-pending.csv");

    private void savePending() {
        try {
            Files.createDirectories(PENDING_FILE.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(PENDING_FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Pending p : pending) {
                    w.write(p.entryAt().toEpochMilli() + "," + p.direction() + "," + p.side() + ","
                            + p.sensexAtm() + "," + p.expiry() + "," + p.entryPrem() + "," + p.niftySpot() + ","
                            + p.sensexSpot() + "," + p.niftyPast() + "," + p.regime() + "\n");
                }
            }
        } catch (IOException e) {
            log.debug("[XIdxShadow] savePending failed: {}", e.toString());
        }
    }

    @jakarta.annotation.PostConstruct
    void loadPending() {
        if (!Files.exists(PENDING_FILE)) return;
        try {
            for (String line : Files.readAllLines(PENDING_FILE, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] c = line.split(",");
                if (c.length < 10) continue;
                try {
                    pending.add(new Pending(
                            Instant.ofEpochMilli(Long.parseLong(c[0].trim())),
                            Integer.parseInt(c[1].trim()), c[2].trim(), Integer.parseInt(c[3].trim()),
                            LocalDate.parse(c[4].trim()), Double.parseDouble(c[5].trim()),
                            Double.parseDouble(c[6].trim()), Double.parseDouble(c[7].trim()),
                            Double.parseDouble(c[8].trim()), c[9].trim()));
                } catch (Exception parse) { /* skip a corrupt row */ }
            }
            if (!pending.isEmpty()) {
                log.info("[XIdxShadow] reloaded {} pending virtual trade(s) from sidecar on startup", pending.size());
            }
        } catch (IOException e) {
            log.debug("[XIdxShadow] loadPending failed: {}", e.toString());
        }
    }

    /** NIFTY spot ~{minutes} ago (nearest sample within 2 min of the target), or null. */
    private Double spotAgo(Instant now, int minutes) {
        long target = now.getEpochSecond() - minutes * 60L;
        Double best = null;
        long bestDiff = Long.MAX_VALUE;
        for (double[] s : niftySpot) {
            long d = Math.abs((long) s[0] - target);
            if (d < bestDiff) { bestDiff = d; best = s[1]; }
        }
        return bestDiff <= 120 ? best : null;
    }

    /** Intraday regime from the trailing ~30-min NIFTY range (matches the daily edge study's regime_at). */
    private String intradayRegime() {
        if (niftySpot.size() < 3) return "unknown";
        double lo = Double.MAX_VALUE, hi = 0;
        for (double[] s : niftySpot) { lo = Math.min(lo, s[1]); hi = Math.max(hi, s[1]); }
        double rng = lo > 0 ? (hi - lo) / lo * 100.0 : 0;
        return rng > regimeRangePct ? "trend" : "range";
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void flush() {
        if (csvBuf.isEmpty()) return;
        try {
            Path file = Path.of("data/tuning", "crossindex-shadow-" + LocalDate.now(IST) + ".csv");
            Files.createDirectories(file.getParent());
            boolean fresh = !Files.exists(file);
            List<String> lines = new ArrayList<>();
            String r;
            while ((r = csvBuf.poll()) != null) lines.add(r);
            if (lines.isEmpty()) return;
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(HEADER);
                for (String line : lines) w.write(line);
            }
        } catch (IOException e) {
            log.warn("[XIdxShadow] flush failed (rows dropped): {}", e.getMessage());
        }
    }

    @PreDestroy
    void onShutdown() {
        flush();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
