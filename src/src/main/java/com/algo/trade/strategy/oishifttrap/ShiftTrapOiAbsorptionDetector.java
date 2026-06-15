package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 8 — OI Absorption detector.
 *
 * <p>When spot aggressively breaks through a strike that holds heavy OI <em>and</em> OI on
 * that strike is still <em>increasing</em>, the writers aren't being squeezed — they're
 * absorbing the move and likely doubling down. The squeeze thesis is broken; the strategy
 * should NOT enter on the side that was being shorted.
 *
 * <p>State per underlying: the last few option-chain snapshots, so we can compare current OI
 * to OI from a few minutes ago and detect a break-through that came with OI build.
 */
@Component
public class ShiftTrapOiAbsorptionDetector {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapOiAbsorptionDetector.class);
    private static final int HISTORY_SIZE = 5;

    public enum Decision { ALLOW, OVERRIDE_NO_ENTRY }

    private final Map<UnderlyingSymbol, Deque<OptionChainSnapshot>> history =
            new EnumMap<>(UnderlyingSymbol.class);

    /** Strategy calls this once per evaluation tick to grow the rolling history. */
    public void recordSnapshot(UnderlyingSymbol underlying, OptionChainSnapshot snapshot) {
        if (underlying == null || snapshot == null) {
            return;
        }
        Deque<OptionChainSnapshot> dq = bucket(underlying);
        synchronized (dq) {
            dq.addLast(snapshot);
            while (dq.size() > HISTORY_SIZE) {
                dq.removeFirst();
            }
        }
    }

    /**
     * Inspect the current snapshot for the absorption pattern.
     *
     * @param wallOiThreshold      minimum OI on the candidate strike to count as a "wall"
     * @return {@link Decision#OVERRIDE_NO_ENTRY} when absorption is detected; otherwise {@link Decision#ALLOW}.
     */
    public Decision check(UnderlyingSymbol underlying, OptionChainSnapshot current, BigDecimal spot,
                           int trendDirection, double wallOiThreshold) {
        if (underlying == null || current == null || spot == null || spot.signum() <= 0) {
            return Decision.ALLOW;
        }
        Deque<OptionChainSnapshot> dq = bucket(underlying);
        OptionChainSnapshot[] past;
        synchronized (dq) {
            if (dq.size() < 2) {
                return Decision.ALLOW;
            }
            past = dq.toArray(new OptionChainSnapshot[0]);
        }
        // Compare current vs. the oldest cached snapshot.
        OptionChainSnapshot earlier = past[0];
        BigDecimal earlierSpot = earlier.underlyingPrice();
        if (earlierSpot == null || earlierSpot.signum() <= 0) {
            return Decision.ALLOW;
        }
        double current_ = spot.doubleValue();
        double earlier_ = earlierSpot.doubleValue();

        for (OptionChainLevel level : current.levels()) {
            long ceOi = level.callOpenInterest();
            long peOi = level.putOpenInterest();
            double strike = level.strike().doubleValue();
            // Case A: bullish break-through call wall (spot crossed UP through a heavy CE strike).
            if (trendDirection >= 0 && ceOi >= wallOiThreshold) {
                if (earlier_ < strike && current_ >= strike) {
                    long ceOiThen = oiForStrike(earlier, level.strike(), true);
                    if (ceOiThen > 0 && ceOi >= ceOiThen) {
                        log.debug("[ShiftTrap] Absorption (CE) at strike={} oiNow={} oiThen={} spotNow={} spotThen={}",
                                level.strike(), ceOi, ceOiThen, spot, earlierSpot);
                        return Decision.OVERRIDE_NO_ENTRY;
                    }
                }
            }
            // Case B: bearish break-through put wall (spot crossed DOWN through a heavy PE strike).
            if (trendDirection <= 0 && peOi >= wallOiThreshold) {
                if (earlier_ > strike && current_ <= strike) {
                    long peOiThen = oiForStrike(earlier, level.strike(), false);
                    if (peOiThen > 0 && peOi >= peOiThen) {
                        log.debug("[ShiftTrap] Absorption (PE) at strike={} oiNow={} oiThen={} spotNow={} spotThen={}",
                                level.strike(), peOi, peOiThen, spot, earlierSpot);
                        return Decision.OVERRIDE_NO_ENTRY;
                    }
                }
            }
        }
        return Decision.ALLOW;
    }

    public void reset(UnderlyingSymbol underlying) {
        if (underlying == null) {
            return;
        }
        Deque<OptionChainSnapshot> dq = bucket(underlying);
        synchronized (dq) {
            dq.clear();
        }
    }

    private static long oiForStrike(OptionChainSnapshot snapshot, BigDecimal strike, boolean call) {
        if (snapshot == null || strike == null) {
            return 0L;
        }
        List<OptionChainLevel> levels = snapshot.levels();
        for (OptionChainLevel l : levels) {
            if (l.strike().compareTo(strike) == 0) {
                return call ? l.callOpenInterest() : l.putOpenInterest();
            }
        }
        return 0L;
    }

    private Deque<OptionChainSnapshot> bucket(UnderlyingSymbol underlying) {
        synchronized (history) {
            return history.computeIfAbsent(underlying, k -> new ArrayDeque<>(HISTORY_SIZE));
        }
    }
}
