package com.algo.trade.config;

import com.algo.trade.domain.BrokerName;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.StrikeSelectionMode;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.domain.UnderlyingSymbol;
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
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed trading configuration. Defaults are intentionally paper-first.
 */
@Validated
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        @NotNull TradingMode mode,
        @NotNull MarketDataMode marketDataMode,
        @NotNull ExecutionMode executionMode,
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
        @Valid @NotNull Telegram telegram,
        @Valid @NotNull Algo algo,
        @Valid @NotNull Backtest backtest
) {

    @ConstructorBinding
    public TradingProperties {
        if (mode == null) {
            mode = TradingMode.PAPER;
        }
        if (marketDataMode == null) {
            marketDataMode = mode == TradingMode.LIVE ? MarketDataMode.ZERODHA : MarketDataMode.MOCK;
        }
        if (executionMode == null) {
            executionMode = mode == TradingMode.LIVE ? ExecutionMode.ZERODHA : ExecutionMode.PAPER;
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
        telegram = telegram == null ? Telegram.defaults() : telegram;
        algo = algo == null ? Algo.defaults() : algo;
        backtest = backtest == null ? Backtest.defaults() : backtest;
    }

    public TradingProperties(
            TradingMode mode,
            boolean liveTradingEnabled,
            ZoneId timezone,
            Broker broker,
            Symbols symbols,
            Strike strike,
            Entry entry,
            Exit exit,
            Risk risk,
            Paper paper,
            Safety safety,
            Algo algo
    ) {
        this(mode, null, null, liveTradingEnabled, timezone, broker, symbols, strike, entry, exit, risk, paper, safety,
                null, algo, null);
    }

    public TradingProperties(
            TradingMode mode,
            boolean liveTradingEnabled,
            ZoneId timezone,
            Broker broker,
            Symbols symbols,
            Strike strike,
            Entry entry,
            Exit exit,
            Risk risk,
            Paper paper,
            Safety safety,
            Algo algo,
            Backtest backtest
    ) {
        this(mode, null, null, liveTradingEnabled, timezone, broker, symbols, strike, entry, exit, risk, paper, safety,
                null, algo, backtest);
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
            @NotNull Duration requestTimeout,
            boolean autoLoginOnStartup,
            boolean ngrokEnabled,
            @NotNull String ngrokPath,
            @Min(1) int ngrokHttpPort,
            @NotNull String ngrokConfigPath,
            @NotNull String ngrokCallbackTunnelName,
            boolean ngrokUiEnabled,
            @Min(1) int ngrokUiHttpPort,
            @NotNull String ngrokUiTunnelName
    ) {
        public Broker {
            if (ngrokPath == null || ngrokPath.isBlank()) {
                ngrokPath = "ngrok.exe";
            }
            if (ngrokHttpPort <= 0) {
                ngrokHttpPort = 8089;
            }
            if (ngrokConfigPath == null || ngrokConfigPath.isBlank()) {
                ngrokConfigPath = "ngrok.yml";
            }
            if (ngrokCallbackTunnelName == null || ngrokCallbackTunnelName.isBlank()) {
                ngrokCallbackTunnelName = "app";
            }
            if (ngrokUiHttpPort <= 0) {
                ngrokUiHttpPort = 8089;
            }
            if (ngrokUiTunnelName == null || ngrokUiTunnelName.isBlank()) {
                ngrokUiTunnelName = "app";
            }
        }

        public static Broker defaults() {
            return new Broker(BrokerName.ZERODHA, "", "", "", "", "https://api.kite.trade",
                    "https://kite.zerodha.com/connect/login", "http://localhost:8089/auth/kite/callback",
                    "/auth/kite/callback", Duration.ofSeconds(5), true, false, "ngrok.exe", 8089,
                    "ngrok.yml", "app", false, 8089, "app");
        }
    }

    public record Symbols(
            @NotEmpty List<UnderlyingSymbol> underlyings,
            @NotNull String defaultExpiry,
            @NotNull Map<UnderlyingSymbol, String> spotQuoteKeys,
            @NotNull Map<UnderlyingSymbol, String> spotHistoricalKeys
    ) {
        public static Symbols defaults() {
            return new Symbols(
                    List.of(UnderlyingSymbol.NIFTY),
                    "NEAREST_WEEKLY",
                    Map.of(
                            UnderlyingSymbol.NIFTY, "NSE:NIFTY 50",
                            UnderlyingSymbol.BANKNIFTY, "NSE:NIFTY BANK",
                            UnderlyingSymbol.SENSEX, "BSE:SENSEX"
                    ),
                    Map.of(
                            UnderlyingSymbol.NIFTY, "NSE:NIFTY 50",
                            UnderlyingSymbol.BANKNIFTY, "NSE:NIFTY BANK",
                            UnderlyingSymbol.SENSEX, "BSE:SENSEX"
                    )
            );
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
            @NotEmpty List<OptionType> enabledOptionTypes,
            boolean vwapFilterEnabled,
            boolean trendFilterEnabled,
            @DecimalMin("1.0") BigDecimal volumeSpikeMultiplier,
            @DecimalMin("0.0") BigDecimal breakoutBufferPercent,
            @Min(1) int breakoutLookback,
            @Min(1) int volumeLookback,
            @DecimalMin("0.0") BigDecimal bullishImbalanceThreshold,
            @DecimalMin("0.0") BigDecimal bearishImbalanceThreshold,
            @Min(0) long minLiquidityVolume,
            @DecimalMin("0.0") BigDecimal maxIvPercent,
            @DecimalMin("0.0") BigDecimal minSignalScorePercent,
            boolean ceOiSupportRequired,
            boolean peOiSupportRequired,
            boolean ceOiDivergenceFilterEnabled,
            boolean peOiDivergenceFilterEnabled,
            @DecimalMin("0.0") BigDecimal oiDivergenceMultiplier,
            @Min(0) long oiDivergenceMinChange,
            @Min(1) int ceBreakoutConfirmationCandles,
            @Min(1) int peBreakoutConfirmationCandles,
            @NotNull LocalTime entryStartTime,
            @NotNull LocalTime entryCutoffTime,
            boolean allowFirstMinutesEntry,
            @Min(0) int noEntryFirstMinutes,
            boolean rsiFilterEnabled,
            @Min(2) int rsiPeriod,
            @DecimalMin("0.0") BigDecimal rsiCeBuyThreshold,
            @DecimalMin("0.0") BigDecimal rsiPeSellThreshold
    ) {
        public static Entry defaults() {
            return new Entry(
                    Timeframe.ONE_MINUTE,
                    Timeframe.FIVE_MINUTE,
                    List.of(OptionType.CE, OptionType.PE),
                    true,   // vwapFilterEnabled
                    true,   // trendFilterEnabled
                    BigDecimal.valueOf(1.2),   // volumeSpikeMultiplier
                    new BigDecimal("0.05"),     // breakoutBufferPercent
                    15,     // breakoutLookback
                    5,      // volumeLookback
                    BigDecimal.valueOf(1.2),   // bullishImbalanceThreshold
                    BigDecimal.valueOf(0.8),   // bearishImbalanceThreshold
                    5_000,  // minLiquidityVolume
                    BigDecimal.valueOf(80),    // maxIvPercent
                    BigDecimal.valueOf(70),    // minSignalScorePercent
                    false,  // ceOiSupportRequired
                    false,  // peOiSupportRequired
                    true,   // ceOiDivergenceFilterEnabled
                    true,   // peOiDivergenceFilterEnabled
                    BigDecimal.valueOf(2.0),   // oiDivergenceMultiplier
                    100_000, // oiDivergenceMinChange
                    1,      // ceBreakoutConfirmationCandles
                    2,      // peBreakoutConfirmationCandles
                    LocalTime.of(9, 25),       // entryStartTime
                    LocalTime.of(15, 10),      // entryCutoffTime
                    false,  // allowFirstMinutesEntry
                    10,     // noEntryFirstMinutes
                    false,  // rsiFilterEnabled
                    14,     // rsiPeriod
                    BigDecimal.valueOf(55),    // rsiCeBuyThreshold
                    BigDecimal.valueOf(45)     // rsiPeSellThreshold
            );
        }
    }

    public record Exit(
            @DecimalMin("0.0") BigDecimal stopLossPercent,
            @DecimalMin("0.0") BigDecimal targetPercent,
            @DecimalMin("0.0") BigDecimal trailingStopActivationPercent,
            @DecimalMin("0.0") BigDecimal trailingGapPercent,
            @NotNull LocalTime forcedExitTime,
            boolean partialProfitBookingEnabled,
            @Min(0) int maxHoldMinutes
    ) {
        public static Exit defaults() {
            return new Exit(BigDecimal.valueOf(12), BigDecimal.valueOf(24), BigDecimal.valueOf(10),
                    BigDecimal.valueOf(5), LocalTime.of(15, 15), false, 0);
        }
    }

    public record Risk(
            @DecimalMin("0.0") BigDecimal totalCapital,
            @DecimalMin("0.0") BigDecimal maxRiskPerTradePercent,
            @DecimalMin("0.0") BigDecimal maxDailyLossPercent,
            @Min(1) int maxTradesPerDay,
            @Min(1) int maxOrdersPerDay,
            @Min(1) int maxConsecutiveLosses,
            @Min(1) int maxOpenTrades,
            @DecimalMin("0.0") BigDecimal sameInstrumentReentryMinPriceMovePercent,
            @Min(0) int cooldownMinutes,
            @DecimalMin("0.0") BigDecimal dailyProfitTarget
    ) {
        public Risk {
            if (dailyProfitTarget == null) {
                dailyProfitTarget = BigDecimal.ZERO;
            }
        }

        public static Risk defaults() {
            return new Risk(BigDecimal.valueOf(60_000), BigDecimal.valueOf(5), BigDecimal.valueOf(5),
                    4, 4, 2, 1, BigDecimal.valueOf(3), 0, BigDecimal.ZERO);
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

    public record Telegram(
            boolean enabled,
            String botToken,
            String chatId,
            @NotNull Duration requestTimeout
    ) {
        public static Telegram defaults() {
            return new Telegram(false, "", "", Duration.ofSeconds(5));
        }
    }

    public record Algo(
            boolean schedulerEnabled,
            @Min(1000) long scanIntervalMs,
            @Min(0) long initialDelayMs,
            @Min(6) int candleLookback,
            @Min(1) int maxEntriesPerScan,
            boolean refreshInstrumentsOnStart,
            boolean autoStartScannerAfterLogin
    ) {
        public static Algo defaults() {
            return new Algo(true, 60_000, 5_000, 30, 1, true, false);
        }
    }

    public record Backtest(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotNull Timeframe candleTimeframe,
            @NotNull String csvImportPath,
            @NotNull String outputDirectory,
            @NotNull String mockInstrumentKey,
            @Min(1) int mockCandleCount,
            @Min(1) int lotSize
    ) {
        public static Backtest defaults() {
            return new Backtest(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31),
                    Timeframe.ONE_MINUTE, "./data/backtest/imports/input.csv", "./data/backtest/results",
                    "NFO:NIFTY-MOCK-ATM-CE", 180, 65);
        }
    }
}
