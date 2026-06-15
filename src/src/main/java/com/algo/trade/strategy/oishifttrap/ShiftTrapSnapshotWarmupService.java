package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.OiMarketSnapshotBuffer;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * On startup, pre-fills forward ring buffers from today's archived chain snapshots
 * so checkpoint backfill works immediately after a mid-day JVM restart.
 */
@Component
public class ShiftTrapSnapshotWarmupService {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapSnapshotWarmupService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OiShiftTrapConfig config;
    private final SnapshotFileWriter snapshotFileWriter;
    private final ShiftTrapChainSnapshotBuffer shiftTrapBuffer;
    private final OiMarketSnapshotBuffer oiMarketSnapshotBuffer;

    public ShiftTrapSnapshotWarmupService(
            OiShiftTrapConfig config,
            SnapshotFileWriter snapshotFileWriter,
            ShiftTrapChainSnapshotBuffer shiftTrapBuffer,
            OiMarketSnapshotBuffer oiMarketSnapshotBuffer) {
        this.config = config;
        this.snapshotFileWriter = snapshotFileWriter;
        this.shiftTrapBuffer = shiftTrapBuffer;
        this.oiMarketSnapshotBuffer = oiMarketSnapshotBuffer;
    }

    @PostConstruct
    void warmFromDisk() {
        if (!config.isChainSnapshotWarmupEnabled()) {
            return;
        }
        try {
            warmToday();
        } catch (Exception ex) {
            log.warn("[ShiftTrapWarmup] failed (non-fatal): {}", ex.getMessage());
        }
    }

    void warmToday() {
        LocalDate today = LocalDate.now(IST);
        List<Path> files = snapshotFileWriter.listAllSnapshots(today);
        if (files.isEmpty()) {
            log.info("[ShiftTrapWarmup] no chain snapshots for {} — live capture will fill buffer", today);
            return;
        }
        Instant cutoff = Instant.now().minus(ShiftTrapChainSnapshotBuffer.retention());
        int ingested = 0;
        for (Path file : files) {
            var opt = snapshotFileWriter.read(file);
            if (opt.isEmpty()) {
                continue;
            }
            ChainSnapshot snap = opt.get();
            if (snap.timestamp().isBefore(cutoff) || snap.timestamp().isAfter(Instant.now().plusSeconds(60))) {
                continue;
            }
            ingest(snap);
            ingested++;
        }
        log.info("[ShiftTrapWarmup] ingested {} snapshot(s) from {} file(s) for {}",
                ingested, files.size(), today);
    }

    void ingest(ChainSnapshot snap) {
        IndexType ix;
        try {
            ix = IndexType.valueOf(snap.underlying().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return;
        }
        double atmCe = 0;
        double atmPe = 0;
        Map<Integer, ShiftTrapChainSnapshotBuffer.StrikeOi> strikes = new HashMap<>();
        int radius = 5;
        for (ChainSnapshot.StrikeData s : snap.strikes()) {
            if (Math.abs(s.strike() - snap.atmStrike()) <= radius * ix.strikeInterval()) {
                strikes.put(s.strike(), new ShiftTrapChainSnapshotBuffer.StrikeOi(
                        s.ceOI(), s.peOI(), s.ceOiChange(), s.peOiChange()));
            }
            if (s.strike() == snap.atmStrike()) {
                atmCe = s.ceLTP();
                atmPe = s.peLTP();
            }
        }
        shiftTrapBuffer.ingest(ix, snap.timestamp(), snap.spot(), snap.atmStrike(), atmCe, atmPe, strikes);
        oiMarketSnapshotBuffer.ingest(ix, snap.timestamp(), snap.spot(), atmCe, atmPe);
    }
}
