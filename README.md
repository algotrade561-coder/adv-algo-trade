# AlgoTrader Pro — Kite API Options

Automated NIFTY/BANKNIFTY options trading system built with Java 21, Spring Boot 3.x, and Angular 19. Supports 13 strategy types, real-time WebSocket tick data, paper/live execution via Zerodha Kite Connect, and historical backtesting with Global Data Feeds CSV data.

> **Disclaimer:** This is for educational and controlled trading use only. It does not make profit claims and must not be treated as financial advice.

## Key Features

- **13 Trading Strategies** — Directional Buy, Scalping (EMA 9/21), Volatility Breakout (Bollinger), Event-Driven Buy, Bull/Bear Spreads, Long/Short Straddle & Strangle, Iron Condor, Butterfly, Calendar Spread
- **WebSocket Primary Trigger** — Kite binary tick stream → LiveCandleBuilder → CandleClosedEvent → instant strategy evaluation
- **REST Poll Fallback** — 60s scheduler as backup when WebSocket is disconnected (disabled by default, toggleable from UI)
- **Risk Management** — Daily loss limits, consecutive loss tracking, position sizing, kill switch, MarketGuard (VIX-based), adaptive position sizer
- **Paper & Live Execution** — Paper broker with simulated fills, Zerodha broker with real order placement, runtime routing toggle
- **Angular Control Console** — Dashboard, Execution controls, Strategy management, Monitoring, Reports, Backtest, Config, Kite Auth
- **DB-Backed Strategy Config** — All strategy parameters (stop-loss, target, trailing, lots, IV rank) editable at runtime from UI
- **Signal Persistence** — Every signal (BUY and NO_TRADE) persisted to H2 with full context for later analysis
- **Backtesting** — Historical candle replay from Global Data Feeds CSV with metrics, equity curve, and trade journal
- **Telegram Alerts** — Entry signals, rejections, order fills, state changes

## Architecture

```
WebSocket Ticks → LiveCandleBuilder → CandleClosedEvent
                                          ↓
                                   AlgoTradingScheduler
                                    ↓              ↓
                          Directional Buy    Additional Strategies
                          (RuleBasedOptions)  (Scalping, Spreads, etc.)
                                    ↓              ↓
                              enrichWithOptionData (ATM lookup)
                                    ↓              ↓
                              RiskEngine → ExecutionEngine → BrokerClient
                                                               ↓
                                                    Paper or Zerodha orders
```

## Quick Start

Prerequisites: Java 21, Maven 3.9+, Node.js 20+ (for UI)

```powershell
# Build UI into Spring Boot static resources
.\build-ui.ps1

# Start the application
mvn spring-boot:run

# Or use the all-in-one script
.\run-local-app.ps1
```

Open: `http://localhost:8089/advalgotrade/`

## Configuration

All config lives in `src/main/resources/application.yml`. Secrets go in `data/trading-secrets.properties` (git-ignored).

### Key Settings

| Setting | Default | Description |
|---|---|---|
| `trading.mode` | LIVE | PAPER, BACKTEST, or LIVE |
| `trading.live-trading-enabled` | true | Must be true for real Zerodha orders |
| `trading.algo.scheduler-enabled` | false | REST poll fallback (WebSocket is primary) |
| `trading.risk.total-capital` | 60000 | Total trading capital in INR |
| `trading.risk.max-risk-per-trade-percent` | 5 | Max risk per trade |
| `trading.entry.min-signal-score-percent` | 70 | Minimum confidence score for entry |
| `server.servlet.context-path` | /advalgotrade | All endpoints prefixed with this |

### Strategy Parameters

Strategy parameters (stop-loss, target, trailing, max hold, lots) are stored in H2 and editable from the Strategies UI page. Defaults are seeded on first access:

| Strategy | Stop Loss | Target | Trailing | Max Hold |
|---|---|---|---|---|
| Directional Buy | 12% | 25% | 10% / 5% | 30 min |
| Scalping | 20% | 40% | - | 30 min |
| Volatility Breakout | 35% | 100% | - | - |
| Event-Driven Buy | 35% | 80% | - | - |
| Bull/Bear Spread | 50% | 80% | - | - |

Option selling strategies (Short Straddle, Short Strangle, Iron Condor, Butterfly, Calendar Spread) are disabled by default and require explicit opt-in from the UI.

## Zerodha Authentication

1. Set credentials in `data/trading-secrets.properties`:
   ```properties
   trading.broker.api-key=YOUR_API_KEY
   trading.broker.api-secret=YOUR_API_SECRET
   ```
2. Configure redirect URL in Kite developer console to your ngrok HTTPS URL + `/advalgotrade/auth/kite/callback`
3. Start the app — it auto-opens the Kite login URL on startup
4. Complete login — callback captures the access token
5. WebSocket connects automatically after auth

## UI Pages

| Page | Description |
|---|---|
| Dashboard | System status, PnL, latest signal (read-only) |
| Strategies | Enable/disable and configure all 13 strategies |
| Execution | Scanner start/stop, WebSocket connect/disconnect, scheduler toggle, mode/routing, manual orders |
| Monitoring | Positions, orders, trades, PnL, recent signals |
| Reports | Trade journal CSV, signal archive, entry signal replay |
| Backtest | Run backtests using Global Data Feeds CSV data |
| Config | View all trading parameters |
| Kite Auth | Login flow and session management |

## REST API

All endpoints are under `/advalgotrade/`:

| Method | Path | Description |
|---|---|---|
| GET | /config | Full configuration snapshot |
| POST | /start, /stop | Scanner control |
| POST | /kill-switch | Emergency stop |
| POST | /scheduler | Toggle REST poll scheduler |
| POST | /websocket/reconnect | Reconnect WebSocket |
| POST | /websocket/disconnect | Disconnect WebSocket |
| POST | /orders/place | Manual order placement |
| POST | /mode | Change trading mode |
| POST | /routing | Change market data / execution routing |
| GET | /signals/entries/paged | Paginated entry signals |
| GET | /signals/rejected/paged | Paginated rejected signals |
| GET | /signals/summary | Signal counts by strategy |
| GET | /strategies | All strategy configs |
| POST | /strategies/{type}/enable | Enable a strategy |
| PUT | /strategies/{type} | Update strategy parameters |
| POST | /backtest/run | Run backtest |
| GET | /positions, /orders, /trades, /pnl | Monitoring |

## Backtesting

Place Global Data Feeds CSV files in `C:\data\backtest\imports\`. Run from the Backtest UI page or:

```powershell
curl -X POST http://localhost:8089/advalgotrade/backtest/run `
  -H "Content-Type: application/json" `
  -d '{"underlying":"NIFTY","optionType":"CE","timeframe":"FIVE_MINUTE"}'
```

## Lot Sizes (NSE Jan 2026)

| Index | Lot Size | Strike Interval |
|---|---|---|
| NIFTY | 65 | 50 |
| BANKNIFTY | 30 | 100 |
| FINNIFTY | 60 | 50 |
| MIDCPNIFTY | 120 | 25 |

## Safety

- Kill switch stops all trading immediately
- Daily loss limit: 3% of capital
- Max consecutive losses: 2
- One open trade at a time (configurable)
- Stale market data detection (10s threshold)
- Forced square-off at 15:15 IST
- Selling strategies disabled by default
- Zerodha adapter blocks orders when `live-trading-enabled=false`
