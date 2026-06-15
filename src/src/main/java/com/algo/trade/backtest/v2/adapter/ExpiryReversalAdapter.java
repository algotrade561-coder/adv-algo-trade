package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.StrategySignal;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Expiry Reversal adapter: Near expiry (0-1 days), fades a sharp intraday spike.
 * Buys against the move expecting pin or reversal to key strike.
 */
@Component
public class ExpiryReversalAdapter extends AbstractStrategyAdapter {

    private static final double MIN_SPIKE_PERCENT = 0.3; // 0.3% spike to fade
    private static final int SPIKE_LOOKBACK = 6; // 30 min at 5-min intervals
    private static final LocalTime ENTRY_START = LocalTime.of(10, 30);
    private static final LocalTime ENTRY_END = LocalTime.of(14, 0);

    @Override
    public StrategyType strategyType() {
        return StrategyType.EXPIRY_REVERSAL;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < SPIKE_LOOKBACK) return Optional.empty();

        // Only trade during entry window
        LocalTime marketTime = getMarketTime(current);
        if (marketTime.isBefore(ENTRY_START) || marketTime.isAfter(ENTRY_END)) {
            return Optional.empty();
        }

        // Check if near expiry (0-1 days)
        LocalDate today = current.timestamp().atZone(IST).toLocalDate();
        String expiryStr = current.expiry();
        if (expiryStr == null) return Optional.empty();
        LocalDate expiry = LocalDate.parse(expiryStr);
        long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
        if (daysToExpiry > 1) return Optional.empty();

        // Detect sharp spike
        ChainSnapshot lookback = history.get(SPIKE_LOOKBACK - 1);
        double spikePercent = spotChangePercent(current, lookback);

        if (Math.abs(spikePercent) < MIN_SPIKE_PERCENT) return Optional.empty();

        // Fade the spike — buy opposite direction
        boolean spikeUp = spikePercent > 0;
        OptionType optionType = spikeUp ? OptionType.PE : OptionType.CE; // Fade = opposite

        // Check if there's a pin strike nearby (high OI strike)
        int targetStrike = current.atmStrike();
        Optional<Integer> pinStrike = findPinStrike(current);
        if (pinStrike.isPresent()) {
            // If pin strike is close, expect reversion toward it
            double distToPin = Math.abs(current.spot() - pinStrike.get());
            double distPercent = distToPin / current.spot() * 100;
            if (distPercent > 1.0) return Optional.empty(); // Too far from pin
        }

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.3 + Math.abs(spikePercent) * 1.5);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.EXPIRY_REVERSAL, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Expiry reversal: spike %.2f%% %s, fading with %s, DTE=%d",
                        spikePercent, spikeUp ? "UP" : "DOWN", optionType, daysToExpiry),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }

    /**
     * Find the strike with highest combined OI (likely pin strike on expiry).
     */
    private Optional<Integer> findPinStrike(ChainSnapshot snapshot) {
        return snapshot.strikes().stream()
                .max((a, b) -> Long.compare(a.ceOI() + a.peOI(), b.ceOI() + b.peOI()))
                .map(ChainSnapshot.StrikeData::strike);
    }
}
