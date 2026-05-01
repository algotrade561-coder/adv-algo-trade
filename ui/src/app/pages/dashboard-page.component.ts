import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { catchError, forkJoin, of, interval, Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../core/api.service';
import { MarketSnapshot, PnlSnapshot, RuntimeStatus, StrategyDecision, TradingStatus } from '../core/models';

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">Dashboard</h1>
          <p class="page-subtitle">System health at a glance.</p>
        </div>
        <span class="spacer"></span>
        <span class="ts">{{ lastUpdatedAt ? 'Updated ' + lastUpdatedAt : '' }}</span>
      </div>

      @if (loadError) {
        <div class="err-bar"><mat-icon>error_outline</mat-icon> {{ loadError }}</div>
      }

      @if (!runtime) {
        <div class="loading-card"><mat-icon>hourglass_empty</mat-icon><span>Connecting to server…</span></div>
      } @else {

        <!-- ── Status Chips ──────────────────────────────────────────── -->
        <div class="chip-row">
          <div class="chip" [class.c-ok]="runtime.running" [class.c-warn]="!runtime.running">
            <mat-icon>{{ runtime.running ? 'play_circle' : 'pause_circle' }}</mat-icon> Scanner {{ runtime.running ? 'Running' : 'Stopped' }}
          </div>
          <div class="chip" [class.c-ok]="runtime.webSocketConnected" [class.c-bad]="!runtime.webSocketConnected">
            <mat-icon>{{ runtime.webSocketConnected ? 'wifi' : 'wifi_off' }}</mat-icon> WebSocket {{ runtime.webSocketConnected ? 'Connected' : 'Disconnected' }}
          </div>
          <div class="chip" [class.c-bad]="runtime.killSwitch" [class.c-ok]="!runtime.killSwitch">
            <mat-icon>{{ runtime.killSwitch ? 'block' : 'verified_user' }}</mat-icon> Kill Switch {{ runtime.killSwitch ? 'ON' : 'Clear' }}
          </div>
          <div class="chip" [class.c-bad]="runtime.haltMode === 'HARD'" [class.c-warn]="runtime.haltMode === 'SOFT'" [class.c-ok]="runtime.haltMode === 'NONE'">
            <mat-icon>pause_circle</mat-icon> {{ runtime.haltMode }}
          </div>
          <div class="chip" [class.c-ok]="runtime.dailyApproved" [class.c-warn]="!runtime.dailyApproved">
            <mat-icon>{{ runtime.dailyApproved ? 'thumb_up' : 'pending' }}</mat-icon> {{ runtime.dailyApproved ? 'Approved' : 'Not Approved' }}
          </div>
        </div>

        <!-- ── Market Indicators ─────────────────────────────────────── -->
        <div class="market-row">

          <div class="mcard index-card">
            <div class="mc-top">
              <span class="mc-label">NIFTY 50</span>
              <span class="mc-badge badge-muted">SPOT</span>
            </div>
            <div class="mc-value">{{ (market?.nifty ?? 0) > 0 ? (market!.nifty | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">{{ (market?.nifty ?? 0) > 0 ? 'Live from WebSocket' : 'Waiting for tick' }}</div>
          </div>

          <div class="mcard index-card">
            <div class="mc-top">
              <span class="mc-label">BANK NIFTY</span>
              <span class="mc-badge badge-muted">SPOT</span>
            </div>
            <div class="mc-value">{{ (market?.banknifty ?? 0) > 0 ? (market!.banknifty | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">{{ (market?.banknifty ?? 0) > 0 ? 'Live from WebSocket' : 'Waiting for tick' }}</div>
          </div>

          <div class="mcard"
            [class.mc-ok]="market?.vixStatus === 'NORMAL'"
            [class.mc-warn]="market?.vixStatus === 'ELEVATED' || market?.vixStatus === 'LOW'"
            [class.mc-bad]="market?.vixStatus === 'HIGH'"
            [class.mc-muted]="!market || market.vixStatus === 'UNKNOWN'">
            <div class="mc-top">
              <span class="mc-label">India VIX</span>
              <span class="mc-badge"
                [class.badge-ok]="market?.vixStatus === 'NORMAL'"
                [class.badge-warn]="market?.vixStatus === 'ELEVATED' || market?.vixStatus === 'LOW'"
                [class.badge-bad]="market?.vixStatus === 'HIGH'"
                [class.badge-muted]="!market || market.vixStatus === 'UNKNOWN'">
                {{ market?.vixStatus ?? '—' }}
              </span>
            </div>
            <div class="mc-value">{{ market ? (market.vix | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">
              @if (market?.vixStatus === 'LOW') { Options cheap — IV may not expand }
              @else if (market?.vixStatus === 'NORMAL') { Sweet spot — good for buying }
              @else if (market?.vixStatus === 'ELEVATED') { Getting expensive — be selective }
              @else if (market?.vixStatus === 'HIGH') { Danger — avoid new entries }
              @else { Waiting for live VIX tick }
            </div>
          </div>

          <div class="mcard"
            [class.mc-ok]="market?.pcrBias === 'BULLISH'"
            [class.mc-warn]="market?.pcrBias === 'NEUTRAL'"
            [class.mc-bad]="market?.pcrBias === 'BEARISH'"
            [class.mc-muted]="!market || market.pcrBias === 'UNKNOWN'">
            <div class="mc-top">
              <span class="mc-label">PCR (Put/Call Ratio)</span>
              <span class="mc-badge"
                [class.badge-ok]="market?.pcrBias === 'BULLISH'"
                [class.badge-warn]="market?.pcrBias === 'NEUTRAL'"
                [class.badge-bad]="market?.pcrBias === 'BEARISH'"
                [class.badge-muted]="!market || market.pcrBias === 'UNKNOWN'">
                {{ market?.pcrBias ?? '—' }}
              </span>
            </div>
            <div class="mc-value">{{ market ? (market.pcr | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">
              @if (market?.pcrBias === 'BULLISH') { Heavy put writing — supports CE buying }
              @else if (market?.pcrBias === 'NEUTRAL') { Balanced OI — no strong bias }
              @else if (market?.pcrBias === 'BEARISH') { Heavy call writing — supports PE buying }
              @else { Waiting for option chain data }
            </div>
          </div>

          <div class="mcard"
            [class.mc-ok]="market?.safeForLongPremium === true"
            [class.mc-bad]="market?.safeForLongPremium === false"
            [class.mc-muted]="!market">
            <div class="mc-top">
              <span class="mc-label">Long Premium</span>
              <span class="mc-badge"
                [class.badge-ok]="market?.safeForLongPremium === true"
                [class.badge-bad]="market?.safeForLongPremium === false"
                [class.badge-muted]="!market">
                {{ market ? (market.safeForLongPremium ? 'SAFE' : 'BLOCKED') : '—' }}
              </span>
            </div>
            <div class="mc-value">
              <mat-icon style="font-size:28px;width:28px;height:28px;vertical-align:middle">
                {{ !market ? 'help_outline' : market.safeForLongPremium ? 'check_circle' : 'cancel' }}
              </mat-icon>
            </div>
            <div class="mc-desc">
              @if (market?.safeForLongPremium) { MarketGuard conditions passed }
              @else if (market?.longPremiumBlockReason) { {{ market!.longPremiumBlockReason }} }
              @else { Waiting for market data }
            </div>
          </div>

          <!-- Performance metrics inline in the same card row -->
          <div class="mcard mc-muted">
            <div class="mc-top"><span class="mc-label">Profit Factor</span></div>
            <div class="mc-value">{{ perf?.tradesToday ? (perf.profitFactorToday | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">W₹{{ (perf?.avgWinToday ?? 0) | number:'1.0-0' }} L₹{{ (perf?.avgLossToday ?? 0) | number:'1.0-0' }}</div>
          </div>
          <div class="mcard mc-muted">
            <div class="mc-top"><span class="mc-label">Win Rate</span></div>
            <div class="mc-value">{{ perf?.tradesToday ? (perf.winRateToday | number:'1.0-0') + '%' : '—' }}</div>
            <div class="mc-desc">{{ perf?.winsToday ?? 0 }}W {{ perf?.lossesToday ?? 0 }}L</div>
          </div>
          <div class="mcard mc-muted">
            <div class="mc-top"><span class="mc-label">Drawdown</span></div>
            <div class="mc-value">{{ perf?.tradesToday ? '₹' + (perf.maxDrawdownToday | number:'1.0-0') : '—' }}</div>
            <div class="mc-desc">Peak ₹{{ (perf?.peakPnlToday ?? 0) | number:'1.0-0' }}</div>
          </div>
          <div class="mcard mc-muted">
            <div class="mc-top"><span class="mc-label">7d WR</span></div>
            <div class="mc-value">{{ perf?.tradesLast7 ? (perf.winRateLast7 | number:'1.0-0') + '%' : '—' }}</div>
            <div class="mc-desc">PF {{ (perf?.profitFactorLast7 ?? 0) | number:'1.1-1' }}</div>
          </div>

        </div>

        <!-- ── PnL Row ───────────────────────────────────────────────── -->
        @if (tradingStatus) {
          <div class="entry-status" [class.es-ok]="tradingStatus.entryAllowed" [class.es-bad]="!tradingStatus.entryAllowed">
            <div class="es-header">
              <mat-icon>{{ tradingStatus.entryAllowed ? 'check_circle' : 'block' }}</mat-icon>
              <strong>{{ tradingStatus.entryAllowed ? 'Entries Allowed' : 'Entries Blocked' }}</strong>
              <span class="es-stats">
                Open: {{ tradingStatus.openTrades }} &nbsp;|&nbsp;
                Paper: {{ tradingStatus.openPaperTrades }} &nbsp;|&nbsp;
                Today: {{ tradingStatus.tradesToday }} &nbsp;|&nbsp;
                Losses: {{ tradingStatus.consecutiveLosses }} &nbsp;|&nbsp;
                P&amp;L: <span [class.pos]="tradingStatus.dailyPnl >= 0" [class.neg]="tradingStatus.dailyPnl < 0">₹{{ tradingStatus.dailyPnl | number:'1.0-0' }}</span>
                / ₹{{ tradingStatus.effectiveDailyLossLimit | number:'1.0-0' }} limit
              </span>
            </div>
            @if (tradingStatus.blockingReasons.length > 0) {
              <ul class="es-reasons">
                @for (r of tradingStatus.blockingReasons; track r) {
                  <li><mat-icon>arrow_right</mat-icon>{{ r }}</li>
                }
              </ul>
            }
          </div>
        }

        <div class="pnl-row">
          <div class="pnl-card">
            <mat-icon class="pi">account_balance_wallet</mat-icon>
            <div><span class="pl">Live Realized PnL</span><span class="pv" [class.pos]="(pnl?.realizedPnl ?? 0) >= 0" [class.neg]="(pnl?.realizedPnl ?? 0) < 0">₹{{ (pnl?.realizedPnl ?? 0) | number:'1.2-2' }}</span></div>
          </div>
          <div class="pnl-card">
            <mat-icon class="pi">trending_up</mat-icon>
            <div><span class="pl">Live Unrealized PnL</span><span class="pv" [class.pos]="(pnl?.unrealizedPnl ?? 0) >= 0" [class.neg]="(pnl?.unrealizedPnl ?? 0) < 0">₹{{ (pnl?.unrealizedPnl ?? 0) | number:'1.2-2' }}</span></div>
          </div>
          <div class="pnl-card pnl-total">
            <mat-icon class="pi pi-accent">assessment</mat-icon>
            <div><span class="pl">Live Total PnL</span><span class="pv pv-big" [class.pos]="(pnl?.totalPnl ?? 0) >= 0" [class.neg]="(pnl?.totalPnl ?? 0) < 0">₹{{ (pnl?.totalPnl ?? 0) | number:'1.2-2' }}</span></div>
          </div>
          <div class="pnl-card pnl-paper">
            <mat-icon class="pi pi-paper">description</mat-icon>
            <div><span class="pl">Paper PnL</span><span class="pv" [class.pos]="(tradingStatus?.paperPnl ?? 0) >= 0" [class.neg]="(tradingStatus?.paperPnl ?? 0) < 0">₹{{ (tradingStatus?.paperPnl ?? 0) | number:'1.2-2' }}</span></div>
          </div>
        </div>

        <!-- ── Runtime & Signal ───────────────────────────────────────── -->
        <div class="grid two">
          <div class="panel">
            <h2><mat-icon class="hi">settings</mat-icon> Runtime</h2>
            <div class="detail-grid">
              <div class="dg"><span>Mode</span><strong>{{ runtime.configuredMode }}</strong></div>
              <div class="dg"><span>Requested</span><strong>{{ runtime.requestedMode }}</strong></div>
              <div class="dg"><span>Market Data</span><strong>{{ runtime.marketDataMode }}</strong></div>
              <div class="dg"><span>Execution</span><strong>{{ runtime.executionMode }}</strong></div>
              <div class="dg"><span>Live Trading</span><strong [class.pos]="runtime.liveTradingEnabled" [class.neg]="!runtime.liveTradingEnabled">{{ runtime.liveTradingEnabled ? 'Enabled' : 'Blocked' }}</strong></div>
              <div class="dg"><span>Underlyings</span><strong>{{ runtime.enabledUnderlyings.join(', ') || '-' }}</strong></div>
              <div class="dg"><span>Halt Mode</span><strong [class.neg]="runtime.haltMode === 'HARD'" [class.warn]="runtime.haltMode === 'SOFT'" [class.pos]="runtime.haltMode === 'NONE'">{{ runtime.haltMode }}</strong></div>
              <div class="dg"><span>Daily Approved</span><strong [class.pos]="runtime.dailyApproved" [class.warn]="!runtime.dailyApproved">{{ runtime.dailyApproved ? 'Yes' : 'No' }}</strong></div>
            </div>
          </div>

          <div class="panel">
            <h2><mat-icon class="hi">radar</mat-icon> Scan & Signal Status</h2>
            <div class="detail-grid">
              <div class="dg"><span>Scanner</span><strong [class.pos]="runtime.running" [class.neg]="!runtime.running">{{ runtime.running ? 'Active' : 'Stopped' }}</strong></div>
              <div class="dg"><span>WebSocket</span><strong [class.pos]="runtime.webSocketConnected" [class.neg]="!runtime.webSocketConnected">{{ runtime.webSocketConnected ? 'Connected' : 'Disconnected' }}</strong></div>
              <div class="dg"><span>Last Scan</span><strong>{{ lastScanTime }}</strong></div>
              <div class="dg"><span>Open Trades</span><strong>{{ tradingStatus?.openTrades ?? 0 }}</strong></div>
              <div class="dg"><span>Paper Trades</span><strong style="color:#a882ff">{{ tradingStatus?.openPaperTrades ?? 0 }}</strong></div>
              <div class="dg"><span>Trades Today</span><strong>{{ tradingStatus?.tradesToday ?? 0 }}</strong></div>
              <div class="dg"><span>Entry Signals</span><strong class="pos">{{ tradingStatus?.entrySignals ?? 0 }}</strong></div>
              <div class="dg"><span>Rejected</span><strong class="neg">{{ tradingStatus?.rejectedSignals ?? 0 }}</strong></div>
            </div>
            @if (latestSignal) {
              <div class="scan-last-signal">
                <span class="sb" [class.sb-buy]="latestSignal.signalType?.startsWith('BUY')" [class.sb-no]="latestSignal.signalType === 'NO_TRADE'">{{ latestSignal.signalType }}</span>
                <span class="sb">{{ latestSignal.underlying }}</span>
                @if (asAny(latestSignal)?.ivRankSource) {
                  <span [class]="asAny(latestSignal).ivRankSource === 'TRACKER' ? 'sb sb-tracker' : 'sb sb-neutral'">
                    IV {{ asAny(latestSignal).ivRankSource }}
                  </span>
                }
                <span class="scan-time">{{ latestSignal.selectedInstrumentKey ?? '' }}</span>
              </div>
            } @else {
              <div class="scan-idle"><mat-icon>hourglass_empty</mat-icon> Waiting for first signal</div>
            }
          </div>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .top-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .ts { font-size: 12px; color: var(--muted); }

    .err-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 10px 16px; border-radius: 8px; font-size: 13px; background: rgba(255,113,106,.06); border: 1px solid rgba(255,113,106,.25); color: var(--bad); }
    .err-bar mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .loading-card { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 60px 0; color: var(--muted); font-size: 15px; }
    .loading-card mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .4; }

    /* Chips */
    .chip-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; }
    .chip { display: flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 20px; font-size: 12px; font-weight: 600; border: 1px solid var(--line); background: var(--panel); }
    .chip mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .c-ok   { border-color: rgba(69,209,140,.4);  color: var(--ok);   background: rgba(69,209,140,.05); }
    .c-warn { border-color: rgba(242,189,75,.4);  color: var(--warn); background: rgba(242,189,75,.05); }
    .c-bad  { border-color: rgba(255,113,106,.4); color: var(--bad);  background: rgba(255,113,106,.05); }
    .c-muted { color: var(--muted); }

    /* Entry status */
    .entry-status { border-radius: 10px; padding: 12px 16px; margin-bottom: 16px; border: 1px solid var(--line); }
    .es-ok  { background: rgba(69,209,140,.05);  border-color: rgba(69,209,140,.35); }
    .es-bad { background: rgba(255,113,106,.05); border-color: rgba(255,113,106,.35); }
    .es-header { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
    .es-header mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .es-ok  .es-header { color: var(--ok); }
    .es-bad .es-header { color: var(--bad); }
    .es-stats { font-size: 12px; color: var(--muted); margin-left: auto; }
    .es-reasons { margin: 10px 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 4px; }
    .es-reasons li { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--bad); }
    .es-reasons mat-icon { font-size: 14px; width: 14px; height: 14px; }

    /* Market cards */
    .market-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-bottom: 20px; }
    .mcard { padding: 16px 18px; border-radius: 12px; border: 1px solid var(--line); background: var(--panel); }
    .mc-ok    { border-color: rgba(69,209,140,.35);  background: rgba(69,209,140,.04); }
    .mc-warn  { border-color: rgba(242,189,75,.35);  background: rgba(242,189,75,.04); }
    .mc-bad   { border-color: rgba(255,113,106,.35); background: rgba(255,113,106,.04); }
    .mc-muted { border-color: var(--line); }
    .index-card { border-color: rgba(97,168,255,.35); background: rgba(97,168,255,.04); }
    .mc-top   { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
    .mc-label { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .mc-value { font-size: 28px; font-weight: 800; color: var(--ink); margin-bottom: 6px; line-height: 1.2; }
    .mc-desc  { font-size: 11px; color: var(--muted); line-height: 1.4; }
    .mc-badge { padding: 2px 8px; border-radius: 20px; font-size: 10px; font-weight: 700; }
    .badge-ok    { background: rgba(69,209,140,.15);  color: var(--ok); }
    .badge-warn  { background: rgba(242,189,75,.15);  color: var(--warn); }
    .badge-bad   { background: rgba(255,113,106,.15); color: var(--bad); }
    .badge-muted { background: rgba(255,255,255,.05); color: var(--muted); }

    /* PnL */
    .pnl-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin-bottom: 20px; }
    .pnl-card { display: flex; align-items: center; gap: 14px; padding: 18px 20px; border-radius: 12px; border: 1px solid var(--line); background: var(--panel); }
    .pnl-total { border-color: rgba(97,168,255,.3); background: rgba(97,168,255,.04); }
    .pnl-paper { border-color: rgba(168,130,255,.3); background: rgba(168,130,255,.04); }
    .pi { font-size: 24px; width: 24px; height: 24px; color: var(--muted); }
    .pi-accent { color: var(--accent); }
    .pi-paper { color: #a882ff; }
    .pl { display: block; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .pv { display: block; font-size: 20px; font-weight: 700; color: var(--ink); margin-top: 2px; }
    .pv-big { font-size: 24px; }
    .pos  { color: var(--ok)   !important; }
    .neg  { color: var(--bad)  !important; }
    .warn { color: var(--warn) !important; }

    /* Panels */
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0 0 14px; display: flex; align-items: center; gap: 6px; }
    .hi { font-size: 18px; width: 18px; height: 18px; color: var(--accent); }
    .detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 8px; }
    .dg { padding: 10px 12px; border-radius: 8px; background: rgba(255,255,255,.02); border: 1px solid var(--line); }
    .dg span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .dg strong { display: block; font-size: 13px; color: var(--ink); margin-top: 3px; word-break: break-all; }

    /* Signal */
    .signal-badges { display: flex; gap: 6px; flex-wrap: wrap; }
    .sb { padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; background: rgba(255,255,255,.04); border: 1px solid var(--line); color: var(--muted); }
    .sb-buy   { background: rgba(69,209,140,.1);  border-color: rgba(69,209,140,.3);  color: var(--ok); }
    .sb-no    { background: rgba(255,113,106,.08); border-color: rgba(255,113,106,.25); color: var(--bad); }
    .sb-strat { background: rgba(97,168,255,.1);  border-color: rgba(97,168,255,.3);  color: var(--accent); }
    .scan-last-signal { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--line); }
    .scan-time { font-size: 11px; color: var(--muted); margin-left: auto; }
    .scan-idle { display: flex; align-items: center; gap: 6px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; }
    .scan-idle mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .reasons { font-size: 12px; color: var(--muted); margin: 12px 0 0; line-height: 1.5; word-break: break-word; }
    .empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 36px 0; color: var(--muted); font-size: 13px; }
    .empty mat-icon { font-size: 32px; width: 32px; height: 32px; opacity: .35; }
    .sb-tracker { background: rgba(69,209,140,.1)  !important; border-color: rgba(69,209,140,.3)  !important; color: var(--ok)   !important; }
    .sb-neutral { background: rgba(242,189,75,.1)  !important; border-color: rgba(242,189,75,.3)  !important; color: var(--warn) !important; }
  `]
})
export class DashboardPageComponent implements OnInit, OnDestroy {
  runtime?: RuntimeStatus;
  pnl?: PnlSnapshot;
  market?: MarketSnapshot;
  tradingStatus?: TradingStatus;
  perf?: any;
  latestSignal?: StrategyDecision | null;
  loading = false;
  loadError = '';
  lastUpdatedAt = '';
  private inFlight = false;
  private marketPollSub?: Subscription;
  private signalPollSub?: Subscription;

  get lastScanTime(): string {
    const s = this.tradingStatus?.lastScanAt;
    if (!s) return '—';
    try { return new Date(s).toLocaleTimeString(); } catch { return '—'; }
  }

  constructor(private readonly api: ApiService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    setTimeout(() => this.load(), 0);
    // Market data (Nifty, BankNifty, VIX, PCR, trading status) — 1 s for live index refresh
    this.marketPollSub = interval(1000).subscribe(() => this.refreshMarket());
    // Signals and P&L change on candle close (every 1–15 min) — 3 s is plenty
    this.signalPollSub = interval(3000).subscribe(() => this.refreshSignal());
  }

  ngOnDestroy(): void {
    this.marketPollSub?.unsubscribe();
    this.signalPollSub?.unsubscribe();
  }

  load(): void {
    if (this.inFlight) return;
    this.inFlight = true;
    this.loading = true;
    this.loadError = '';
    this.api.config().subscribe({
      next: c => {
        const rt = 'runtime' in c ? c.runtime : ('running' in c ? c as unknown as RuntimeStatus : undefined);
        if (!rt) { this.fail('No runtime in response'); return; }
        this.runtime = rt;
        this.loading = false;
        this.inFlight = false;
        this.lastUpdatedAt = new Date().toLocaleTimeString();
        this.cd.detectChanges();
        this.loadExtra();
      },
      error: () => this.fail()
    });
  }

  private refreshMarket(): void {
    forkJoin({
      market: this.api.market().pipe(catchError(() => of(null as MarketSnapshot | null))),
      status: this.api.tradingStatus().pipe(catchError(() => of(null as TradingStatus | null))),
      perf: this.api.performance().pipe(catchError(() => of(null)))
    }).subscribe(({ market, status, perf }) => {
      if (market) this.market = market;
      if (perf) this.perf = perf;
      if (status) this.tradingStatus = status;
      this.lastUpdatedAt = new Date().toLocaleTimeString();
      this.cd.detectChanges();
    });
  }

  private refreshSignal(): void {
    forkJoin({
      sig: this.api.latestSignal().pipe(catchError(() => of(null as StrategyDecision | null))),
      pnl: this.api.pnl().pipe(catchError(() => of(null as PnlSnapshot | null)))
    }).subscribe(({ sig, pnl }) => {
      this.latestSignal = sig;
      if (pnl) this.pnl = pnl;
      this.cd.detectChanges();
    });
  }

  private loadExtra(): void {
    forkJoin({
      pnl:    this.api.pnl().pipe(catchError(() => of(null as PnlSnapshot | null))),
      sig:    this.api.latestSignal().pipe(catchError(() => of(null as StrategyDecision | null))),
      market: this.api.market().pipe(catchError(() => of(null as MarketSnapshot | null))),
      ts:     this.api.tradingStatus().pipe(catchError(() => of(null as TradingStatus | null)))
    }).subscribe(({ pnl, sig, market, ts }) => {
      if (pnl)    this.pnl    = pnl;
      if (market) this.market = market;
      if (ts)     this.tradingStatus = ts;
      this.latestSignal = sig;
      this.cd.detectChanges();
    });
  }

  asAny(v: unknown): any { return v as any; }

  private fail(msg = 'Unable to reach server. Click Refresh.'): void {
    this.loading = false;
    this.inFlight = false;
    this.loadError = msg;
    this.cd.detectChanges();
  }
}
