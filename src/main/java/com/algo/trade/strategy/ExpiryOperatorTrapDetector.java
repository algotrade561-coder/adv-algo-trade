package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.execution.DailyResettable;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expiry-Day Operator Trap Detector — detects deliberate post-2PM manipulation
 * patterns: OI divergence traps, gamma blasts, distant OI laddering, OI unwind
 * collapses, and expiry pinning toward max pain.
 *
 * <h3>Observed Operator Patterns (post-2PM expiry):</h3>
 * <ul>
 *   <li><b>OI Divergence Trap</b>: Price moves OPPOSITE to where OI is building.
 *       High call OI zones get lifted while low OI zones collapse to zero.</li>
 *   <li><b>Max Pain Shift</b>: Max pain drifts toward ATM intraday, forcing pinning.
 *       Recalculated every 2 minutes to catch the shift.</li>
 *   <li><b>Gamma Blast Zone</b>: Sudden explosive moves near ATM (2:15–3:00 PM)
 *       when IV + OI spike at ATM strikes simultaneously.</li>
 *   <li><b>Distant OI Laddering</b>: Operators write far OTM (200–300 pts away),
 *       then move spot toward those levels before reversing.</li>
 *   <li><b>OI Unwind Collapse</b>: OI drops &gt;30% in &lt;10 minutes at key strikes,
 *       creating false breakdowns before expiry pinning.</li>
 *   <li><b>Expiry Pinning</b>: Post-2:30 PM, price gravitates to max pain strike.
 *       Bot should bias all trades toward max pain direction.</li>
 * </ul>
 *
 * <p>Runs every 2 minutes on expiry days during market hours.</p>
 */
