package com.algo.trade.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the documented configuration payload returned by the control API.
 */
public final class ConfigDocumentation {

    private ConfigDocumentation() {
    }

    public static ConfigResponse from(TradingProperties properties, Map<String, Object> runtime) {
        List<ConfigParameter> parameters = List.of(
                param("trading.mode", properties.mode(), "Configured operating mode: PAPER, BACKTEST, or LIVE.",
                        "Sets the default trading workflow and fallback broker routing."),
                param("trading.market-data-mode", properties.marketDataMode(),
                        "Configured source for quotes, option chains, and historical candles.",
                        "MOCK uses generated data; ZERODHA uses Kite Connect market data."),
                param("trading.execution-mode", properties.executionMode(),
                        "Configured destination for order placement.",
                        "PAPER simulates fills; ZERODHA sends orders to Kite when live trading is enabled."),
                param("trading.live-trading-enabled", properties.liveTradingEnabled(),
                        "Hard safety flag that must be true before live Zerodha execution is allowed.",
                        "Blocks LIVE mode changes and Zerodha order placement when false."),
                param("trading.timezone", properties.timezone(),
                        "Application trading timezone.",
                        "Interprets entry windows, square-off time, token expiry, and reports."),

                param("trading.broker.name", properties.broker().name(), "Broker adapter to use.",
                        "Selects the broker-specific client implementation."),
                secret("trading.broker.api-key", properties.broker().apiKey(), "Zerodha Kite Connect API key.",
                        "Used to build login URLs and authenticate REST calls."),
                secret("trading.broker.api-secret", properties.broker().apiSecret(), "Zerodha Kite Connect API secret.",
                        "Used once to exchange a request token for an access token."),
                secret("trading.broker.access-token", properties.broker().accessToken(),
                        "Optional preconfigured daily Kite access token.",
                        "Used to skip manual login when a valid same-day token is available."),
                secret("trading.broker.user-id", properties.broker().userId(), "Optional Kite user id.",
                        "Logged with authentication diagnostics when available."),
                param("trading.broker.base-url", properties.broker().baseUrl(), "Kite REST API base URL.",
                        "Used by the Zerodha REST client."),
                param("trading.broker.login-url", properties.broker().loginUrl(), "Kite login endpoint.",
                        "Used to generate manual login links."),
                param("trading.broker.redirect-url", properties.broker().redirectUrl(), "Configured Kite redirect URL.",
                        "Kite redirects here with request_token after login."),
                param("trading.broker.callback-path", properties.broker().callbackPath(), "Local callback path.",
                        "Spring handles this path to complete Kite login."),
                param("trading.broker.request-timeout", properties.broker().requestTimeout(), "Broker HTTP timeout.",
                        "Applied to Zerodha REST calls."),
                param("trading.broker.auto-login-on-startup", properties.broker().autoLoginOnStartup(),
                        "Whether startup should validate/login to Kite automatically.",
                        "Controls the startup login flow in live or Zerodha-routed sessions."),
                param("trading.broker.ngrok-enabled", properties.broker().ngrokEnabled(),
                        "Whether the app should start an ngrok tunnel for Kite callbacks.",
                        "Helps expose the local callback endpoint to Zerodha during login."),
                param("trading.broker.ngrok-path", properties.broker().ngrokPath(), "Path to ngrok executable.",
                        "Used when ngrok callback tunneling is enabled."),
                param("trading.broker.ngrok-http-port", properties.broker().ngrokHttpPort(),
                        "Local port forwarded by ngrok.",
                        "Must match the local callback listener target."),
                param("trading.broker.ngrok-config-path", properties.broker().ngrokConfigPath(),
                        "Path to the ngrok config file with named tunnels.",
                        "Used when the UI tunnel is enabled."),
                param("trading.broker.ngrok-callback-tunnel-name", properties.broker().ngrokCallbackTunnelName(),
                        "Named ngrok tunnel for the Kite callback listener.",
                        "Must exist in the configured ngrok config file when the UI tunnel is enabled."),
                param("trading.broker.ngrok-ui-enabled", properties.broker().ngrokUiEnabled(),
                        "Whether the app should start a second ngrok tunnel for the UI.",
                        "Exposes the Angular dev UI on a separate public ngrok URL."),
                param("trading.broker.ngrok-ui-http-port", properties.broker().ngrokUiHttpPort(),
                        "Local UI port forwarded by the UI ngrok tunnel.",
                        "Defaults to the Angular development server port."),
                param("trading.broker.ngrok-ui-tunnel-name", properties.broker().ngrokUiTunnelName(),
                        "Named ngrok tunnel for the local UI.",
                        "Must exist in the configured ngrok config file when the UI tunnel is enabled."),
                param("trading.symbols.underlyings", properties.symbols().underlyings(),
                        "Default underlyings enabled for scanning.",
                        "Seeds runtime scan toggles at startup."),
                param("trading.symbols.default-expiry", properties.symbols().defaultExpiry(),
                        "Default option expiry selector.",
                        "Used when selecting tradable option contracts."),
                param("trading.symbols.spot-quote-keys", properties.symbols().spotQuoteKeys(),
                        "Broker quote keys for each underlying.",
                        "Used to fetch live spot quotes."),
                param("trading.symbols.spot-historical-keys", properties.symbols().spotHistoricalKeys(),
                        "Broker historical candle keys or instrument tokens for each underlying.",
                        "Used to fetch underlying candle history."),

                param("trading.strike.selection-mode", properties.strike().selectionMode(),
                        "How strikes are selected around the underlying price.",
                        "ATM currently selects near at-the-money option candidates."),
                param("trading.strike.nearby-strikes", properties.strike().nearbyStrikes(),
                        "Number of strikes around ATM to inspect.",
                        "Controls option-chain breadth for candidate lookup and context."),

                param("trading.entry.timeframe", properties.entry().timeframe(), "Primary entry candle timeframe.",
                        "Used by the scanner and strategy filters."),
                param("trading.entry.trend-timeframe", properties.entry().trendTimeframe(), "Trend candle timeframe.",
                        "Reserved for higher-timeframe trend confirmation."),
                param("trading.entry.enabled-option-types", properties.entry().enabledOptionTypes(),
                        "Option sides evaluated for entries.",
                        "Controls whether CE, PE, or both can produce BUY decisions."),
                param("trading.entry.vwap-filter-enabled", properties.entry().vwapFilterEnabled(),
                        "Whether entries require price alignment with VWAP or EMA fallback.",
                        "Filters trades against intraday trend context."),
                param("trading.entry.trend-filter-enabled", properties.entry().trendFilterEnabled(),
                        "Whether additional trend confirmation is required.",
                        "Reserved gate for trend-following entry confirmation."),
                param("trading.entry.volume-spike-multiplier", properties.entry().volumeSpikeMultiplier(),
                        "Required volume multiple versus recent average.",
                        "Rejects entries without enough participation."),
                param("trading.entry.breakout-buffer-percent", properties.entry().breakoutBufferPercent(),
                        "Extra percentage required beyond breakout levels.",
                        "Reduces false breakouts by requiring a price buffer."),
                param("trading.entry.breakout-lookback", properties.entry().breakoutLookback(),
                        "Candle count used to find recent breakout levels.",
                        "Feeds swing high/low breakout detection."),
                param("trading.entry.volume-lookback", properties.entry().volumeLookback(),
                        "Candle count used for average volume.",
                        "Feeds volume spike detection."),
                param("trading.entry.bullish-imbalance-threshold", properties.entry().bullishImbalanceThreshold(),
                        "Put/call OI imbalance threshold favoring bullish CE trades.",
                        "Used by option-chain analysis and signal scoring."),
                param("trading.entry.bearish-imbalance-threshold", properties.entry().bearishImbalanceThreshold(),
                        "Put/call OI imbalance threshold favoring bearish PE trades.",
                        "Used by option-chain analysis and signal scoring."),
                param("trading.entry.min-liquidity-volume", properties.entry().minLiquidityVolume(),
                        "Minimum option volume required for a tradable setup.",
                        "Avoids illiquid contracts."),
                param("trading.entry.max-iv-percent", properties.entry().maxIvPercent(),
                        "Maximum implied volatility percentage allowed.",
                        "Reserved guard for avoiding overly expensive options."),
                param("trading.entry.min-signal-score-percent", properties.entry().minSignalScorePercent(),
                        "Minimum strategy score required to buy.",
                        "Final quality gate before risk checks and execution."),
                param("trading.entry.ce-oi-support-required", properties.entry().ceOiSupportRequired(),
                        "Whether CE entries require positive OI support.",
                        "When false, bullish breakouts may pass without explicit CE-side OI confirmation."),
                param("trading.entry.pe-oi-support-required", properties.entry().peOiSupportRequired(),
                        "Whether PE entries require positive OI support.",
                        "Keeps bearish entries stricter when OI does not support downside continuation."),
                param("trading.entry.ce-oi-divergence-filter-enabled",
                        properties.entry().ceOiDivergenceFilterEnabled(),
                        "Whether bullish CE entries are rejected when nearby call writing dominates.",
                        "Blocks likely false breakouts when call OI builds much faster than put OI."),
                param("trading.entry.pe-oi-divergence-filter-enabled",
                        properties.entry().peOiDivergenceFilterEnabled(),
                        "Whether bearish PE entries are rejected when nearby put writing dominates.",
                        "Blocks likely downside traps when put OI builds much faster than call OI."),
                param("trading.entry.oi-divergence-multiplier", properties.entry().oiDivergenceMultiplier(),
                        "Relative dominance required before OI divergence rejects an entry.",
                        "Higher values require a larger nearby OI change imbalance before blocking the trade."),
                param("trading.entry.oi-divergence-min-change", properties.entry().oiDivergenceMinChange(),
                        "Minimum nearby OI change needed before the divergence filter activates.",
                        "Avoids triggering divergence rejections on small or noisy OI moves."),
                param("trading.entry.ce-breakout-confirmation-candles",
                        properties.entry().ceBreakoutConfirmationCandles(),
                        "Number of consecutive confirming candles required for CE breakouts.",
                        "Lets bullish confirmation be tuned independently from PE."),
                param("trading.entry.pe-breakout-confirmation-candles",
                        properties.entry().peBreakoutConfirmationCandles(),
                        "Number of consecutive confirming candles required for PE breakouts.",
                        "Lets bearish confirmation stay stricter than CE when needed."),
                param("trading.entry.entry-start-time", properties.entry().entryStartTime(),
                        "Earliest time entries may be considered.",
                        "Prevents scans from trading before the configured market window."),
                param("trading.entry.entry-cutoff-time", properties.entry().entryCutoffTime(),
                        "Latest time new entries may be opened.",
                        "Stops fresh trades near the end of the session."),
                param("trading.entry.allow-first-minutes-entry", properties.entry().allowFirstMinutesEntry(),
                        "Whether entries are allowed immediately after market open.",
                        "Controls the first-minutes no-entry guard."),
                param("trading.entry.no-entry-first-minutes", properties.entry().noEntryFirstMinutes(),
                        "Number of minutes after open to block entries when enabled.",
                        "Avoids early-session noise."),
                param("trading.entry.rsi-filter-enabled", properties.entry().rsiFilterEnabled(),
                        "Whether RSI must confirm entry direction.",
                        "Adds momentum confirmation when enabled."),
                param("trading.entry.rsi-period", properties.entry().rsiPeriod(), "RSI lookback period.",
                        "Used by the RSI filter."),
                param("trading.entry.rsi-ce-buy-threshold", properties.entry().rsiCeBuyThreshold(),
                        "Minimum RSI for CE buy confirmation.",
                        "Requires bullish momentum for CE entries when RSI filtering is enabled."),
                param("trading.entry.rsi-pe-sell-threshold", properties.entry().rsiPeSellThreshold(),
                        "Maximum RSI for PE buy confirmation.",
                        "Requires bearish momentum for PE entries when RSI filtering is enabled."),

                param("trading.exit.stop-loss-percent", properties.exit().stopLossPercent(),
                        "Long-option stop loss percentage from entry.",
                        "Used for risk sizing and exit simulation."),
                param("trading.exit.target-percent", properties.exit().targetPercent(),
                        "Long-option profit target percentage from entry.",
                        "Used by exit logic and backtests."),
                param("trading.exit.trailing-stop-activation-percent", properties.exit().trailingStopActivationPercent(),
                        "Profit percentage at which trailing stop activates.",
                        "Starts trailing only after the trade has moved enough in profit."),
                param("trading.exit.trailing-gap-percent", properties.exit().trailingGapPercent(),
                        "Distance between peak profit and trailing stop.",
                        "Controls how much profit giveback is allowed."),
                param("trading.exit.forced-exit-time", properties.exit().forcedExitTime(),
                        "Time by which open intraday trades should be closed.",
                        "Used by backtests and planned square-off logic."),
                param("trading.exit.partial-profit-booking-enabled", properties.exit().partialProfitBookingEnabled(),
                        "Whether partial exits are enabled.",
                        "Reserved for future partial profit booking behavior."),
                param("trading.exit.max-hold-minutes", properties.exit().maxHoldMinutes(),
                        "Maximum minutes to hold a trade; 0 disables this limit.",
                        "Used by backtests to force time-based exits."),

                param("trading.risk.total-capital", properties.risk().totalCapital(),
                        "Capital base for risk calculations.",
                        "Position sizing uses this with max risk per trade."),
                param("trading.risk.max-risk-per-trade-percent", properties.risk().maxRiskPerTradePercent(),
                        "Maximum capital percentage risked on one trade.",
                        "Determines order quantity from stop-loss distance."),
                param("trading.risk.max-daily-loss-percent", properties.risk().maxDailyLossPercent(),
                        "Maximum daily loss percentage before new entries are blocked.",
                        "Risk engine compares realized daily PnL to this limit."),
                param("trading.risk.max-trades-per-day", properties.risk().maxTradesPerDay(),
                        "Maximum accepted trades per day.",
                        "Risk engine rejects entries after this count."),
                param("trading.risk.max-orders-per-day", properties.risk().maxOrdersPerDay(),
                        "Maximum broker orders per day.",
                        "Execution guard against excessive order attempts."),
                param("trading.risk.max-consecutive-losses", properties.risk().maxConsecutiveLosses(),
                        "Maximum losing streak before blocking entries.",
                        "Risk engine stops trading after repeated losses."),
                param("trading.risk.max-open-trades", properties.risk().maxOpenTrades(),
                        "Maximum number of concurrent open trades allowed.",
                        "Set to 1 for single-position mode, higher for multi-position."),
                param("trading.risk.same-instrument-reentry-min-price-move-percent",
                        properties.risk().sameInstrumentReentryMinPriceMovePercent(),
                        "Minimum price movement before re-entering the same instrument.",
                        "Prevents immediate churn in the same contract."),
                param("trading.risk.cooldown-minutes", properties.risk().cooldownMinutes(),
                        "Minutes to wait after a trade before another entry.",
                        "Reserved cooldown guard for trade pacing."),

                param("trading.paper.slippage-percent", properties.paper().slippagePercent(),
                        "Simulated slippage percentage for paper fills.",
                        "Paper broker adjusts fill prices by this amount."),
                param("trading.paper.starting-cash", properties.paper().startingCash(),
                        "Initial virtual cash balance.",
                        "Used by the paper broker account simulation."),

                param("trading.safety.kill-switch-enabled", properties.safety().killSwitchEnabled(),
                        "Startup kill-switch state.",
                        "Blocks trading when enabled."),
                param("trading.safety.stale-market-data-threshold", properties.safety().staleMarketDataThreshold(),
                        "Maximum allowed market-data age.",
                        "Safety guard for avoiding entries on stale quotes."),
                param("trading.safety.broker-retry-count", properties.safety().brokerRetryCount(),
                        "Number of broker retry attempts.",
                        "Used for recoverable broker call failures."),
                param("trading.safety.broker-retry-backoff", properties.safety().brokerRetryBackoff(),
                        "Delay between broker retries.",
                        "Controls retry pacing."),

                param("trading.telegram.enabled", properties.telegram().enabled(), "Whether Telegram alerts are sent.",
                        "Enables scanner, risk, order, and state-change notifications."),
                secret("trading.telegram.bot-token", properties.telegram().botToken(), "Telegram bot token.",
                        "Authenticates alert delivery to the Telegram Bot API."),
                secret("trading.telegram.chat-id", properties.telegram().chatId(), "Telegram chat id.",
                        "Destination chat for alerts."),
                param("trading.telegram.request-timeout", properties.telegram().requestTimeout(),
                        "Telegram HTTP timeout.",
                        "Applied to alert delivery requests."),

                param("trading.algo.scheduler-enabled", properties.algo().schedulerEnabled(),
                        "Whether scheduled scanning is enabled.",
                        "Controls the periodic algo scanner."),
                param("trading.algo.scan-interval-ms", properties.algo().scanIntervalMs(),
                        "Delay between scheduled scans in milliseconds.",
                        "Controls scanner frequency."),
                param("trading.algo.initial-delay-ms", properties.algo().initialDelayMs(),
                        "Delay before the first scheduled scan.",
                        "Lets the app finish startup before scanning."),
                param("trading.algo.candle-lookback", properties.algo().candleLookback(),
                        "Number of candles loaded per scan.",
                        "Feeds indicators and strategy evaluation."),
                param("trading.algo.max-entries-per-scan", properties.algo().maxEntriesPerScan(),
                        "Maximum entries allowed from one scan.",
                        "Limits order bursts when multiple signals pass."),
                param("trading.algo.refresh-instruments-on-start", properties.algo().refreshInstrumentsOnStart(),
                        "Whether to refresh broker instruments at startup.",
                        "Keeps option contract lookup current."),
                param("trading.algo.auto-start-scanner-after-login", properties.algo().autoStartScannerAfterLogin(),
                        "Whether successful Kite login should start scanning.",
                        "Useful for live market-data sessions that should begin after authentication."),

                param("trading.backtest.from", properties.backtest().from(), "Default backtest start date.",
                        "Used when a backtest request does not override the date range."),
                param("trading.backtest.to", properties.backtest().to(), "Default backtest end date.",
                        "Used when a backtest request does not override the date range."),
                param("trading.backtest.candle-timeframe", properties.backtest().candleTimeframe(),
                        "Default candle timeframe for backtests.",
                        "Controls imported data selection and replay granularity."),
                param("trading.backtest.csv-import-path", properties.backtest().csvImportPath(),
                        "Default CSV input path for backtests.",
                        "Used by the backtest engine and data download workflow."),
                param("trading.backtest.output-directory", properties.backtest().outputDirectory(),
                        "Directory for backtest outputs.",
                        "Stores metrics, trades, equity curves, and suite reports."),
                param("trading.backtest.mock-instrument-key", properties.backtest().mockInstrumentKey(),
                        "Instrument key used for mock backtest data.",
                        "Used when CSV import data is unavailable."),
                param("trading.backtest.mock-candle-count", properties.backtest().mockCandleCount(),
                        "Number of mock candles to generate.",
                        "Controls fallback backtest data size."),
                param("trading.backtest.lot-size", properties.backtest().lotSize(),
                        "Option lot size for backtest sizing.",
                        "Rounds simulated quantities to tradable lots.")
        );
        return new ConfigResponse(configuration(parameters), parameters, runtime);
    }

