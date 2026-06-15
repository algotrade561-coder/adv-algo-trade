package com.algo.trade.strategy.filter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTM Volume Spike Detector — "Zero-to-Hero" scanner.
 *
 * Scans OTM strikes (3-5 strikes away from ATM) for sudden volume spikes
 * that indicate institutional activity or "smart money" positioning.
 *
 * Runs every 60 seconds after 2:30 PM on expiry days.
 */
@Component
public class OTMVolumeSpikeDetector implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(OTMVolumeSpikeDetector.class);

    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;

    private static final IndexType[] TRACKED_INDICES = {IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX};
    private static final double SPIKE_MULTIPLIER = 3.0;
    private static final int OTM_STRIKES_FROM = 3;
    private static final int OTM_STRIKES_TO = 5;

    private final Map<Long, Deque<VolumeSnapshot>> volumeHistory = new ConcurrentHashMap<>();
    private final Map<IndexType, List<SpikeInfo>> activeSpikes = new ConcurrentHashMap<>();

    public record VolumeSnapshot(long timestampMs, long cumulativeVolume) {}

    public record SpikeInfo(
            String tradingSymbol, int strikePrice, String optionType,
            IndexType indexType, long currentVolume, long avgVolume,
            double spikeRatio, long detectedAt
    ) {}

    public OTMVolumeSpikeDetector(LiveInstrumentCache instrumentCache, ExpiryCalendar expiryCalendar) {
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    public List<SpikeInfo> getActiveSpikes(IndexType indexType) {
        return activeSpikes.getOrDefault(indexType, List.of());
    }

    @Scheduled(fixedDelay = 60_000)
    public void scan() {
        java.time.LocalTime now = java.time.LocalTime.now();
        if (now.isBefore(java.time.LocalTime.of(14, 30)) || now.isAfter(java.time.LocalTime.of(15, 25))) return;

        for (IndexType idx : TRACKED_INDICES) {
            try {
                if (!expiryCalendar.isExpiryDay(idx)) continue;
                scanIndex(idx);
            } catch (Exception e) {
                log.debug("[OTMSpike] Error scanning {}: {}", idx, e.getMessage());
            }
        }
    }

    private void scanIndex(IndexType idx) {
        double spot = instrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return;

        int atmStrike = idx.roundToATM(spot);
        int interval = idx.strikeInterval();

        List<SpikeInfo> spikes = new ArrayList<>();
        long now = System.currentTimeMillis();
        java.time.LocalDate expiry = expiryCalendar.getCurrentExpiry(idx);

        for (int i = OTM_STRIKES_FROM; i <= OTM_STRIKES_TO; i++) {
            int ceStrike = atmStrike + (i * interval);
            instrumentCache.getOption(idx, ceStrike, "CE", expiry)
                    .ifPresent(opt -> checkForSpike(opt, idx, now, spikes));

            int peStrike = atmStrike - (i * interval);
            instrumentCache.getOption(idx, peStrike, "PE", expiry)
                    .ifPresent(opt -> checkForSpike(opt, idx, now, spikes));
        }

        activeSpikes.put(idx, spikes);

        for (SpikeInfo spike : spikes) {
            log.info("[OTMSpike] {} {} {} volume spike: current={} avg={} ratio={:.1f}x",
                    idx, spike.strikePrice(), spike.optionType(),
                    spike.currentVolume(), spike.avgVolume(), spike.spikeRatio());
        }
    }

    private void checkForSpike(OptionInstrument opt, IndexType idx, long now, List<SpikeInfo> spikes) {
        long token = opt.getInstrumentToken();
        long currentVolume = opt.getVolume();

        Deque<VolumeSnapshot> history = volumeHistory.computeIfAbsent(token, k -> new ArrayDeque<>());
        history.addLast(new VolumeSnapshot(now, currentVolume));

        long cutoff = now - 30 * 60 * 1000L;
        while (!history.isEmpty() && history.peekFirst().timestampMs() < cutoff) {
            history.pollFirst();
        }

        if (history.size() < 6) return;

        long fiveMinAgo = now - 5 * 60 * 1000L;
        long volumeFiveMinAgo = 0;
        for (VolumeSnapshot snap : history) {
            if (snap.timestampMs() <= fiveMinAgo) volumeFiveMinAgo = snap.cumulativeVolume();
        }
        long recentVolume = currentVolume - volumeFiveMinAgo;
        if (recentVolume <= 0) return;

        long totalHistoryVolume = 0;
        int intervals = 0;
        VolumeSnapshot prev = null;
        for (VolumeSnapshot snap : history) {
            if (snap.timestampMs() > fiveMinAgo) break;
            if (prev != null) {
                totalHistoryVolume += (snap.cumulativeVolume() - prev.cumulativeVolume());
                intervals++;
            }
            prev = snap;
        }

        if (intervals == 0) return;
        long avgVolumePerInterval = totalHistoryVolume / intervals;
        long avgFiveMinVolume = avgVolumePerInterval * 5;
        if (avgFiveMinVolume <= 0) return;

        double spikeRatio = (double) recentVolume / avgFiveMinVolume;
        if (spikeRatio >= SPIKE_MULTIPLIER) {
            spikes.add(new SpikeInfo(
                    opt.getTradingSymbol(), opt.getStrikePrice(), opt.getOptionType(),
                    idx, recentVolume, avgFiveMinVolume, spikeRatio, now));
        }
    }

    public void resetDaily() {
        volumeHistory.clear();
        activeSpikes.clear();
    }
}
