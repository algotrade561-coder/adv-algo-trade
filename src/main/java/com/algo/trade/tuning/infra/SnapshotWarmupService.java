package com.algo.trade.tuning.infra;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import com.algo.trade.domain.IndexType;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * On JVM startup, pre-fills {@link MarketSnapshotBuffer} from today's archived chain
 * snapshots so {@link ForwardCheckpointService} can backfill immediately after a
 * mid-day restart — without waiting up to 60 seconds for the first live capture.
 *
 * <p>Reads {@code data/chain-snapshots/&lt;today_IST&gt;/*.json.gz} via the existing
 * {@link SnapshotFileWriter}, filters out snapshots outside the buffer's retention
 * window, converts each to a {@link MarketSnapshotBuffer.MarketSnapshot}, and ingests
 * it. Failures are logged at WARN; the trading JVM never blocks on warm-up.</p>
 *
 * <p>Replaces {@code ShiftTrapSnapshotWarmupService}. Disabled implicitly when there
 * are no chain snapshots on disk for today (e.g. fresh deploy on a market holiday).</p>
 */
@Component
public class SnapshotWarmupService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWarmupService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Generous future skew tolerance — accept snapshots up to 60s past "now" (clock drift). */
    private static final long FUTURE_SKEW_TOLERANCE_SEC = 60;

    private final SnapshotFileWriter snapshotFileWriter;
    private final MarketSnapshotBuffer buffer;

    public SnapshotWarmupService(SnapshotFileWriter snapshotFileWriter,
                                  MarketSnapshotBuffer buffer) {
        this.snapshotFileWriter = snapshotFileWriter;
        this.buffer = buffer;
    }

    /**
     * Number of calendar days back to warm. Sized to cover the worst realistic Indian
     * market non-trading stretch (Diwali / Christmas–New Year = 6 days) with ~4 days
     * of margin for anomalies like an extended EC2 outage.
     */
    private static final int WARMUP_DAYS_BACK = 10;

    @PostConstruct
    void warmFromDisk() {
        try {
            int n = warmRecent(Instant.now());
            log.info("[SnapshotWarmup] ingested {} snapshot(s) into MarketSnapshotBuffer", n);
        } catch (Exception ex) {
            log.warn("[SnapshotWarmup] warm-up failed (non-fatal): {}", ex.getMessage());
        }
    }

    /**
     * Reads the last {@link #WARMUP_DAYS_BACK} calendar days of snapshots (oldest
     * first so {@link MarketSnapshotBuffer}'s retention doesn't evict them), filters
     * by retention window + skew tolerance, and ingests into the buffer.
     *
     * <p>Loading multiple days handles the Friday-late-signal scenario: a signal that
     * fires at 15:25 IST Friday has its +30m checkpoint due at 15:55 — past EC2's
     * 15:45 shutdown. When EC2 boots Monday at 08:45, this warmup brings Friday's
     * snapshots back into memory so the Monday morning sweep of
     * {@link ForwardCheckpointService} can compute the missing checkpoint.</p>
     */
    public int warmRecent(Instant now) {
        LocalDate today = now.atZone(IST).toLocalDate();
        Instant futureLimit = now.plusSeconds(FUTURE_SKEW_TOLERANCE_SEC);

        int ingestedTotal = 0;
        // Walk oldest first so retention cutoff (anchored on the latest map entry)
        // doesn't drop the older days as newer days come in.
        for (int dayOffset = WARMUP_DAYS_BACK - 1; dayOffset >= 0; dayOffset--) {
            LocalDate date = today.minusDays(dayOffset);
            List<Path> files = snapshotFileWriter.listAllSnapshots(date);
            if (files.isEmpty()) {
                continue;
            }
            int ingestedDay = 0;
            for (Path file : files) {
                Optional<ChainSnapshot> opt = snapshotFileWriter.read(file);
                if (opt.isEmpty()) {
                    continue;
                }
                ChainSnapshot snap = opt.get();
                Instant t = snap.timestamp();
                if (t == null || t.isAfter(futureLimit)) {
                    continue;
                }
                if (ingestOne(snap)) {
                    ingestedDay++;
                }
            }
            if (ingestedDay > 0) {
                log.info("[SnapshotWarmup] {} ingested {} snapshot(s) from {} file(s)",
                        date, ingestedDay, files.size());
            }
            ingestedTotal += ingestedDay;
        }
        return ingestedTotal;
    }

    /** Backwards-compatible single-day entry point used by existing tests. */
    public int warmToday(Instant now) {
        return warmRecent(now);
    }

    /** Convert one archived {@link ChainSnapshot} to a buffer entry and ingest. */
    boolean ingestOne(ChainSnapshot snap) {
        IndexType ix;
        try {
            ix = IndexType.valueOf(snap.underlying().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
        double atmCe = 0, atmPe = 0;
        Map<Integer, MarketSnapshotBuffer.StrikeOi> strikes = new HashMap<>();
        int radius = 5 * ix.strikeInterval();

        for (ChainSnapshot.StrikeData s : snap.strikes()) {
            if (Math.abs(s.strike() - snap.atmStrike()) <= radius) {
                strikes.put(s.strike(),
                        new MarketSnapshotBuffer.StrikeOi(s.ceOI(), s.peOI(), s.ceOiChange(), s.peOiChange()));
            }
            if (s.strike() == snap.atmStrike()) {
                atmCe = s.ceLTP();
                atmPe = s.peLTP();
            }
        }
        buffer.ingest(ix, snap.timestamp(), snap.spot(), snap.atmStrike(), atmCe, atmPe, strikes);
        return true;
    }
}
