package com.algo.trade.marketdata;

import com.algo.trade.domain.OptionInstrument;
import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase-1 high-frequency ATM microstructure capture (research; see FIXES-REGISTER "SCOPE").
 *
 * <p>The Kite websocket already streams per-tick LTP, cumulative volume, OI, exchange_timestamp,
 * and best bid/ask (full mode) for every subscribed option. This recorder taps that hot path
 * (via {@code KiteWebSocketClient.parsePacket}) and persists a throttled, per-strike row stream to
 * {@code data/tuning/atm-microstructure-<date>.csv} so the lead-lag harness can be re-run below the
 * 5-minute snapshot floor.</p>
 *
 * <p><b>Design constraints (live websocket thread):</b> {@link #onOptionTick} does only O(1) work —
 * a token lookup, a per-second throttle check, a cheap dedup check, and a non-blocking enqueue. It
 * never throws and never blocks. All file I/O happens on the {@link #flush()} scheduler thread.
 * Volume is Kite's cumulative daily figure; intervals are derived downstream.</p>
 *
 * <p><b>Canonical time key.</b> {@code exchangeTsEpochSec} is the exchange_timestamp the parser
 * previously discarded. It — NOT {@code recvEpochMs} (which carries client clock skew) — is the
 * canonical time key for all forward-return / lead-lag analysis, and it lets the analysis measure
 * the true OI refresh cadence (GATE-0).</p>
 *
 * <p><b>Duplicate guard (DATA-1).</b> Consecutive rows whose market-state fingerprint
 * (exchangeTs + ltp + bid/ask + qtys + cumVolume + oi, i.e. everything except recvEpochMs) is
 * byte-identical to the previously emitted row for that token are dropped. This removes pure
 * duplicate exchange states — the same corruption pattern that inflated exit aggregates in
 * FIXES-REGISTER DATA-1 — WITHOUT downsampling: every distinct state change (and its exchangeTs)
 * is still captured, so no information is lost.</p>
 */
@Component
public class AtmMicrostructureRecorder {

    private static final Logger log = LoggerFactory.getLogger(AtmMicrostructureRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String HEADER =
            "recvEpochMs,exchangeTsEpochSec,index,strike,optionType,tradingSymbol,ltp,"
            + "bestBid,bestAsk,bidQty,askQty,cumVolume,oi,"
            + "bookImbalance,bestBidOrders,bestAskOrders,bidSpoofScore,askSpoofScore,"
            + "signedVolume,aggressor,isAbsorption,spoofActive,"
            // Raw 5-level ladder (bid then ask; price/qty/orders per level) + aggregate pending qty.
            + "bpx1,bq1,bo1,bpx2,bq2,bo2,bpx3,bq3,bo3,bpx4,bq4,bo4,bpx5,bq5,bo5,"
            + "apx1,aq1,ao1,apx2,aq2,ao2,apx3,aq3,ao3,apx4,aq4,ao4,apx5,aq5,ao5,"
            + "totalBuyQty,totalSellQty\n";
    /** Safety cap so a flush stall can never grow the buffer without bound. */
    private static final int MAX_BUFFER = 500_000;

    private final LiveInstrumentCache cache;

    @Value("${atm-microstructure.enabled:true}")
    private boolean enabled;

    /** Comma-separated index names to capture (NIFTY + BANKNIFTY + SENSEX by default; 2026-06-20). */
    @Value("${atm-microstructure.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private String underlyingsCsv;

    private volatile Set<String> underlyings = Set.of("NIFTY", "BANKNIFTY", "SENSEX");
    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, Long> lastSecByToken = new ConcurrentHashMap<>();
    /** Last emitted market-state fingerprint per token (excludes recvEpochMs) — DATA-1 dedup guard. */
    private final ConcurrentHashMap<Long, String> lastStateByToken = new ConcurrentHashMap<>();
    /** Latest full 5-level depth per token — set by {@link #onDepth} (which the parser calls BEFORE
     *  onOptionTick in the same packet), consumed by onOptionTick to write both the derived features AND
     *  the RAW ladder (5 px/qty/orders per side + totals). Raw is captured deliberately: derived features
     *  can always be recomputed from raw, never the reverse — so a future spoof re-tune/redesign is possible
     *  without recapturing. The wider CSV compacts to tiny Parquet daily. */
    private final ConcurrentHashMap<Long, DepthSnapshot> depthByToken = new ConcurrentHashMap<>();
    private final AtomicInteger dropped = new AtomicInteger(0);
    private final AtomicInteger deduped = new AtomicInteger(0);

    /** Optional — supplies Lee-Ready flow features (signedVolume/aggressor/absorption) for the CSV. The
     *  COE owns the single stateful OrderFlowReconstructor.onTick() call per tick (calling it twice per
     *  packet would corrupt the volume deltas), so the recorder READS its published snapshot instead. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConvictionOverrideEngine convictionOverrideEngine;

    public AtmMicrostructureRecorder(LiveInstrumentCache cache) {
        this.cache = cache;
    }

    @PostConstruct
    void init() {
        try {
            this.underlyings = Set.of(underlyingsCsv.toUpperCase().replace(" ", "").split(","));
        } catch (Exception ignore) { /* keep default */ }
        log.info("[AtmMicro] enabled={} underlyings={}", enabled, underlyings);
    }

    /**
     * Called from the websocket parse path for every option tick. Must stay allocation-light and
     * exception-safe — it runs on the WS thread.
     *
     * @param exchangeTsEpochSec Kite exchange_timestamp (epoch seconds); 0 if unavailable.
     */
    public void onOptionTick(long token, double ltp, long cumVolume, long oi,
                             double bestBid, double bestAsk, long bidQty, long askQty,
                             int exchangeTsEpochSec, Instant recv) {
        if (!enabled) return;
        try {
            OptionInstrument inst = cache.getByToken(token).orElse(null);
            if (inst == null) return;
            String idx = inst.getIndexType().name();
            if (!underlyings.contains(idx)) return;

            // Throttle to at most one row per token per wall-clock second.
            long sec = recv.getEpochSecond();
            Long prev = lastSecByToken.get(token);
            if (prev != null && prev == sec) return;
            lastSecByToken.put(token, sec);

            // Full 5-level depth cached by onDepth (same packet) + flow features from the COE's published
            // snapshot. Both optional — zeros when unavailable (e.g. quote-mode packet, COE disabled).
            DepthSnapshot d = depthByToken.get(token);
            ConvictionOverrideEngine.OverrideSnapshot os = convictionOverrideEngine != null
                    ? convictionOverrideEngine.get(inst.getIndexType(), inst.getStrikePrice(), inst.getOptionType())
                    : null;

            // DATA-1 dedup: drop a row whose exchange state is unchanged vs the last emitted
            // one for this token. Fingerprint deliberately excludes recvEpochMs so only genuine
            // state changes are kept — no information lost, just redundant duplicates removed.
            // Includes the full ladder so a deeper-level change (e.g. a level pulled) still records.
            String state = exchangeTsEpochSec + "|" + ltp + "|" + bestBid + "|" + bestAsk + "|"
                    + bidQty + "|" + askQty + "|" + cumVolume + "|" + oi
                    + "|" + (d != null ? Arrays.toString(d.bidQtys()) + Arrays.toString(d.askQtys())
                                       + Arrays.toString(d.bidOrders()) + Arrays.toString(d.askOrders()) : "-");
            String last = lastStateByToken.get(token);
            if (state.equals(last)) { deduped.incrementAndGet(); return; }
            lastStateByToken.put(token, state);

            if (buffer.size() >= MAX_BUFFER) { dropped.incrementAndGet(); return; }
            buffer.add(recv.toEpochMilli() + "," + exchangeTsEpochSec + "," + idx + ","
                    + inst.getStrikePrice() + "," + inst.getOptionType() + "," + inst.getTradingSymbol() + ","
                    + ltp + "," + bestBid + "," + bestAsk + "," + bidQty + "," + askQty + ","
                    + cumVolume + "," + oi + ","
                    + (d != null
                        ? String.format(java.util.Locale.US, "%.4f,%d,%d,%.3f,%.3f,",
                            d.bookImbalance(), d.bidOrders()[0], d.askOrders()[0],
                            d.bidSpoofScores()[0], d.askSpoofScores()[0])
                        : "0,0,0,0,0,")
                    + (os != null
                        ? os.signedVolume() + "," + os.aggressor() + "," + os.absorption() + "," + os.spoofActive() + ","
                        : "0,0,false,false,")
                    + (d != null ? ladderCsv(d) : "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")
                    + "\n");
        } catch (Exception e) {
            // Never propagate into the WS thread.
            log.debug("[AtmMicro] onOptionTick skipped: {}", e.toString());
        }
    }
    
    /**
     * Called when depth data is available (5-level full packet from Kite) — BEFORE onOptionTick for the
     * same packet. Caches the full immutable {@link DepthSnapshot} per token so onOptionTick can write both
     * the derived features AND the raw ladder (the capture that makes the spoof detector tunable from a live
     * day). Unthrottled: a single cheap map put; the row-rate throttle lives in onOptionTick.
     */
    public void onDepth(long token, DepthSnapshot depth, int exchangeTsEpochSec, Instant recv) {
        if (!enabled || depth == null) return;
        try {
            depthByToken.put(token, depth);
        } catch (Exception e) {
            log.debug("[AtmMicro] onDepth skipped: {}", e.toString());
        }
    }

    /** Raw 5-level ladder as CSV: bid px/qty/orders x5, then ask px/qty/orders x5, then totals (32 fields). */
    private static String ladderCsv(DepthSnapshot d) {
        StringBuilder sb = new StringBuilder(160);
        for (int i = 0; i < 5; i++) sb.append(d.bidPrices()[i]).append(',').append(d.bidQtys()[i]).append(',').append(d.bidOrders()[i]).append(',');
        for (int i = 0; i < 5; i++) sb.append(d.askPrices()[i]).append(',').append(d.askQtys()[i]).append(',').append(d.askOrders()[i]).append(',');
        sb.append(d.totalBuyQty()).append(',').append(d.totalSellQty());
        return sb.toString();
    }

    /** Drains the buffer to today's CSV. Runs off the WS thread. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void flush() {
        if (!enabled || buffer.isEmpty()) return;
        List<String> batch = new ArrayList<>();
        for (String row; (row = buffer.poll()) != null; ) {
            batch.add(row);
            if (batch.size() >= 50_000) break; // bound per-flush work
        }
        if (batch.isEmpty()) return;
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("atm-microstructure-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(HEADER);
                for (String row : batch) w.write(row);
            }
            int d = dropped.getAndSet(0);
            if (d > 0) log.warn("[AtmMicro] buffer cap hit — dropped {} rows since last flush", d);
            int dd = deduped.getAndSet(0);
            if (dd > 0) log.debug("[AtmMicro] dedup guard skipped {} unchanged-state rows since last flush", dd);
        } catch (IOException e) {
            log.warn("[AtmMicro] flush failed (rows requeued): {}", e.getMessage());
            buffer.addAll(batch); // retry next cycle rather than lose data
        }
    }
}