    private static Map<String, Object> configuration(List<ConfigParameter> parameters) {
        Map<String, Object> trading = new LinkedHashMap<>();
        for (ConfigParameter parameter : parameters) {
            if (parameter.path().startsWith("trading.")) {
                addNested(trading, parameter.path().substring("trading.".length()), parameter.value());
            }
        }
        return Map.of("trading", trading);
    }

    @SuppressWarnings("unchecked")
    private static void addNested(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[index], ignored -> new LinkedHashMap<>());
        }
        current.put(parts[parts.length - 1], value);
    }

    private static ConfigParameter param(String path, Object value, String description, String usedBy) {
        return new ConfigParameter(path, value, description, usedBy, false);
    }

    private static ConfigParameter secret(String path, String value, String description, String usedBy) {
        return new ConfigParameter(path, secretValue(value), description, usedBy, true);
    }

    private static Map<String, Object> secretValue(String value) {
        return Map.of(
                "configured", value != null && !value.isBlank(),
                "value", value == null || value.isBlank() ? "" : "***"
        );
    }

    public record ConfigResponse(
            Map<String, Object> configuration,
            List<ConfigParameter> parameters,
            Map<String, Object> runtime
    ) {
    }

    public record ConfigParameter(
            String path,
            Object value,
            String description,
            String usedBy,
            boolean sensitive
    ) {
    }
}
