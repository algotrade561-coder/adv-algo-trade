package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.oimomentum.OiMomentumEntryDiagnostics;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.adapter.TuningCaptureAdapter;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 2 adapter for {@link StrategyType#OI_MOMENTUM}.
 *
 * <p>OI Momentum is a HIGH-cadence (1 Hz) strategy with rich evaluation diagnostics —
 * five bucket dimensions worth declaring for analyzer auto-breakdowns. The adapter
 * itself is metadata; the actual {@link SignalEvent} construction happens in
 * {@link #buildSignalEvent}, called from {@code OIMomentumStrategy} at its existing
 * legacy signal-recording site (dual-write).</p>
 *
 * <h2>Bucket dimensions declared</h2>
 * <ol>
 *   <li>{@code entryCase} — categorical (CASE0_OI_LED, CASE1_M+OI+PCR, CASE2_M+PCR,
 *       CASE3_M+OI, SPIKE, RANGE_FADE, etc.).</li>
 *   <li>{@code biasScore} — numeric, banded 0/20/30/40/50/60/70/80.</li>
 *   <li>{@code matrixCase} — categorical (CASE1, CASE2, CASE3, SPIKE, CASE0, …).</li>
 *   <li>{@code momentumType} — categorical (30M_HIGH_BREAK, 30M_LOW_BREAK, 15M_*, 5M_*).</li>
 *   <li>{@code timeOfDayMode} — categorical (OPENING_DRIVE, TREND_FOLLOW, MIDDAY_DISCIPLINE,
 *       AFTERNOON_POSITION, LAST_HOUR, EOD_SQUEEZE_ONLY).</li>
 * </ol>
 *
 * <h2>Episode window</h2>
 * 60s — matches the legacy {@code RejectEpisodeAggregator} value tested in production.
 */
@Component
public class OiMomentumCaptureAdapter implements TuningCaptureAdapter {

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.HIGH;
    }

    @Override
    public int defaultEpisodeWindowSec() {
        return 60;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("entryCase", "entryCase", null, BandStyle.CATEGORICAL),
                new BucketDimension("biasScore", "biasScore",
                        List.of(0.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0),
                        BandStyle.NUMERIC_RANGE),
                new BucketDimension("matrixCase", "matrixCase", null, BandStyle.CATEGORICAL),
                new BucketDimension("momentumType", "momentumType", null, BandStyle.CATEGORICAL),
                new BucketDimension("timeOfDayMode", "timeOfDayMode", null, BandStyle.CATEGORICAL)
        );
    }

    /**
     * Allow-list of blocker prefixes whose colon-suffix carries pure timing detail
     * (e.g. {@code sl_cooldown:42s}) and should be stripped for bucketing. Other
     * colon-bearing labels (like {@code low_bias:38<40 [...]}) keep their full text
     * because the post-colon content is semantically distinct, not a timer.
     */
    private static final java.util.Set<String> TIMING_BLOCKER_PREFIXES = java.util.Set.of(
            "sl_cooldown", "spike_dedupe");

    @Override
    public String normalizeBlocker(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        // Strip variant suffixes after pipe (e.g. "matrix_skip:CASE5_SKIP|CASE5_PCR_VS_MOMENTUM"
        // → "matrix_skip:CASE5_SKIP"). Keep the primary token; details stay in attr_extra.
        int pipe = raw.indexOf('|');
        if (pipe > 0) {
            return raw.substring(0, pipe);
        }
        // Strip trailing timer detail only for known timing-prefix blockers
        // (e.g. "sl_cooldown:42s" → "sl_cooldown"). Other colon-bearing labels
        // like "low_bias:38<40 [...]" stay verbatim — the post-colon content is
        // semantic, not a timer.
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String prefix = raw.substring(0, colon);
            if (TIMING_BLOCKER_PREFIXES.contains(prefix)) {
                return prefix;
            }
        }
        return raw;
    }

    // ── Event construction helpers ────────────────────────────────────────

    /**
     * Builds a {@link SignalEvent} from the same data the legacy
     * {@code OiMomentumTuneRecorder.recordBuy} consumes — called from
     * {@code OIMomentumStrategy} at its existing fire site so the new pipeline gets
     * an identical view of the decision.
     */
    public SignalEvent buildSignalEvent(StrategyDecision decision,
                                         OiMomentumEntryDiagnostics diag,
                                         IndexType index,
                                         BigDecimal entryPremium,
                                         String correlationKey) {
        int strike = decision.selectedStrike().map(BigDecimal::intValue).orElse(0);
        OptionType optionType = decision.optionType().orElse(OptionType.CE);
        String instrumentKey = decision.selectedInstrumentKey().orElse("");

        return new SignalEvent(
                decision.timestamp() != null ? decision.timestamp() : Instant.now(),
                Instant.now(),
                StrategyType.OI_MOMENTUM,
                index,
                correlationKey,
                instrumentKey,
                strike,
                optionType,
                entryPremium,
                signalAttributes(diag)
        );
    }

    /**
     * Maps {@link OiMomentumEntryDiagnostics} into a {@link SignalEvent#attributes}
     * map. Public + exposed separately so unit tests can assert the mapping without
     * constructing a full {@link StrategyDecision}.
     */
    public Map<String, Object> signalAttributes(OiMomentumEntryDiagnostics diag) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (diag == null) return a;
        // Hot dimensions (will become flat columns in Phase 6 promotion):
        putIfPresent(a, "entryCase", diag.entryCase());
        // 2026-06-01: read biasScore from the diagnostics record (was hardcoded 0 — bug).
        a.put("biasScore", diag.biasScore());
        putIfPresent(a, "matrixCase", diag.matrixCase());
        putIfPresent(a, "momentumType", diag.momentumType());
        putIfPresent(a, "timeOfDayMode", diag.timeOfDayMode());
        // Detection path (LEGACY / SPIKE / CASE0 / RANGE_FADE / V3 / …) — lets tuning
        // split signals by which detector produced them.
        putIfPresent(a, "entryPath", diag.entryPath());
        // Operator-framework score (when set via withScores).
        a.put("operatorScore", diag.operatorScore());
        // Cold diagnostics (stay in JSON sidecar):
        a.put("momentumDir", diag.momentumDir());
        a.put("momentumMagnitudePct", diag.momentumMagnitudePct());
        a.put("oiDir", diag.oiDir());
        a.put("pcrDir", diag.pcrDir());
        a.put("pcr", diag.pcr());
        a.put("ceOiChange", diag.ceOiChange());
        a.put("peOiChange", diag.peOiChange());
        a.put("oiAvailable", diag.oiAvailable());
        a.put("spot", diag.spot());
        a.put("atm", diag.atm());
        a.put("vix", diag.vix());
        a.put("daysToExpiry", diag.daysToExpiry());
        a.put("expiryDay", diag.expiryDay());
        a.put("paperTrading", diag.paperTrading());
        a.put("oiAdvanced", diag.oiAdvanced());
        a.put("rangePct30m", diag.rangePct30m());
        putIfPresent(a, "signalReason", diag.signalReason());
        putIfPresent(a, "blockDetail", diag.blockDetail());
        putIfPresent(a, "spikeEpisodeId", diag.spikeEpisodeId());
        a.put("atmCeLast", diag.atmCeLast());
        a.put("atmPeLast", diag.atmPeLast());
        a.put("spot30mHigh", diag.spot30mHigh());
        a.put("spot30mLow", diag.spot30mLow());
        a.put("breakoutDistancePct", diag.breakoutDistancePct());
        return a;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * Builds an {@link EvaluationEvent} from an {@link EpisodeAggregator.EpisodeRow}
     * emitted when a dedup episode closes (key change, window expiry, or explicit
     * flush). The event represents a {@link EvaluationOutcome#BLOCKED} outcome — all
     * legacy {@code recordReject} calls map to BLOCKED in the unified model.
     */
    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, OiMomentumEntryDiagnostics> row) {
        OiMomentumEntryDiagnostics diag = row.firstPayload();
        Map<String, Object> attrs = signalAttributes(diag);
        // Episode-specific attributes
        attrs.put("episodeFirstAt", row.firstAt().toString());
        attrs.put("episodeLastAt", row.lastAt().toString());
        attrs.put("rawBlocker", row.dedupKey());
        // Correlation key uses a stable hash of (index, blocker, first-at) so the same
        // episode can be re-derived from raw events for joins.
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (row.streamKey().name() + ":" + row.dedupKey() + ":" + row.firstAt().toEpochMilli())
                        .hashCode(), 16);
        return new EvaluationEvent(
                row.firstAt(),
                Instant.now(),
                StrategyType.OI_MOMENTUM,
                row.streamKey(),
                episodeId,
                EvaluationOutcome.BLOCKED,
                row.dedupKey(),
                row.tickCount(),
                attrs
        );
    }

    /**
     * Builds an {@link ExitEvent} from a closed {@link TradeEntity}, the
     * {@link MaeMfeTracker.Snapshot} captured over the trade's lifetime, and the
     * exit reason. {@code correlationKey} should be the original signal's
     * {@code decisionKey} so the analyzer can join entry↔exit↔forward checkpoints.
     */
    public ExitEvent buildExitEvent(IndexType index,
                                     TradeEntity trade,
                                     MaeMfeTracker.Snapshot snapshot,
                                     String correlationKey,
                                     BigDecimal exitPrice,
                                     String exitReason,
                                     boolean reversal) {
        BigDecimal entryPrice = trade.getEntryPrice() != null
                ? trade.getEntryPrice() : BigDecimal.ZERO;
        double realizedPnlPct = 0.0;
        if (entryPrice.signum() > 0 && exitPrice != null) {
            realizedPnlPct = exitPrice.subtract(entryPrice)
                    .divide(entryPrice, java.math.MathContext.DECIMAL64)
                    .doubleValue() * 100.0;
        }
        long holdSec = 0;
        if (trade.getEntryTime() != null) {
            // Clamp at 0 — a future-dated entry (or a clock skew at exit time) must not
            // produce a negative duration. ExitEvent's compact constructor rejects
            // negative holdSec.
            holdSec = Math.max(0L,
                    Duration.between(trade.getEntryTime(), Instant.now()).getSeconds());
        }

        double maePct = snapshot != null ? snapshot.maePct() : 0.0;
        double mfePct = snapshot != null ? snapshot.mfePct() : 0.0;
        long timeToMaeSec = snapshot != null ? snapshot.timeToMaeSec() : 0L;
        long timeToMfeSec = snapshot != null ? snapshot.timeToMfeSec() : 0L;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("instrumentKey", trade.getInstrumentKey());
        attrs.put("quantity", trade.getQuantity());
        attrs.put("optionType", trade.getOptionType());
        if (snapshot != null) {
            attrs.put("tickCount", snapshot.tickCount());
            if (snapshot.spotAtMae() > 0) attrs.put("spotAtMae", snapshot.spotAtMae());
            if (snapshot.spotAtMfe() > 0) attrs.put("spotAtMfe", snapshot.spotAtMfe());
        }

        return new ExitEvent(
                Instant.now(),
                Instant.now(),
                StrategyType.OI_MOMENTUM,
                index,
                correlationKey != null ? correlationKey : trade.getTradeId(),
                trade.getTradeId(),
                exitReason != null ? exitReason : "UNKNOWN",
                entryPrice,
                exitPrice != null ? exitPrice : BigDecimal.ZERO,
                realizedPnlPct,
                holdSec,
                maePct,
                mfePct,
                timeToMaeSec,
                timeToMfeSec,
                reversal,
                attrs);
    }
}
