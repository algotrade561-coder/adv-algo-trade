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
 * <p>Per-evaluation diagnostics flow through the unified tuning pipeline
 * ({@code tuning/} → {@code OiMomentumCaptureAdapter}). The legacy
 * {@code V3DecisionRecorder} CSV recorder was removed in Phase 6 of the
 * Signal Capture &amp; Tuning redesign.</p>
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
                            OIMomentumConfig oiConfig) {
        this.regimeClassifier = regimeClassifier;
        this.marketContext = marketContext;
        this.chainSignalAnalyzer = chainSignalAnalyzer;
        this.gates = gates;
        this.picker = picker;
        this.sizer = sizer;
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
            return V3EntryDecision.skip("no_momentum");
        }

        // P0b: null snapshot → warn (throttled) + SKIP
        if (snapshot == null) {
            warnNullSnapshot(ix);
            return V3EntryDecision.skip("null_snapshot");
        }

        Set<Regime> regimes = regimeClassifier.classify(ix);
        TimeOfDayMode mode = TimeOfDayMode.classify(evaluationMarketTime());
        if (!mode.allowsEntry()) {
            return V3EntryDecision.skip("time_mode:" + mode.name());
        }

        // ── OI signal ──
        OiSignal signal = chainSignalAnalyzer.analyze(ix, snapshot);
        if (!signal.isActionable()) {
            return V3EntryDecision.skip("oi:" + signal.label());
        }
        if (signal.direction() != momentumDir) {
            return V3EntryDecision.skip("oi_direction_vs_momentum");
        }

        // ── Strike picker (P0a: must run BEFORE G4) ──
        int[] walls = marketContext.gammaWalls(ix);
        int maxPain = marketContext.maxPainStrike(ix);
        boolean isExpiry = regimes.contains(Regime.EXPIRY_DAY);
        List<MultiStrikePicker.Candidate> ranked = picker.rankCandidates(ix, snapshot, signal,
                momentumDir, walls[0], walls[1], maxPain, isExpiry);
        if (ranked.isEmpty()) {
            return V3EntryDecision.skip("no_viable_strike");
        }

        // ── LIQUIDITY_REROUTE (review P1): try candidates in order; pick first that
        // gives a G4-passing verdict. Stop after MAX_REROUTE_ATTEMPTS to bound cost. ──
        MultiStrikePicker.Candidate chosen = null;
        OperatorFilterGate.Verdict verdict = null;
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
                if (i > 0) {
                    log.info("[V3] LIQUIDITY_REROUTE: candidate #{} ({} strike={}) passed G4 "
                            + "after top-{} rejected", i + 1, c.roleTag(), c.strike(), i);
                }
                break;
            }
        }
        // If no candidate cleared G4, fall back to top-ranked and let the gate-threshold
        // check below register the SKIP.
        if (chosen == null) {
            chosen = ranked.get(0);
            verdict = gates.evaluate(ix, signal, momentumDir, marketContext, regimes,
                    spot, null);
        }

        // G4 is mandatory for entry — 3-of-4 threshold must not admit a strike that
        // failed liquidity after LIQUIDITY_REROUTE exhausted all candidates.
        if (!verdict.g4Pass()) {
            return V3EntryDecision.skip("g4_liquidity:" + verdict.g4Reason());
        }

        // ── P1c: EOD_SQUEEZE_ONLY pattern + ATM-strike constraint ──
        int atm = snapshot.atmStrike();
        if (mode == TimeOfDayMode.EOD_SQUEEZE_ONLY) {
            if (!signal.isSqueeze()) {
                return V3EntryDecision.skip("eod_squeeze_only:non_squeeze_pattern");
            }
            if (chosen.strike() != atm) {
                return V3EntryDecision.skip(
                        "eod_squeeze_only:non_atm_strike=" + chosen.strike());
            }
        }

        // ── Gate threshold check (with MIDDAY_DISCIPLINE SQUEEZE carve-out) ──
        int required = mode.requiredGates();
        if (mode == TimeOfDayMode.MIDDAY_DISCIPLINE && signal.isSqueeze()) required = 3;
        if (!verdict.meetsThreshold(required)) {
            return V3EntryDecision.skip(
                    "gates_below_threshold:" + verdict.passedCount() + "of4 req=" + required);
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
            return V3EntryDecision.skip("sized_zero:" + sized.breakdown());
        }

        return V3EntryDecision.enter(chosen.strike(), chosen.optionType(),
                sized.lots(), signal, verdict, mode, regimes, pattern, sized.convictionCapped());
    }

    // ── Internal helpers ───────────────────────────────────────────────────

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
}
