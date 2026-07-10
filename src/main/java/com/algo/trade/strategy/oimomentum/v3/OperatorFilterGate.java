package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 OPERATOR — Four-gate entry filter (G1 OI Confluence, G2 Structural, G3 Volatility, G4 Liquidity).
 *
 * <p>Each gate returns pass/fail with a reason. The overall {@link Verdict} carries the
 * count of passed gates so downstream sizing can grade conviction.</p>
 *
 * <p>Stateless. Thread-safe.</p>
 */
@Component
public class OperatorFilterGate {

    private static final Logger log = LoggerFactory.getLogger(OperatorFilterGate.class);

    /** Maximum bid-ask spread % allowed at G4. */
    public static final double MAX_SPREAD_PCT = 3.0;

    /** Throttle per-index COLD_START_OK warning so operators see at most one per 10 min. */
    private final Map<IndexType, Instant> lastColdStartWarn = new ConcurrentHashMap<>();
    private static final Duration COLD_START_WARN_COOLDOWN = Duration.ofMinutes(10);

    /** Verdict from running all four gates. */
    public record Verdict(
            boolean g1Pass, String g1Reason,
            boolean g2Pass, String g2Reason,
            boolean g3Pass, String g3Reason,
            boolean g4Pass, String g4Reason,
            int passedCount
    ) {
        public boolean meetsThreshold(int requiredGates) { return passedCount >= requiredGates; }
        public String summary() {
            return String.format("g1=%s g2=%s g3=%s g4=%s passed=%d/4",
                    g1Pass ? "✓" : "✗", g2Pass ? "✓" : "✗",
                    g3Pass ? "✓" : "✗", g4Pass ? "✓" : "✗", passedCount);
        }
    }

