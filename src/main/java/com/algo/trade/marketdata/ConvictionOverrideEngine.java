package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Conviction Override Engine — the single primary override authority for OI-momentum ENTRY and EXIT
 * (see docs/CONVICTION-OVERRIDE-ENGINE.md).
 *
 * <p>Thin consumer layer built on Tamil's microstructure primitives:
 * {@link DepthSnapshot} (5-level book imbalance + per-level spoof suspicion) and
 * {@link OrderFlowReconstructor} (Lee-Ready signed order flow + absorption). It feeds the (otherwise
 * dormant) order-flow reconstructor from the WS hot path, fuses the signals into one per-contract
 * {@link OverrideSnapshot} keyed by {@code INDEX:strike:TYPE}, and exposes the decision read-API:
 * <ul>
 *   <li>{@link #confirmsLong} — real one-directional buying on the option's own book (ENTRY confirm)</li>
 *   <li>{@link #isFakeMove}   — spoofed liquidity or the up-move being absorbed (ENTRY veto)</li>
 *   <li>{@link #isReversing}  — the same, used to trigger an immediate EXIT (EXIT override)</li>
 * </ul>
 *
 * <p>{@link #onDepthTick} runs on the WS thread — O(1), allocation-light, never throws. Consumers
 * read an immutable published snapshot with no locking.
 */
@Component
public class ConvictionOverrideEngine {

    private static final Logger log = LoggerFactory.getLogger(ConvictionOverrideEngine.class);

    private final LiveInstrumentCache cache;
    private final OrderFlowReconstructor orderFlow;

    @Value("${conviction-override.enabled:true}")
    private boolean enabled;
    @Value("${conviction-override.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private String underlyingsCsv;
    /** Min 5-level book imbalance (in the option's favour) for an ENTRY confirm.
     *  Tuned 2026-07-04 from 07-03 prod L1 data: imbalance p75 ≈ 0.36 (NIFTY) / 0.27 (SENSEX), so 0.25
     *  means "genuinely bid-heavy" rather than the looser 0.15 (~top-40% of ticks). */
    @Value("${conviction-override.imbalance-min:0.25}")
    private double imbalanceMin;
    /** Per-level spoof suspicion [0..1] at/above which a top level counts as SUSPICIOUS (large qty, few
     *  orders — one big resting order). Suspicion alone is NOT a spoof: a genuine institutional order looks
     *  identical. NOTE: not yet data-tuned — revisit once the depth-capture build has logged a live day. */
    @Value("${conviction-override.spoof-score-threshold:0.6}")
    private double spoofScoreThreshold;
    /** Fraction of a suspicious top level's qty that must VANISH (without trading) to confirm a spoof.
     *  This is the temporal leg of the design: suspicious level appears → collapses before price trades
     *  through it → the liquidity was fake/pulled. */
    @Value("${conviction-override.spoof-collapse-pct:0.6}")
    private double spoofCollapsePct;
    /** A snapshot older than this (ms) is stale and yields no confirm/veto. */
    @Value("${conviction-override.freshness-ms:3000}")
    private long freshnessMs;
    // Per-index min |signed volume| for absorption — layered on top of OrderFlowReconstructor's
    // book-unchanged flag (whose hardcoded ≥1000 is ~15× too sensitive for NIFTY). Tuned 2026-07-04 from
    // the full microstructure history (06-20..07-03) — per-day ΔV p90; see tools/microstructure-tuning/.
    // NIFTY non-expiry median ≈ 13.8k (0DTE ~160k, ~12×); SENSEX ≈ 1.5k; BANKNIFTY ≈ 0.8k.
    @Value("${conviction-override.absorb-min-signed-vol-nifty:15000}")
    private double absorbMinSvNifty;
    @Value("${conviction-override.absorb-min-signed-vol-banknifty:1000}")
    private double absorbMinSvBanknifty;
    @Value("${conviction-override.absorb-min-signed-vol-sensex:1500}")
    private double absorbMinSvSensex;

    private volatile Set<String> underlyings = Set.of("NIFTY", "BANKNIFTY", "SENSEX");
    private final ConcurrentHashMap<String, OverrideSnapshot> byKey = new ConcurrentHashMap<>();
    /** Previous top-of-book per token — the temporal spoof check compares consecutive depth ticks. */
    private final ConcurrentHashMap<Long, TopOfBook> prevTopByToken = new ConcurrentHashMap<>();

    /** Prior-tick top-level state for the vanish/collapse spoof confirmation. */
    private static final class TopOfBook {
        long bidQty0, askQty0;
        double bidPx0, askPx0;
        boolean bidSuspicious, askSuspicious; // spoof-score ≥ threshold on THAT tick
        long lastSpoofMs;                     // carried forward — keeps the flag sticky for freshness-ms
    }

    public ConvictionOverrideEngine(LiveInstrumentCache cache, OrderFlowReconstructor orderFlow) {
        this.cache = cache;
        this.orderFlow = orderFlow;
    }

    @PostConstruct
    void init() {
        try { this.underlyings = Set.of(underlyingsCsv.toUpperCase().replace(" ", "").split(",")); }
        catch (Exception ignore) { /* keep default */ }
        log.info("[COE] enabled={} underlyings={} imbalanceMin={} spoofThreshold={} freshnessMs={}",
                enabled, underlyings, imbalanceMin, spoofScoreThreshold, freshnessMs);
    }

    public boolean isEnabled() { return enabled; }

    /** Immutable fused microstructure snapshot per contract. */
    public record OverrideSnapshot(
            long publishedMs,
            double bookImbalance,   // 5-level, >0 = bid-heavy (buyers stacking the option)
            int aggressor,          // Lee-Ready: +1 buy, -1 sell, 0 neutral
            long signedVolume,      // signed ΔV this tick
            boolean absorption,     // heavy signed flow, book unchanged
            boolean spoofActive     // a level's spoof suspicion ≥ threshold
    ) {}

    /**
     * Fed from {@code KiteWebSocketClient.parsePacket} for every full-mode option tick. O(1), never throws.
     */
    public void onDepthTick(long token, double ltp, long cumVolume, DepthSnapshot depth, Instant recv) {
        if (!enabled || depth == null || ltp <= 0) return;
        try {
            OptionInstrument inst = cache.getByToken(token).orElse(null);
            if (inst == null) return;
            if (!underlyings.contains(inst.getIndexType().name())) return;

            double bestBid = depth.bidPrices()[0];
            double bestAsk = depth.askPrices()[0];
            OrderFlowReconstructor.OrderFlowSnapshot of = orderFlow.onTick(token, ltp, cumVolume, bestBid, bestAsk);

            // TEMPORAL spoof detection (per the design, not just the static ratio): a top level that was
            // SUSPICIOUS last tick (large qty, few orders — spoof score ≥ threshold) counts as a spoof only
            // when its quantity COLLAPSES (≥ spoof-collapse-pct) without being traded away — the vanished
            // size must exceed what actually printed (deltaVolume), i.e. the order was PULLED, not filled.
            // A large genuine institutional order that sits (or gets hit) never fires this.
            TopOfBook prev = prevTopByToken.get(token);
            long bq0 = depth.bidQtys()[0], aq0 = depth.askQtys()[0];
            boolean spoofBid = false, spoofAsk = false;
            if (prev != null) {
                // Track the suspicious level by PRICE, not ladder index (review 2026-07-04): after a
                // price move, slot 0 holds a DIFFERENT level and a naive qty comparison is apples-to-
                // oranges — the old form both missed the classic full pull (level cancelled, book steps
                // back) and false-fired on genuine advances (fresh smaller size at a new higher bid).
                // Wherever prev's top price now rests in the ladder (0 if gone), the spoof test is:
                //   vanishedUntraded = (prevQty − qtyNowAtThatPrice) − tradedVolume ≥ collapse-pct·prevQty
                // ⇒ the size was PULLED, not filled. A genuine advance leaves the old level deeper in
                // the book with its qty intact ⇒ vanished ≈ 0 ⇒ no flag. deltaVolume is total traded
                // volume (not side-attributed) — crediting all of it against either side errs
                // conservative: spoof fires less, never more.
                long bidNowAtPrev = qtyAtPrice(depth.bidPrices(), depth.bidQtys(), prev.bidPx0);
                long askNowAtPrev = qtyAtPrice(depth.askPrices(), depth.askQtys(), prev.askPx0);
                double bidVanishedUntraded = (prev.bidQty0 - bidNowAtPrev) - (double) of.deltaVolume();
                double askVanishedUntraded = (prev.askQty0 - askNowAtPrev) - (double) of.deltaVolume();
                spoofBid = prev.bidSuspicious && bidVanishedUntraded >= prev.bidQty0 * spoofCollapsePct;
                spoofAsk = prev.askSuspicious && askVanishedUntraded >= prev.askQty0 * spoofCollapsePct;
            }
            long nowMs = recv.toEpochMilli();
            TopOfBook cur = new TopOfBook();
            cur.bidQty0 = bq0; cur.askQty0 = aq0;
            cur.bidPx0 = bestBid; cur.askPx0 = bestAsk;
            cur.bidSuspicious = depth.bidSpoofScores()[0] >= spoofScoreThreshold;
            cur.askSuspicious = depth.askSpoofScores()[0] >= spoofScoreThreshold;
            // Sticky flag: a spoof is a one-tick event but ticks arrive several times/sec — without carrying
            // it forward the next tick would clear the veto within ~300ms. Hold it for freshness-ms.
            cur.lastSpoofMs = (spoofBid || spoofAsk) ? nowMs : (prev != null ? prev.lastSpoofMs : 0);
            prevTopByToken.put(token, cur);
            boolean spoofActive = cur.lastSpoofMs > 0 && (nowMs - cur.lastSpoofMs) <= freshnessMs;

            // Absorption = OrderFlow's book-unchanged flag AND a per-index "large flow" bar (its own
            // ≥1000 is ~20× too sensitive for NIFTY — see 07-03 tuning in docs/CONVICTION-OVERRIDE-ENGINE.md).
            boolean absorption = of.isAbsorption()
                    && Math.abs(of.signedVolume()) >= absorbMinSignedVol(inst.getIndexType());

            String key = key(inst.getIndexType(), inst.getStrikePrice(), inst.getOptionType());
            byKey.put(key, new OverrideSnapshot(recv.toEpochMilli(), depth.bookImbalance(),
                    of.aggressor(), of.signedVolume(), absorption, spoofActive));
        } catch (Exception e) {
            log.debug("[COE] onDepthTick skipped: {}", e.toString());
        }
    }

    // ── Decision read-API ─────────────────────────────────────────────────────

    public OverrideSnapshot get(IndexType index, int strike, String type) {
        if (index == null || type == null) return null;
        return byKey.get(key(index, strike, type));
    }

    private boolean fresh(OverrideSnapshot s) {
        return s != null && (System.currentTimeMillis() - s.publishedMs()) <= freshnessMs;
    }

    /** ENTRY confirm: real one-directional buying on the option's own book (bid-heavy + buy flow). */
    public boolean confirmsLong(IndexType index, int strike, String type) {
        OverrideSnapshot s = get(index, strike, type);
        return fresh(s) && s.bookImbalance() >= imbalanceMin && s.signedVolume() > 0;
    }

    /** ENTRY veto / EXIT trigger: the move is faked (spoofed liquidity) or being absorbed (resistance). */
    public boolean isFakeMove(IndexType index, int strike, String type) {
        OverrideSnapshot s = get(index, strike, type);
        if (!fresh(s)) return false;
        boolean buyFlowAbsorbed = s.absorption() && s.aggressor() > 0; // buyers hitting, price not moving → wall
        return s.spoofActive() || buyFlowAbsorbed;
    }

    /** EXIT override: the liquidity carrying the long was faked, or buy-flow is being absorbed. */
    public boolean isReversing(IndexType index, int strike, String type) {
        return isFakeMove(index, strike, type);
    }

    private double absorbMinSignedVol(IndexType index) {
        return switch (index) {
            case SENSEX -> absorbMinSvSensex;
            case BANKNIFTY -> absorbMinSvBanknifty;
            default -> absorbMinSvNifty;
        };
    }

    /** Qty resting at {@code price} anywhere in the 5-level ladder; 0 when the level is gone.
     *  Half-tick tolerance (tick = ₹0.05) — prices are paise-derived doubles; don't trust {@code ==}. */
    private static long qtyAtPrice(double[] prices, long[] qtys, double price) {
        if (prices == null || qtys == null || price <= 0) return 0;
        int n = Math.min(prices.length, qtys.length);
        for (int i = 0; i < n; i++) {
            if (Math.abs(prices[i] - price) < 0.025) return qtys[i];
        }
        return 0;
    }

    private static String key(IndexType index, int strike, String type) {
        return index.name() + ":" + strike + ":" + type;
    }

}
