package com.kiteapioptions.config;

import com.kiteapioptions.domain.BrokerName;
import com.kiteapioptions.domain.StrikeSelectionMode;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.domain.UnderlyingSymbol;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed trading configuration. Defaults are intentionally paper-first.
 */
@Validated
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        @NotNull TradingMode mode,
        boolean liveTradingEnabled,
        @NotNull ZoneId timezone,
        @Valid @NotNull Broker broker,
        @Valid @NotNull Symbols symbols,
        @Valid @NotNull Strike strike,
        @Valid @NotNull Entry entry,
        @Valid @NotNull Exit exit,
        @Valid @NotNull Risk risk,
        @Valid @NotNull Paper paper,
        @Valid @NotNull Safety safety,
        @Valid @NotNull Backtest backtest
) {

    public TradingProperties {
        if (mode == null) {
            mode = TradingMode.PAPER;
        }
        if (timezone == null) {
            timezone = ZoneId.of("Asia/Kolkata");
        }
        broker = broker == null ? Broker.defaults() : broker;
        symbols = symbols == null ? Symbols.defaults() : symbols;
        strike = strike == null ? Strike.defaults() : strike;
        entry = entry == null ? Entry.defaults() : entry;
        exit = exit == null ? Exit.defaults() : exit;
        risk = risk == null ? Risk.defaults() : risk;
        paper = paper == null ? Paper.defaults() : paper;
        safety = safety == null ? Safety.defaults() : safety;
        backtest = backtest == null ? Backtest.defaults() : backtest;
    }

    public record Broker(
            @NotNull BrokerName name,
            String apiKey,
            String apiSecret,
            String accessToken,
            String userId,
            @NotNull String baseUrl,
            @NotNull String loginUrl,
            @NotNull String redirectUrl,
            @NotNull String callbackPath,
            @NotNull Duration requestTimeout
    ) {
        public static Broker defaults() {
            return new Broker(BrokerName.ZERODHA, "", "", "", "", "https://api.kite.trade",
                    "https://kite.zerodha.com/connect/login", "http://localhost:8080/auth/kite/callback",
                    "/auth/kite/callback", Duration.ofSeconds(5));
        }
    }

    public record Symbols(
            @NotEmpty List<UnderlyingSymbol> underlyings,
            @NotNull String defaultExpiry
    ) {
        public static Symbols defaults() {
            return new Symbols(List.of(UnderlyingSymbol.NIFTY, UnderlyingSymbol.BANKNIFTY), "NEAREST_WEEKLY");
        }
    }

    public record Strike(
            @NotNull StrikeSelectionMode selectionMode,
            @Min(1) int nearbyStrikes
    ) {
        public static Strike defaults() {
            return new Strike(StrikeSelectionMode.ATM, 5);
        }
    }

    public record Entry(
            @NotNull Timeframe timeframe,
            @NotNull Timeframe trendTimeframe,
            boolean vwapFilterEnabled,
            boolean trendFilterEnabled,
            @DecimalMin("1.0") BigDecimal volumeSpikeMultiplier,
            @DecimalMin("0.0") BigDecimal breakoutBufferPercent,
            @Min(0) long minLiquidityVolume,
            @DecimalMin("0.0") BigDecimal maxIvPercent,
            @NotNull LocalTime entryStartTime,
            @NotNull LocalTime entryCutoffTime,
            boolean allowFirstMinutesEntry,
            @Min(0) int noEntryFirstMinutes
    ) {
        public static Entry defaults() {
            return new Entry(
                    Timeframe.ONE_MINUTE,
                    Timeframe.FIVE_MINUTE,
                    true,
                    false,
                    BigDecimal.valueOf(1.5),
                    BigDecimal.valueOf(0.1),
                    10_000,
                    BigDecimal.valueOf(80),
                    LocalTime.of(9, 25),
                    LocalTime.of(14, 45),
                    false,
                    10
            );
        }
    }

    public record Exit(
            @DecimalMin("0.0") BigDecimal stopLossPercent,
            @DecimalMin("0.0") BigDecimal targetPercent,
            @DecimalMin("0.0") BigDecimal trailingStopActivationPercent,
            @DecimalMin("0.0") BigDecimal trailingGapPercent,
            @NotNull LocalTime forcedExitTime,
            boolean partialProfitBookingEnabled
    ) {
        public static Exit defaults() {
            return new Exit(BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.valueOf(12),
                    BigDecimal.valueOf(6), LocalTime.of(15, 15), false);
        }
    }

    public record Risk(
            @DecimalMin("0.0") BigDecimal totalCapital,
            @DecimalMin("0.0") BigDecimal maxRiskPerTradePercent,
            @DecimalMin("0.0") BigDecimal maxDailyLossPercent,
            @Min(1) int maxTradesPerDay,
            @Min(1) int maxConsecutiveLosses,
            boolean oneOpenTradeAtATime,
            @Min(0) int cooldownMinutes
    ) {
        public static Risk defaults() {
            return new Risk(BigDecimal.valueOf(300_000), BigDecimal.ONE, BigDecimal.valueOf(3),
                    2, 2, true, 10);
        }
    }

    public record Paper(
            @DecimalMin("0.0") BigDecimal slippagePercent,
            @DecimalMin("0.0") BigDecimal startingCash
    ) {
        public static Paper defaults() {
            return new Paper(BigDecimal.valueOf(0.05), BigDecimal.valueOf(300_000));
        }
    }

    public record Safety(
            boolean killSwitchEnabled,
            @NotNull Duration staleMarketDataThreshold,
            @Min(0) int brokerRetryCount,
            @NotNull Duration brokerRetryBackoff
    ) {
        public static Safety defaults() {
            return new Safety(false, Duration.ofSeconds(10), 2, Duration.ofMillis(500));
        }
    }

    public record Backtest(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotNull Timeframe candleTimeframe,
            @NotNull String csvImportPath,
            @NotNull String outputDirectory
    ) {
        public static Backtest defaults() {
            return new Backtest(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31),
                    Timeframe.ONE_MINUTE, "data/backtest/input.csv", "reports/backtest");
        }
    }
}