    /**
     * Evaluate all four gates and return a verdict.
     *
     * @param ix             index
     * @param oiSignal       result of ChainSignalAnalyzer
     * @param momentumDir    momentum direction (+1 / −1 / 0)
     * @param ctx            market context
     * @param regimes        active regime tags
     * @param spot           current spot
     * @param candidateQuote candidate option quote for G4 (may be null)
     */
    public Verdict evaluate(IndexType ix, OiSignal oiSignal, int momentumDir,
                            MarketContextService ctx, Set<Regime> regimes,
                            double spot, Quote candidateQuote) {
        // ── G1: OI Confluence ──────────────────────────────────────────────
        boolean g1Pass;
        String g1Reason;
        if (!oiSignal.isActionable()) {
            g1Pass = false;
            g1Reason = "no_pattern:" + oiSignal.label();
        } else if (oiSignal.direction() != momentumDir) {
            g1Pass = false;
            g1Reason = "pattern_vs_momentum:" + oiSignal.label() + "(oi=" + oiSignal.direction()
                    + ",mom=" + momentumDir + ")";
        } else {
            g1Pass = true;
            g1Reason = oiSignal.label();
        }

        // ── G2: Structural Context (gamma walls + VWAP + max-pain + PCR slope) ─
        int[] walls = ctx.gammaWalls(ix);
        boolean g2Pass = true;
        StringBuilder g2 = new StringBuilder();
        // Gamma wall proximity: only block if wall is VERY close (< 0.15% from spot).
        // Walls at 0.15-0.30% are "soft resistance" — informational, not a veto.
        // Previous threshold (0.30%) was too aggressive and blocked normal intraday noise.
        if (momentumDir > 0 && walls[0] > 0) {
            double distPct = (walls[0] - spot) / spot * 100.0;
            g2.append("wallAbove=").append(walls[0]).append("(").append(String.format("%.2f", distPct)).append("%)");
            if (distPct < 0.15) { g2Pass = false; g2.append("[BLOCK]"); }
            else if (distPct < 0.30) { g2.append("[SOFT]"); }
        } else if (momentumDir < 0 && walls[1] > 0) {
            double distPct = (spot - walls[1]) / spot * 100.0;
            g2.append("wallBelow=").append(walls[1]).append("(").append(String.format("%.2f", distPct)).append("%)");
            if (distPct < 0.15) { g2Pass = false; g2.append("[BLOCK]"); }
            else if (distPct < 0.30) { g2.append("[SOFT]"); }
        }
        // VWAP alignment (unless OI signal is a SQUEEZE — those can go counter-VWAP)
        if (g2Pass && !oiSignal.isSqueeze()) {
            int vwapAlign = ctx.spotVsVwap(ix, spot);
            if (vwapAlign != 0 && vwapAlign != momentumDir) {
                g2Pass = false;
                g2.append(",vwap_against(=").append(vwapAlign).append(")");
            } else {
                g2.append(",vwap=").append(vwapAlign);
            }
        }
        // PCR slope confirmation (review #47):
        // PCR rising → put-writing dominating → bullish bias. Falling → bearish.
        // Only veto if slope STRONGLY opposes momentum (|slope| > 0.03).
        // Previous threshold (0.05) was correct for clear opposition; lowered to 0.03
        // to catch more subtle slope disagreements. Values < 0.03 are noise — ignored.
        double pcrSlope = ctx.pcrSlope5Min(ix);
        if (Math.abs(pcrSlope) > 0.03) {
            int pcrDir = pcrSlope > 0 ? +1 : -1;
            g2.append(",pcrSlope=").append(String.format("%+.3f", pcrSlope)).append("(dir=").append(pcrDir).append(")");
            if (g2Pass && !oiSignal.isSqueeze() && pcrDir != momentumDir) {
                g2Pass = false;
                g2.append("[PCR_AGAINST]");
            }
        } else if (Math.abs(pcrSlope) > 0.001) {
            // Noise-range slope: record but don't gate
            g2.append(",pcrSlope=").append(String.format("%+.3f", pcrSlope)).append("[NOISE_IGNORE]");
        }
        // Max-pain alignment on 0-1 DTE (informational only otherwise)
        if (regimes.contains(Regime.EXPIRY_DAY)) {
            int mp = ctx.maxPainStrike(ix);
            g2.append(",maxPain=").append(mp);
        }
        // FII/DII bias (review #55): surfaced for end-of-day analysis only — informational,
        // does not gate. Treated as a directional bias overlay rather than a hard signal
        // since FII data is published with a one-day lag.
        double fii = ctx.getFiiNetCrore();
        double dii = ctx.getDiiNetCrore();
        if (fii != 0 || dii != 0) {
            int fiiBias = fii > 500 ? +1 : fii < -500 ? -1 : 0;
            g2.append(",fii=").append(String.format("%.0f", fii))
              .append(",dii=").append(String.format("%.0f", dii))
              .append(",fiiBias=").append(fiiBias);
        }
        String g2Reason = g2.length() == 0 ? "no_walls" : g2.toString();

        // ── G3: Volatility Regime — multi-signal long-vol gate (review #44) ──
        // Requires AT LEAST ONE positive long-vol signal. IV > 85 is a WARNING
        // (premium expensive) but NOT a hard veto — strong G1+G2+G4 conviction should
        // still allow entries with expensive IV (the direction signal matters more than
        // getting cheap premium). VIX crush (dropping > 5% intraday) remains a hard veto.
        //
        //   POSITIVE  (any one passes the gate):
        //     P1. IV percentile < 50 (genuinely cheap)
        //     P2. VIX % change vs session open ≥ +3%
        //     P3. VIX session percentile ≥ VIX_SESSION_ELEVATED_PERCENTILE
        //     P4. Expiry day (gamma rules)
        //     P5. IV percentile 50–85 (neutral — not cheap but not expensive)
        //
        //   NEGATIVE (hard veto):
        //     N1. VIX dropping > 5% intraday (vol-crush regime)
        //
        //   WARNING (logged, does NOT veto):
        //     W1. IV percentile > 85 (premium expensive — noted but allowed)
        boolean g3Pass = false;
        boolean g3Veto = false;
        StringBuilder g3 = new StringBuilder();
        double ivPct = ctx.ivPercentile(ix);
        g3.append("ivPct=").append(String.format("%.0f", ivPct));
        if (ivPct > 0 && ivPct < 50 && ivPct != 50) { g3Pass = true; g3.append("[CHEAP]"); }
        else if (ivPct > 85) { g3.append("[EXPENSIVE_WARN]"); } // Warning only, not a veto
        else if (ivPct > 0 && ivPct != 50 && ivPct <= 85) { g3Pass = true; g3.append("[NEUTRAL_OK]"); }

        double vixPctVsOpen = ctx.vixPctVsSessionOpen();
        g3.append(",vixVsOpen=").append(String.format("%+.2f%%", vixPctVsOpen));
        if (vixPctVsOpen >= 3.0) { g3Pass = true; g3.append("[VIX_EXP]"); }
        if (vixPctVsOpen <= -5.0) { g3Veto = true; g3.append("[VIX_CRUSH]"); }

        // VIX_HIGH semantics (review #53): vixSessionPercentile ≥ 70 means *within today's
        // rolling 15-min VIX window* the current VIX is in the top 30% — i.e. vol is
        // EXPANDING intraday. For long-premium momentum this is favorable (IV likely
        // continues expanding), not "cheap entry". A LOW session VIX percentile
        // intentionally does NOT pass G3 here — that path is covered by ivPercentile<50
        // (genuinely cheap premium). Document this if anyone wonders why a calm session
        // doesn't auto-pass on percentile alone.
        double vixSessPct = ctx.vixSessionPercentile();
        g3.append(",vixSessPct=").append(String.format("%.0f", vixSessPct));
        if (vixSessPct >= MarketContextService.VIX_SESSION_ELEVATED_PERCENTILE) {
            g3Pass = true; g3.append("[VIX_HIGH]");
        }

        if (regimes.contains(Regime.EXPIRY_DAY)) {
            g3Pass = true; g3.append(",expiry[OK]");
        }
        // Fallback for cold-start mid-day: when both ivPct sentinel (50) and
        // vixSessPct sentinel (-1) AND no expansion seen AND not expiry,
        // allow G3 to pass — but log the cold-start so operator knows.
        boolean coldStart = (ivPct == 50.0) && (vixSessPct < 0) && (vixPctVsOpen == 0)
                && !regimes.contains(Regime.EXPIRY_DAY);
        if (coldStart && !g3Veto) {
            g3Pass = true; g3.append("[COLD_START_OK]");
            // Review #54: throttled WARN so the operator knows context is stale.
            Instant last = lastColdStartWarn.get(ix);
            Instant now = Instant.now();
            if (last == null || Duration.between(last, now).compareTo(COLD_START_WARN_COOLDOWN) >= 0) {
                lastColdStartWarn.put(ix, now);
                log.warn("[V3] G3 COLD_START_OK for {} — context (IV/VIX) not yet primed; "
                        + "ivPct sentinel=50, vixSessPct sentinel=-1, vixVsOpen=0. "
                        + "Once V3ContextFeeder + snapshots accumulate ~15 min of data, "
                        + "G3 will use real values.", ix);
            }
        }
        if (g3Veto) g3Pass = false;  // Veto trumps positives
        String g3Reason = g3.toString();

        // ── G4: Liquidity ──────────────────────────────────────────────────
        // FIX (review P0): a missing/empty quote is NOT a pass. Without bid/ask we
        // cannot verify liquidity, so default to FAIL. The pipeline's LIQUIDITY_REROUTE
        // logic then tries the next-ranked candidate.
        boolean g4Pass;
        String g4Reason;
        if (candidateQuote == null) {
            g4Pass = false;
            g4Reason = "no_quote_available";
        } else if (candidateQuote.bid().isEmpty() || candidateQuote.ask().isEmpty()
                || candidateQuote.bid().get().signum() <= 0 || candidateQuote.ask().get().signum() <= 0) {
            g4Pass = false;
            g4Reason = "no_bidask_data";
        } else {
            double bid = candidateQuote.bid().get().doubleValue();
            double ask = candidateQuote.ask().get().doubleValue();
            double mid = (bid + ask) / 2.0;
            double spreadPct = (ask - bid) / mid * 100.0;
            g4Pass = spreadPct <= MAX_SPREAD_PCT;
            g4Reason = String.format("spreadPct=%.2f%s", spreadPct, g4Pass ? "[OK]" : "[WIDE]");
        }

        int passedCount = (g1Pass ? 1 : 0) + (g2Pass ? 1 : 0) + (g3Pass ? 1 : 0) + (g4Pass ? 1 : 0);
        return new Verdict(g1Pass, g1Reason, g2Pass, g2Reason, g3Pass, g3Reason,
                g4Pass, g4Reason, passedCount);
    }
}
