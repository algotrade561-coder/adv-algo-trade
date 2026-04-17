# Kite API Options

Production-style Java 21 / Spring Boot 3.x skeleton for a paper-first Zerodha Kite Connect options buying system. The application is intended for educational and controlled trading use. It does not make profit claims and it must not be treated as financial advice.

## Current Scope

Completed through Phase 5:

- Maven Spring Boot project skeleton
- Clean package structure under `com.kiteapioptions`
- Strongly typed configuration classes
- Broker abstraction through `BrokerClient`
- Domain model for instruments, quotes, candles, option chains, orders, positions, trades, PnL, signals, and decisions
- Zerodha REST adapter for instrument download, quotes, historical candles, order placement, orders, and positions
- Paper broker with in-memory simulated fills, orders, and positions
- Instrument CSV parser and in-memory instrument cache with option lookup helpers
- Broker-backed market data service
- Mock market data generator for local paper mode
- VWAP, EMA, volume spike, breakout, volatility, and OI change indicators
- Option-chain analyzer for highest call OI resistance, highest put OI support, and nearby put/call OI imbalance
- Rule-based options strategy evaluator that returns explainable `StrategyDecision` values without placing orders
- Risk engine for entry gating, daily loss/consecutive loss/open trade limits, and risk-based quantity sizing
- Execution engine for broker order placement from approved strategy decisions and trade close journaling
- Trailing stop service for long option exits
- JPA persistence for trades, orders, signals, strategy decisions, daily summaries, errors, and backtest result placeholders
- Reporting service for positions, orders, trades, PnL, latest signal, and trade journal CSV
- REST endpoints for health, config, mode, start/stop, kill switch, monitoring, and backtesting
- Backtest engine with CSV candle import, mock-data fallback, candle replay, entries/exits, metrics, daily PnL, drawdown, and CSV exports
- Sample report formats under `docs/`
- Unit and context tests
- README with architecture and safety notes

## Proposed Package Structure

```text
src/main/java/com/kiteapioptions
  KiteApiOptionsApplication.java
  broker/
  config/
  domain/
  indicator/
  marketdata/
  persistence/
  reporting/
  risk/
  execution/
  controller/
  strategy/
  backtest/
  util/
```

## Architecture Summary

The core application is broker-neutral. `BrokerClient` defines the contract for instruments, quotes, historical candles, positions, orders, and streaming subscription hooks. Zerodha-specific REST code lives behind that interface in `broker.zerodha`.

Trading mode is explicit through configuration:

- `PAPER` is the default and should be used for local validation.
- `BACKTEST` can replay historical candles through the backtest engine.
- `LIVE` is blocked by default unless `trading.live-trading-enabled=true` is set.

Domain objects are immutable Java records with constructor validation where useful. The strategy evaluator is a pure service over candles, quotes, and option-chain snapshots; it records why entries pass or fail but does not place orders.

Phase 4 adds execution as a separate service. `ExecutionEngine` consumes `StrategyDecision` values, runs `RiskEngine`, sizes quantity, routes through `BrokerClient`, and persists order/trade records. The Zerodha broker adapter still blocks live order placement unless `trading.live-trading-enabled=true`.

## REST Endpoints

- `GET /health`
- `GET /config`
- `POST /mode`
- `POST /start`
- `POST /stop`
- `POST /kill-switch`
- `GET /routing`
- `POST /routing`
- `GET /scan/underlyings`
- `POST /scan/underlyings/{underlying}`
- `GET /positions`
- `GET /orders`
- `GET /trades`
- `GET /trades/journal.csv`
- `GET /pnl`
- `GET /signals/latest`
- `POST /reports/entry-signals/archive`
- `POST /backtest/run`
- `POST /backtest/run-suite`
- `POST /backtest/download-and-run-suite`
- `POST /backtest/analyze-variants`
- `POST /backtest/analyze-quick`
- `GET /backtest/results/{id}`

## Configuration

Runtime defaults live in `src/main/resources/application.yml`. Use `application.yml.example` as a shareable template. Do not commit secrets or access tokens. Zerodha expects a fresh manual login/access token each trading day.

Local broker and Telegram secrets are loaded from `data/trading-secrets.properties`, which is ignored by Git. Use `docs/trading-secrets.properties.example` as the template for that local file.