@Component
public class ExpiryOperatorTrapDetector implements DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(ExpiryOperatorTrapDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final PcrCalculator pcrCalculator;
    private final MarketGuard marketGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService alertService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    // ── Configuration ─────────────────────────────────────────────────────

    @Value("${trading.expiry-trap.enabled:true}")
    private boolean enabled;

    /** OI unwind threshold: alert when OI at a strike drops more than this % in one scan. */
    @Value("${trading.expiry-trap.oi-unwind-threshold-pct:30}")
    private double oiUnwindThresholdPct;

    /** Distant OI threshold: track OI buildup more than N% away from spot. */
    @Value("${trading.expiry-trap.distant-oi-distance-pct:2.0}")
    private double distantOiDistancePct;

    /** Minimum OI at a distant strike to consider it "operator laddering". */
    @Value("${trading.expiry-trap.distant-oi-min-contracts:500000}")
    private long distantOiMinContracts;

    /** Max pain bias activation time — after this, bias toward max pain. */
    @Value("${trading.expiry-trap.pin-bias-start-time:14:30}")
    private String pinBiasStartTime;

    /** Square-off deadline — all positions should be closed by this time. */
    @Value("${trading.expiry-trap.squareoff-deadline:15:20}")
    private String squareoffDeadline;

    // ── Per-index state ───────────────────────────────────────────────────

    private final Map<IndexType, TrapState> states = new ConcurrentHashMap<>();

    public ExpiryOperatorTrapDetector(LiveInstrumentCache liveInstrumentCache,
                                      ExpiryCalendar expiryCalendar,
                                      PcrCalculator pcrCalculator,
                                      MarketGuard marketGuard) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.pcrCalculator = pcrCalculator;
        this.marketGuard = marketGuard;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Get the current operator trap assessment for an index.
     * Used by strategies to adjust behavior on expiry day after 2 PM.
     */
    public TrapAssessment assess(IndexType indexType) {
        TrapState state = states.get(indexType);
        if (state == null || !enabled) return TrapAssessment.NONE;
        return state.assessment;
    }

    /**
     * Is the max-pain pinning bias active? (Post-2:30 PM on expiry)
     */
    public boolean isMaxPainBiasActive(IndexType indexType) {
        if (!enabled || !expiryCalendar.isExpiryDay(indexType)) return false;
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.parse(pinBiasStartTime));
    }

    /**
     * Get the max pain strike that the bot should bias toward.
     * Returns 0 if not on expiry day or bias not active.
     */
    public int getMaxPainTarget(IndexType indexType) {
        TrapState state = states.get(indexType);
        if (state == null) return 0;
        return state.currentMaxPain;
    }

    /**
     * Direction to max pain: +1 if spot is below max pain (bias bullish),
     * -1 if spot is above max pain (bias bearish), 0 if at max pain.
     */
    public int getMaxPainDirection(IndexType indexType) {
        TrapState state = states.get(indexType);
        if (state == null || state.currentMaxPain == 0) return 0;
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;
        int interval = indexType.strikeInterval();
        if (spot < state.currentMaxPain - interval) return 1;  // Below max pain → bullish bias
        if (spot > state.currentMaxPain + interval) return -1; // Above max pain → bearish bias
        return 0; // At max pain — pinned
    }

    /**
     * Should we exit immediately? (OI unwind collapse or approaching squareoff deadline)
     */
    public boolean shouldForceExit(IndexType indexType) {
        if (!enabled || !expiryCalendar.isExpiryDay(indexType)) return false;
        TrapState state = states.get(indexType);
        // Force exit if OI unwind collapse detected
        if (state != null && state.assessment.oiUnwindCollapse) return true;
        // Force exit approaching squareoff deadline
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.parse(squareoffDeadline));
    }

    /**
     * Is there an active gamma blast risk? (ATM IV + OI spiking simultaneously)
     */
    public boolean isGammaBlastRisk(IndexType indexType) {
        TrapState state = states.get(indexType);
        return state != null && state.assessment.gammaBlastZone;
    }

    /**
     * Are there distant OI ladders that operators might push spot toward?
     */
    public List<DistantOiLadder> getDistantLadders(IndexType indexType) {
        TrapState state = states.get(indexType);
        return state != null ? state.distantLadders : List.of();
    }

    /**
     * Diagnostics for API/monitoring.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        for (Map.Entry<IndexType, TrapState> entry : states.entrySet()) {
            TrapState s = entry.getValue();
            Map<String, Object> idxStatus = new LinkedHashMap<>();
            idxStatus.put("isExpiryDay", expiryCalendar.isExpiryDay(entry.getKey()));
            idxStatus.put("maxPain", s.currentMaxPain);
            idxStatus.put("maxPainDirection", getMaxPainDirection(entry.getKey()));
            idxStatus.put("oiDivergenceTrap", s.assessment.oiDivergenceTrap);
            idxStatus.put("gammaBlastZone", s.assessment.gammaBlastZone);
            idxStatus.put("oiUnwindCollapse", s.assessment.oiUnwindCollapse);
            idxStatus.put("expiryPinning", s.assessment.expiryPinning);
            idxStatus.put("distantLadders", s.distantLadders.size());
            status.put(entry.getKey().name(), idxStatus);
        }
        return status;
    }

    /**
     * Reset all state at start of day (invoked by DailyResetService at midnight).
     * Clears dayOpen, maxPain, OI snapshots, and assessments so detection on the
     * next expiry day starts from a clean slate.
     */
    @Override
    public void resetDaily() {
        states.clear();
        log.info("[ExpiryTrap] Daily state reset.");
    }

    // ── Scheduled Detection ───────────────────────────────────────────────

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("expiryTrapDetector",
                    "Expiry operator trap detection (2-5min)", 120_000, this::detect);
        }
    }

    /**
     * Main detection loop. Runs every 2 minutes during expiry-day afternoon.
     * Runs every 5 minutes during expiry-day morning.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void detect() {
        if (!enabled) return;
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("expiryTrapDetector")) return;
        if (!isMarketHours()) return;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
            if (!expiryCalendar.isExpiryDay(idx)) continue;
            detectForIndex(idx);
        }
    }

    private void detectForIndex(IndexType indexType) {
        TrapState state = states.computeIfAbsent(indexType, k -> new TrapState());
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        LocalTime now = LocalTime.now(IST);
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();

        // ── 1. Recalculate Max Pain ──────────────────────────────────────
        int newMaxPain = computeMaxPain(indexType, expiry, atm, interval);
        if (newMaxPain > 0) {
            if (state.currentMaxPain > 0 && state.currentMaxPain != newMaxPain) {
                int shift = newMaxPain - state.currentMaxPain;
                log.info("[ExpiryTrap][{}] Max pain shifted: {} → {} (Δ{})",
                        indexType, state.currentMaxPain, newMaxPain, shift);
            }
            state.currentMaxPain = newMaxPain;
        }

        // ── 2. OI Divergence Trap Detection ──────────────────────────────
        boolean oiDivergence = detectOiDivergence(indexType, expiry, spot, atm, interval, state);

        // ── 3. Gamma Blast Zone Detection ────────────────────────────────
        boolean gammaBlast = detectGammaBlast(indexType, expiry, atm, interval, now);

        // ── 4. Distant OI Laddering ──────────────────────────────────────
        List<DistantOiLadder> ladders = detectDistantLadders(indexType, expiry, spot, interval);
        state.distantLadders = ladders;

        // ── 5. OI Unwind Collapse ────────────────────────────────────────
        boolean oiCollapse = detectOiUnwindCollapse(indexType, expiry, atm, interval, state);

        // ── 6. Expiry Pinning Check ──────────────────────────────────────
        boolean pinning = isMaxPainBiasActive(indexType) && state.currentMaxPain > 0
                && Math.abs(spot - state.currentMaxPain) < interval * 2;

        // ── Build assessment ─────────────────────────────────────────────
        state.assessment = new TrapAssessment(
                oiDivergence, gammaBlast, oiCollapse, pinning,
                ladders.size() > 0, state.currentMaxPain);

        // ── Alerts ───────────────────────────────────────────────────────
        if (now.isAfter(LocalTime.of(14, 0))) {
            if (oiCollapse && alertService != null) {
                alertService.systemAlert(String.format(
                        "⚠️ OI UNWIND COLLAPSE — %s\nOI dropped >%d%% at key strike in <10min\nOperator trap likely — consider exit",
                        indexType, (int) oiUnwindThresholdPct));
            }
            if (gammaBlast && alertService != null) {
                alertService.systemAlert(String.format(
                        "💥 GAMMA BLAST ZONE — %s\nATM IV + OI spiking simultaneously\nExplosive move imminent (2:15-3:00 PM)",
                        indexType));
            }
        }
    }

    // ── Detection Methods ─────────────────────────────────────────────────

    private boolean detectOiDivergence(IndexType indexType, LocalDate expiry,
                                       double spot, int atm, int interval, TrapState state) {
        // Check if price direction opposes OI build-up direction
        // High call OI zones being lifted = trap for call sellers
        long totalCeOi = 0, totalPeOi = 0;
        long ceOiAboveAtm = 0, peOiBelowAtm = 0;

        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || !opt.getExpiry().equals(expiry)) continue;
            if (opt.getOpenInterest() <= 0) continue;

            if ("CE".equals(opt.getOptionType())) {
                totalCeOi += opt.getOpenInterest();
                if (opt.getStrikePrice() > atm) ceOiAboveAtm += opt.getOpenInterest();
            } else {
                totalPeOi += opt.getOpenInterest();
                if (opt.getStrikePrice() < atm) peOiBelowAtm += opt.getOpenInterest();
            }
        }

        if (totalCeOi == 0 || totalPeOi == 0) return false;

        // Divergence: heavy CE OI above (bearish signal) but price rising (bullish)
        // OR heavy PE OI below (bullish signal) but price falling (bearish)
        double priceMoveFromOpen = spot - state.dayOpen;
        if (state.dayOpen == 0) { state.dayOpen = spot; return false; }

        boolean priceRising = priceMoveFromOpen > interval * 0.5;
        boolean priceFalling = priceMoveFromOpen < -interval * 0.5;
        double ceRatio = (double) ceOiAboveAtm / totalCeOi;
        double peRatio = (double) peOiBelowAtm / totalPeOi;

        // Trap: massive CE OI built above ATM (bearish wall) but price rising anyway
        if (priceRising && ceRatio > 0.6) {
            log.debug("[ExpiryTrap][{}] OI DIVERGENCE: price rising but CE OI wall above ATM (ratio={})",
                    indexType, String.format("%.2f", ceRatio));
            return true;
        }
        // Trap: massive PE OI built below ATM (bullish floor) but price falling anyway
        if (priceFalling && peRatio > 0.6) {
            log.debug("[ExpiryTrap][{}] OI DIVERGENCE: price falling but PE OI floor below ATM (ratio={})",
                    indexType, String.format("%.2f", peRatio));
            return true;
        }
        return false;
    }

    private boolean detectGammaBlast(IndexType indexType, LocalDate expiry,
                                     int atm, int interval, LocalTime now) {
        // Gamma blast: IV + OI both spiking at ATM strikes between 2:15–3:00 PM
        if (now.isBefore(LocalTime.of(14, 15)) || now.isAfter(LocalTime.of(15, 0))) return false;

        Optional<OptionInstrument> atmCe = liveInstrumentCache.getOption(indexType, atm, "CE", expiry);
        Optional<OptionInstrument> atmPe = liveInstrumentCache.getOption(indexType, atm, "PE", expiry);
        if (atmCe.isEmpty() || atmPe.isEmpty()) return false;

        double ceIv = atmCe.get().getImpliedVolatility();
        double peIv = atmPe.get().getImpliedVolatility();
        long ceOi = atmCe.get().getOpenInterest();
        long peOi = atmPe.get().getOpenInterest();

        // Gamma blast conditions:
        // 1. IV at ATM is elevated (>25% for NIFTY, >30% for BANKNIFTY)
        double ivThreshold = indexType == IndexType.BANKNIFTY ? 30.0 : 25.0;
        boolean ivElevated = ceIv > ivThreshold || peIv > ivThreshold;

        // 2. Combined ATM OI is significant (both sides have high OI = pin contest)
        long combinedOi = ceOi + peOi;
        boolean oiSignificant = combinedOi > 1_000_000; // > 1M contracts combined

        // 3. OI has been building recently (not just static)
        double ceOiChange = atmCe.get().getOiChangePercentSince(3);
        double peOiChange = atmPe.get().getOiChangePercentSince(3);
        boolean oiBuilding = ceOiChange > 2.0 || peOiChange > 2.0;

        return ivElevated && oiSignificant && oiBuilding;
    }

    private List<DistantOiLadder> detectDistantLadders(IndexType indexType, LocalDate expiry,
                                                       double spot, int interval) {
        List<DistantOiLadder> ladders = new ArrayList<>();
        double distanceThreshold = spot * (distantOiDistancePct / 100.0);

        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || !opt.getExpiry().equals(expiry)) continue;
            if (opt.getOpenInterest() < distantOiMinContracts) continue;

            double distance = Math.abs(opt.getStrikePrice() - spot);
            if (distance >= distanceThreshold) {
                ladders.add(new DistantOiLadder(
                        opt.getStrikePrice(), opt.getOptionType(),
                        opt.getOpenInterest(), distance,
                        (distance / spot) * 100));
            }
        }

        // Sort by OI descending — highest OI distant strikes are the operator targets
        ladders.sort((a, b) -> Long.compare(b.oi, a.oi));
        return ladders.size() > 5 ? ladders.subList(0, 5) : ladders;
    }

    private boolean detectOiUnwindCollapse(IndexType indexType, LocalDate expiry,
                                           int atm, int interval, TrapState state) {
        // Check if any key strike (ATM ± 3) had OI drop > 30% since last scan
        boolean collapse = false;

        for (int offset = -3; offset <= 3; offset++) {
            int strike = atm + offset * interval;
            for (String optType : new String[]{"CE", "PE"}) {
                Optional<OptionInstrument> opt = liveInstrumentCache.getOption(indexType, strike, optType, expiry);
                if (opt.isEmpty()) continue;

                long currentOi = opt.get().getOpenInterest();
                String key = indexType + "|" + strike + "|" + optType;
                Long previousOi = state.oiSnapshot.get(key);

                if (previousOi != null && previousOi > 100_000) {
                    double changePct = ((double)(currentOi - previousOi) / previousOi) * 100;
                    if (changePct <= -oiUnwindThresholdPct) {
                        log.warn("[ExpiryTrap][{}] OI COLLAPSE: {} {} OI dropped {}% ({} → {})",
                                indexType, strike, optType, String.format("%.0f", changePct),
                                previousOi, currentOi);
                        collapse = true;
                    }
                }
                state.oiSnapshot.put(key, currentOi);
            }
        }
        return collapse;
    }

    private int computeMaxPain(IndexType indexType, LocalDate expiry, int atm, int interval) {
        int range = 10 * interval;
        int bestStrike = 0;
        double minLoss = Double.MAX_VALUE;

        for (int strike = atm - range; strike <= atm + range; strike += interval) {
            double writerLoss = 0;
            for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
                if (opt.getIndexType() != indexType || !opt.getExpiry().equals(expiry)) continue;
                if (opt.getOpenInterest() <= 0) continue;
                double intrinsic = "CE".equals(opt.getOptionType())
                        ? Math.max(0, strike - opt.getStrikePrice())
                        : Math.max(0, opt.getStrikePrice() - strike);
                writerLoss += intrinsic * opt.getOpenInterest();
            }
            if (writerLoss < minLoss) { minLoss = writerLoss; bestStrike = strike; }
        }
        return bestStrike;
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 35));
    }

    // ── State & Records ───────────────────────────────────────────────────

    static class TrapState {
        volatile int currentMaxPain;
        volatile double dayOpen;
        volatile TrapAssessment assessment = TrapAssessment.NONE;
        volatile List<DistantOiLadder> distantLadders = List.of();
        final Map<String, Long> oiSnapshot = new ConcurrentHashMap<>();
    }

    /**
     * Assessment of operator trap patterns currently active.
     */
    public record TrapAssessment(
            boolean oiDivergenceTrap,
            boolean gammaBlastZone,
            boolean oiUnwindCollapse,
            boolean expiryPinning,
            boolean distantLaddering,
            int maxPainStrike
    ) {
        public static final TrapAssessment NONE = new TrapAssessment(false, false, false, false, false, 0);
        public boolean anyTrapActive() { return oiDivergenceTrap || gammaBlastZone || oiUnwindCollapse || distantLaddering; }
    }

    /**
     * Distant OI ladder — operator-written far OTM option with high OI.
     */
    public record DistantOiLadder(
            int strike,
            String optionType,
            long oi,
            double distancePoints,
            double distancePercent
    ) {}
}
