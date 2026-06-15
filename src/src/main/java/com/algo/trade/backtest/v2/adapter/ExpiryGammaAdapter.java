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
 * Expiry Gamma adapter: Exploits ATM gamma on expiry day.
 * Buys in the direction of momentum between 13:00 and 14:30 for explosive premium moves.
 */
@Component
public class ExpiryGammaAdapter extends AbstractStrategyAdapter {

    private static final LocalTime ENTRY_START = LocalTime.of(13, 0);
    private static final LocalTime ENTRY_END = LocalTime.of(14, 30);
    private static final double MIN_GAMMA = 0.008; // Minimum gamma for explosive move
    private static final double MIN_MOMENTUM_PERCENT = 0.1; // 0.1% spot move in last 15 min
    private static final int MOMENTUM_LOOKBACK = 3; // 15 min at 5-min intervals

    @Override
    public StrategyType strategyType() {
        return StrategyType.EXPIRY_GAMMA;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < MOMENTUM_LOOKBACK) return Optional.empty();

        // Only trade during gamma window (13:00 - 14:30)
        LocalTime marketTime = getMarketTime(current);
        if (marketTime.isBefore(ENTRY_START) || marketTime.isAfter(ENTRY_END)) {
            return Optional.empty();
        }

        // Check if it's expiry day (expiry in snapshot should be today)
        LocalDate today = current.timestamp().atZone(IST).toLocalDate();
        String expiryStr = current.expiry();
        if (expiryStr == null) return Optional.empty();
        LocalDate expiry = LocalDate.parse(expiryStr);
        if (!expiry.equals(today)) return Optional.empty();

        // Check ATM gamma is high enough
        Optional<ChainSnapshot.StrikeData> atmData = getAtmStrike(current);
        if (atmData.isEmpty()) return Optional.empty();

        double ceGamma = atmData.get().ceGamma();
        double peGamma = atmData.get().peGamma();
        if (ceGamma < MIN_GAMMA && peGamma < MIN_GAMMA) return Optional.empty();

        // Determine momentum direction
        ChainSnapshot lookback = history.get(MOMENTUM_LOOKBACK - 1);
        double momentumPercent = spotChangePercent(current, lookback);

        if (Math.abs(momentumPercent) < MIN_MOMENTUM_PERCENT) return Optional.empty();

        boolean bullish = momentumPercent > 0;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double gamma = bullish ? ceGamma : peGamma;
        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.4 + gamma * 20 + Math.abs(momentumPercent) * 2);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.EXPIRY_GAMMA, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Expiry gamma %s: momentum=%.2f%%, gamma=%.4f, time=%s",
                        bullish ? "BULL" : "BEAR", momentumPercent, gamma, marketTime),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }
}