The important safety defaults are:

- `trading.mode: PAPER`
- `trading.live-trading-enabled: false`
- one open trade by default
- max two trades per day
- 10% hard stop
- 20% target
- no entries after 14:45
- forced square-off by 15:15

## Local Run

Prerequisites:

- Java 21
- Maven 3.9+

Commands:

```powershell
mvn test
mvn spring-boot:run
```

The app starts with paper broker wiring by default, JPA repositories, REST monitoring endpoints, execution/risk services, the backtest runner, and a scheduled algo scanner. The scanner only runs after `/start` sets the trading state to running.

Start the scanner after the app is running:

```powershell
curl.exe -X POST http://localhost:8080/start
```

It then runs on the configured interval, refreshes instruments if needed, fetches spot and option market data, builds a near-ATM option-chain snapshot, evaluates CE/PE entries, and routes accepted signals through risk checks and execution. A `NO_TRADE` decision is still a valid scan result when strategy filters do not pass.

Every entry evaluation is appended to `reports/entry-signals/entry-signals.csv` with the raw candle/quote inputs, option-chain context, pass/fail flags, confidence score, config snapshot fields, and final reasons for later strategy tuning. New runs also write normalized analysis files under `reports/entry-signals/`: `entry-evaluations.csv` for one row per strategy decision, `entry-candles.csv` for the underlying and selected option candle history used by that decision, `option-chain-levels.csv` for the full option-chain snapshot levels available during the evaluation, and `entry-execution-outcomes.csv` for BUY decisions that reach risk checks, sizing, and order placement. All of these files now include a shared `decisionKey` so live signal quality can be joined with execution outcomes later.

The trend condition uses VWAP when the underlying candles include volume. If the underlying candle volume is zero, as seen with NIFTY index historical candles from Zerodha, it falls back to an EMA of recent closes.

By default, the scanner only scans NIFTY. Enable BANKNIFTY at runtime when needed:

```powershell
curl.exe -X POST http://localhost:8080/scan/underlyings/BANKNIFTY -H "Content-Type: application/json" -d "{\"enabled\":true}"
```

Check the current scan set:

```powershell
curl.exe http://localhost:8080/scan/underlyings
```

Scanner config:

```yaml
trading:
  symbols:
    underlyings:
      - NIFTY
  algo:
    scheduler-enabled: true
    scan-interval-ms: 60000
    initial-delay-ms: 5000
    candle-lookback: 30
    max-entries-per-scan: 1
    refresh-instruments-on-start: true
    auto-start-scanner-after-login: false
  entry:
    min-signal-score-percent: 70
  risk:
    max-trades-per-day: 6
    max-orders-per-day: 6
    same-instrument-reentry-min-price-move-percent: 10
  telegram:
    enabled: ${TELEGRAM_ALERTS_ENABLED:false}
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
    request-timeout: 5s
```

Telegram alerts are disabled by default. When enabled, the app sends alerts for scanner start/stop, kill switch changes, scan toggles, entry rejections after BUY signals, filled entry orders, and broker/order non-fill outcomes.

Set `trading.algo.auto-start-scanner-after-login=true` to automatically start the scanner after the startup Kite access token is loaded and validated.

## Zerodha Manual Login

The app uses Spring Boot itself as the localhost listener on port `8080`; it does not start a second raw `HttpServer`, because that would conflict with the application port. Configure your Kite developer redirect URL to your ngrok HTTPS URL that forwards to:

```text
http://localhost:8080/auth/kite/callback
```

Example:

```text
https://your-ngrok-domain.ngrok-free.app/auth/kite/callback
```

Set credentials through environment variables or `data/trading-secrets.properties`:

```properties
trading.broker.api-key=${KITE_API_KEY:}
trading.broker.api-secret=${KITE_API_SECRET:}
trading.broker.user-id=
trading.telegram.enabled=false
trading.telegram.bot-token=
trading.telegram.chat-id=
```

With `trading.mode=LIVE` and no access token present, startup starts a temporary callback listener on the configured redirect port, opens/logs the Kite login URL automatically, and blocks until the callback captures the access token. Use a redirect URL on a different port from Spring Boot, for example `http://localhost:8081/auth/kite/callback`. If the login callback is not received within the configured login timeout, startup fails instead of continuing without a token. You can also open:

```text
http://localhost:8080/auth/kite/login
```

Copy/open the returned `loginUrl`, complete Kite login manually, and Zerodha will redirect to the ngrok callback. The callback exchanges `request_token` for `access_token` and stores it in memory for this app run. If you already have a daily access token, you can also set `KITE_ACCESS_TOKEN`.

After a successful Kite login, the app stores the access token metadata in `data/kite-access-token.properties` with a 06:00 Asia/Kolkata expiry. On the next startup the app loads and validates that same-day token before prompting for login again. If validation fails, the persisted token file is cleared and the normal manual login flow is used. The `data/` directory is ignored by Git because it contains local runtime state.

For the blocking login-manager flow, call:

```text
http://localhost:8080/auth/kite/session
```

This uses `KITE_ACCESS_TOKEN` immediately when configured. Otherwise it opens/logs the Kite login URL and waits up to two minutes for the Spring callback to capture the generated access token.

## Backtesting

Default config:

```yaml
trading:
  backtest:
    from: 2025-01-01
    to: 2025-01-31
    candle-timeframe: ONE_MINUTE
    csv-import-path: C:/data/backtest/imports/input.csv
    output-directory: C:/data/backtest/results
    mock-instrument-key: NFO:NIFTY-MOCK-ATM-CE
    mock-candle-count: 180
    lot-size: 65
```

CSV input format:

```csv
timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
2026-04-12T03:45:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100.00,101.00,99.00,100.50,10000,100000
```

Global Datafeeds ZIP conversion:

```powershell
java com.kiteapioptions.backtest.GlobalDataFeedsOptionConverter `
  --source "C:\data\Nifty _Option_15.04.2025_to_15.04.2026_1 _Min_data" `
  --output "C:\data\backtest\imports\global-datafeeds" `
  --underlying "NIFTY" `
  --timeframe "ONE_MINUTE"
```

The converter reads each daily ZIP directly, converts `Ticker,Date,Time,Open,High,Low,Close,Volume,Open Interest`
into the backtest candle schema above, and writes:

- `C:/data/backtest/imports/global-datafeeds/by-day/YYYY/YYYY-MM-DD.csv`
- `C:/data/backtest/imports/global-datafeeds/by-contract/<trading-symbol>.csv`
- `C:/data/backtest/imports/global-datafeeds/manifest.csv`

Those outputs are sorted and normalized for later backtest selection work. The current backtest engine still replays a selected option stream, so the per-contract files are the directly usable artifacts.

Run:

```powershell
curl -X POST http://localhost:8080/backtest/run
```

Run the complete comparative suite from a single endpoint:

```powershell
curl.exe -X POST http://localhost:8080/backtest/analyze-variants -H "Content-Type: application/json" -d "{}"
```

Run the broader weekend validation matrix in one request:

```powershell
curl.exe -X POST http://localhost:8080/backtest/analyze-weekend-intensive -H "Content-Type: application/json" -d "{ \"underlying\": \"NIFTY\", \"to\": \"2026-04-17\" }"
```

That preset uses CE and PE, one-minute and five-minute candles, last-day through one-year windows, a train/validate split, and a broad parameter grid covering capital/risk, stop/target, score, volume, breakout, cutoff, trailing stop, lot size, RSI, and max-hold variants.

Before running the full matrix, use the quick endpoint to run only the baseline variant against PE 5-minute data:

```powershell
curl.exe -X POST http://localhost:8080/backtest/analyze-quick -H "Content-Type: application/json" -d "{ \"underlying\": \"NIFTY\", \"optionTypes\": [\"PE\"], \"timeframes\": [\"FIVE_MINUTE\"], \"to\": \"2026-04-15\", \"windows\": [{ \"name\": \"1-year\", \"from\": \"2025-04-15\", \"to\": \"2026-04-15\" }] }"
```

The suite endpoint creates a dated suite folder under `C:/data/backtest/results/suites/` and stores:

- `suite-request.json`
- `suite-summary.csv`
- `suite-summary.json`
- `suite-variant-ranking.csv`
- `suite-period-performance.csv`
- `suite-period-performance.json`
- `suite-report.html`
- separate run directories grouped by compact variant/window folders

