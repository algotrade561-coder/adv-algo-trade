package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Head-to-head shadow recorder — scores {@link SyntheticOiVelocityDetector} (volume-based) against
 * {@link OiVelocityEarlyDetector} (OI-sampled-fast) on the only thing that matters: did each one's
 * directional call agree with the <em>next actual OI print</em>?
 *
 * <h2>Why</h2>
 * The two detectors aim at the same goal (an early directional read) from different inputs. The open
 * question is whether sampling OI faster ({@code OiVelocityEarlyDetector}) adds anything the
 * volume-based proxy ({@code SyntheticOiVelocityDetector}) doesn't already capture — given NSE only
 * refreshes OI every ~3 minutes. This recorder lets you answer it from your own data before
 * retiring either module.
 *
 * <h2>How</h2>
 * Between two consecutive ATM-band OI prints (an "interval"), it keeps each detector's most-recent
 * directional call. When the next print lands, it computes the realized OI-change direction over
 * that interval (OiPattern semantics: bullish = PE building / CE unwinding; bearish = the reverse)
 * and writes one row pairing both predictions with the realized direction and a hit flag each.
 *
 * <p>Output: {@code data/tuning/oi-prediction-shadow-<date>.csv}. Low volume (~1 row/index per OI
 * print ≈ 1 row/index/3min). Exception-safe; runs on the Spring scheduler; never trades; default OFF.
 * For a fair comparison BOTH detectors must be enabled so their {@code getLatest()} is populated.</p>
 */
