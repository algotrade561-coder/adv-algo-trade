package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.strategy.oimomentum.OIMomentumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * V3 OPERATOR — Entry pipeline façade.
 *
 * <p>Order of operations (fixed in P0a):
 * <ol>
 *   <li>Mode + Regime classification.</li>
 *   <li>Chain OI signal detection (G1 source).</li>
 *   <li>G1 OI confluence + G2 Structural + G3 Volatility — these three do not need the
 *       picked strike. They run early to short-circuit on bad days.</li>
 *   <li>Multi-strike picker — chooses the candidate strike.</li>
 *   <li>G4 Liquidity — evaluated on the picked candidate's live quote, NOT the ATM
 *       quote. This was P0a in the audit review.</li>
 *   <li>EOD_SQUEEZE_ONLY constraint enforcement (pattern must be SQUEEZE, strike must
 *       be ATM). This was P1c.</li>
 *   <li>Conviction sizing.</li>
 * </ol>
 *
 * <p>A {@link V3DecisionRecord} is written for each evaluation when this pipeline runs
 * (requires {@code oi-momentum.v3-enabled=true}).</p>
 */
@Component
public class V3EntryPipeline {

    private static final Logger log = LoggerFactory.getLogger(V3EntryPipeline.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Test-only override for wall-clock IST used by {@link TimeOfDayMode#classify(LocalTime)}.
     * Package-visible so {@code V3EntryPipelineRerouteTest} can pin OPENING_DRIVE hours.
     */
    static volatile LocalTime evaluationTimeOverride = null;

    static LocalTime evaluationMarketTime() {
        LocalTime override = evaluationTimeOverride;
        return override != null ? override : LocalTime.now(IST);
    }

    /** Quote resolver — caller supplies one that maps (index, strike, side) → live Quote. */
    @FunctionalInterface
    public interface QuoteResolver {
        Quote resolve(IndexType ix, int strike, OptionType ot);
    }

    private final RegimeClassifier regimeClassifier;
    private final MarketContextService marketContext;
    private final ChainSignalAnalyzer chainSignalAnalyzer;
    private final OperatorFilterGate gates;
    private final MultiStrikePicker picker;
    private final ConvictionSizer sizer;
    private final V3DecisionRecorder recorder;
    private final OIMomentumConfig oiConfig;

    /**
     * Source of the {@code maxLotsPerTrade} ceiling fed into the conviction sizer.
     * Optional so unit tests that wire V3EntryPipeline without the full Spring
     * context don't need to provide it — when null, the sizer's cap defaults to
     * the {@code baseLotCount} (i.e. no upsizing — the legacy single-lot floor).
     */
    @Autowired(required = false)
    private GlobalConfigService globalConfigService;

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    /** Per-index throttle for null-snapshot warnings (one alert / 30 min). */
    private final ConcurrentHashMap<IndexType, Instant> lastNullSnapshotAlert = new ConcurrentHashMap<>();
    private static final Duration NULL_SNAPSHOT_ALERT_COOLDOWN = Duration.ofMinutes(30);

    @Autowired
    public V3EntryPipeline(RegimeClassifier regimeClassifier,
                            MarketContextService marketContext,
                            ChainSignalAnalyzer chainSignalAnalyzer,
                            OperatorFilterGate gates,
                            MultiStrikePicker picker,
                            ConvictionSizer sizer,
                            V3DecisionRecorder recorder,
                            OIMomentumConfig oiConfig) {
        this.regimeClassifier = regimeClassifier;
        this.marketContext = marketContext;
        this.chainSignalAnalyzer = chainSignalAnalyzer;
        this.gates = gates;
        this.picker = picker;
        this.sizer = sizer;
        this.recorder = recorder;
        this.oiConfig = oiConfig;
    }

    /**
     * Evaluate the V3 entry pipeline for a momentum signal on an index.
     *
     * @param ix              index
     * @param momentumDir     momentum direction (+1, −1, 0)
     * @param momentumType    momentum trigger label (e.g. "30M_HIGH_BREAK")
     * @param momentumMagPct  realised magnitude pct
     * @param spot            current spot
     * @param vix             current VIX
     * @param snapshot        latest chain snapshot (may be null)
     * @param quoteResolver   how to fetch a live quote for the picked strike (P0a fix)
     * @param baseLots        baseline LOT COUNT (typically 1) to feed the conviction
     *                        sizer. The sizer scales this up by conviction and caps
     *                        at {@code globalConfig.maxLotsPerTrade}. Callers convert
     *                        the returned lot count to share count by multiplying by
     *                        {@code indexType.lotSize()} before sending to the broker.
     */
    public V3EntryDecision evaluate(IndexType ix,
                                     int momentumDir, String momentumType, double momentumMagPct,
                                     double spot, double vix,
                                     ChainSnapshot snapshot, QuoteResolver quoteResolver,
                                     int baseLots) {
        if (momentumDir == 0) {
            // Review nit #52: telemetry parity — record CSV row for no_momentum skip too.
            V3EntryDecision dec = V3EntryDecision.skip("no_momentum");
            try {
                record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                        Set.of(Regime.NORMAL), TimeOfDayMode.classify(evaluationMarketTime()),
                        OiSignal.EMPTY, emptyVerdict(), baseLots, dec));
            } catch (Exception ignored) {}
            return dec;
        }

        // P0b: null snapshot → warn (throttled) + SKIP
        if (snapshot == null) {
            warnNullSnapshot(ix);
            V3EntryDecision dec = V3EntryDecision.skip("null_snapshot");
            record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    Set.of(Regime.NORMAL), TimeOfDayMode.classify(evaluationMarketTime()),
                    OiSignal.EMPTY, emptyVerdict(), baseLots, dec));
            return dec;
        }

        Set<Regime> regimes = regimeClassifier.classify(ix);
        TimeOfDayMode mode = TimeOfDayMode.classify(evaluationMarketTime());
        if (!mode.allowsEntry()) {
            V3EntryDecision dec = V3EntryDecision.skip("time_mode:" + mode.name());
            record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, OiSignal.EMPTY, emptyVerdict(), baseLots, dec));
            return dec;
        }

        // ── OI signal ──
        OiSignal signal = chainSignalAnalyzer.analyze(ix, snapshot);
        if (!signal.isActionable()) {
            V3EntryDecision dec = V3EntryDecision.skip("oi:" + signal.label());
            record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal,
                    gates.evaluate(ix, signal, momentumDir, marketContext, regimes, spot, null),
                    baseLots, dec));
            return dec;
        }
        if (signal.direction() != momentumDir) {
            V3EntryDecision dec = V3EntryDecision.skip("oi_direction_vs_momentum");
            record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal,
                    gates.evaluate(ix, signal, momentumDir, marketContext, regimes, spot, null),
                    baseLots, dec));
            return dec;
        }

        // ── Strike picker (P0a: must run BEFORE G4) ──
        int[] walls = marketContext.gammaWalls(ix);
        int maxPain = marketContext.maxPainStrike(ix);
        boolean isExpiry = regimes.contains(Regime.EXPIRY_DAY);
        List<MultiStrikePicker.Candidate> ranked = picker.rankCandidates(ix, snapshot, signal,
                momentumDir, walls[0], walls[1], maxPain, isExpiry);
        if (ranked.isEmpty()) {
            V3EntryDecision dec = V3EntryDecision.skip("no_viable_strike");
            record(buildSkipRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal,
                    gates.evaluate(ix, signal, momentumDir, marketContext, regimes, spot, null),
                    baseLots, dec));
            return dec;
        }

        // ── LIQUIDITY_REROUTE (review P1): try candidates in order; pick first that
        // gives a G4-passing verdict. Stop after MAX_REROUTE_ATTEMPTS to bound cost. ──
        MultiStrikePicker.Candidate chosen = null;
        OperatorFilterGate.Verdict verdict = null;
        Quote pickedQuote = null;
        int rerouteAttempts = 0;
        boolean rerouteSucceeded = false;
        // Go-live safety: capped at 1 attempt until production data validates
        // non-ATM picks (TRAP / GAMMA_WALL / MAX_PAIN). Bump to 3 once V3 has shown
        // it picks sensible alternatives.
        final int MAX_REROUTE_ATTEMPTS = Math.min(1, ranked.size());
        for (int i = 0; i < MAX_REROUTE_ATTEMPTS; i++) {
            MultiStrikePicker.Candidate c = ranked.get(i);
            Quote q = null;
            try {
                if (quoteResolver != null) {
                    q = quoteResolver.resolve(ix, c.strike(), c.optionType());
                }
            } catch (Exception qre) {
                log.debug("[V3] picked-strike quote resolve failed for {} {}{}: {}",
                        ix, c.strike(), c.optionType(), qre.getMessage());
            }
            OperatorFilterGate.Verdict v = gates.evaluate(ix, signal, momentumDir,
                    marketContext, regimes, spot, q);
            if (v.g4Pass()) {
                chosen = c;
                verdict = v;
                pickedQuote = q;
                rerouteSucceeded = i > 0;
                if (i > 0) {
                    log.info("[V3] LIQUIDITY_REROUTE: candidate #{} ({} strike={}) passed G4 "
                            + "after top-{} rejected", i + 1, c.roleTag(), c.strike(), i);
                }
                break;
            }
            rerouteAttempts++;
        }
        // If no candidate cleared G4, fall back to top-ranked and let the gate-threshold
        // check below register the SKIP — that way the diagnostic record reflects the
        // best-effort candidate (rather than synthesising one). rerouteSucceeded stays
        // false so CSV consumers can distinguish "first candidate worked" from "all failed".
        if (chosen == null) {
            chosen = ranked.get(0);
            verdict = gates.evaluate(ix, signal, momentumDir, marketContext, regimes,
                    spot, null);
        }
        final int rerouteCount = rerouteAttempts;
        final boolean rerouteOk = rerouteSucceeded;

        // G4 is mandatory for entry — 3-of-4 threshold must not admit a strike that
        // failed liquidity after LIQUIDITY_REROUTE exhausted all candidates.
        if (!verdict.g4Pass()) {
            V3EntryDecision dec = V3EntryDecision.skip("g4_liquidity:" + verdict.g4Reason());
            record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal, verdict, chosen, null, baseLots, dec,
                    rerouteCount, rerouteOk));
            return dec;
        }

        // ── P1c: EOD_SQUEEZE_ONLY pattern + ATM-strike constraint ──
        int atm = snapshot.atmStrike();
        if (mode == TimeOfDayMode.EOD_SQUEEZE_ONLY) {
            if (!signal.isSqueeze()) {
                V3EntryDecision dec = V3EntryDecision.skip("eod_squeeze_only:non_squeeze_pattern");
                record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                        regimes, mode, signal, verdict, chosen, null, baseLots, dec,
                        rerouteCount, rerouteOk));
                return dec;
            }
            if (chosen.strike() != atm) {
                V3EntryDecision dec = V3EntryDecision.skip(
                        "eod_squeeze_only:non_atm_strike=" + chosen.strike());
                record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                        regimes, mode, signal, verdict, chosen, null, baseLots, dec,
                        rerouteCount, rerouteOk));
                return dec;
            }
        }

        // ── Gate threshold check (with MIDDAY_DISCIPLINE SQUEEZE carve-out) ──
        int required = mode.requiredGates();
        if (mode == TimeOfDayMode.MIDDAY_DISCIPLINE && signal.isSqueeze()) required = 3;
        if (!verdict.meetsThreshold(required)) {
            V3EntryDecision dec = V3EntryDecision.skip(
                    "gates_below_threshold:" + verdict.passedCount() + "of4 req=" + required);
            record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal, verdict, chosen, null, baseLots, dec,
                    rerouteCount, rerouteOk));
            return dec;
        }

        // ── Conviction sizing ──
        OiPattern pattern = OiPattern.valueOf(signal.label());
        // Pull the global lot-count cap (default to baseLots when GlobalConfigService
        // isn't wired — keeps unit tests that construct V3EntryPipeline standalone
        // working without forcing them to provide the service).
        int maxLotsPerTrade = (globalConfigService != null)
                ? Math.max(1, globalConfigService.getMaxLotsPerTrade())
                : Math.max(1, baseLots);
        ConvictionSizer.SizingResult sized = sizer.size(ix, baseLots, maxLotsPerTrade,
                verdict.passedCount(), required, pattern, mode, regimes,
                marketContext.ivPercentile(ix));
        if (sized.lots() <= 0) {
            V3EntryDecision dec = V3EntryDecision.skip("sized_zero:" + sized.breakdown());
            record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                    regimes, mode, signal, verdict, chosen, sized, baseLots, dec,
                    rerouteCount, rerouteOk));
            return dec;
        }

        V3EntryDecision dec = V3EntryDecision.enter(chosen.strike(), chosen.optionType(),
                sized.lots(), signal, verdict, mode, regimes, pattern, sized.convictionCapped());
        record(buildFullRecord(ix, spot, vix, momentumDir, momentumType, momentumMagPct,
                regimes, mode, signal, verdict, chosen, sized, baseLots, dec,
                rerouteCount, rerouteOk));
        return dec;
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    /** Empty verdict used when we need to record a SKIP that happened before gates. */
    private OperatorFilterGate.Verdict emptyVerdict() {
        return new OperatorFilterGate.Verdict(false, "n/a", false, "n/a",
                false, "n/a", false, "n/a", 0);
    }

    /** Throttled WARN + Telegram when chain snapshot missing for an index. */
    private void warnNullSnapshot(IndexType ix) {
        Instant last = lastNullSnapshotAlert.get(ix);
        Instant now = Instant.now();
        if (last != null && Duration.between(last, now).compareTo(NULL_SNAPSHOT_ALERT_COOLDOWN) < 0) {
            return;
        }
        lastNullSnapshotAlert.put(ix, now);
        log.warn("[V3] null chain snapshot for {} — V3 will SKIP every entry attempt until "
                + "OptionChainSnapshotScheduler produces a snapshot. Check expiry resolution / "
                + "instrument subscription.", ix);
        if (telegramAlertService != null) {
            try {
                telegramAlertService.systemAlert("⚠️ V3 OPERATOR: no chain snapshot for "
                        + ix + " — all V3 entries will be skipped until capture resumes. "
                        + "Check ExpiryCalendar + LiveInstrumentCache subscription.");
            } catch (Exception alertEx) {
                log.debug("[V3] null-snapshot telegram failed: {}", alertEx.getMessage());
            }
        }
    }

    private void record(V3DecisionRecord r) {
        try { recorder.record(r); }
        catch (Exception e) { log.debug("[V3] decision record failed: {}", e.getMessage()); }
    }

    private V3DecisionRecord buildSkipRecord(IndexType ix, double spot, double vix,
                                              int momentumDir, String momentumType, double momMagPct,
                                              Set<Regime> regimes, TimeOfDayMode mode,
                                              OiSignal signal, OperatorFilterGate.Verdict verdict,
                                              int baseLots, V3EntryDecision dec) {
        int[] walls = marketContext.gammaWalls(ix);
        return new V3DecisionRecord(
                Instant.now(), ix, spot,
                (int) Math.round(spot / ix.strikeInterval()) * ix.strikeInterval(),
                vix, momentumDir, momentumType, momMagPct,
                regimes, mode,
                regimeClassifier.getDailyAtrPct(ix),
                regimeClassifier.getSessionOpen(ix),
                regimeClassifier.getPreviousClose(ix),
                marketContext.ivPercentile(ix),
                marketContext.vixSlope15Min(),
                marketContext.spotVsVwap(ix, spot),
                walls[0], walls[1],
                marketContext.maxPainStrike(ix),
                marketContext.pcrSlope5Min(ix),
                signal,
                verdict.g1Pass(), verdict.g1Reason(),
                verdict.g2Pass(), verdict.g2Reason(),
                verdict.g3Pass(), verdict.g3Reason(),
                verdict.g4Pass(), verdict.g4Reason(),
                verdict.passedCount(), mode.requiredGates(),
                0, "NONE", 0, 0, 0, 0,
                baseLots, 0, 0, "",
                dec.skip() ? "SKIP" : "ENTER", dec.reason(),
                0, false);
    }

    private V3DecisionRecord buildFullRecord(IndexType ix, double spot, double vix,
                                               int momentumDir, String momentumType, double momMagPct,
                                               Set<Regime> regimes, TimeOfDayMode mode,
                                               OiSignal signal, OperatorFilterGate.Verdict verdict,
                                               MultiStrikePicker.Candidate chosen,
                                               ConvictionSizer.SizingResult sized,
                                               int baseLots, V3EntryDecision dec,
                                               int rerouteAttempts, boolean rerouteSucceeded) {
        int[] walls = marketContext.gammaWalls(ix);
        int lots = sized != null ? sized.lots() : 0;
        double conv = sized != null ? sized.convictionCapped() : 0;
        String breakdown = sized != null ? sized.breakdown() : "";
        return new V3DecisionRecord(
                Instant.now(), ix, spot,
                (int) Math.round(spot / ix.strikeInterval()) * ix.strikeInterval(),
                vix, momentumDir, momentumType, momMagPct,
                regimes, mode,
                regimeClassifier.getDailyAtrPct(ix),
                regimeClassifier.getSessionOpen(ix),
                regimeClassifier.getPreviousClose(ix),
                marketContext.ivPercentile(ix),
                marketContext.vixSlope15Min(),
                marketContext.spotVsVwap(ix, spot),
                walls[0], walls[1],
                marketContext.maxPainStrike(ix),
                marketContext.pcrSlope5Min(ix),
                signal,
                verdict.g1Pass(), verdict.g1Reason(),
                verdict.g2Pass(), verdict.g2Reason(),
                verdict.g3Pass(), verdict.g3Reason(),
                verdict.g4Pass(), verdict.g4Reason(),
                verdict.passedCount(), mode.requiredGates(),
                chosen.strike(), chosen.roleTag(), chosen.delta(), chosen.openInterest(),
                chosen.impliedVol(), chosen.score(),
                baseLots, lots, conv, breakdown,
                dec.skip() ? "SKIP" : "ENTER", dec.reason(),
                rerouteAttempts, rerouteSucceeded);
    }
}