Default suite variants run a broad comparison matrix: baseline, capital variants, lower-target variants, stricter filter variants, tighter-stop variants, entry cutoff variants, RSI/hold-time variants, and focused `60000 / 1%` follow-up variants. Imported Global Datafeeds year runs are prepared as a rolling daily option stream from `global-datafeeds/by-day`, not as one fixed contract for the full year. The period performance files break each successful run into weekly and monthly PnL rows for strategy analysis.

Custom variants can also be sent in the request body:

```json
{
  "underlying": "NIFTY",
  "timeframes": ["ONE_MINUTE"],
  "optionTypes": ["PE"],
  "variants": [
    { "name": "baseline" },
    { "name": "target-16", "targetPercent": 16 },
    { "name": "strict-score", "minSignalScorePercent": 75, "volumeSpikeMultiplier": 1.5 }
  ]
}
```

Outputs for single backtest runs are written under `C:/data/backtest/results/{id}/`:

- `metrics.csv`
- `trades.csv`
- `equity-curve.csv`

Sample formats are in [docs/sample-backtest-metrics.csv](docs/sample-backtest-metrics.csv), [docs/sample-backtest-trades.csv](docs/sample-backtest-trades.csv), [docs/sample-equity-curve.csv](docs/sample-equity-curve.csv), and [docs/sample-candles.csv](docs/sample-candles.csv).

## Paper Trading Instructions

Paper mode is the default. The current paper broker simulates fills using generated market prices plus configurable slippage, maintains virtual orders and positions in memory, and exposes mock quotes/candles. Later phases will connect this to strategy execution, persistence, logs, and trade journals.

Example paper settings:

```yaml
trading:
  mode: PAPER
  market-data-mode: ZERODHA
  execution-mode: PAPER
  live-trading-enabled: false
  paper:
    slippage-percent: 0.05
    starting-cash: 300000
```

To change mode and slippage, edit `src/main/resources/application.yml`:

```yaml
trading:
  mode: PAPER
  live-trading-enabled: false
  paper:
    slippage-percent: 0.05
```

Use `market-data-mode: ZERODHA` with `execution-mode: PAPER` to scan live Zerodha market data while keeping order placement simulated. Paper execution fills use the active market-data route, so this setup simulates fills from live Zerodha quotes instead of mock prices. Only set `execution-mode: ZERODHA` together with `live-trading-enabled: true` after deliberate review.

Runtime routing can also be changed without restart:

```powershell
curl.exe -X POST http://localhost:8080/routing -H "Content-Type: application/json" -d "{\"marketDataMode\":\"ZERODHA\",\"executionMode\":\"PAPER\"}"
```

## Live Deployment Precautions

Live trading must remain disabled unless all of these are true:

- You have reviewed the strategy, risk, and broker code.
- `trading.mode=LIVE` is set intentionally.
- `trading.live-trading-enabled=true` is set intentionally.
- Daily loss limits, max consecutive losses, stale data checks, duplicate order prevention, and kill switch behavior are tested.
- You understand Zerodha API limits, session expiry, order rejection behavior, and exchange market risk.

The Zerodha adapter rejects `placeOrder` whenever `trading.live-trading-enabled=false`, even if `trading.mode=LIVE`.

For live Zerodha scanning, set `trading.symbols.spot-quote-keys` to Kite quote keys and `trading.symbols.spot-historical-keys` to valid historical instrument tokens for the index/underlying. Option historical candles use the option instrument token from the downloaded instrument master.

## Known Limitations

- Scheduled entry scanning is implemented through REST quotes and historical candles; Zerodha WebSocket streaming is not implemented yet.
- Full exit orchestration is not implemented yet; trailing stop calculation and explicit trade close journaling exist, but no scheduler calls them.
- Zerodha historical candle lookup currently expects the instrument token as `instrumentKey`; later phases can resolve this through `InstrumentCache`.
- Backtest strategy is a simplified long-option candle replay using VWAP, breakout, volume spike, stop, target, and trailing stop logic; it does not reconstruct full live option-chain state.

These limitations are intentional so each phase can compile and remain reviewable.
