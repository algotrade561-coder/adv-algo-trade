package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/**
 * Feature 6 support — convergence tracking.
 *
 * <p>Maintains a per-underlying ring of the last N observed spot prices. {@link
 * #isConverging} returns true when a trend-fitted linear regression on (sampleIndex,
 * distance-to-target) shows a strictly negative slope — i.e. distance is shrinking on
 * average across the window, not just at the endpoints. This avoids the false-positive
 * the legacy first-vs-last check produced when spot touched the target then bounced away.
 */
@Component
public class ShiftTrapConvergenceTracker {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapConvergenceTracker.class);
    private static final int WINDOW_SIZE = 5;

    private final Map<UnderlyingSymbol, Deque<Double>> spotHistory = new EnumMap<>(UnderlyingSymbol.class);

    public void recordSpot(UnderlyingSymbol underlying, BigDecimal spot) {
        if (underlying == null || spot == null || spot.signum() <= 0) {
            return;
        }
        Deque<Double> window = window(underlying);
        synchronized (window) {
            window.addLast(spot.doubleValue());
            while (window.size() > WINDOW_SIZE) {
                window.removeFirst();
            }
        }
    }

    /**
     * Test whether the recorded spot history is, on average, drifting toward {@code
     * target} (slope of distance-vs-time strictly negative). Returns {@code false} on
     * fewer than 3 samples — the slope test needs at least 3 points to be meaningful.
     */
    public boolean isConverging(UnderlyingSymbol underlying, BigDecimal target) {
        if (underlying == null || target == null || target.signum() <= 0) {
            return false;
        }
        Deque<Double> window = window(underlying);
        Double[] snapshot;
        synchronized (window) {
            if (window.size() < 3) {
                return false;
            }
            snapshot = window.toArray(new Double[0]);
        }
        double slope = slopeOfDistance(snapshot, target.doubleValue());
        boolean converged = slope < 0;
        if (converged) {
            log.debug("[ShiftTrap] Convergence {}: target={} samples={} slope={}",
                    underlying, target, snapshot.length, String.format("%.4f", slope));
        }
        return converged;
    }

    /**
     * Slope of {@code distance-to-target vs sampleIndex}. Negative means distance is
     * shrinking (converging); positive means diverging. Returns 0 with insufficient data.
     */
    public double driftSlope(UnderlyingSymbol underlying, BigDecimal target) {
        if (underlying == null || target == null || target.signum() <= 0) {
            return 0.0;
        }
        Deque<Double> window = window(underlying);
        Double[] snapshot;
        synchronized (window) {
            if (window.size() < 3) {
                return 0.0;
            }
            snapshot = window.toArray(new Double[0]);
        }
        return slopeOfDistance(snapshot, target.doubleValue());
    }

    public void reset(UnderlyingSymbol underlying) {
        if (underlying == null) {
            return;
        }
        Deque<Double> window = window(underlying);
        synchronized (window) {
            window.clear();
        }
    }

    /**
     * Linear regression slope of distance-to-target across the window. Uses x = sample
     * index (0..n-1), y = |target - sample|. Returns 0 when variance is zero (all samples
     * identical) so the convergence check returns false in that degenerate case.
     */
    private static double slopeOfDistance(Double[] samples, double target) {
        int n = samples.length;
        double xBar = (n - 1) / 2.0;
        double yBar = 0.0;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = Math.abs(target - samples[i]);
            yBar += y[i];
        }
        yBar /= n;
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = i - xBar;
            num += dx * (y[i] - yBar);
            den += dx * dx;
        }
        return den == 0 ? 0.0 : num / den;
    }

    private Deque<Double> window(UnderlyingSymbol underlying) {
        synchronized (spotHistory) {
            return spotHistory.computeIfAbsent(underlying, k -> new ArrayDeque<>(WINDOW_SIZE));
        }
    }
}
