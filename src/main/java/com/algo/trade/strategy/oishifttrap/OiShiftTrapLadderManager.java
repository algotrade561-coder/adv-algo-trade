package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.strategy.oimomentum.OperatorAccumulationDetector;
import com.algo.trade.strategy.oimomentum.OperatorFrameworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three-tier limit-ladder entry layer for OI Shift Trap.
 *
 * <p>When the legacy gates produce a trap candidate and the operator score
 * passes the arm floor, {@link #arm} is invoked. The manager then virtually
 * places three resting limit BUY orders at progressively deeper discounts
 * to the arm premium. On each tick (via {@link #poll}), the live LTP from
 * {@link LiveInstrumentCache} is compared against each tier's limit price;
 * any tier whose limit is at or above the live LTP is "filled" and emits a
 * {@link StrategyDecision}. Cancel triggers (window, op-score collapse, op-
 * score direction flip) close all remaining tiers.</p>
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@code OFF}    — {@link #arm} is a no-op. Legacy entry fires unchanged.</li>
 *   <li>{@code SHADOW} — Ladder runs the FSM and records diagnostics, but
 *       {@link FillResult#decision()} is always {@code null}. Legacy entry is
 *       suppressed by the strategy when shadow is on so trade volume reflects
 *       the ladder's would-be entries, not the legacy ones.</li>
 *   <li>{@code LIVE}   — Ladder emits real decisions, one per filled tier.</li>
 * </ul>
 *
 * <h3>Position sizing</h3>
 * <p>Each tier carries 1/3 of the legacy lot quantity. The
 * {@link StrategyDecision#lotSize()} carries the per-tier lot count so the
 * execution layer sizes the broker order correctly.</p>
 */
@Component
public class OiShiftTrapLadderManager {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapLadderManager.class);

    public record Key(IndexType index, OptionType side, int strike) {}

    public enum TierStatus { OPEN, FILLED, CANCELLED }

    public static final class Tier {
        final int index;          // 1, 2, or 3
        final double limitPrice;
        volatile TierStatus status = TierStatus.OPEN;
        volatile double fillPrice = 0.0;
        volatile Instant resolvedAt = null;

        Tier(int index, double limitPrice) {
            this.index = index;
            this.limitPrice = limitPrice;
        }

        public int index() { return index; }
        public double limitPrice() { return limitPrice; }
        public TierStatus status() { return status; }
        public double fillPrice() { return fillPrice; }
        public Instant resolvedAt() { return resolvedAt; }
    }

    public static final class Ladder {
        final Key key;
        final Instant armedAt;
        final double armLtp;
        final int armOpScore;
        final int armOpDirection;
        final int lotsPerTier;
        final OiShiftTrapDiagnostics seedDiagnostics;
        final Tier tier1, tier2, tier3;

        Ladder(Key key, Instant armedAt, double armLtp, int armOpScore, int armOpDirection,
               int lotsPerTier, OiShiftTrapDiagnostics seedDiag,
               double t1Price, double t2Price, double t3Price) {
            this.key = key;
            this.armedAt = armedAt;
            this.armLtp = armLtp;
            this.armOpScore = armOpScore;
            this.armOpDirection = armOpDirection;
            this.lotsPerTier = lotsPerTier;
            this.seedDiagnostics = seedDiag;
            this.tier1 = new Tier(1, t1Price);
            this.tier2 = new Tier(2, t2Price);
            this.tier3 = new Tier(3, t3Price);
        }

        public Key key() { return key; }
        public Instant armedAt() { return armedAt; }
        public double armLtp() { return armLtp; }
        public Tier tier1() { return tier1; }
        public Tier tier2() { return tier2; }
        public Tier tier3() { return tier3; }
        public OiShiftTrapDiagnostics seedDiagnostics() { return seedDiagnostics; }

        public boolean allResolved() {
            return tier1.status != TierStatus.OPEN
                    && tier2.status != TierStatus.OPEN
                    && tier3.status != TierStatus.OPEN;
        }
    }

    /** Outcome of a single {@link #poll} tick. */
    public record FillResult(
            StrategyDecision decision,
            OiShiftTrapDiagnostics diagnostics,
            int tierIndex,
            String event,        // TIER_FILLED | CANCELLED
            String cancelReason
    ) {}

    private final OperatorFrameworkService operatorFramework;
    private final OiShiftTrapLadderConfigService configService;
    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final OiShiftTrapLadderConfig directConfig;

    private final Map<Key, Ladder> ladders = new ConcurrentHashMap<>();

    public OiShiftTrapLadderManager(
            @Autowired(required = false) OperatorFrameworkService operatorFramework,
            @Autowired(required = false) OiShiftTrapLadderConfigService configService,
            @Autowired(required = false) LiveInstrumentCache liveInstrumentCache,
            @Autowired(required = false) ExpiryCalendar expiryCalendar) {
        this.operatorFramework = operatorFramework;
        this.configService = configService;
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.directConfig = null;
    }

    /** Test-only constructor — drives the manager with a plain config + mock LTP source. */
    OiShiftTrapLadderManager(OperatorFrameworkService operatorFramework,
                              OiShiftTrapLadderConfig directConfig) {
        this.operatorFramework = operatorFramework;
        this.configService = null;
        this.liveInstrumentCache = null;
        this.expiryCalendar = null;
        this.directConfig = directConfig;
    }

    OiShiftTrapLadderConfig config() {
        if (directConfig != null) return directConfig;
        return configService == null ? null : configService.getCached();
    }

    public boolean isEnabled() {
        OiShiftTrapLadderConfig c = config();
        return c != null && c.isEnabled();
    }

    public boolean isShadow() {
        OiShiftTrapLadderConfig c = config();
        return c != null && c.isShadow();
    }

    public int activeLadderCount() { return ladders.size(); }

    public Ladder peek(Key key) { return ladders.get(key); }

    /**
     * Arm a ladder for a legacy trap candidate. Returns {@code true} when the
     * ladder was placed (mode is enabled AND op-score gate passes). Returns
     * {@code false} when the gate blocks arming or the mode is OFF — in which
     * case the legacy strategy should proceed with its normal immediate entry.
     */
    public synchronized boolean arm(IndexType index, OptionType side, int strike,
                                    double armLtp, int totalLots,
                                    OiShiftTrapDiagnostics seedDiag) {
        OiShiftTrapLadderConfig cfg = config();
        if (cfg == null || !cfg.isEnabled()) return false;
        if (armLtp <= 0 || totalLots <= 0) return false;

        int opScore = readOpScore(index);
        int opDir = readOpDirection(index);
        if (opScore < cfg.getLadderOpScoreArmFloor()) {
            log.info("[ShiftTrapLadder] arm BLOCKED: op-score {} < floor {} (idx={} side={} strike={})",
                    opScore, cfg.getLadderOpScoreArmFloor(), index, side, strike);
            return false;
        }

        double t1Price = armLtp * (1.0 - cfg.getTier1Discount());
        double t2Price = armLtp * (1.0 - cfg.getTier2Discount());
        double t3Price = armLtp * (1.0 - cfg.getTier3Discount());
        int lotsPerTier = Math.max(1, totalLots / 3);

        Key key = new Key(index, side, strike);
        Ladder fresh = new Ladder(key, Instant.now(), armLtp, opScore, opDir,
                lotsPerTier, seedDiag, t1Price, t2Price, t3Price);
        ladders.put(key, fresh);
        log.info("[ShiftTrapLadder] ARMED {} side={} strike={} arm_ltp={} tiers=[{}, {}, {}] lots/tier={} mode={}",
                index, side, strike, armLtp, t1Price, t2Price, t3Price, lotsPerTier, cfg.getLadderMode());
        return true;
    }

    /**
     * Tick-evaluation entry point. Returns all tier resolutions (fills +
     * cancels) that happened this tick across all ladders for the given
     * index. Empty when nothing happened.
     */
    public synchronized List<FillResult> poll(IndexType index) {
        if (!isEnabled()) return List.of();
        List<FillResult> results = new ArrayList<>();
        for (Map.Entry<Key, Ladder> e : new ArrayList<>(ladders.entrySet())) {
            if (e.getKey().index() != index) continue;
            evaluate(e.getValue(), results);
            if (e.getValue().allResolved()) {
                ladders.remove(e.getKey());
            }
        }
        return results;
    }

    /** Test helper / mid-day reset. */
    public synchronized void clear() {
        ladders.clear();
    }

    // ── Evaluation ───────────────────────────────────────────────────────

    private void evaluate(Ladder L, List<FillResult> out) {
        OiShiftTrapLadderConfig cfg = config();
        if (cfg == null) return;

        long armAgeSec = Math.max(0L,
                Instant.now().getEpochSecond() - L.armedAt.getEpochSecond());

        // ── Cancel triggers (apply to ALL open tiers) ──
        String cancelReason = null;
        if (armAgeSec >= cfg.getLadderWindowMin() * 60L) {
            cancelReason = "window_expired";
        } else {
            int curOpScore = readOpScore(L.key.index());
            int curOpDir = readOpDirection(L.key.index());
            if (curOpScore > 0
                    && (L.armOpScore - curOpScore) >= cfg.getLadderOpScoreCancelDelta()) {
                cancelReason = "op_score_collapsed";
            } else if (L.armOpDirection != 0 && curOpDir != 0
                    && curOpDir != L.armOpDirection) {
                cancelReason = "op_direction_flipped";
            }
        }
        if (cancelReason != null) {
            cancelOpenTiers(L, cancelReason, out);
            return;
        }

        // ── Fill detection ──
        double live = readLiveLtp(L.key);
        if (live <= 0) return;

        if (L.tier1.status == TierStatus.OPEN && live <= L.tier1.limitPrice) {
            fillTier(L, L.tier1, live, out);
        }
        if (L.tier2.status == TierStatus.OPEN && live <= L.tier2.limitPrice) {
            fillTier(L, L.tier2, live, out);
        }
        if (L.tier3.status == TierStatus.OPEN && live <= L.tier3.limitPrice) {
            fillTier(L, L.tier3, live, out);
        }
    }

    private void fillTier(Ladder L, Tier T, double fillPrice, List<FillResult> out) {
        T.status = TierStatus.FILLED;
        T.fillPrice = fillPrice;
        T.resolvedAt = Instant.now();
        StrategyDecision decision = isShadow() ? null : buildDecisionFor(L, T, fillPrice);
        OiShiftTrapDiagnostics diag = buildLadderDiag(L, "TIER_FILLED", null, T.index);
        log.info("[ShiftTrapLadder] FILL tier{} {} side={} strike={} price={} (limit={})",
                T.index, L.key.index(), L.key.side(), L.key.strike(), fillPrice, T.limitPrice);
        out.add(new FillResult(decision, diag, T.index, "TIER_FILLED", null));
    }

    private void cancelOpenTiers(Ladder L, String reason, List<FillResult> out) {
        Instant now = Instant.now();
        for (Tier T : List.of(L.tier1, L.tier2, L.tier3)) {
            if (T.status != TierStatus.OPEN) continue;
            T.status = TierStatus.CANCELLED;
            T.resolvedAt = now;
            OiShiftTrapDiagnostics diag = buildLadderDiag(L, "CANCELLED", reason, T.index);
            out.add(new FillResult(null, diag, T.index, "CANCELLED", reason));
        }
        log.info("[ShiftTrapLadder] CANCELLED ladder {} side={} strike={} reason={}",
                L.key.index(), L.key.side(), L.key.strike(), reason);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double readLiveLtp(Key k) {
        if (liveInstrumentCache == null || expiryCalendar == null) return 0;
        LocalDate expiry;
        try {
            expiry = expiryCalendar.getCurrentExpiry(k.index());
        } catch (RuntimeException ex) {
            return 0;
        }
        if (expiry == null) return 0;
        Optional<OptionInstrument> opt = liveInstrumentCache.getOption(
                k.index(), k.strike(), k.side() == OptionType.CE ? "CE" : "PE", expiry);
        return opt.map(OptionInstrument::getLastPrice).orElse(0.0);
    }

    /** Test helper — directly inject an LTP for fill simulation. */
    public synchronized List<FillResult> simulateTick(IndexType index, double simulatedLtp) {
        if (!isEnabled()) return List.of();
        List<FillResult> results = new ArrayList<>();
        for (Map.Entry<Key, Ladder> e : new ArrayList<>(ladders.entrySet())) {
            if (e.getKey().index() != index) continue;
            evaluateWithLtp(e.getValue(), simulatedLtp, results);
            if (e.getValue().allResolved()) ladders.remove(e.getKey());
        }
        return results;
    }

    private void evaluateWithLtp(Ladder L, double ltp, List<FillResult> out) {
        OiShiftTrapLadderConfig cfg = config();
        if (cfg == null) return;

        long armAgeSec = Math.max(0L,
                Instant.now().getEpochSecond() - L.armedAt.getEpochSecond());
        String cancelReason = null;
        if (armAgeSec >= cfg.getLadderWindowMin() * 60L) {
            cancelReason = "window_expired";
        } else {
            int curOpScore = readOpScore(L.key.index());
            int curOpDir = readOpDirection(L.key.index());
            if (curOpScore > 0
                    && (L.armOpScore - curOpScore) >= cfg.getLadderOpScoreCancelDelta()) {
                cancelReason = "op_score_collapsed";
            } else if (L.armOpDirection != 0 && curOpDir != 0
                    && curOpDir != L.armOpDirection) {
                cancelReason = "op_direction_flipped";
            }
        }
        if (cancelReason != null) {
            cancelOpenTiers(L, cancelReason, out);
            return;
        }
        if (ltp <= 0) return;
        if (L.tier1.status == TierStatus.OPEN && ltp <= L.tier1.limitPrice) fillTier(L, L.tier1, ltp, out);
        if (L.tier2.status == TierStatus.OPEN && ltp <= L.tier2.limitPrice) fillTier(L, L.tier2, ltp, out);
        if (L.tier3.status == TierStatus.OPEN && ltp <= L.tier3.limitPrice) fillTier(L, L.tier3, ltp, out);
    }

    private int readOpScore(IndexType index) {
        if (operatorFramework == null) return 0;
        OperatorAccumulationDetector.OperatorSignal sig = operatorFramework.getOperatorSignal(index);
        return sig == null ? 0 : sig.getScore();
    }

    private int readOpDirection(IndexType index) {
        if (operatorFramework == null) return 0;
        OperatorAccumulationDetector.OperatorSignal sig = operatorFramework.getOperatorSignal(index);
        return sig == null ? 0 : sig.getDirection();
    }

    private OiShiftTrapDiagnostics buildLadderDiag(Ladder L, String event, String cancelReason,
                                                    int tierIndex) {
        double effective = computeEffectiveFillPrice(L);
        double discount = L.armLtp > 0 ? (effective / L.armLtp - 1.0) : 0.0;
        OiShiftTrapDiagnostics.LadderInfo info = new OiShiftTrapDiagnostics.LadderInfo(
                event,
                config() == null ? "OFF" : config().getLadderMode(),
                L.armedAt, L.armLtp, L.armOpScore, L.armOpDirection,
                L.tier1.limitPrice, L.tier2.limitPrice, L.tier3.limitPrice,
                L.tier1.status == TierStatus.FILLED ? L.lotsPerTier : 0,
                L.tier2.status == TierStatus.FILLED ? L.lotsPerTier : 0,
                L.tier3.status == TierStatus.FILLED ? L.lotsPerTier : 0,
                effective, discount, cancelReason, readOpScore(L.key.index()));
        return L.seedDiagnostics.withLadderInfo(info);
    }

    private double computeEffectiveFillPrice(Ladder L) {
        double sumPx = 0, sumQty = 0;
        for (Tier T : List.of(L.tier1, L.tier2, L.tier3)) {
            if (T.status == TierStatus.FILLED) {
                sumPx += T.fillPrice * L.lotsPerTier;
                sumQty += L.lotsPerTier;
            }
        }
        return sumQty > 0 ? sumPx / sumQty : 0.0;
    }

    private static StrategyDecision buildDecisionFor(Ladder L, Tier T, double fillPrice) {
        OiShiftTrapDiagnostics seed = L.seedDiagnostics;
        OiShiftTrapDiagnostics.CandidateSnapshot snap = "CE".equals(seed.trapSide())
                ? seed.bestCe() : seed.bestPe();
        BigDecimal strike = BigDecimal.valueOf(L.key.strike());
        BigDecimal score = BigDecimal.valueOf(seed.signalScore());
        BigDecimal imbalance = snap != null && snap.present()
                ? BigDecimal.valueOf(snap.imbalance()) : BigDecimal.ZERO;
        BigDecimal premium = BigDecimal.valueOf(fillPrice);
        List<String> reasons = List.of(
                String.format("OI Shift Trap (ladder tier %d): side=%s strike=%s arm_ltp=%.2f fill=%.2f",
                        T.index, L.key.side(), strike, L.armLtp, fillPrice),
                String.format("Discount vs arm: %.2f%%", (fillPrice / L.armLtp - 1.0) * 100.0));
        return new StrategyDecision(
                Instant.now(),
                UnderlyingSymbol.valueOf(seed.underlying()),
                L.key.side() == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE,
                BigDecimal.ZERO,                                  // underlying price unknown at fill emit
                Optional.of(premium),
                Optional.empty(),                                  // open interest
                Optional.of(L.lotsPerTier),                        // per-tier lot count
                Optional.empty(),                                  // lotPrice
                Optional.empty(),                                  // instrumentKey
                Optional.of(strike),
                Optional.of(L.key.side()),
                false,
                Optional.of(imbalance),
                false,
                score,
                reasons);
    }
}
