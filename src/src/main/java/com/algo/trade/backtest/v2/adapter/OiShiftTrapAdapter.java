package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.StrategySignal;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * OI Shift Trap adapter: Detects call/put writer exposure near current price.
 * When spot approaches a heavy-OI strike, writers may cover → squeeze.
 */
@Component
public class OiShiftTrapAdapter extends AbstractStrategyAdapter {

    private static final double OI_CONCENTRATION_THRESHOLD = 2.0; // 2x average OI
    private static final double PROXIMITY_PERCENT = 0.5; // Within 0.5% of heavy strike
    private static final int MIN_OI_CHANGE_THRESHOLD = 5000;

    @Override
    public StrategyType strategyType() {
        return StrategyType.OI_SHIFT_TRAP;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < 3) return Optional.empty();

        // Find the strike with highest OI concentration
        Optional<ChainSnapshot.StrikeData> heavyCeStrike = findHeavyOiStrike(current, OptionType.CE);
        Optional<ChainSnapshot.StrikeData> heavyPeStrike = findHeavyOiStrike(current, OptionType.PE);

        // Check if spot is approaching a heavy CE OI strike (resistance)
        // If CE writers are trapped → short covering → spot goes up → buy CE
        if (heavyCeStrike.isPresent()) {
            int ceStrike = heavyCeStrike.get().strike();
            double distancePercent = ((double) ceStrike - current.spot()) / current.spot() * 100;

            if (distancePercent > 0 && distancePercent < PROXIMITY_PERCENT) {
                // Spot approaching heavy CE strike from below — potential short cover
                long oiChange = heavyCeStrike.get().ceOiChange();
                if (oiChange < -MIN_OI_CHANGE_THRESHOLD) {
                    // CE OI unwinding = writers covering = bullish
                    int targetStrike = current.atmStrike();
                    double entryPrice = getOptionPrice(current, targetStrike, OptionType.CE);
                    if (entryPrice <= 0) return Optional.empty();

                    return Optional.of(new StrategySignal(
                            current.timestamp(), StrategyType.OI_SHIFT_TRAP, OptionType.CE,
                            targetStrike, entryPrice, current.spot(),
                            String.format("CE OI trap: heavy strike %d (OI change %d), spot approaching",
                                    ceStrike, oiChange),
                            0.6, getOptionIV(current, targetStrike, OptionType.CE),
                            getOptionDelta(current, targetStrike, OptionType.CE),
                            getOptionOI(current, targetStrike, OptionType.CE),
                            getOptionVolume(current, targetStrike, OptionType.CE)
                    ));
                }
            }
        }

        // Check if spot is approaching a heavy PE OI strike (support)
        // If PE writers are trapped → short covering → spot goes down → buy PE
        if (heavyPeStrike.isPresent()) {
            int peStrike = heavyPeStrike.get().strike();
            double distancePercent = (current.spot() - (double) peStrike) / current.spot() * 100;

            if (distancePercent > 0 && distancePercent < PROXIMITY_PERCENT) {
                long oiChange = heavyPeStrike.get().peOiChange();
                if (oiChange < -MIN_OI_CHANGE_THRESHOLD) {
                    // PE OI unwinding = writers covering = bearish
                    int targetStrike = current.atmStrike();
                    double entryPrice = getOptionPrice(current, targetStrike, OptionType.PE);
                    if (entryPrice <= 0) return Optional.empty();

                    return Optional.of(new StrategySignal(
                            current.timestamp(), StrategyType.OI_SHIFT_TRAP, OptionType.PE,
                            targetStrike, entryPrice, current.spot(),
                            String.format("PE OI trap: heavy strike %d (OI change %d), spot approaching",
                                    peStrike, oiChange),
                            0.6, getOptionIV(current, targetStrike, OptionType.PE),
                            getOptionDelta(current, targetStrike, OptionType.PE),
                            getOptionOI(current, targetStrike, OptionType.PE),
                            getOptionVolume(current, targetStrike, OptionType.PE)
                    ));
                }
            }
        }

        return Optional.empty();
    }

    private Optional<ChainSnapshot.StrikeData> findHeavyOiStrike(ChainSnapshot snapshot, OptionType optionType) {
        long avgOI = (long) snapshot.strikes().stream()
                .mapToLong(s -> optionType == OptionType.CE ? s.ceOI() : s.peOI())
                .average().orElse(0);

        long threshold = (long) (avgOI * OI_CONCENTRATION_THRESHOLD);

        return snapshot.strikes().stream()
                .filter(s -> (optionType == OptionType.CE ? s.ceOI() : s.peOI()) > threshold)
                .max((a, b) -> Long.compare(
                        optionType == OptionType.CE ? a.ceOI() : a.peOI(),
                        optionType == OptionType.CE ? b.ceOI() : b.peOI()));
    }
}
