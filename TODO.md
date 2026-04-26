# adv-algo-trade — TODO & Review List

## Before Monday startup
- [ ] Restart app to create new DB columns (strategy_type, peak_price, entry_delta/theta/iv/gamma, paper_trading, itm_depth, minimum_move, minimum_strength_gap, minimum_volume, max_lots_per_trade)
- [ ] If column-not-found error: run `DELETE FROM strategy_configs; DELETE FROM iv_samples;` in H2 console, then restart
- [ ] Verify UI loads at http://localhost:8089/advalgotrade/ — check Strategies page shows PAPER badge on spread strategies
- [ ] Verify ITM_CONVICTION shows as paper mode with config fields

## Live strategies (paperTrading=false)
- DIRECTIONAL_BUY
- VOLATILITY_BREAKOUT
- SCALPING
- EVENT_DRIVEN_BUY

## Paper strategies (paperTrading=true) — tracking P&L with live prices, no broker orders
- All 13 spread/selling strategies + ITM_CONVICTION

## Future enhancements (not blocking)

### Features to implement
- [ ] Monte Carlo simulator — stress-test strategies across random price paths
- [ ] Straddle adjustment engine — shift losing legs instead of SL (needed when spreads go live)
- [ ] Smart order router — limit orders with auto-modification if unfilled
- [ ] Market open analyser — gap-up/gap-down analysis for first 15 min entry timing
- [ ] Payoff simulator — P&L diagram visualization
- [ ] Ratio spread strategy — 1:2 ratio spreads
- [ ] Multi-leg execution engine — place all spread legs atomically (required before spreads go live)
- [ ] Docker deployment

### Code quality
- [ ] Merge RiskManager into RiskEngine (RiskManager is monitor-only, not in execution path)
- [ ] Order idempotency — deterministic order IDs to prevent duplicates on retry
- [ ] Confidence score weight calibration — run feature importance on live signal data
- [ ] Partial profit execution — `ExecutionEngine.closePartial(tradeId, fraction, price)` for the 50%-at-30% feature
- [ ] Position averager — auto average down (controversial, evaluate after live data)

### Data quality
- [ ] Backtest slippage model — add configurable slippage % to BacktestEngine
- [ ] Cross-strategy cooldown per underlying
- [ ] Futures subscription for more accurate spot price (currently using spot quotes)

## What was done this weekend (summary)
- 60+ fixes and features implemented
- 139 tests passing, BUILD SUCCESS
- Strike parsing bug fixed (root cause of all spread backtest failures)
- Per-strategy paper trading with full P&L tracking
- ITM Conviction strategy added
- Exit order retry with MARKET escalation
- ATR-based dynamic SL/target/trailing wired into live exit monitor
- 7-factor regime scoring (VIX, IV rank, PCR, trend, OI wall, VIX trend, IV skew) all live
- News sentiment analysis, weekly exposure tracker, safe week predictor all wired
- Smart alert deduplication, token expiry monitor, option chain collector active
- All review comments from 4 external reviews addressed
