# TODO / Considerations

## Current Decision

Hold off on adding new strategies until the current strategy has been observed over multiple paper/live-market-data sessions.

## Observe Current Strategy First

- Track whether entries happen at the right time.
- Review rejected signals and confirm the rejection reasons are valid.
- Compare BUY signals against actual trade outcomes.
- Check whether exits are too early, too late, or appropriately timed.
- Monitor order fill quality, slippage, and rejected orders.
- Identify market regimes where the current strategy performs poorly.

## Data To Review

- `reports/entry-signals/entry-signals.csv`
- `reports/entry-signals/entry-evaluations.csv`
- `reports/entry-signals/entry-execution-outcomes.csv`
- Trades, orders, PnL, latest signals, and recent signals from the UI/API.

## High-Value Safety / Reliability Items

Consider these before adding more strategies:

- Order status sync from Kite.
- Position synchronizer between broker positions and local DB state.
- Fail-safe square-off daemon near market close.
- Dynamic exit manager with ATR-based trailing stop.
- Weekly exposure cap for option premium spent.

## Strategy Ideas To Revisit Later

- Advanced regime filter before entry evaluation.
- Volatility breakout strategy using Bollinger squeeze + breakout confirmation.
- Directional strategy enhancements with EMA crossover and higher-timeframe confirmation.
- Long straddle / long strangle only after IV rank and event-awareness are available.
- Event-driven strategy only after reliable event/calendar support exists.
- Multi-leg spreads only after single-leg execution, exits, and reconciliation are stable.

## Items To Avoid For Now

- Position averaging for losing long options.
- Tail hedging for the current long-option focused approach.
- Complex multi-leg strategies before execution reliability is proven.
