import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { catchError, forkJoin, of, interval, Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { MarketSnapshot, OilPriceSnapshot, PnlSnapshot, RuntimeStatus, StrategyDecision, TradingStatus } from '../core/models';
import { PcrChartComponent } from '../shared/pcr-chart.component';

interface TuningHealth {
  generatedAt?: string;
  captureToggles?: {
    totalStrategies?: number;
    enabledStrategies?: number;
    enabledList?: string[];
  };
  todayEvents?: {
    EVALUATION?: number;
    SIGNAL?: number;
    EXECUTION?: number;
    EXIT?: number;
    FORWARD_CHECKPOINT?: number;
    SHADOW_GATE?: number;
    LEG?: number;
  };
  recorder?: {
    present?: boolean;
    totalWrites?: number;
    totalFailures?: number;
    avgWriteLatencyMicros?: number;
  };
  lastRollerRun?: {
    present?: boolean;
    at?: string | null;
    success?: boolean | null;
    datesProcessed?: number;
    filesRolled?: number;
    retentionPurged?: number;
    errorMessage?: string | null;
  };
  lastForwardSweep?: {
    present?: boolean;
    at?: string | null;
    success?: boolean | null;
    newCheckpoints?: number;
    errorMessage?: string | null;
  };
  duckdbTempDir?: {
    path?: string;
    exists?: boolean;
    freeBytes?: number | null;
    error?: string;
  };
  latestReport?: {
    present?: boolean;
    jobId?: string;
    status?: string;
    requestedAt?: string | null;
    fromDate?: string | null;
    toDate?: string | null;
    durationSec?: number | null;
    hasHtml?: boolean;
  };
}

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule, RouterLink, PcrChartComponent],
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

          <div class="mcard index-card">
            <div class="mc-top">
              <span class="mc-label">SENSEX</span>
              <span class="mc-badge badge-muted">SPOT</span>
            </div>
            <div class="mc-value">{{ (market?.sensex ?? 0) > 0 ? (market!.sensex | number:'1.2-2') : '—' }}</div>
            <div class="mc-desc">{{ (market?.sensex ?? 0) > 0 ? 'Live from WebSocket' : 'Waiting for tick' }}</div>
          </div>

          <div class="mcard index-card">
            <div class="mc-top">
              <span class="mc-label">IV Rank</span>
              <span class="mc-badge" [class.badge-ok]="(market?.ivRank ?? 50) < 30"
                [class.badge-warn]="(market?.ivRank ?? 50) >= 30 && (market?.ivRank ?? 50) < 60"
                [class.badge-bad]="(market?.ivRank ?? 50) >= 60">
                {{ (market?.ivRank ?? 50) < 30 ? 'LOW' : (market?.ivRank ?? 50) < 60 ? 'MID' : 'HIGH' }}
              </span>
            </div>
            <div class="mc-value">{{ market?.ivRank ? (market!.ivRank | number:'1.1-1') + '%' : '—' }}</div>
            <div class="mc-desc">NIFTY IV percentile rank</div>
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

          <div class="mcard oil-card"
            [class.mc-ok]="oilPrice?.regime === 'LOW' || oilPrice?.regime === 'NORMAL'"
            [class.mc-warn]="oilPrice?.regime === 'HIGH'"
            [class.mc-bad]="oilPrice?.regime === 'CRISIS'"
            [class.mc-muted]="!oilPrice">
            <div class="mc-top">
              <span class="mc-label">Brent Crude Oil</span>
              <span class="mc-badge"
                [class.badge-ok]="oilPrice?.regime === 'LOW' || oilPrice?.regime === 'NORMAL'"
                [class.badge-warn]="oilPrice?.regime === 'HIGH'"
                [class.badge-bad]="oilPrice?.regime === 'CRISIS'"
                [class.badge-muted]="!oilPrice">
                {{ oilPrice?.regime ?? '—' }}
              </span>
            </div>
            <div class="mc-value">
              @if (oilPrice) {
                &#36;{{ oilPrice.priceUSD | number:'1.2-2' }}
                <span class="oil-change" [class.pos]="oilPrice.dailyChangePct >= 0" [class.neg]="oilPrice.dailyChangePct < 0">
                  {{ oilPrice.dailyChangePct >= 0 ? '+' : '' }}{{ oilPrice.dailyChangePct | number:'1.2-2' }}%
                </span>
              } @else {
                —
              }
            </div>
            <div class="mc-desc">
              @if (oilPrice?.regime === 'LOW') { Low oil — risk-on sentiment }
              @else if (oilPrice?.regime === 'NORMAL') { Normal range — neutral impact }
              @else if (oilPrice?.regime === 'HIGH') { Elevated — watch for risk-off }
              @else if (oilPrice?.regime === 'CRISIS') { Crisis level — risk-off mode }
              @else { Waiting for Brent data }
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

        </div>

        <!-- ── Intraday PCR daily chart (per index, 09:00–15:30) ─────── -->
        <app-pcr-chart />

        <!-- ── Entry Status ──────────────────────────────────────────── -->
        @if (tradingStatus) {
          <div class="entry-status" [class.es-ok]="tradingStatus.entryAllowed" [class.es-bad]="!tradingStatus.entryAllowed">
            <div class="es-header">
              <mat-icon>{{ tradingStatus.entryAllowed ? 'check_circle' : 'block' }}</mat-icon>
              <strong>{{ tradingStatus.entryAllowed ? 'Entries Allowed' : 'Entries Blocked' }}</strong>
              <span class="es-stats">
                Open: {{ tradingStatus.openTrades }} &nbsp;|&nbsp;
                Paper: {{ tradingStatus.openPaperTrades }} &nbsp;|&nbsp;
                Today: {{ tradingStatus.tradesToday }} &nbsp;|&nbsp;
                Hr: {{ tradingStatus.tradesThisHour }}/{{ tradingStatus.maxTradesPerHour }} &nbsp;|&nbsp;
                Orders: {{ tradingStatus.buyOrdersToday }} &nbsp;|&nbsp;
                Losses: {{ tradingStatus.consecutiveLosses }} &nbsp;|&nbsp;
                WR: {{ tradingStatus.rollingWinRate | number:'1.0-0' }}% &nbsp;|&nbsp;
                P&amp;L: <span [class.pos]="tradingStatus.dailyPnl >= 0" [class.neg]="tradingStatus.dailyPnl < 0">₹{{ tradingStatus.dailyPnl | number:'1.0-0' }}</span>
                / ₹{{ tradingStatus.effectiveDailyLossLimit | number:'1.0-0' }} limit
                @if (tradingStatus.globalExitOverride) { &nbsp;|&nbsp; <span class="warn">EXIT OVERRIDE</span> }
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
              <div class="dg"><span>Evaluated</span><strong>{{ tradingStatus?.totalEvaluations ?? 0 }}</strong></div>
              <div class="dg"><span>Blocked</span><strong class="neg">{{ tradingStatus?.totalBlocked ?? 0 }}</strong></div>
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

          <!-- ── Tuning Capture Health (glance widget) ─────────────────── -->
          <div class="panel tuning-panel">
            <h2><mat-icon class="hi">tune</mat-icon> Tuning Capture Health</h2>
            @if (!tuningHealth) {
              <div class="scan-idle"><mat-icon>hourglass_empty</mat-icon> Loading…</div>
            } @else {
              <div class="th-grid">
                <div class="th-row">
                  <span class="th-label">Capturing today</span>
                  <a class="th-value th-link"
                     [class.pos]="(tuningHealth.captureToggles?.enabledStrategies ?? 0) > 0"
                     [class.muted]="(tuningHealth.captureToggles?.enabledStrategies ?? 0) === 0"
                     routerLink="/tuning-capture">
                    {{ tuningHealth.captureToggles?.enabledStrategies ?? 0 }} / {{ tuningHealth.captureToggles?.totalStrategies ?? 22 }}
                  </a>
                </div>
                <div class="th-row">
                  <span class="th-label">Today's events</span>
                  <span class="th-value">
                    {{ tuningHealth.todayEvents?.EVALUATION ?? 0 }} eval ·
                    {{ tuningHealth.todayEvents?.SIGNAL ?? 0 }} sig ·
                    {{ tuningHealth.todayEvents?.EXIT ?? 0 }} exit
                  </span>
                </div>
                <div class="th-row">
                  <span class="th-label">Recorder failures (since boot)</span>
                  <span class="th-value"
                        [class.pos]="(tuningHealth.recorder?.totalFailures ?? 0) === 0"
                        [class.neg]="(tuningHealth.recorder?.totalFailures ?? 0) > 0">
                    {{ tuningHealth.recorder?.totalFailures ?? 0 }}
                    @if ((tuningHealth.recorder?.totalWrites ?? 0) > 0) {
                      <span class="th-sub">of {{ tuningHealth.recorder?.totalWrites }} writes</span>
                    }
                  </span>
                </div>
                <div class="th-row">
                  <span class="th-label">Last roller run</span>
                  <span class="th-value"
                        [class.pos]="tuningHealth.lastRollerRun?.success === true"
                        [class.neg]="tuningHealth.lastRollerRun?.success === false"
                        [class.muted]="!tuningHealth.lastRollerRun?.at">
                    {{ formatTimeOnly(tuningHealth.lastRollerRun?.at) }}
                    @if (tuningHealth.lastRollerRun?.at) {
                      <span class="th-sub">{{ tuningHealth.lastRollerRun?.filesRolled ?? 0 }} files</span>
                    }
                  </span>
                </div>
                <div class="th-row">
                  <span class="th-label">Last forward sweep</span>
                  <span class="th-value"
                        [class.pos]="tuningHealth.lastForwardSweep?.success === true"
                        [class.neg]="tuningHealth.lastForwardSweep?.success === false"
                        [class.muted]="!tuningHealth.lastForwardSweep?.at">
                    {{ formatTimeOnly(tuningHealth.lastForwardSweep?.at) }}
                    @if (tuningHealth.lastForwardSweep?.at) {
                      <span class="th-sub">+{{ tuningHealth.lastForwardSweep?.newCheckpoints ?? 0 }} checkpoints</span>
                    }
                  </span>
                </div>
                <div class="th-row">
                  <span class="th-label">DuckDB temp dir</span>
                  <span class="th-value"
                        [class.pos]="tuningHealth.duckdbTempDir?.exists === true"
                        [class.neg]="tuningHealth.duckdbTempDir?.exists === false">
                    {{ tuningHealth.duckdbTempDir?.exists ? 'OK' : 'Missing' }}
                    @if (tuningHealth.duckdbTempDir?.freeBytes != null) {
                      <span class="th-sub">{{ formatBytes(tuningHealth.duckdbTempDir?.freeBytes) }} free</span>
                    }
                  </span>
                </div>
                <div class="th-row">
                  <span class="th-label">Latest report</span>
                  @if (tuningHealth.latestReport?.present && tuningHealth.latestReport?.hasHtml) {
                    <a class="th-value th-link pos"
                       [routerLink]="['/tuning/reports', tuningHealth.latestReport?.jobId]">
                      {{ tuningHealth.latestReport?.fromDate }} → {{ tuningHealth.latestReport?.toDate }}
                      <mat-icon class="th-arrow">open_in_new</mat-icon>
                    </a>
                  } @else if (tuningHealth.latestReport?.present) {
                    <span class="th-value muted">{{ tuningHealth.latestReport?.status }}</span>
                  } @else {
                    <a class="th-value th-link muted" routerLink="/reports">No reports yet — Generate</a>
                  }
                </div>
              </div>
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

    /* Oil price card */
    .oil-card { border-color: rgba(242,189,75,.35); background: rgba(242,189,75,.04); }
    .oil-change { font-size: 16px; font-weight: 600; margin-left: 8px; }

    /* Entry status */
    .entry-status { border-radius: 10px; padding: 12px 16px; margin-bottom: 16px; border: 1px solid var(--line); }
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

    /* Tuning Capture Health — sits in the same auto-fit row as Runtime + Scan & Signal Status */
    .th-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
    .th-row {
      display: flex; flex-direction: column; gap: 4px;
      padding: 10px 12px; border-radius: 8px;
      background: rgba(255,255,255,.02); border: 1px solid var(--line);
    }
    .th-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .th-value { font-size: 13px; font-weight: 600; color: var(--ink); display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .th-value.muted { color: var(--muted); }
    .th-value.pos   { color: var(--ok); }
    .th-value.neg   { color: var(--bad); }
    .th-sub { font-size: 11px; font-weight: 500; color: var(--muted); }
    .th-link { text-decoration: none; }
    .th-link:hover { text-decoration: underline; }
    .th-arrow { font-size: 14px; width: 14px; height: 14px; }
  `]
})
export class DashboardPageComponent implements OnInit, OnDestroy {
  runtime?: RuntimeStatus;
  pnl?: PnlSnapshot;
  market?: MarketSnapshot;
  tradingStatus?: TradingStatus;
  perf?: any;
  latestSignal?: StrategyDecision | null;
  oilPrice?: OilPriceSnapshot;
  tuningHealth?: TuningHealth;
  loading = false;
  loadError = '';
  lastUpdatedAt = '';
  private inFlight = false;
  private marketPollSub?: Subscription;
  private tuningHealthPollSub?: Subscription;
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
    // Tuning health is server-side aggregated; 30 s poll is plenty (forward sweep fires every 5 min, roller once a day)
    this.refreshTuningHealth();
    this.tuningHealthPollSub = interval(30000).subscribe(() => this.refreshTuningHealth());
  }

  ngOnDestroy(): void {
    this.marketPollSub?.unsubscribe();
    this.signalPollSub?.unsubscribe();
    this.tuningHealthPollSub?.unsubscribe();
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
      perf: this.api.performance().pipe(catchError(() => of(null))),
      oil: this.api.oilPrice().pipe(catchError(() => of(null as OilPriceSnapshot | null)))
    }).subscribe(({ market, status, perf, oil }) => {
      if (market) this.market = market;
      if (perf) this.perf = perf;
      if (status) this.tradingStatus = status;
      if (oil) this.oilPrice = oil;
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

  formatTimeOnly(iso?: string | null): string {
    if (!iso) return '—';
    try { return new Date(iso).toLocaleTimeString(); } catch { return iso; }
  }

  formatBytes(n?: number | null): string {
    if (n == null) return '—';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
    return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  private refreshTuningHealth(): void {
    this.api.getTuningHealth().pipe(catchError(() => of(null))).subscribe(h => {
      if (h) {
        this.tuningHealth = h as TuningHealth;
        this.cd.detectChanges();
      }
    });
  }

  private fail(msg = 'Unable to reach server. Click Refresh.'): void {
    this.loading = false;
    this.inFlight = false;
    this.loadError = msg;
    this.cd.detectChanges();
  }
}
