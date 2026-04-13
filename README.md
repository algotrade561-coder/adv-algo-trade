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
- `GET /scan/underlyings`
- `POST /scan/underlyings/{underlying}`
- `GET /positions`
- `GET /orders`
- `GET /trades`
- `GET /trades/journal.csv`
- `GET /pnl`
- `GET /signals/latest`
- `POST /backtest/run`
- `GET /backtest/results/{id}`

## Configuration

Runtime defaults live in `src/main/resources/application.yml`. Use `application.yml.example` as a shareable template. Do not commit secrets or access tokens. Zerodha expects a fresh manual login/access token each trading day.

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
```

## Zerodha Manual Login

The app uses Spring Boot itself as the localhost listener on port `8080`; it does not start a second raw `HttpServer`, because that would conflict with the application port. Configure your Kite developer redirect URL to your ngrok HTTPS URL that forwards to:

```text
http://localhost:8080/auth/kite/callback
```

Example:

```text
https://your-ngrok-domain.ngrok-free.app/auth/kite/callback
```

Set credentials through environment variables or `src/main/resources/application.yml`:

```yaml
trading:
  broker:
    api-key: ${KITE_API_KEY:}
    api-secret: ${KITE_API_SECRET:}
    redirect-url: ${KITE_REDIRECT_URL:http://localhost:8081/auth/kite/callback}
    auto-login-on-startup: true
```

With `trading.mode=LIVE` and no access token present, startup starts a temporary callback listener on the configured redirect port, opens/logs the Kite login URL automatically, and blocks until the callback captures the access token. Use a redirect URL on a different port from Spring Boot, for example `http://localhost:8081/auth/kite/callback`. If the login callback is not received within the configured login timeout, startup fails instead of continuing without a token. You can also open:

```text
http://localhost:8080/auth/kite/login
```

Copy/open the returned `loginUrl`, complete Kite login manually, and Zerodha will redirect to the ngrok callback. The callback exchanges `request_token` for `access_token` and stores it in memory for this app run. If you already have a daily access token, you can also set `KITE_ACCESS_TOKEN`.

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
    csv-import-path: data/backtest/input.csv
    output-directory: reports/backtest
    mock-instrument-key: NFO:NIFTY-MOCK-ATM-CE
    mock-candle-count: 180
    lot-size: 75
```

CSV input format:

```csv
timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
2026-04-12T03:45:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100.00,101.00,99.00,100.50,10000,100000
```

Run:

```powershell
curl -X POST http://localhost:8080/backtest/run
```

If `trading.backtest.csv-import-path` does not exist, the engine uses generated mock candles so the endpoint remains runnable locally. Outputs are written under `reports/backtest/{id}/`:

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

Only set `mode: LIVE` together with `live-trading-enabled: true` after deliberate review.

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
