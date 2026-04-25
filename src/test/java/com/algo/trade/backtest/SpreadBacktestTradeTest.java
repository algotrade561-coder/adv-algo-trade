package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.strategy.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SpreadBacktestTradeTest {

    @Test
    void toBacktestTrade_mapsFieldsCorrectly() {
        var legs = List.of(
                new SpreadLeg("NFO:NIFTY26JAN24500CE", 24500, OptionType.CE, OrderSide.BUY, 50, LocalDate.of(2026, 1, 29)),
                new SpreadLeg("NFO:NIFTY26JAN24700CE", 24700, OptionType.CE, OrderSide.SELL, 50, LocalDate.of(2026, 1, 29))
        );
        var entryPrices = Map.of(
                "NFO:NIFTY26JAN24500CE", new BigDecimal("120.00"),
                "NFO:NIFTY26JAN24700CE", new BigDecimal("-85.00")
        );
        var exitPrices = Map.of(
                "NFO:NIFTY26JAN24500CE", new BigDecimal("150.00"),
                "NFO:NIFTY26JAN24700CE", new BigDecimal("-100.00")
        );
        var trade = new SpreadBacktestTrade(
                "T-001", StrategyType.BULL_CALL_SPREAD, legs,
                Instant.parse("2026-01-15T04:00:00Z"),
                Instant.parse("2026-01-15T08:30:00Z"),
                entryPrices, exitPrices,
                new BigDecimal("750.00"),
                "Bullish breakout", "Target hit"
        );

        BacktestTrade bt = trade.toBacktestTrade();

        assertThat(bt.tradeId()).isEqualTo("T-001");
        assertThat(bt.instrumentKey()).isEqualTo("BULL_CALL_SPREAD");
        assertThat(bt.entryTime()).isEqualTo(Instant.parse("2026-01-15T04:00:00Z"));
        assertThat(bt.exitTime()).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
        assertThat(bt.quantity()).isEqualTo(100); // 50 + 50
        assertThat(bt.pnl()).isEqualByComparingTo("750.00");
        assertThat(bt.entryReason()).isEqualTo("Bullish breakout");
        assertThat(bt.exitReason()).isEqualTo("Target hit");
    }

    @Test
    void toBacktestTrade_netPricesSumAllLegs() {
        // Iron condor: 4 legs
        var legs = List.of(
                new SpreadLeg("NFO:NIFTY26JAN24200PE", 24200, OptionType.PE, OrderSide.BUY, 25, LocalDate.of(2026, 1, 29)),
                new SpreadLeg("NFO:NIFTY26JAN24400PE", 24400, OptionType.PE, OrderSide.SELL, 25, LocalDate.of(2026, 1, 29)),
                new SpreadLeg("NFO:NIFTY26JAN24600CE", 24600, OptionType.CE, OrderSide.SELL, 25, LocalDate.of(2026, 1, 29)),
                new SpreadLeg("NFO:NIFTY26JAN24800CE", 24800, OptionType.CE, OrderSide.BUY, 25, LocalDate.of(2026, 1, 29))
        );
        var entryPrices = Map.of(
                "NFO:NIFTY26JAN24200PE", new BigDecimal("30.00"),
                "NFO:NIFTY26JAN24400PE", new BigDecimal("-60.00"),
                "NFO:NIFTY26JAN24600CE", new BigDecimal("-55.00"),
                "NFO:NIFTY26JAN24800CE", new BigDecimal("25.00")
        );
        var exitPrices = Map.of(
                "NFO:NIFTY26JAN24200PE", new BigDecimal("10.00"),
                "NFO:NIFTY26JAN24400PE", new BigDecimal("-20.00"),
                "NFO:NIFTY26JAN24600CE", new BigDecimal("-15.00"),
                "NFO:NIFTY26JAN24800CE", new BigDecimal("5.00")
        );
        var trade = new SpreadBacktestTrade(
                "T-IC-001", StrategyType.IRON_CONDOR, legs,
                Instant.parse("2026-01-15T04:00:00Z"),
                Instant.parse("2026-01-16T08:30:00Z"),
                entryPrices, exitPrices,
                new BigDecimal("1500.00"),
                "Range-bound regime", "Target hit"
        );

        BacktestTrade bt = trade.toBacktestTrade();

        assertThat(bt.instrumentKey()).isEqualTo("IRON_CONDOR");
        assertThat(bt.quantity()).isEqualTo(100); // 25 × 4 legs
        // Net entry: 30 - 60 - 55 + 25 = -60 (net credit)
        assertThat(bt.entryPrice()).isEqualByComparingTo("-60.00");
        // Net exit: 10 - 20 - 15 + 5 = -20
        assertThat(bt.exitPrice()).isEqualByComparingTo("-20.00");
        assertThat(bt.pnl()).isEqualByComparingTo("1500.00");
    }

    @Test
    void defensiveCopies_preventMutation() {
        var trade = new SpreadBacktestTrade(
                "T-002", StrategyType.LONG_STRADDLE, List.of(),
                Instant.now(), Instant.now(),
                Map.of(), Map.of(),
                BigDecimal.ZERO, "test", "test"
        );

        assertThat(trade.legs()).isEmpty();
        assertThat(trade.entryPrices()).isEmpty();
        assertThat(trade.exitPrices()).isEmpty();
    }

    @Test
    void nullCollections_defaultToEmpty() {
        var trade = new SpreadBacktestTrade(
                "T-003", StrategyType.SHORT_STRANGLE, null,
                Instant.now(), Instant.now(),
                null, null,
                BigDecimal.ZERO, "test", "test"
        );

        assertThat(trade.legs()).isEmpty();
        assertThat(trade.entryPrices()).isEmpty();
        assertThat(trade.exitPrices()).isEmpty();
    }
}
