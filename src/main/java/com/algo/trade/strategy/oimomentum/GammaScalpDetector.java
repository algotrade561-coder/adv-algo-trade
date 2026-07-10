package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gamma Scalp Detector — detects operator gamma scalping near expiry.
 *
 * <p>Near expiry, operators scalp gamma by rapidly buying/selling ATM options. This creates
 * alternating OI bursts + premium velocity spikes within minutes. The pattern:</p>
 * <ol>
 *   <li>Premium velocity spike in one direction (operator opens)</li>
 *   <li>Premium stalls or reverses (operator books profit)</li>
 *   <li>Premium velocity spikes again in same or opposite direction (next scalp)</li>
 * </ol>
 *
 * <p>When ≥3 alternating premium velocity bursts occur within 10 minutes on an expiry day,
 * it signals active gamma scalping. The bot can ride the latest direction.</p>
 *
 * <h2>Signal</h2>
 * Outputs the LATEST direction of the premium velocity burst with confidence based on
 * burst count and gamma amplification factor.
 */
@Component
public class GammaScalpDetector {

    private static final Logger log = LoggerFactory.getLogger(GammaScalpDetector.class);

    private final PremiumVelocityTracker premiumVelocityTracker;
    private final ExpiryCalendar expiryCalendar;

    @Value("${oi-momentum.gamma-scalp.enabled:true}")
    private boolean enabled;

    @Value("${oi-momentum.gamma-scalp.min-bursts:3}")
    private int minBursts;

    @Value("${oi-momentum.gamma-scalp.burst-threshold-pct:3.0}")
    private double burstThresholdPct;

    @Value("${oi-momentum.gamma-scalp.window-minutes:10}")
    private int windowMinutes;

    private final Map<IndexType, ScalpState> states = new ConcurrentHashMap<>();

    public record GammaSignal(
            int direction,       // latest burst direction (+1 CE, -1 PE)
            double confidence,   // 0.5–1.0
            int burstCount,      // bursts in window
            double amplification, // gamma amplification factor
            String detail
    ) {
        public static final GammaSignal NONE = new GammaSignal(0, 0, 0, 0, "");
        public boolean isActive() { return direction != 0 && confidence >= 0.5; }
    }

    private static class ScalpState {
        final Deque<PremBurst> bursts = new ArrayDeque<>();
        volatile GammaSignal lastSignal = GammaSignal.NONE;
        volatile Instant lastSignalTime = Instant.EPOCH;
        volatile int lastBurstDirection = 0;
        volatile Instant lastBurstTime = null;
    }

    private record PremBurst(int direction, double velocityPct, double amplification, Instant time) {}

    public GammaScalpDetector(PremiumVelocityTracker premiumVelocityTracker,
                              ExpiryCalendar expiryCalendar) {
        this.premiumVelocityTracker = premiumVelocityTracker;
        this.expiryCalendar = expiryCalendar;
    }

    // ── Public API ──

    public GammaSignal getSignal(IndexType indexType) {
        if (!enabled) return GammaSignal.NONE;
        // Only active on expiry days (gamma scalping is an expiry phenomenon)
        if (!expiryCalendar.isExpiryDay(indexType)) return GammaSignal.NONE;
        ScalpState state = states.get(indexType);
        if (state == null) return GammaSignal.NONE;
        if (state.lastSignal.isActive()
                && Duration.between(state.lastSignalTime, Instant.now()).toMinutes() < 3) {
            return state.lastSignal;
        }
        return GammaSignal.NONE;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Tick — called every second from the strategy. Checks premium velocity for burst events
     * and evaluates whether the gamma scalp pattern is active.
     */
    public void tick(IndexType indexType) {
        if (!enabled) return;
        if (!expiryCalendar.isExpiryDay(indexType)) return;

        ScalpState state = states.computeIfAbsent(indexType, k -> new ScalpState());
        Instant now = Instant.now();

        // Get current premium velocity
        PremiumVelocityTracker.PremiumVelocity premVel = premiumVelocityTracker.getVelocity(indexType);
        if (!premVel.hasSignal()) return;

        double vel = premVel.maxPremiumVelocityPct();
        int dir = premVel.direction();

        // Detect a burst: velocity above threshold AND direction changed or fresh burst
        if (vel >= burstThresholdPct && dir != 0) {
            // Debounce: don't register same-direction burst within 30s
            if (state.lastBurstTime != null && dir == state.lastBurstDirection
                    && Duration.between(state.lastBurstTime, now).getSeconds() < 30) {
                return;
            }

            state.bursts.addLast(new PremBurst(dir, vel, premVel.amplification(), now));
            state.lastBurstDirection = dir;
            state.lastBurstTime = now;
        }

        // Expire old bursts
        Instant windowStart = now.minus(Duration.ofMinutes(windowMinutes));
        while (!state.bursts.isEmpty() && state.bursts.peekFirst().time().isBefore(windowStart)) {
            state.bursts.pollFirst();
        }

        // Evaluate: enough bursts = gamma scalping pattern
        if (state.bursts.size() >= minBursts) {
            PremBurst latest = state.bursts.peekLast();
            double avgAmplification = state.bursts.stream()
                    .mapToDouble(PremBurst::amplification).average().orElse(1.0);
            double confidence = Math.min(1.0, 0.4 + state.bursts.size() * 0.12);

            String detail = String.format("GAMMA_SCALP(%d bursts in %dmin, amp=%.1fx, latestDir=%s)",
                    state.bursts.size(), windowMinutes, avgAmplification,
                    latest.direction() > 0 ? "BULL" : "BEAR");

            state.lastSignal = new GammaSignal(latest.direction(), confidence,
                    state.bursts.size(), avgAmplification, detail);
            state.lastSignalTime = now;

            log.debug("[GammaScalp][{}] {}", indexType, detail);
        }
    }
}
