package com.algo.trade.marketdata;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PCR + Max Pain Tracker — calculates put-call ratio and max pain strike per index.
 *
 * Provides:
 * - PCR (put-call ratio by OI): > 1.0 = put-heavy (bullish), < 1.0 = call-heavy (bearish)
 * - Max Pain strike: the strike where option writers have minimum liability
 * - PCR shift alerts: when PCR changes direction significantly
 *
 * Recalculates every 15 minutes from the latest option chain snapshot.
 */
@Component
public class PCRMaxPainTracker {

    private static final Logger log = LoggerFactory.getLogger(PCRMaxPainTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final OptionChainCollector optionChainCollector;

    public record PCRSnapshot(
            UnderlyingSymbol underlying,
            double pcr,
            String bias,            // "BULLISH", "BEARISH", "NEUTRAL"
            BigDecimal maxPainStrike,
            BigDecimal spotPrice,
            double distanceToMaxPainPercent,
            long totalCallOI,
            long totalPutOI
    ) {}

    private final Map<UnderlyingSymbol, PCRSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UnderlyingSymbol, Double> previousPcr = new ConcurrentHashMap<>();

    // Configurable thresholds
    private static final double PCR_BULLISH_THRESHOLD = 1.05;
    private static final double PCR_BEARISH_THRESHOLD = 0.95;
    private static final double PCR_SHIFT_ALERT_THRESHOLD = 0.15; // alert if PCR changes > 0.15 in one cycle

    public PCRMaxPainTracker(OptionChainCollector optionChainCollector) {
        this.optionChainCollector = optionChainCollector;
    }

    /**
     * Recalculate PCR and Max Pain every 15 minutes during market hours.
     */
    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public void recalculate() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 20)) || now.isAfter(LocalTime.of(15, 30))) return;

        for (UnderlyingSymbol underlying : UnderlyingSymbol.values()) {
            try {
                OptionChainSnapshot chain = optionChainCollector.collectForDisplay(underlying);
                if (chain == null || chain.levels().isEmpty()) continue;

                long totalCallOI = 0, totalPutOI = 0;
                for (OptionChainLevel level : chain.levels()) {
                    totalCallOI += level.callOpenInterest();
                    totalPutOI += level.putOpenInterest();
                }

                double pcr = totalCallOI > 0 ? (double) totalPutOI / totalCallOI : 1.0;
                String bias;
                if (pcr > PCR_BULLISH_THRESHOLD) bias = "BULLISH";
                else if (pcr < PCR_BEARISH_THRESHOLD) bias = "BEARISH";
                else bias = "NEUTRAL";

                // Calculate max pain
                BigDecimal maxPainStrike = calculateMaxPain(chain.levels());
                BigDecimal spot = chain.underlyingPrice();
                double distancePercent = spot.signum() > 0
                        ? maxPainStrike.subtract(spot).divide(spot, MC).doubleValue() * 100
                        : 0;

                PCRSnapshot snapshot = new PCRSnapshot(
                        underlying, pcr, bias, maxPainStrike, spot,
                        distancePercent, totalCallOI, totalPutOI);
                snapshots.put(underlying, snapshot);

                // Check for significant PCR shift
                Double prevPcr = previousPcr.get(underlying);
                if (prevPcr != null && Math.abs(pcr - prevPcr) > PCR_SHIFT_ALERT_THRESHOLD) {
                    log.info("[PCR] {} PCR shifted: {:.2f} → {:.2f} (bias={})",
                            underlying, prevPcr, pcr, bias);
                }
                previousPcr.put(underlying, pcr);

            } catch (Exception e) {
                log.debug("[PCR] Calculation failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    /**
     * Calculate max pain strike — the strike where total option buyer loss is maximized
     * (equivalently, where option writer liability is minimized).
     */
    private BigDecimal calculateMaxPain(List<OptionChainLevel> levels) {
        if (levels.isEmpty()) return BigDecimal.ZERO;

        BigDecimal minPain = null;
        BigDecimal maxPainStrike = levels.get(0).strike();

        for (OptionChainLevel testStrike : levels) {
            BigDecimal totalPain = BigDecimal.ZERO;

            for (OptionChainLevel level : levels) {
                // Call holder pain: max(0, strike - settlementPrice) × OI
                BigDecimal callPain = level.strike().subtract(testStrike.strike()).max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(level.callOpenInterest()));
                // Put holder pain: max(0, settlementPrice - strike) × OI
                BigDecimal putPain = testStrike.strike().subtract(level.strike()).max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(level.putOpenInterest()));
                totalPain = totalPain.add(callPain).add(putPain);
            }

            if (minPain == null || totalPain.compareTo(minPain) < 0) {
                minPain = totalPain;
                maxPainStrike = testStrike.strike();
            }
        }

        return maxPainStrike;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<PCRSnapshot> getSnapshot(UnderlyingSymbol underlying) {
        return Optional.ofNullable(snapshots.get(underlying));
    }

    public double getPCR(UnderlyingSymbol underlying) {
        PCRSnapshot snap = snapshots.get(underlying);
        return snap != null ? snap.pcr() : 1.0;
    }

    public String getBias(UnderlyingSymbol underlying) {
        PCRSnapshot snap = snapshots.get(underlying);
        return snap != null ? snap.bias() : "NEUTRAL";
    }

    public BigDecimal getMaxPainStrike(UnderlyingSymbol underlying) {
        PCRSnapshot snap = snapshots.get(underlying);
        return snap != null ? snap.maxPainStrike() : BigDecimal.ZERO;
    }

    public boolean isBullish(UnderlyingSymbol underlying) {
        return "BULLISH".equals(getBias(underlying));
    }

    public boolean isBearish(UnderlyingSymbol underlying) {
        return "BEARISH".equals(getBias(underlying));
    }

    public Map<UnderlyingSymbol, PCRSnapshot> getAllSnapshots() {
        return Map.copyOf(snapshots);
    }
}
