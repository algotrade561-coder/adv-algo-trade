package com.algo.trade.indicator;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * OI + Price Action Filter — confirms breakouts by checking if OI shifts
 * align with the price direction.
 *
 * This is fundamentally different from OIDivergenceFilter:
 * - OIDivergenceFilter: checks if OI confirms a single candle's price move (micro)
 * - OIPriceActionFilter: snapshots aggregate OI every 5 minutes and checks if
 *   the cumulative OI shift supports a breakout direction (macro)
 *
 * Logic:
 *   BULLISH breakout confirmed when: CE OI at resistance strikes rises >= threshold
 *   BEARISH breakout confirmed when: PE OI at support strikes rises >= threshold
 *   Rejects false breakouts where price moves but OI doesn't support.
 *
 * Improvements over the external project version:
 * - Uses our LiveInstrumentCache instead of their InstrumentResolver
 * - Compares against 2nd-most-recent snapshot (not just previous) for noise reduction
 * - Configurable via application.yml
 * - Handles missing data gracefully (returns true = don't block)
 */
@Component
public class OIPriceActionFilter {

    private static final Logger log = LoggerFactory.getLogger(OIPriceActionFilter.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @Value("${oi-price-action.min-oi-change-percent:5.0}")
    private double minOiChangePercent;

    @Value("${oi-price-action.enabled:true}")
    private boolean enabled;

    @Value("${oi-price-action.max-snapshots:50}")
    private int maxSnapshots;

    private record OISnapshot(long totalCeOI, long totalPeOI, double spot, long timestampMs) {}

    private final ConcurrentHashMap<IndexType, Deque<OISnapshot>> oiHistory = new ConcurrentHashMap<>();

    public OIPriceActionFilter(LiveInstrumentCache liveInstrumentCache,
                                ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    /**
     * Capture OI snapshot every 5 minutes during market hours.
     * Aggregates total CE and PE OI across the nearest expiry strike chain.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void captureSnapshot() {
        if (!enabled || !liveInstrumentCache.isReady()) return;

        for (IndexType idx : IndexType.values()) {
            try {
                double spot = liveInstrumentCache.getFuturesPrice(idx);
                if (spot <= 0) continue;

                LocalDate expiry = expiryCalendar.getCurrentExpiry(idx);
                List<OptionInstrument> chain = liveInstrumentCache.getStrikeChain(idx, expiry);
                if (chain.isEmpty()) continue;

                // Aggregate OI for ATM ± 5 strikes (the liquid range)
                int atm = idx.roundToATM(spot);
                int interval = idx.strikeInterval();
                long ceOI = 0, peOI = 0;

                for (int i = -5; i <= 5; i++) {
                    int strike = atm + (i * interval);
                    var ceOpt = liveInstrumentCache.getOption(idx, strike, "CE", expiry);
                    var peOpt = liveInstrumentCache.getOption(idx, strike, "PE", expiry);
                    if (ceOpt.isPresent() && ceOpt.get().getOpenInterest() > 0) {
                        ceOI += ceOpt.get().getOpenInterest();
                    }
                    if (peOpt.isPresent() && peOpt.get().getOpenInterest() > 0) {
                        peOI += peOpt.get().getOpenInterest();
                    }
                }

                if (ceOI == 0 && peOI == 0) continue;

                Deque<OISnapshot> history = oiHistory.computeIfAbsent(idx, k -> new ConcurrentLinkedDeque<>());
                history.addFirst(new OISnapshot(ceOI, peOI, spot, System.currentTimeMillis()));
                while (history.size() > maxSnapshots) history.removeLast();

                log.debug("[OIPriceAction] Snapshot {}: spot={} ceOI={} peOI={} snapshots={}",
                        idx, String.format("%.0f", spot), ceOI, peOI, history.size());
            } catch (Exception e) {
                log.debug("[OIPriceAction] Snapshot failed for {}: {}", idx, e.getMessage());
            }
        }
    }

    /**
     * Confirm a breakout signal with OI data.
     *
     * @param indexType the index being traded
     * @param isBullish true for CE/bullish breakout, false for PE/bearish
     * @return true if OI confirms the breakout direction, false if rejected
     */
    public boolean isBreakoutConfirmed(IndexType indexType, boolean isBullish) {
        if (!enabled) return true;

        Deque<OISnapshot> history = oiHistory.get(indexType);
        if (history == null || history.size() < 2) return true; // no data, don't block

        OISnapshot current = history.peekFirst();
        // Use 2nd snapshot (not immediately previous) to reduce noise from single-tick OI jumps
        OISnapshot reference = null;
        int count = 0;
        for (OISnapshot snap : history) {
            if (count == 2) { reference = snap; break; }
            if (count == 0 && history.size() < 3) { reference = snap; } // fallback to 1st if only 2 snapshots
            count++;
        }
        if (reference == null) {
            // Only 1 snapshot — use it
            var iter = history.iterator();
            iter.next(); // skip current
            if (iter.hasNext()) reference = iter.next();
            else return true;
        }

        if (isBullish) {
            // Bullish: CE OI should rise (call buyers entering = bullish conviction)
            if (reference.totalCeOI == 0) return true;
            double ceChange = ((double) (current.totalCeOI - reference.totalCeOI) / reference.totalCeOI) * 100;
            boolean confirmed = ceChange >= minOiChangePercent;
            if (!confirmed) {
                log.info("[OIPriceAction] BULLISH breakout REJECTED for {}: CE OI change={}% (need >={}%)",
                        indexType, String.format("%.1f", ceChange), minOiChangePercent);
            }
            return confirmed;
        } else {
            // Bearish: PE OI should rise (put buyers entering = bearish conviction)
            if (reference.totalPeOI == 0) return true;
            double peChange = ((double) (current.totalPeOI - reference.totalPeOI) / reference.totalPeOI) * 100;
            boolean confirmed = peChange >= minOiChangePercent;
            if (!confirmed) {
                log.info("[OIPriceAction] BEARISH breakout REJECTED for {}: PE OI change={}% (need >={}%)",
                        indexType, String.format("%.1f", peChange), minOiChangePercent);
            }
            return confirmed;
        }
    }

    /**
     * Get a human-readable rejection reason, or null if confirmed.
     */
    public String getRejectReason(IndexType indexType, boolean isBullish) {
        if (isBreakoutConfirmed(indexType, isBullish)) return null;
        String direction = isBullish ? "BULLISH" : "BEARISH";
        return "OI_PRICE_ACTION_REJECTED: " + direction + " breakout not confirmed by OI for " + indexType;
    }

    /** Number of snapshots captured for an index (for diagnostics). */
    public int getSnapshotCount(IndexType indexType) {
        Deque<OISnapshot> history = oiHistory.get(indexType);
        return history != null ? history.size() : 0;
    }
}
