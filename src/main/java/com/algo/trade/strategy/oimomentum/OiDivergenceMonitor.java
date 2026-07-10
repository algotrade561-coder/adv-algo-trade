package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OI–price <b>divergence</b> monitor for OI-momentum options (2026-07-03).
 *
 * <p>One indicator, two uses. On the <i>specific option strike</i> we hold (or are about to buy),
 * a rise in the strike's own OI while its premium falls is the signature of <b>writers stacking
 * into that strike</b> — bearish for a long holder regardless of CE/PE (a long call: call writers;
 * a long put: put writers). The interpretation is symmetric because we always read the held
 * option's own OI and its own premium.</p>
 *
 * <ul>
 *   <li><b>Exit use</b> ({@link #evaluateHeldExit}): after entry, if the held strike develops
 *       OI↑ + premium↓ (confirmed, and past an arming delay) → protective exit
 *       {@code OI_WRITER_STOP}, earlier than the hard SL and without the trailing give-back.</li>
 *   <li><b>Entry use</b> ({@link #evaluateEntry}): if the candidate strike already shows the
 *       divergence just before entry, we're buying into a writer wall — a low-quality entry.</li>
 * </ul>
 *
 * <p><b>Shadow-first.</b> Default {@code oi-divergence.shadow=true}: every would-fire is logged and
 * appended to {@code data/tuning/oi-divergence-shadow-<date>.csv} but NO trading decision changes.
 * Only when {@code shadow=false} does {@link #evaluateHeldExit} return {@code true} to authorise a
 * real exit. This lets the tuning loop measure the false-positive cost (how often it would cut
 * winners) on live winners AND losers before it can act. The measurement substrate for the entry
 * side is the (now SENSEX-inclusive) atm-microstructure capture; the exit side, once enforced,
 * auto-appears in the exit-attribution report grouped by the {@code OI_WRITER_STOP} reason.</p>
 *
 * <p><b>Safety.</b> Every public method is exception-safe and never throws into the caller — a
 * monitor hiccup must never disturb the live managePosition loop. Sampling is O(band) per tick and
 * reads only the in-memory {@link LiveInstrumentCache}.</p>
 */
@Component
public class OiDivergenceMonitor {

    private static final Logger log = LoggerFactory.getLogger(OiDivergenceMonitor.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String HEADER =
            "recvEpochMs,phase,index,strike,optionType,tradeId,entryPrice,premium,profitPct,"
            + "heldSec,oiNow,oiRisePct,premChgPct,confirmations,fires,shadow\n";
    private static final String GIVEBACK_HEADER =
            "recvEpochMs,index,strike,optionType,tradeId,entryPrice,peakPremium,premium,"
            + "peakPct,profitPct,pullbackPct,heldSec,oiRisePct,premChgPct,volRatio,recentVol,priorVol,"
            + "confirmations,fires,shadow\n";
    private static final String ENTRY_CONFIRM_HEADER =
            "recvEpochMs,index,strike,optionType,tag,entryCase,premium,volRatio,recentVol,priorVol,"
            + "thrustPct,volSurge,priceThrust,wouldConfirm\n";

    @Value("${oi-divergence.enabled:true}")
    private boolean enabled;

    /** true = log/capture only, never authorise a real exit (default). false = enforce. */
    @Value("${oi-divergence.shadow:true}")
    private boolean shadow;

    /** Look-back window (seconds) for the OI/premium change. Empirical writer-build cadence ≈ 60s. */
    @Value("${oi-divergence.window-sec:60}")
    private int windowSec;

    /** Minimum held-strike OI rise (% over the window) to count as writer buildup. From 2026-07-03 losers. */
    @Value("${oi-divergence.oi-rise-pct:0.8}")
    private double oiRisePct;

    /** Consecutive confirmed ticks required before the exit fires — dodges the entry-moment whipsaw. */
    @Value("${oi-divergence.confirm-ticks:3}")
    private int confirmTicks;

    /** Minimum hold (seconds) before the exit can fire — the arming delay. */
    @Value("${oi-divergence.arm-sec:60}")
    private int armSec;

    /** ATM ± this many strikes are sampled each tick so candidate/held strikes have history. */
    @Value("${oi-divergence.band-strikes:2}")
    private int bandStrikes;

    // ── Peak-profit-giveback confirmation (2026-07-03, additive) ───────────────────────────────
    // Fully independent sub-feature: separate enable/shadow flags, separate thresholds, separate
    // confirmation-streak/throttle state from OI_WRITER_STOP above. It never modifies the writer-
    // stop path and, when unconfirmed, never modifies the trailing-stop/profit-lock ratchet either
    // — it only ADDS an earlier confirmed-exit path for a still-profitable position that has pulled
    // back from its peak. See evaluatePeakGivebackExit().

    /** true = log/capture only, never authorise a real giveback exit (default). false = enforce. */
    @Value("${oi-divergence.giveback.enabled:true}")
    private boolean givebackEnabled;

    @Value("${oi-divergence.giveback.shadow:true}")
    private boolean givebackShadow;

    /** Minimum peak profit (%) reached before the giveback check even arms. */
    @Value("${oi-divergence.giveback.min-peak-pct:4.0}")
    private double givebackMinPeakPct;

    /** Minimum pullback from peak (percentage points of premium) before we look for confirmation. */
    @Value("${oi-divergence.giveback.trigger-pct:1.5}")
    private double givebackTriggerPct;

    /** Recent-window volume delta ÷ prior-window volume delta must be ≥ this to count as a surge. */
    @Value("${oi-divergence.giveback.volume-surge-multiplier:1.5}")
    private double volumeSurgeMultiplier;

    /** Minimum prior-window volume delta for the surge ratio to be considered meaningful (not noise). */
    @Value("${oi-divergence.giveback.min-volume:500}")
    private long minVolume;

    /** Consecutive confirmed ticks required before the giveback exit fires (anti-whipsaw). */
    @Value("${oi-divergence.giveback.confirm-ticks:2}")
    private int givebackConfirmTicks;

    /** A3 entry-side tape-confirmation for the OI-unavailable path. */
    @Value("${oi-divergence.entry-confirm.enabled:true}")
    private boolean entryConfirmEnabled;

    /** true = capture-only (never blocks); false = ENFORCE (block a no-OI entry with no tape confirmation). */
    @Value("${oi-divergence.entry-confirm.shadow:false}")
    private boolean entryConfirmShadow;

    private final LiveInstrumentCache cache;
    private final ExpiryCalendar expiryCalendar;

    /** key = index|strike|type → time-ordered (oldest→newest) samples within ~2× the window. */
    private final Map<String, Deque<Sample>> buffers = new ConcurrentHashMap<>();
    /** key = tradeId → consecutive confirmed-fire tick count (exit confirmation streak). */
    private final Map<String, Integer> exitConfirms = new ConcurrentHashMap<>();
    /** key = tradeId → last epoch-sec we wrote a shadow row (write throttle). */
    private final Map<String, Long> lastWriteSec = new ConcurrentHashMap<>();
    /** tradeIds whose entry-time divergence has already been captured (first-sight guard). */
    private final java.util.Set<String> entryCaptured = ConcurrentHashMap.newKeySet();
    /** key = tradeId → consecutive confirmed-fire tick count for the PEAK-GIVEBACK path (independent streak). */
    private final Map<String, Integer> givebackConfirms = new ConcurrentHashMap<>();
    /** key = tradeId → last epoch-sec we wrote a giveback shadow row (independent write throttle). */
    private final Map<String, Long> lastGivebackWriteSec = new ConcurrentHashMap<>();

    private record Sample(long sec, long oi, double prem, long vol) {}

    /** Divergence read for one strike. {@code fires} = OI rose ≥ threshold AND premium fell over the window. */
    public record Result(boolean valid, boolean fires, double oiRisePct, double premChgPct, long oiNow) {
        static Result none() { return new Result(false, false, 0, 0, 0); }
    }

    /** Volume-surge read for one strike: recent-window volume delta vs the prior equal-length window. */
    public record VolWindow(boolean valid, boolean surge, double ratio, long recentVol, long priorVol) {
        static VolWindow none() { return new VolWindow(false, false, 0, 0, 0); }
    }

    public OiDivergenceMonitor(LiveInstrumentCache cache, ExpiryCalendar expiryCalendar) {
        this.cache = cache;
        this.expiryCalendar = expiryCalendar;
    }

    public boolean isEnabled() { return enabled; }

    public boolean isShadow() { return shadow; }

    /**
     * Sample the ATM band (both CE and PE) into the ring buffers. Call once per tick per index from the
     * strategy loop so both candidate (entry) and held (exit) strikes accumulate ~window history.
     */
    public void sampleBand(IndexType index, int atm) {
        if (!enabled || index == null || atm <= 0) return;
        try {
            LocalDate expiry = expiryCalendar.getCurrentExpiry(index);
            if (expiry == null) return;
            int interval = index.strikeInterval();
            long now = Instant.now().getEpochSecond();
            for (int i = -bandStrikes; i <= bandStrikes; i++) {
                int strike = atm + i * interval;
                sampleOne(index, strike, "CE", expiry, now);
                sampleOne(index, strike, "PE", expiry, now);
            }
        } catch (Exception e) {
            log.debug("[OiDivergence] sampleBand skipped: {}", e.toString());
        }
    }

    private void sampleOne(IndexType index, int strike, String type, LocalDate expiry, long now) {
        OptionInstrument opt = cache.getOption(index, strike, type, expiry).orElse(null);
        if (opt == null) return;
        long oi = opt.getOpenInterest();
        double prem = opt.getLastPrice();
        long vol = opt.getVolume(); // cumulative day volume; deltas are computed between samples
        if (oi <= 0 || prem <= 0) return;
        Deque<Sample> dq = buffers.computeIfAbsent(key(index, strike, type), k -> new ArrayDeque<>());
        synchronized (dq) {
            Sample last = dq.peekLast();
            if (last != null && last.sec() == now) return; // ≤1 sample/sec/strike
            dq.addLast(new Sample(now, oi, prem, vol));
            // Retain enough history for BOTH the writer-stop window and the giveback recent+prior
            // volume windows (2× each, whichever is larger).
            long cutoff = now - Math.max(Math.max(windowSec * 2L, 180L), givebackWindowRetentionSec());
            while (dq.peekFirst() != null && dq.peekFirst().sec() < cutoff) dq.pollFirst();
        }
    }

    /** How far back the giveback volume-surge windows need (recent window + prior window, doubled for margin). */
    private long givebackWindowRetentionSec() {
        return Math.max(120L, GIVEBACK_VOL_WINDOW_SEC * 4L);
    }

    /**
     * Compute the divergence for one strike from its buffered history vs {@code premNow}. Returns
     * {@code Result.none()} (valid=false) when there isn't yet a sample at least {@code windowSec} old.
     */
    public Result evaluate(IndexType index, int strike, String type, double premNow) {
        if (!enabled || premNow <= 0) return Result.none();
        Deque<Sample> dq = buffers.get(key(index, strike, type));
        if (dq == null) return Result.none();
        long now = Instant.now().getEpochSecond();
        Sample past = null;
        long oiNow = 0;
        synchronized (dq) {
            for (Sample s : dq) {
                if (s.sec() <= now - windowSec) past = s; // newest sample at/older than the window start
            }
            Sample last = dq.peekLast();
            if (last != null) oiNow = last.oi();
        }
        if (past == null || past.oi() <= 0 || past.prem() <= 0) return Result.none();
        double oiRise = 100.0 * (oiNow - past.oi()) / past.oi();
        double premChg = 100.0 * (premNow - past.prem()) / past.prem();
        boolean fires = oiRise >= oiRisePct && premChg < 0;
        return new Result(true, fires, oiRise, premChg, oiNow);
    }

    /** Recent-window length for the peak-giveback volume-surge comparison (30s: fast enough to catch
     *  the unwind burst, slow enough to smooth single-print noise). Prior window is the same length,
     *  immediately preceding it — a same-length before/after comparison, not a long rolling average. */
    private static final long GIVEBACK_VOL_WINDOW_SEC = 30L;

    /**
     * Volume-surge read for one strike: cumulative-volume delta over the most recent
     * {@code GIVEBACK_VOL_WINDOW_SEC} vs the equal-length window immediately before it. A real
     * unwind burst should show materially more traded volume right now than it did just before —
     * this is the piece the OI+premium divergence alone does not capture (a single large order can
     * move OI without real turnover; a genuine unwind shows both).
     */
    public VolWindow evaluateVolumeSurge(IndexType index, int strike, String type) {
        Deque<Sample> dq = buffers.get(key(index, strike, type));
        if (dq == null) return VolWindow.none();
        long now = Instant.now().getEpochSecond();
        long recentCutoff = now - GIVEBACK_VOL_WINDOW_SEC;
        long priorCutoff = now - GIVEBACK_VOL_WINDOW_SEC * 2;
        Sample newest = null, atRecentCutoff = null, atPriorCutoff = null;
        synchronized (dq) {
            for (Sample s : dq) {
                newest = s;
                if (s.sec() <= recentCutoff) atRecentCutoff = s;
                if (s.sec() <= priorCutoff) atPriorCutoff = s;
            }
        }
        if (newest == null || atRecentCutoff == null || atPriorCutoff == null) return VolWindow.none();
        long recentVol = newest.vol() - atRecentCutoff.vol();
        long priorVol = atRecentCutoff.vol() - atPriorCutoff.vol();
        if (recentVol < 0 || priorVol < 0) return VolWindow.none(); // day-volume reset (e.g. new session) guard
        // Prior window too thin to mean anything (illiquid strike) — caller should fall back to
        // price-only trailing rather than trust a surge ratio computed off near-zero volume.
        if (priorVol < minVolume) return new VolWindow(true, false, 0, recentVol, priorVol);
        double ratio = (double) recentVol / priorVol;
        boolean surge = ratio >= volumeSurgeMultiplier;
        return new VolWindow(true, surge, ratio, recentVol, priorVol);
    }

    /**
     * Exit-side evaluation for the held position. Samples the held strike, evaluates the divergence, tracks
     * the confirmation streak, and (in shadow) logs+records the would-exit. Returns {@code true} ONLY when
     * a real exit should be taken — i.e. confirmed + armed AND {@code shadow=false}. In shadow it always
     * returns {@code false} (no trading change) while still recording the signal.
     *
     * @param direction +1 for a long CE, -1 for a long PE (selects the option type read)
     */
    public boolean evaluateHeldExit(IndexType index, int strike, int direction, double premNow,
                                    String tradeId, double entryPrice, long heldSec) {
        if (!enabled || index == null || strike <= 0 || premNow <= 0 || tradeId == null) return false;
        try {
            String type = direction >= 0 ? "CE" : "PE";
            LocalDate expiry = expiryCalendar.getCurrentExpiry(index);
            if (expiry != null) sampleOne(index, strike, type, expiry, Instant.now().getEpochSecond());
            Result r = evaluate(index, strike, type, premNow);
            if (!r.valid()) return false;

            // First-sight: capture the divergence that existed leading INTO entry (the ~window before the
            // first managePosition tick is pre-entry). Measurement only — the entry filter is a later step.
            if (entryCaptured.add(tradeId)) {
                double entryProfit = entryPrice > 0 ? 100.0 * (premNow - entryPrice) / entryPrice : 0;
                record("ENTRY", index, strike, type, tradeId, entryPrice, premNow, entryProfit, heldSec, r, 0);
                if (r.fires()) {
                    log.info("[OiDivergence][{}] ENTRY divergence PRESENT {} strike={}{} tradeId={} "
                                    + "oiRise={}%/{}s premChg={}% (bought into writer buildup) — capture-only",
                            index, type, strike, type, tradeId, fmt(r.oiRisePct()), windowSec, fmt(r.premChgPct()));
                }
            }

            int streak = r.fires() ? exitConfirms.merge(tradeId, 1, Integer::sum) : 0;
            if (!r.fires()) exitConfirms.remove(tradeId);

            boolean armed = heldSec >= armSec;
            boolean confirmed = r.fires() && streak >= confirmTicks && armed && premNow < entryPrice;
            if (!confirmed) return false;

            double profitPct = entryPrice > 0 ? 100.0 * (premNow - entryPrice) / entryPrice : 0;
            recordThrottled("EXIT", index, strike, type, tradeId, entryPrice, premNow, profitPct,
                    heldSec, r, streak);
            log.info("[OiDivergence][{}] {} OI_WRITER_STOP {} strike={}{} tradeId={} "
                            + "profit={}% oiRise={}%/{}s premChg={}% confirms={} held={}s",
                    index, shadow ? "SHADOW would-exit" : "ENFORCE exit", type, strike, type, tradeId,
                    fmt(profitPct), fmt(r.oiRisePct()), windowSec, fmt(r.premChgPct()), streak, heldSec);
            return !shadow; // authorise a real exit only when enforcing
        } catch (Exception e) {
            log.debug("[OiDivergence] evaluateHeldExit skipped: {}", e.toString());
            return false;
        }
    }

    /**
     * Peak-profit-giveback confirmation. Distinct from {@link #evaluateHeldExit} (OI_WRITER_STOP) —
     * this fires only on a STILL-PROFITABLE position that has pulled back from its peak, and requires
     * BOTH the OI+premium divergence AND a same-strike volume surge before treating the pullback as a
     * confirmed reversal rather than noise:
     *
     * <ul>
     *   <li>Not armed until peak profit ≥ {@code giveback.min-peak-pct} — never touches a scalp-sized
     *       trade that never had meaningful profit to protect.</li>
     *   <li>Not evaluated until price has pulled back ≥ {@code giveback.trigger-pct} percentage points
     *       from the peak — a routine 1-tick wobble at the very top never even reaches the check.</li>
     *   <li>Requires the OI/premium divergence (writers building against us in this exact contract)
     *       AND a volume surge (recent turnover materially above the immediately-prior window) in the
     *       SAME evaluation — either alone is not enough. This is the missing "confirm with volume"
     *       half: OI can shift on a single large order with no real turnover; requiring both filters
     *       that out.</li>
     *   <li>If the volume window is too thin to be meaningful (illiquid strike), this NEVER fires —
     *       it falls back silently to the existing price-only trailing stop/profit-lock ratchet
     *       rather than guess off sparse data.</li>
     *   <li>{@code giveback.shadow=true} (default): logs/records the would-fire, never authorises a
     *       real exit — independent shadow flag from {@code oi-divergence.shadow} so this half can be
     *       validated on its own timeline without being coupled to the writer-stop rollout.</li>
     * </ul>
     *
     * <p>When unconfirmed (pullback with no OI+volume evidence), this method does nothing — it never
     * tightens, loosens, or otherwise touches the existing trailing-stop/profit-lock ratchet. Those
     * remain the sole backstop for an unconfirmed pullback, exactly as before this method existed.</p>
     *
     * @param direction +1 for a long CE, -1 for a long PE
     * @param peakPremium the highest premium reached so far this trade (state.peakPrice)
     * @return true ONLY when a confirmed giveback exit should be taken now (armed + pullback +
     *         OI-divergence + volume-surge + confirm streak met + shadow=false). Always false in shadow.
     */
    public boolean evaluatePeakGivebackExit(IndexType index, int strike, int direction, double premNow,
                                            String tradeId, double entryPrice, double peakPremium,
                                            long heldSec) {
        if (!givebackEnabled || index == null || strike <= 0 || premNow <= 0 || tradeId == null
                || entryPrice <= 0 || peakPremium <= 0) return false;
        try {
            String type = direction >= 0 ? "CE" : "PE";
            double peakPct = 100.0 * (peakPremium - entryPrice) / entryPrice;
            double profitPct = 100.0 * (premNow - entryPrice) / entryPrice;
            double pullbackPct = peakPct - profitPct; // percentage points given back from the peak

            // Not armed: peak never reached the minimum profit worth protecting.
            if (peakPct < givebackMinPeakPct) return false;
            // Still profitable but hasn't pulled back far enough yet to even ask the question.
            if (pullbackPct < givebackTriggerPct) {
                givebackConfirms.remove(tradeId);
                return false;
            }

            Result divergence = evaluate(index, strike, type, premNow);
            VolWindow volWindow = evaluateVolumeSurge(index, strike, type);

            boolean divergenceFires = divergence.valid() && divergence.fires();
            boolean volumeConfirms = volWindow.valid() && volWindow.surge();
            boolean bothConfirm = divergenceFires && volumeConfirms;

            int streak = bothConfirm ? givebackConfirms.merge(tradeId, 1, Integer::sum) : 0;
            if (!bothConfirm) givebackConfirms.remove(tradeId);

            boolean confirmed = bothConfirm && streak >= givebackConfirmTicks;

            if (confirmed || (divergenceFires || volumeConfirms)) {
                // Record whenever at least one leg fires (useful shadow signal for tuning even when
                // not both legs align yet) — throttled per trade so we don't flood the CSV.
                recordGivebackThrottled(index, strike, type, tradeId, entryPrice, peakPremium, premNow,
                        peakPct, profitPct, pullbackPct, heldSec, divergence, volWindow, streak, confirmed);
            }

            if (!confirmed) return false;

            log.info("[OiDivergence][{}] {} PEAK_GIVEBACK {} strike={}{} tradeId={} peak={}% profit={}% "
                            + "pullback={}pp oiRise={}% premChg={}% volRatio={} confirms={} held={}s",
                    index, givebackShadow ? "SHADOW would-exit" : "ENFORCE exit", type, strike, type,
                    tradeId, fmt(peakPct), fmt(profitPct), fmt(pullbackPct), fmt(divergence.oiRisePct()),
                    fmt(divergence.premChgPct()), fmt(volWindow.ratio()), streak, heldSec);
            return !givebackShadow; // authorise a real exit only when enforcing
        } catch (Exception e) {
            log.debug("[OiDivergence] evaluatePeakGivebackExit skipped: {}", e.toString());
            return false;
        }
    }

    /**
     * Entry-side evaluation for a candidate strike — capture-only (never blocks; the entry filter is a
     * later, data-calibrated step). Records the divergence present at entry so the tuning loop can score
     * whether entries into a writer wall underperform.
     *
     * @param direction +1 for a candidate long CE, -1 for a long PE
     */
    public void evaluateEntry(IndexType index, int strike, int direction, double premNow, String tag) {
        if (!enabled || index == null || strike <= 0 || premNow <= 0) return;
        try {
            String type = direction >= 0 ? "CE" : "PE";
            Result r = evaluate(index, strike, type, premNow);
            if (!r.valid()) return;
            String tradeId = tag != null ? tag : "ENTRY";
            record("ENTRY", index, strike, type, tradeId, premNow, premNow, 0, 0, r, 0);
            if (r.fires()) {
                log.info("[OiDivergence][{}] ENTRY divergence PRESENT {} strike={}{} oiRise={}%/{}s premChg={}% "
                                + "(buying into writer buildup) — capture-only",
                        index, type, strike, type, fmt(r.oiRisePct()), windowSec, fmt(r.premChgPct()));
            }
        } catch (Exception e) {
            log.debug("[OiDivergence] evaluateEntry skipped: {}", e.toString());
        }
    }

    /**
     * A3 (2026-07-03) — entry-side tape confirmation for the OI-UNAVAILABLE path. When OI can't confirm an
     * entry, the substitute evidence is a same-strike VOLUME SURGE plus a directional PRICE THRUST (premium
     * rising for a long CE / a long PE in its favour direction — here we read the option's own premium, so
     * "rising premium" is the confirming thrust for either). Capture-only: records to
     * {@code oi-entry-confirm-shadow-<date>.csv} whether confirmation was present so the tuning loop can
     * measure whether REQUIRING it would filter weak no-OI entries. Never blocks — A1's score gate is the
     * live control. Exception-safe.
     *
     * @param direction +1 candidate long CE, -1 candidate long PE
     * @param entryCase the resolved entry case (e.g. CASE2_OPERATOR) — recorded for offline slicing
     */
    public boolean evaluateEntryConfirmation(IndexType index, int strike, int direction, double premNow,
                                             String tag, String entryCase) {
        if (!enabled || !entryConfirmEnabled || index == null || strike <= 0 || premNow <= 0) return false;
        try {
            String type = direction >= 0 ? "CE" : "PE";
            VolWindow vol = evaluateVolumeSurge(index, strike, type);
            // Directional price thrust: premium now vs the sample ~GIVEBACK_VOL_WINDOW_SEC ago (rising = confirming).
            Deque<Sample> dq = buffers.get(key(index, strike, type));
            double thrustPct = 0;
            boolean thrustValid = false;
            if (dq != null) {
                long now = Instant.now().getEpochSecond();
                Sample past = null;
                synchronized (dq) {
                    for (Sample s : dq) if (s.sec() <= now - GIVEBACK_VOL_WINDOW_SEC) past = s;
                }
                if (past != null && past.prem() > 0) {
                    thrustPct = 100.0 * (premNow - past.prem()) / past.prem();
                    thrustValid = true;
                }
            }
            boolean volSurge = vol.valid() && vol.surge();
            boolean priceThrust = thrustValid && thrustPct > 0;
            boolean wouldConfirm = volSurge && priceThrust;
            // SAFETY: only enforce a block when the tape is actually JUDGEABLE — a warm volume window, a
            // non-illiquid prior window (≥ minVolume), and a valid thrust read. On a cold buffer / illiquid
            // strike we CANNOT judge, so we NEVER block (A1's score gate remains the control) — no cold-start
            // over-blocking. Block only on positive dis-confirmation (judgeable AND not confirmed).
            boolean judgeable = vol.valid() && vol.priorVol() >= minVolume && thrustValid;
            boolean block = !entryConfirmShadow && judgeable && !wouldConfirm;
            recordEntryConfirmRow(index, strike, type, tag, entryCase, premNow, vol, thrustPct,
                    volSurge, priceThrust, wouldConfirm);
            log.info("[OiDivergence][{}] ENTRY-CONFIRM {} strike={}{} case={} volRatio={} thrust={}% "
                            + "volSurge={} priceThrust={} confirm={} judgeable={} → {}",
                    index, type, strike, type, entryCase, fmt(vol.ratio()), fmt(thrustPct),
                    volSurge, priceThrust, wouldConfirm, judgeable,
                    block ? "BLOCK" : (entryConfirmShadow && judgeable && !wouldConfirm ? "would-block(shadow)" : "allow"));
            return block;
        } catch (Exception e) {
            log.debug("[OiDivergence] evaluateEntryConfirmation skipped: {}", e.toString());
            return false; // never block on error
        }
    }

    private void recordEntryConfirmRow(IndexType index, int strike, String type, String tag, String entryCase,
                                       double premNow, VolWindow vol, double thrustPct, boolean volSurge,
                                       boolean priceThrust, boolean wouldConfirm) {
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("oi-entry-confirm-shadow-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            String row = System.currentTimeMillis() + "," + index.name() + "," + strike + "," + type + ","
                    + (tag != null ? tag : "ENTRY") + "," + (entryCase != null ? entryCase : "") + ","
                    + fmt(premNow) + "," + fmt(vol.ratio()) + "," + vol.recentVol() + "," + vol.priorVol() + ","
                    + fmt(thrustPct) + "," + volSurge + "," + priceThrust + "," + wouldConfirm + "\n";
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(ENTRY_CONFIRM_HEADER);
                w.write(row);
            }
        } catch (IOException e) {
            log.debug("[OiDivergence] recordEntryConfirmRow failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Clear per-trade confirmation state when a position closes (called from the exit path). */
    public void onPositionClosed(String tradeId) {
        if (tradeId == null) return;
        exitConfirms.remove(tradeId);
        lastWriteSec.remove(tradeId);
        entryCaptured.remove(tradeId);
        givebackConfirms.remove(tradeId);
        lastGivebackWriteSec.remove(tradeId);
    }

    // ── recording ──────────────────────────────────────────────────────────

    /** Throttle repeated EXIT would-fires for the same trade to ≤1 row / 15s while the fire persists. */
    private void recordThrottled(String phase, IndexType index, int strike, String type, String tradeId,
                                 double entryPrice, double premNow, double profitPct, long heldSec,
                                 Result r, int confirms) {
        long now = Instant.now().getEpochSecond();
        Long last = lastWriteSec.get(tradeId);
        if (last != null && now - last < 15) return;
        lastWriteSec.put(tradeId, now);
        record(phase, index, strike, type, tradeId, entryPrice, premNow, profitPct, heldSec, r, confirms);
    }

    private void record(String phase, IndexType index, int strike, String type, String tradeId,
                        double entryPrice, double premNow, double profitPct, long heldSec,
                        Result r, int confirms) {
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("oi-divergence-shadow-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            String row = System.currentTimeMillis() + "," + phase + "," + index.name() + "," + strike + ","
                    + type + "," + tradeId + "," + fmt(entryPrice) + "," + fmt(premNow) + ","
                    + fmt(profitPct) + "," + heldSec + "," + r.oiNow() + "," + fmt(r.oiRisePct()) + ","
                    + fmt(r.premChgPct()) + "," + confirms + "," + r.fires() + "," + shadow + "\n";
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(HEADER);
                w.write(row);
            }
        } catch (IOException e) {
            log.debug("[OiDivergence] record failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Throttle repeated PEAK_GIVEBACK would-fires for the same trade to ≤1 row / 15s. */
    private void recordGivebackThrottled(IndexType index, int strike, String type, String tradeId,
                                         double entryPrice, double peakPremium, double premNow,
                                         double peakPct, double profitPct, double pullbackPct,
                                         long heldSec, Result divergence, VolWindow volWindow,
                                         int confirms, boolean fires) {
        long now = Instant.now().getEpochSecond();
        Long last = lastGivebackWriteSec.get(tradeId);
        if (last != null && now - last < 15) return;
        lastGivebackWriteSec.put(tradeId, now);
        recordGiveback(index, strike, type, tradeId, entryPrice, peakPremium, premNow, peakPct, profitPct,
                pullbackPct, heldSec, divergence, volWindow, confirms, fires);
    }

    private void recordGiveback(IndexType index, int strike, String type, String tradeId,
                                double entryPrice, double peakPremium, double premNow,
                                double peakPct, double profitPct, double pullbackPct, long heldSec,
                                Result divergence, VolWindow volWindow, int confirms, boolean fires) {
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("oi-peak-giveback-shadow-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            String row = System.currentTimeMillis() + "," + index.name() + "," + strike + "," + type + ","
                    + tradeId + "," + fmt(entryPrice) + "," + fmt(peakPremium) + "," + fmt(premNow) + ","
                    + fmt(peakPct) + "," + fmt(profitPct) + "," + fmt(pullbackPct) + "," + heldSec + ","
                    + fmt(divergence.oiRisePct()) + "," + fmt(divergence.premChgPct()) + ","
                    + fmt(volWindow.ratio()) + "," + volWindow.recentVol() + "," + volWindow.priorVol() + ","
                    + confirms + "," + fires + "," + givebackShadow + "\n";
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(GIVEBACK_HEADER);
                w.write(row);
            }
        } catch (IOException e) {
            log.debug("[OiDivergence] recordGiveback failed (non-fatal): {}", e.getMessage());
        }
    }

    private static String key(IndexType index, int strike, String type) {
        return index.name() + "|" + strike + "|" + type;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