@Component
public class OiPredictionShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiPredictionShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Max interval length to score — beyond this the resolve spanned a session gap and is dropped. */
    private static final long MAX_RESOLVE_HORIZON_SEC = 900; // 15 min
    private static final String HEADER =
            "predictTime,resolveTime,index,intervalSec,synthDir,synthConf,synthVol,synthHit,"
            + "earlyDir,earlyVelPct,earlyHit,ceChg,peChg,realizedOiDir\n";

    private final LiveInstrumentCache cache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SyntheticOiVelocityDetector syntheticDetector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiVelocityEarlyDetector earlyDetector;

    @Value("${oi-prediction-shadow.enabled:false}")
    private boolean enabled;

    @Value("${oi-prediction-shadow.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private String underlyingsCsv;

    @Value("${oi-prediction-shadow.strikes-around:3}")
    private int strikesAround;

    private volatile Set<String> underlyings = Set.of("NIFTY", "BANKNIFTY", "SENSEX");
    private final ConcurrentHashMap<IndexType, Interval> state = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay = LocalDate.now(IST);

    public OiPredictionShadowRecorder(LiveInstrumentCache cache) {
        this.cache = cache;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            this.underlyings = Set.of(underlyingsCsv.toUpperCase(Locale.ROOT).replace(" ", "").split(","));
        } catch (Exception ignore) { /* keep default */ }
        if (enabled) {
            log.info("[OiPredShadow] enabled underlyings={} band=±{} — logs synthetic vs early vs realized OI print",
                    underlyings, strikesAround);
        }
    }

    /** Per-index interval: the open prediction window between two OI prints. */
    private static final class Interval {
        long baseCeOi = -1, basePeOi = -1;   // band OI at the start of the current interval
        long lastCeOi = -1, lastPeOi = -1;   // last observed band OI (print detection)
        Instant startedAt;
        // most-recent directional calls seen during this interval
        int synthDir, synthConf;
        long synthVol;
        int earlyDir;
        double earlyVel;
    }

    @Scheduled(fixedDelayString = "${oi-prediction-shadow.sample-interval-ms:5000}", initialDelay = 25_000)
    public void tick() {
        if (!enabled) return;
        try {
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(currentDay)) { state.clear(); currentDay = today; }
            for (OptionInstrument o : cache.allOptions()) {
                if (o == null || o.getIndexType() == null) continue;
                if (underlyings.contains(o.getIndexType().name())) {
                    // touch the map so every configured+subscribed index gets a state entry
                    state.computeIfAbsent(o.getIndexType(), k -> new Interval());
                }
            }
            for (var e : state.entrySet()) {
                evaluate(e.getKey(), e.getValue());
            }
        } catch (Exception ex) {
            log.debug("[OiPredShadow] tick skipped: {}", ex.toString());
        }
    }

    private void evaluate(IndexType index, Interval iv) {
        double spot = cache.getFuturesPrice(index);
        if (spot <= 0) return;
        int atm = index.roundToATM(spot);
        int interval = index.strikeInterval();
        int lo = atm - strikesAround * interval, hi = atm + strikesAround * interval;

        long ceOi = 0, peOi = 0;
        for (OptionInstrument o : cache.allOptions()) {
            if (o.getIndexType() != index) continue;
            int strike = o.getStrikePrice();
            if (strike < lo || strike > hi) continue;
            if ("CE".equalsIgnoreCase(o.getOptionType())) ceOi += o.getOpenInterest();
            else peOi += o.getOpenInterest();
        }
        if (ceOi <= 0 && peOi <= 0) return;

        // First observation for this index — open the first interval, nothing to resolve yet.
        if (iv.startedAt == null) {
            openInterval(iv, ceOi, peOi);
            return;
        }

        boolean printed = (ceOi != iv.lastCeOi) || (peOi != iv.lastPeOi);
        if (printed) {
            // Resolve the just-ended interval against the realized OI-change direction.
            long ceChg = ceOi - iv.baseCeOi;
            long peChg = peOi - iv.basePeOi;
            int realized = oiPattern(ceChg, peChg);
            // Data quality: discard resolves whose interval spanned a long gap (overnight carryover,
            // lunch lull, feed stall). These produced intervalSec up to ~32,000s and polluted the
            // hit-rate stats with stale, cross-session comparisons. Only score intraday-cadence prints.
            long resolveSec = iv.startedAt != null
                    ? Duration.between(iv.startedAt, Instant.now()).getSeconds() : 0;
            if (realized != 0 && resolveSec <= MAX_RESOLVE_HORIZON_SEC) {
                writeRow(index, iv, ceChg, peChg, realized);
            }
            // Start the next interval; predictions reset to the current reads.
            openInterval(iv, ceOi, peOi);
        } else {
            // Mid-interval: keep the most-recent directional call from each detector.
            capturePredictions(index, iv);
        }
    }

    /** Reset the interval baseline + capture the detectors' current calls as the opening prediction. */
    private void openInterval(Interval iv, long ceOi, long peOi) {
        iv.baseCeOi = ceOi; iv.basePeOi = peOi;
        iv.lastCeOi = ceOi; iv.lastPeOi = peOi;
        iv.startedAt = Instant.now();
        iv.synthDir = 0; iv.synthConf = 0; iv.synthVol = 0;
        iv.earlyDir = 0; iv.earlyVel = 0;
    }

    /** Overwrite with the latest confident call from each detector (so the print resolves the freshest read). */
    private void capturePredictions(IndexType index, Interval iv) {
        if (syntheticDetector != null) {
            SyntheticOiVelocityDetector.Signal s = syntheticDetector.getLatest(index);
            if (s != null && s.direction() != 0) {
                iv.synthDir = s.direction();
                iv.synthConf = s.confidence();
                iv.synthVol = s.intervalVolume();
            }
        }
        if (earlyDetector != null) {
            EarlyDetectionShadowRecorder.VirtualSignal v = earlyDetector.getLatest(index);
            // only count an early call that was emitted within the current interval
            if (v != null && v.direction() != 0 && v.at() != null
                    && iv.startedAt != null && !v.at().isBefore(iv.startedAt)) {
                iv.earlyDir = v.direction();
                iv.earlyVel = v.oiVelocityPct();
            }
        }
    }

    /** OiPattern direction: bullish = PE building + CE unwinding; bearish = the reverse. */
    private static int oiPattern(long ceChg, long peChg) {
        double bull = Math.max(0, peChg) + Math.max(0, -ceChg);
        double bear = Math.max(0, ceChg) + Math.max(0, -peChg);
        return bull > bear ? 1 : (bear > bull ? -1 : 0);
    }

    private void writeRow(IndexType index, Interval iv, long ceChg, long peChg, int realized) {
        String synthHit = iv.synthDir == 0 ? "" : (iv.synthDir == realized ? "1" : "0");
        String earlyHit = iv.earlyDir == 0 ? "" : (iv.earlyDir == realized ? "1" : "0");
        long intervalSec = iv.startedAt != null
                ? Duration.between(iv.startedAt, Instant.now()).getSeconds() : 0;
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("oi-prediction-shadow-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            synchronized (this) {
                try (FileWriter w = new FileWriter(file.toFile(), true)) {
                    if (fresh) w.write(HEADER);
                    w.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d,%s,%d,%.2f,%s,%d,%d,%d%n",
                            iv.startedAt, Instant.now(), index.name(), intervalSec,
                            iv.synthDir, iv.synthConf, iv.synthVol, synthHit,
                            iv.earlyDir, iv.earlyVel, earlyHit, ceChg, peChg, realized));
                }
            }
        } catch (Exception e) {
            log.debug("[OiPredShadow] write failed: {}", e.getMessage());
        }
    }
}
