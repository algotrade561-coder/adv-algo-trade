import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { catchError, forkJoin, interval, of, Subscription, timer } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService, StrategyDto } from '../core/api.service';
import { ApiRecord, JvmHealth, PnlSnapshot, RuntimeStatus, TradingStatus } from '../core/models';

type ScorecardRow = { strategyType: string; totalEntries: number; filled: number; rejected: number; fillRate: number; avgIvRank: number; avgSpread: number; ivRankSource: string };

@Component({
  selector: 'app-monitoring-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule, MatTabsModule],
  template: `
    <section class="page">

      <!-- Header -->
      <div class="row">
        <div>
          <h1 class="page-title">Monitoring</h1>
          <p class="page-subtitle">Live and paper trading overview — positions, orders, trades, and signals.</p>
        </div>
        <span class="spacer"></span>
        @if (lastRefreshed) { <span class="refresh-ts">Updated {{ refreshedLabel }}</span> }
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
      </div>

      <!-- Banners -->
      @if (runtime?.haltMode === 'HARD') {
        <div class="banner banner-hard">
          <mat-icon>block</mat-icon>
          <strong>HARD HALT ACTIVE</strong> — All trading stopped. Kill switch is enabled.
        </div>
      }
      @if (runtime?.haltMode === 'SOFT') {
        <div class="banner banner-soft">
          <mat-icon>pause_circle</mat-icon>
          <strong>SOFT HALT ACTIVE</strong> — No new entries. Open positions are still managed.
        </div>
      }
      @if (runtime && !runtime.dailyApproved) {
        <div class="banner banner-info">
          <mat-icon>pending</mat-icon>
          <strong>NOT APPROVED</strong> — Daily trading not approved. Auto-approves at 10:30 AM.
        </div>
      }

      <!-- Metrics grid -->
      <div class="metrics-section">
        <div class="metrics-group">
          <div class="group-label live-label"><mat-icon>wifi</mat-icon> Live Trading</div>
          <div class="metrics-row">
            <div class="metric-card live-card">
              <span class="metric-label">Total P&amp;L</span>
              <span class="metric-val" [class.pos]="liveTotal >= 0" [class.neg]="liveTotal < 0">
                ₹{{ liveTotal | number:'1.2-2' }}
              </span>
              <span class="metric-sub">Realized ₹{{ livePnl | number:'1.2-2' }} · Unrealized ₹{{ liveUnrealized | number:'1.2-2' }}</span>
            </div>
            <div class="metric-card live-card">
              <span class="metric-label">Est. Charges</span>
              <span class="metric-val neg">₹{{ liveCharges | number:'1.2-2' }}</span>
              <span class="metric-sub">Net P&amp;L: <span [class.pos]="liveNetPnl >= 0" [class.neg]="liveNetPnl < 0">₹{{ liveNetPnl | number:'1.2-2' }}</span></span>
            </div>
            <div class="metric-card live-card">
              <span class="metric-label">Open Positions</span>
              <span class="metric-val">{{ tradingStatus?.openTrades ?? positions.length }}</span>
              <span class="metric-sub">Live open trades</span>
            </div>
            <div class="metric-card live-card">
              <span class="metric-label">Trades Today</span>
              <span class="metric-val">{{ tradingStatus?.tradesToday ?? liveTrades.length }}</span>
              <span class="metric-sub">Consec. losses: {{ tradingStatus?.consecutiveLosses ?? 0 }}</span>
            </div>
          </div>
        </div>

        <div class="metrics-group">
          <div class="group-label paper-label"><mat-icon>description</mat-icon> Paper Trading</div>
          <div class="metrics-row">
            <div class="metric-card paper-card">
              <span class="metric-label">Paper P&amp;L</span>
              <span class="metric-val" [class.pos]="paperPnl >= 0" [class.neg]="paperPnl < 0">
                ₹{{ paperPnl | number:'1.2-2' }}
              </span>
              <span class="metric-sub">Closed paper trades</span>
            </div>
            <div class="metric-card paper-card">
              <span class="metric-label">Est. Charges</span>
              <span class="metric-val neg">₹{{ paperCharges | number:'1.2-2' }}</span>
              <span class="metric-sub">Net P&amp;L: <span [class.pos]="paperNetPnl >= 0" [class.neg]="paperNetPnl < 0">₹{{ paperNetPnl | number:'1.2-2' }}</span></span>
            </div>
            <div class="metric-card paper-card">
              <span class="metric-label">Open Positions</span>
              <span class="metric-val">{{ tradingStatus?.openPaperTrades ?? openPaperPositions.length }}</span>
              <span class="metric-sub">Simulated open</span>
            </div>
            <div class="metric-card paper-card">
              <span class="metric-label">Total Trades</span>
              <span class="metric-val">{{ paperTrades.length }}</span>
              <span class="metric-sub">All simulated trades</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Daily Loss Bar -->
      @if (dailyLossLimit > 0) {
        <div class="loss-bar-wrap">
          <div class="loss-bar-hdr">
            <span class="loss-bar-label"><mat-icon>monitor_heart</mat-icon> Daily Loss Used</span>
            <span class="loss-bar-values"
              [class.loss-critical]="lossUsedPercent >= 80"
              [class.loss-warn]="lossUsedPercent >= 50 && lossUsedPercent < 80">
              ₹{{ dailyLossUsed | number:'1.0-0' }} / ₹{{ effectiveLossLimit | number:'1.0-0' }}
              @if ((runtime?.dailyLossExtension ?? 0) > 0) {
                <span class="ext-badge">+₹{{ runtime?.dailyLossExtension | number:'1.0-0' }} extended</span>
              }
              <span class="loss-pct">({{ lossUsedPercent | number:'1.0-0' }}%)</span>
            </span>
          </div>
          <div class="loss-bar-track">
            <div class="loss-bar-fill" [style.width.%]="lossUsedPercent"
              [class.fill-ok]="lossUsedPercent < 50"
              [class.fill-warn]="lossUsedPercent >= 50 && lossUsedPercent < 80"
              [class.fill-bad]="lossUsedPercent >= 80"></div>
          </div>
        </div>
      }

      <!-- JVM Health -->
      @if (jvm) {
        <div class="jvm-bar-wrap">
          <div class="jvm-header">
            <mat-icon>memory</mat-icon>
            <span class="jvm-title">JVM Health</span>
            <span class="jvm-uptime">Up {{ uptimeLabel }}</span>
          </div>
          <div class="jvm-grid">
            <div class="jvm-card" [class.jvm-warn]="jvm.heapUsedPercent >= 70" [class.jvm-bad]="jvm.heapUsedPercent >= 85">
              <span class="jvm-label">Heap Used</span>
              <span class="jvm-val">{{ jvm.heapUsedMb }} MB</span>
              <div class="jvm-sub-bar">
                <div class="jvm-sub-fill" [style.width.%]="jvm.heapUsedPercent"
                  [class.fill-ok]="jvm.heapUsedPercent < 70"
                  [class.fill-warn]="jvm.heapUsedPercent >= 70 && jvm.heapUsedPercent < 85"
                  [class.fill-bad]="jvm.heapUsedPercent >= 85"></div>
              </div>
              <span class="jvm-sub-label">{{ jvm.heapUsedPercent }}% of {{ jvm.heapMaxMb }} MB max</span>
            </div>
            <div class="jvm-card">
              <span class="jvm-label">Committed</span>
              <span class="jvm-val">{{ jvm.heapTotalMb }} MB</span>
              <span class="jvm-sub-label">Allocated from OS</span>
            </div>
            <div class="jvm-card">
              <span class="jvm-label">Threads</span>
              <span class="jvm-val">{{ jvm.threadCount }}</span>
              <span class="jvm-sub-label">Live JVM threads</span>
            </div>
            <div class="jvm-card">
              <span class="jvm-label">GC Pauses</span>
              <span class="jvm-val">{{ jvm.gcPauseMs }} ms</span>
              <span class="jvm-sub-label">{{ jvm.gcCollections }} total</span>
            </div>
          </div>
        </div>
      }

      <!-- Main detail panel -->
      <div class="panel tab-panel">

        <!-- Mode selector -->
        <div class="panel-toolbar">
          <div class="mode-seg">
            <button class="seg-btn" [class.seg-live]="activeMode === 'live'" (click)="setMode('live')">
              <mat-icon>wifi</mat-icon> Live
            </button>
            <button class="seg-btn" [class.seg-paper]="activeMode === 'paper'" (click)="setMode('paper')">
              <mat-icon>description</mat-icon> Paper
            </button>
          </div>
          <span class="mode-desc">
            @if (activeMode === 'live') { Showing real broker positions, orders and trades }
            @else { Showing simulated paper trades only }
          </span>
          @if (activeMode === 'paper' && !hasPaperSignals && strategiesLoaded) {
            <span class="no-paper-hint">No paper signals found — enable paper mode on a strategy first</span>
          }
        </div>

        <mat-tab-group animationDuration="200ms">

          <!-- Positions -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>receipt_long</mat-icon> Positions
              <span class="tab-count">{{ activeMode === 'live' ? positions.length : openPaperPositions.length }}</span>
            </ng-template>
            @if (activeMode === 'live') {
              @if (positions.length === 0) {
                <div class="empty-state"><mat-icon>inbox</mat-icon><span>No open live positions</span></div>
              } @else {
                <div class="table-wrap">
                  <table class="mon-table">
                    <thead><tr>
                      <th>Instrument</th><th>Status</th><th>Qty</th><th>Avg ₹</th><th>Last ₹</th><th>Day P&L</th><th>Strength</th><th>Rec.</th>
                    </tr></thead>
                    <tbody>
                      @for (r of positions; track r['instrumentKey']) {
                        <tr [class.row-closed-pos]="num(r['quantity']) === 0">
                          <td class="inst-cell">{{ r['instrumentKey'] }}</td>
                          <td>
                            @if (num(r['quantity']) > 0) {
                              <span class="status-badge status-open">OPEN</span>
                            } @else {
                              <span class="status-badge status-closed">CLOSED</span>
                            }
                          </td>
                          <td class="mono">{{ r['quantity'] }}</td>
                          <td class="mono">{{ fmtNum(r['averagePrice']) }}</td>
                          <td class="mono">{{ fmtNum(r['lastPrice']) }}</td>
                          <td class="mono" [class.pos]="num(r['unrealizedPnl']) >= 0" [class.neg]="num(r['unrealizedPnl']) < 0">
                            {{ fmtNum(r['unrealizedPnl']) }}
                          </td>
                          <td>
                            <span [class]="'strength-badge strength-' + getHealthClass(r)">
                              {{ getStrengthScore(r) }}
                            </span>
                          </td>
                          <td>
                            <span [class]="'rec-badge rec-' + getRecClass(r)">{{ getRecommendation(r) }}</span>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            } @else {
              @if (openPaperPositions.length === 0) {
                <div class="empty-state">
                  <mat-icon>description</mat-icon>
                  <span>No open paper positions</span>
                  <span class="empty-hint">Enable paper trading on a strategy via the Strategies page.</span>
                </div>
              } @else {
                <div class="table-wrap">
                  <table class="mon-table">
                    <thead><tr>
                      <th>Entry Time</th><th>Underlying</th><th>Type</th><th>Instrument</th><th>Entry ₹</th><th>Qty</th>
                    </tr></thead>
                    <tbody>
                      @for (r of openPaperPositions; track r['tradeId']) {
                        <tr>
                          <td class="mono time-cell">{{ fmtTime(r['entryTime']) }}</td>
                          <td>{{ r['underlying'] }}</td>
                          <td><span [class]="'type-badge ' + typeCls(r['optionType'])">{{ r['optionType'] }}</span></td>
                          <td class="inst-cell">{{ r['instrumentKey'] }}</td>
                          <td class="mono">{{ fmtNum(r['entryPrice']) }}</td>
                          <td class="mono">{{ r['quantity'] }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            }
          </mat-tab>

          <!-- Orders -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>list_alt</mat-icon> Orders
              <span class="tab-count">{{ activeMode === 'live' ? liveOrders.length : 0 }}</span>
            </ng-template>
            @if (activeMode === 'live') {
              @if (liveOrders.length === 0) {
                <div class="empty-state"><mat-icon>inbox</mat-icon><span>No live orders today</span></div>
              } @else {
                <div class="table-wrap">
                  <table class="mon-table">
                    <thead><tr>
                      <th>Time</th><th>Strategy</th><th>Instrument</th><th>Side</th>
                      <th>Req Qty</th><th>Filled Qty</th><th>Fill ₹</th><th>Status</th><th>Rejection</th>
                    </tr></thead>
                    <tbody>
                      @for (r of liveOrders; track r['clientOrderId']) {
                        <tr>
                          <td class="mono time-cell">{{ fmtTime(r['orderPlacedAt'] ?? r['updatedAt']) }}</td>
                          <td>{{ fmtStrategy(r['strategyType']) }}</td>
                          <td class="inst-cell">{{ r['instrumentKey'] }}</td>
                          <td><span [class]="'side-badge side-' + str(r['side']).toLowerCase()">{{ r['side'] }}</span></td>
                          <td class="mono">{{ r['requestedQuantity'] }}</td>
                          <td class="mono">{{ r['filledQuantity'] }}</td>
                          <td class="mono">{{ fmtNum(r['averageFillPrice']) }}</td>
                          <td><span [class]="'status-badge status-' + str(r['status']).toLowerCase()">{{ r['status'] }}</span></td>
                          <td class="reason-cell" [title]="str(r['rejectionReason'])">{{ truncate(r['rejectionReason'], 30) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            } @else {
              <div class="empty-state">
                <mat-icon>info</mat-icon>
                <span>Paper trades are fully simulated — no broker orders are placed</span>
                <span class="empty-hint">Switch to Live mode to see real broker orders.</span>
              </div>
            }
          </mat-tab>

          <!-- Trades -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>swap_vert</mat-icon> Trades
              <span class="tab-count">{{ activeMode === 'live' ? liveTrades.length : paperTrades.length }}</span>
            </ng-template>
            @if (activeMode === 'live') {
              @if (liveTrades.length === 0) {
                <div class="empty-state"><mat-icon>inbox</mat-icon><span>No live trades today</span></div>
              } @else {
                <div class="table-wrap">
                  <table class="mon-table">
                    <thead><tr>
                      <th>Entry</th><th>Exit</th><th>Strategy</th><th>Underlying</th><th>Type</th>
                      <th>Instrument</th><th>Entry ₹</th><th>Exit ₹</th><th>Qty</th><th>P&L</th><th>Status</th>
                    </tr></thead>
                    <tbody>
                      @for (r of liveTrades; track r['tradeId']) {
                        <tr>
                          <td class="mono time-cell">{{ fmtTime(r['entryTime']) }}</td>
                          <td class="mono time-cell">{{ r['exitTime'] ? fmtTime(r['exitTime']) : '-' }}</td>
                          <td>{{ fmtStrategy(r['strategyType']) }}</td>
                          <td>{{ r['underlying'] }}</td>
                          <td><span [class]="'type-badge ' + typeCls(r['optionType'])">{{ r['optionType'] }}</span></td>
                          <td class="inst-cell">{{ r['instrumentKey'] }}</td>
                          <td class="mono">{{ fmtNum(r['entryPrice']) }}</td>
                          <td class="mono">{{ r['exitPrice'] ? fmtNum(r['exitPrice']) : '-' }}</td>
                          <td class="mono">{{ r['quantity'] }}</td>
                          <td class="mono" [class.pos]="num(r['realizedPnl']) >= 0" [class.neg]="num(r['realizedPnl']) < 0">
                            {{ r['realizedPnl'] ? fmtNum(r['realizedPnl']) : '-' }}
                          </td>
                          <td><span [class]="'status-badge status-' + str(r['status']).toLowerCase()">{{ r['status'] }}</span></td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            } @else {
              @if (paperTrades.length === 0) {
                <div class="empty-state">
                  <mat-icon>description</mat-icon>
                  <span>No paper trades yet</span>
                  <span class="empty-hint">Enable paper trading on a strategy via the Strategies page.</span>
                </div>
              } @else {
                <div class="table-wrap">
                  <table class="mon-table">
                    <thead><tr>
                      <th>Entry</th><th>Exit</th><th>Strategy</th><th>Underlying</th><th>Type</th>
                      <th>Instrument</th><th>Entry ₹</th><th>Exit ₹</th><th>Qty</th><th>P&L</th><th>Status</th>
                    </tr></thead>
                    <tbody>
                      @for (r of paperTrades; track r['tradeId']) {
                        <tr>
                          <td class="mono time-cell">{{ fmtTime(r['entryTime']) }}</td>
                          <td class="mono time-cell">{{ r['exitTime'] ? fmtTime(r['exitTime']) : '-' }}</td>
                          <td>{{ fmtStrategy(r['strategyType']) }}</td>
                          <td>{{ r['underlying'] }}</td>
                          <td><span [class]="'type-badge ' + typeCls(r['optionType'])">{{ r['optionType'] }}</span></td>
                          <td class="inst-cell">{{ r['instrumentKey'] }}</td>
                          <td class="mono">{{ fmtNum(r['entryPrice']) }}</td>
                          <td class="mono">{{ r['exitPrice'] ? fmtNum(r['exitPrice']) : '-' }}</td>
                          <td class="mono">{{ r['quantity'] }}</td>
                          <td class="mono" [class.pos]="num(r['realizedPnl']) >= 0" [class.neg]="num(r['realizedPnl']) < 0">
                            {{ r['realizedPnl'] ? fmtNum(r['realizedPnl']) : '-' }}
                          </td>
                          <td><span [class]="'status-badge status-' + str(r['status']).toLowerCase()">{{ r['status'] }}</span></td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            }
          </mat-tab>

          <!-- Scorecard -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>leaderboard</mat-icon> Scorecard
              <span class="tab-count">{{ scorecard.length }}</span>
            </ng-template>
            <div class="filter-row" style="gap:8px">
              <span class="filter-label">Period</span>
              @for (p of scorecardPeriods; track p.value) {
                <button class="filter-chip" [class.chip-active]="scorecardPeriod === p.value" (click)="setScorecardPeriod(p.value)">{{ p.label }}</button>
              }
            </div>
            @if (scorecard.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No entry signal data for the selected period</span></div>
            } @else {
              <div class="table-wrap">
                <table class="mon-table">
                  <thead><tr>
                    <th>Strategy</th><th>Entries</th><th>Fills</th><th>Rejected</th>
                    <th>Fill %</th><th>Avg IV Rank</th><th>Avg Spread ₹</th><th>IV Source</th>
                  </tr></thead>
                  <tbody>
                    @for (r of scorecard; track r.strategyType) {
                      <tr>
                        <td>{{ fmtStrategy(r.strategyType) }}</td>
                        <td class="mono">{{ r.totalEntries }}</td>
                        <td class="mono pos">{{ r.filled }}</td>
                        <td class="mono" [class.neg]="r.rejected > 0">{{ r.rejected }}</td>
                        <td>
                          <div class="fill-bar-row">
                            <div class="fill-bar-track">
                              <div class="fill-bar-fill"
                                [style.width.%]="r.fillRate"
                                [class.fill-ok]="r.fillRate >= 50"
                                [class.fill-warn]="r.fillRate > 0 && r.fillRate < 50"
                                [class.fill-bad]="r.fillRate === 0"></div>
                            </div>
                            <span class="fill-pct">{{ r.fillRate }}%</span>
                          </div>
                        </td>
                        <td class="mono">{{ r.avgIvRank > 0 ? r.avgIvRank.toFixed(1) : '—' }}</td>
                        <td class="mono" [class.warn]="r.avgSpread > 3 && r.avgSpread <= 6" [class.neg]="r.avgSpread > 6">
                          {{ r.avgSpread > 0 ? r.avgSpread.toFixed(2) : '—' }}
                        </td>
                        <td>
                          <span [class]="r.ivRankSource === 'TRACKER' ? 'iv-badge iv-tracker' : 'iv-badge iv-neutral'">
                            {{ r.ivRankSource }}
                          </span>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </mat-tab>

          <!-- Quality -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>insights</mat-icon> Quality
            </ng-template>
            @if (scorecard.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No quality data yet</span></div>
            } @else {
              <div class="quality-grid">
                @for (r of scorecard; track r.strategyType) {
                  @if (r.totalEntries > 0) {
                    <div class="q-card">
                      <div class="q-title">{{ fmtStrategy(r.strategyType) }}</div>
                      <div class="q-row">
                        <span class="q-label">Fill Rate</span>
                        <div class="q-bar-wrap">
                          <div class="q-bar" [style.width.%]="r.fillRate"
                            [class.q-ok]="r.fillRate >= 50"
                            [class.q-warn]="r.fillRate > 0 && r.fillRate < 50"
                            [class.q-bad]="r.fillRate === 0"></div>
                        </div>
                        <span class="q-val">{{ r.fillRate }}%</span>
                      </div>
                      <div class="q-row">
                        <span class="q-label">Avg Spread ₹</span>
                        <span class="q-val-big"
                          [class.pos]="r.avgSpread > 0 && r.avgSpread < 2"
                          [class.warn]="r.avgSpread >= 2 && r.avgSpread < 5"
                          [class.neg]="r.avgSpread >= 5">
                          {{ r.avgSpread > 0 ? r.avgSpread.toFixed(2) : '—' }}
                        </span>
                      </div>
                      <div class="q-row">
                        <span class="q-label">Avg IV Rank</span>
                        <span class="q-val-big">{{ r.avgIvRank > 0 ? r.avgIvRank.toFixed(1) : '—' }}</span>
                      </div>
                      <div class="q-footer">
                        <span class="q-entries">{{ r.totalEntries }} entries</span>
                        <span [class]="r.ivRankSource === 'TRACKER' ? 'iv-badge iv-tracker' : 'iv-badge iv-neutral'">{{ r.ivRankSource }}</span>
                      </div>
                    </div>
                  }
                }
              </div>
            }
          </mat-tab>

        </mat-tab-group>
      </div>
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1400px; }
    .row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .refresh-ts { font-size: 11px; color: var(--muted); }
    .muted { color: var(--muted); }

    /* Banners */
    .banner { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-radius: 10px; font-size: 13px; margin-bottom: 12px; }
    .banner mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .banner-hard { background: rgba(255,113,106,.1);  border: 1px solid rgba(255,113,106,.4); color: var(--bad); }
    .banner-soft { background: rgba(242,189,75,.08);  border: 1px solid rgba(242,189,75,.35); color: var(--warn); }
    .banner-info { background: rgba(97,168,255,.07);  border: 1px solid rgba(97,168,255,.3);  color: var(--accent); }

    /* Metrics */
    .metrics-section { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
    .group-label { display: flex; align-items: center; gap: 6px; font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: .08em; margin-bottom: 6px; padding: 0 2px; }
    .group-label mat-icon { font-size: 13px; width: 13px; height: 13px; }
    .live-label  { color: var(--accent); }
    .paper-label { color: var(--gold, #e6b74c); }
    .metrics-row { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 10px; }
    @media (max-width: 700px) { .metrics-row { grid-template-columns: 1fr 1fr; } }
    .metric-card { display: flex; flex-direction: column; gap: 3px; padding: 12px 14px; border-radius: 10px; border: 1px solid var(--line); background: rgba(255,255,255,.03); }
    .live-card  { border-color: rgba(97,168,255,.2);  background: rgba(97,168,255,.03); }
    .paper-card { border-color: rgba(230,183,76,.2);  background: rgba(230,183,76,.03); }
    .metric-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .metric-val   { font-size: 20px; font-weight: 800; color: var(--ink); line-height: 1.2; }
    .metric-val.big { font-size: 24px; }
    .metric-sub   { font-size: 10px; color: var(--muted); margin-top: 2px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }

    /* Loss bar */
    .loss-bar-wrap { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px 16px; margin-bottom: 14px; }
    .loss-bar-hdr { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; flex-wrap: wrap; gap: 8px; }
    .loss-bar-label { display: flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
    .loss-bar-label mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .loss-bar-values { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--ink); }
    .loss-critical { color: var(--bad) !important; }
    .loss-warn     { color: var(--warn) !important; }
    .loss-pct { font-size: 11px; color: var(--muted); }
    .ext-badge { font-size: 11px; padding: 2px 8px; border-radius: 20px; background: rgba(242,189,75,.12); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }
    .loss-bar-track { height: 8px; background: rgba(255,255,255,.06); border-radius: 4px; overflow: hidden; }
    .loss-bar-fill  { height: 100%; border-radius: 4px; transition: width 600ms ease, background 400ms; }
    .fill-ok   { background: var(--ok); }
    .fill-warn { background: var(--warn); }
    .fill-bad  { background: var(--bad); }

    /* JVM */
    .jvm-bar-wrap { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px 16px; margin-bottom: 14px; }
    .jvm-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; font-size: 11px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
    .jvm-header mat-icon { font-size: 14px; width: 14px; height: 14px; color: var(--accent); }
    .jvm-title { color: var(--ink); }
    .jvm-uptime { margin-left: auto; font-size: 11px; color: var(--muted); font-weight: 400; text-transform: none; letter-spacing: 0; }
    .jvm-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
    .jvm-card { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; border-radius: 8px; background: rgba(255,255,255,.03); border: 1px solid var(--line); }
    .jvm-warn { border-color: rgba(242,189,75,.35) !important; }
    .jvm-bad  { border-color: rgba(255,113,106,.35) !important; }
    .jvm-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .jvm-val   { font-size: 17px; font-weight: 700; color: var(--ink); }
    .jvm-sub-label { font-size: 10px; color: var(--muted); }
    .jvm-sub-bar { height: 4px; background: rgba(255,255,255,.06); border-radius: 2px; overflow: hidden; margin: 2px 0; }
    .jvm-sub-fill { height: 100%; border-radius: 2px; transition: width 600ms ease; }

    /* Panel */
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 0; }
    .tab-panel mat-tab-group { min-height: 200px; }
    .tab-panel mat-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 6px; vertical-align: middle; }

    /* Mode toolbar */
    .panel-toolbar { display: flex; align-items: center; gap: 14px; padding: 12px 16px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
    .mode-seg { display: flex; border-radius: 8px; border: 1px solid var(--line); overflow: hidden; background: rgba(255,255,255,.03); }
    .seg-btn { display: flex; align-items: center; gap: 6px; padding: 7px 16px; border: none; cursor: pointer; background: transparent; color: var(--muted); font-size: 12px; font-weight: 700; font-family: inherit; transition: background 160ms, color 160ms; }
    .seg-btn mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .seg-btn:hover { background: rgba(255,255,255,.05); color: var(--ink); }
    .seg-live  { background: rgba(97,168,255,.16) !important;  color: var(--accent) !important; }
    .seg-paper { background: rgba(230,183,76,.16) !important;  color: var(--gold, #e6b74c) !important; }
    .mode-desc { font-size: 12px; color: var(--muted); }
    .no-paper-hint { font-size: 11px; color: var(--warn); margin-left: auto; }

    .tab-count { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 18px; padding: 0 5px; margin-left: 6px; border-radius: 10px; background: rgba(255,255,255,.1); border: 1px solid var(--line); font-size: 10px; font-weight: 800; color: var(--muted); vertical-align: middle; }

    /* Signal filters */
    .filter-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 8px 16px; border-bottom: 1px solid rgba(255,255,255,.05); }
    .filter-label { font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); min-width: 54px; }
    .filter-chip { padding: 3px 10px; border-radius: 20px; cursor: pointer; background: transparent; border: 1px solid var(--line); color: var(--muted); font-size: 11px; font-weight: 700; font-family: inherit; transition: background 140ms, color 140ms, border-color 140ms; }
    .filter-chip:hover { border-color: var(--accent); color: var(--ink); }
    .chip-active { background: rgba(97,168,255,.14) !important; border-color: rgba(97,168,255,.4) !important; color: var(--accent) !important; }

    /* Empty state */
    .empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 0; color: var(--muted); font-size: 14px; }
    .empty-state mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .4; }
    .empty-hint { font-size: 12px; color: var(--muted); opacity: .7; text-align: center; max-width: 360px; }

    /* Table */
    .table-wrap { overflow-x: auto; max-height: 520px; overflow-y: auto; padding: 0 4px 4px; }
    .mon-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .mon-table th { position: sticky; top: 0; z-index: 1; padding: 10px 12px; background: var(--panel); text-align: left; font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); border-bottom: 1px solid var(--line); white-space: nowrap; }
    .mon-table td { padding: 9px 12px; border-bottom: 1px solid rgba(255,255,255,.04); color: var(--ink); vertical-align: middle; }
    .mon-table tbody tr:hover { background: rgba(255,255,255,.03); }
    .mon-table tbody tr:last-child td { border-bottom: none; }
    .mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 11px; }
    .time-cell { color: var(--muted); white-space: nowrap; }
    .inst-cell { font-size: 11px; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .reason-cell { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 11px; }
    .stage-cell { font-size: 10px; color: var(--muted); white-space: nowrap; }

    /* Signal badges */
    .sig-badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 10px; font-weight: 800; letter-spacing: .03em; white-space: nowrap; }
    .sig-ce  { background: rgba(82,196,120,.15); color: #52c478; border: 1px solid rgba(82,196,120,.3); }
    .sig-pe  { background: rgba(255,113,106,.15); color: var(--bad); border: 1px solid rgba(255,113,106,.3); }
    .sig-no  { background: rgba(255,255,255,.06); color: var(--muted); border: 1px solid var(--line); }

    /* Type / Status / Side badges */
    .type-badge { display: inline-block; padding: 2px 7px; border-radius: 10px; font-size: 10px; font-weight: 800; }
    .type-ce { background: rgba(82,196,120,.12); color: #52c478; }
    .type-pe { background: rgba(255,113,106,.12); color: var(--bad); }
    .status-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 10px; font-weight: 800; text-transform: uppercase; }
    .status-open    { background: rgba(97,168,255,.12); color: var(--accent); }
    .status-closed  { background: rgba(255,255,255,.06); color: var(--muted); }
    .status-complete { background: rgba(82,196,120,.12); color: #52c478; }
    .status-rejected { background: rgba(255,113,106,.12); color: var(--bad); }
    .side-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 10px; font-weight: 800; text-transform: uppercase; }
    .side-buy  { background: rgba(82,196,120,.12); color: #52c478; }
    .side-sell { background: rgba(255,113,106,.12); color: var(--bad); }

    /* Outcome badges */
    .outcome-badge { display: inline-block; padding: 2px 9px; border-radius: 12px; font-size: 10px; font-weight: 800; letter-spacing: .02em; white-space: nowrap; }
    .outcome-filled   { background: rgba(82,196,120,.15);  color: #52c478; border: 1px solid rgba(82,196,120,.3); }
    .outcome-inorder  { background: rgba(97,168,255,.15);  color: var(--accent); border: 1px solid rgba(97,168,255,.3); }
    .outcome-rejected { background: rgba(255,113,106,.15); color: var(--bad); border: 1px solid rgba(255,113,106,.3); }
    .outcome-warn     { background: rgba(242,189,75,.15);  color: var(--warn); border: 1px solid rgba(242,189,75,.3); }
    .outcome-scan     { background: rgba(255,255,255,.05); color: var(--muted); border: 1px solid var(--line); }

    /* Row tinting by outcome */
    .row-filled   { background: rgba(82,196,120,.025) !important; }
    .row-inorder  { background: rgba(97,168,255,.025) !important; }
    .row-rejected { background: rgba(255,113,106,.025) !important; }
    .row-warn     { background: rgba(242,189,75,.025)  !important; }

    /* Signal summary strip */
    .sig-summary-bar { display: flex; align-items: center; gap: 6px; padding: 8px 16px; border-bottom: 1px solid rgba(255,255,255,.05); flex-wrap: wrap; }
    .sum-chip { display: inline-block; padding: 2px 10px; border-radius: 14px; font-size: 11px; font-weight: 800; }
    .sum-filled   { background: rgba(82,196,120,.12);  color: #52c478; }
    .sum-inorder  { background: rgba(97,168,255,.12);  color: var(--accent); }
    .sum-rejected { background: rgba(255,113,106,.12); color: var(--bad); }
    .sum-warn     { background: rgba(242,189,75,.12);  color: var(--warn); }
    .sum-notrade  { background: rgba(255,255,255,.06); color: var(--muted); }
    .sum-divider  { flex: 1; }
    .sum-total    { font-size: 11px; color: var(--muted); }

    /* Closed position row */
    .row-closed-pos { opacity: 0.55; }
    .row-closed-pos:hover { opacity: 0.75 !important; }

    /* Strength & Recommendation badges */
    .strength-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 800; }
    .strength-strong { background: rgba(82,196,120,.15); color: #52c478; }
    .strength-hold   { background: rgba(97,168,255,.15); color: var(--accent); }
    .strength-watch  { background: rgba(242,189,75,.15); color: var(--warn); }
    .strength-weak   { background: rgba(255,113,106,.15); color: var(--bad); }
    .strength-none   { background: rgba(255,255,255,.06); color: var(--muted); }
    .rec-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 10px; font-weight: 800; text-transform: uppercase; }
    .rec-strong { background: rgba(82,196,120,.12); color: #52c478; }
    .rec-hold   { background: rgba(97,168,255,.12); color: var(--accent); }
    .rec-watch  { background: rgba(242,189,75,.12); color: var(--warn); }
    .rec-weak   { background: rgba(255,113,106,.12); color: var(--bad); }
    .rec-none   { background: rgba(255,255,255,.06); color: var(--muted); }

    /* Spot price indicator in signals table */
    .spot-price { color: var(--muted); }
    .spot-price sup { font-size: 8px; margin-left: 1px; opacity: 0.7; }

    /* IV rank source badges */
    .iv-badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 10px; font-weight: 800; }
    .iv-tracker { background: rgba(69,209,140,.12); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .iv-neutral  { background: rgba(242,189,75,.12); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    /* Scorecard fill bar */
    .fill-bar-row { display: flex; align-items: center; gap: 8px; }
    .fill-bar-track { width: 80px; height: 8px; background: rgba(255,255,255,.06); border-radius: 4px; overflow: hidden; flex-shrink: 0; }
    .fill-bar-fill { height: 100%; border-radius: 4px; transition: width 400ms; }
    .fill-pct { font-size: 11px; color: var(--muted); min-width: 28px; }

    /* Quality cards grid */
    .quality-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; padding: 16px; }
    .q-card { background: rgba(255,255,255,.025); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; display: flex; flex-direction: column; gap: 8px; }
    .q-title { font-size: 12px; font-weight: 700; color: var(--ink); padding-bottom: 6px; border-bottom: 1px solid rgba(255,255,255,.05); }
    .q-row { display: flex; align-items: center; gap: 8px; }
    .q-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); min-width: 80px; }
    .q-bar-wrap { flex: 1; height: 6px; background: rgba(255,255,255,.05); border-radius: 3px; overflow: hidden; }
    .q-bar { height: 100%; border-radius: 3px; transition: width 400ms; }
    .q-val { font-size: 11px; color: var(--muted); min-width: 30px; text-align: right; }
    .q-val-big { font-size: 14px; font-weight: 700; color: var(--ink); margin-left: auto; }
    .q-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 2px; padding-top: 6px; border-top: 1px solid rgba(255,255,255,.04); }
    .q-entries { font-size: 10px; color: var(--muted); }
    .q-ok  { background: var(--ok); }
    .q-warn { background: var(--warn); }
    .q-bad { background: var(--bad); }
  `]
})
export class MonitoringPageComponent implements OnInit, OnDestroy {
  private _activeMode: 'live' | 'paper' = 'live';
  get activeMode(): 'live' | 'paper' { return this._activeMode; }

  setMode(m: 'live' | 'paper'): void {
    this._activeMode = m;
    this.startHealthPolling();
    this.cd.detectChanges();
  }

  positions: ApiRecord[] = [];
  orders: ApiRecord[] = [];
  trades: ApiRecord[] = [];
  signals: ApiRecord[] = [];
  strategies: StrategyDto[] = [];
  strategiesLoaded = false;
  pnl?: PnlSnapshot;
  runtime?: RuntimeStatus;
  tradingStatus?: TradingStatus;
  jvm?: JvmHealth;
  lastRefreshed?: Date;
  scorecard: ScorecardRow[] = [];
  scorecardPeriod = 'LAST30';
  readonly scorecardPeriods = [
    { value: 'TODAY', label: 'Today' },
    { value: 'LAST7', label: '7 days' },
    { value: 'LAST30', label: '30 days' }
  ];

  private jvmSub?: import('rxjs').Subscription;
  private mainDataSub?: import('rxjs').Subscription;
  private healthPollSub: Subscription | null = null;
  positionHealth: ApiRecord[] = [];

  dailyLossLimit = 0;
  dailyLossUsed = 0;

  get paperStrategyTypes(): Set<string> {
    return new Set(this.strategies.filter(s => s.paperTrading).map(s => s.type));
  }

  get hasPaperSignals(): boolean {
    return this.signals.some(s => s['paperTrade'] === true);
  }

  get uptimeLabel(): string {
    if (!this.jvm) return '';
    const s = Math.floor(this.jvm.uptimeMs / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  get refreshedLabel(): string   { return this.lastRefreshed ? this.lastRefreshed.toLocaleTimeString() : ''; }
  get livePnl(): number          { return Number(this.pnl?.realizedPnl ?? 0); }
  get liveUnrealized(): number   { return Number(this.pnl?.unrealizedPnl ?? 0); }
  get liveTotal(): number        { return Number(this.pnl?.totalPnl ?? 0); }
  get paperPnl(): number         { return Number((this.tradingStatus as any)?.paperPnl ?? 0); }
  get liveCharges(): number      { return Number((this.tradingStatus as any)?.liveEstimatedCharges ?? 0); }
  get paperCharges(): number     { return Number((this.tradingStatus as any)?.paperEstimatedCharges ?? 0); }
  get liveNetPnl(): number       { return Number((this.tradingStatus as any)?.liveNetPnl ?? this.liveTotal); }
  get paperNetPnl(): number      { return Number((this.tradingStatus as any)?.paperNetPnl ?? this.paperPnl); }
  get effectiveLossLimit(): number { return this.dailyLossLimit + (this.runtime?.dailyLossExtension ?? 0); }
  get lossUsedPercent(): number {
    if (this.effectiveLossLimit <= 0) return 0;
    return Math.min(100, (this.dailyLossUsed / this.effectiveLossLimit) * 100);
  }

  get liveTrades(): ApiRecord[] {
    return this.trades.filter(t => !String(t['tradeId'] ?? '').startsWith('PAPER-'))
      .filter(t => this.isToday(String(t['entryTime'] ?? '')));
  }
  get paperTrades(): ApiRecord[] {
    return this.trades.filter(t => String(t['tradeId'] ?? '').startsWith('PAPER-'))
      .filter(t => this.isToday(String(t['entryTime'] ?? '')));
  }
  get openPaperPositions(): ApiRecord[] {
    return this.paperTrades.filter(t => String(t['status'] ?? '').toUpperCase() === 'OPEN');
  }
  get liveOrders(): ApiRecord[] {
    return this.orders.filter(o => !String(o['clientOrderId'] ?? '').startsWith('PAPER-'))
      .filter(o => this.isToday(String(o['orderPlacedAt'] ?? o['updatedAt'] ?? '')));
  }

  constructor(private readonly api: ApiService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.load();
    this.loadJvm();
    // Retry positions once after 4s — broker client may not be ready on cold start
    timer(4000).subscribe(() => {
      if (this.positions.length === 0) { this.loadPositions(); }
    });
    this.jvmSub    = interval(15000).subscribe(() => this.loadJvm());
    this.mainDataSub = interval(60000).subscribe(() => this.load());
  }

  ngOnDestroy(): void {
    this.jvmSub?.unsubscribe();
    this.mainDataSub?.unsubscribe();
    this.healthPollSub?.unsubscribe();
  }

  private loadJvm(): void {
    this.api.jvmHealth()
      .pipe(catchError(() => of(null as JvmHealth | null)))
      .subscribe(j => { if (j) { this.jvm = j; this.cd.detectChanges(); } });
  }

  private loadPositions(): void {
    this.api.positions()
      .pipe(catchError(() => of([] as ApiRecord[])))
      .subscribe(p => { this.positions = p; this.startHealthPolling(); this.cd.detectChanges(); });
  }

  private startHealthPolling(): void {
    this.healthPollSub?.unsubscribe();
    this.healthPollSub = null;
    if (this.activeMode === 'live' && this.positions.length > 0) {
      this.loadPositionHealth();
      this.healthPollSub = interval(15000).subscribe(() => this.loadPositionHealth());
    }
  }

  private loadPositionHealth(): void {
    this.api.positionHealth()
      .pipe(catchError(() => of([] as ApiRecord[])))
      .subscribe(h => { this.positionHealth = h; this.cd.detectChanges(); });
  }

  getStrengthScore(position: ApiRecord): string {
    const health = this.findHealth(position);
    if (!health) return '-';
    const score = Number(health['strengthScore']);
    return score < 0 ? '-' : String(score);
  }

  getRecommendation(position: ApiRecord): string {
    const health = this.findHealth(position);
    if (!health) return '-';
    return String(health['recommendation'] ?? '-');
  }

  getHealthClass(position: ApiRecord): string {
    const health = this.findHealth(position);
    if (!health) return 'none';
    const rec = String(health['recommendation'] ?? '').toLowerCase();
    if (rec === 'strong') return 'strong';
    if (rec === 'hold') return 'hold';
    if (rec === 'watch') return 'watch';
    if (rec === 'weak') return 'weak';
    return 'none';
  }

  getRecClass(position: ApiRecord): string {
    return this.getHealthClass(position);
  }

  private findHealth(position: ApiRecord): ApiRecord | undefined {
    const key = String(position['instrumentKey'] ?? '');
    return this.positionHealth.find(h => String(h['instrumentKey'] ?? '') === key);
  }

  load(): void {
    forkJoin({
      config:     this.api.config().pipe(catchError(() => of(null))),
      positions:  this.api.positions().pipe(catchError(() => of([] as ApiRecord[]))),
      orders:     this.api.orders().pipe(catchError(() => of([] as ApiRecord[]))),
      trades:     this.api.trades().pipe(catchError(() => of([] as ApiRecord[]))),
      pnl:        this.api.pnl().pipe(catchError(() => of(null))),
      signals:    this.api.recentSignals().pipe(catchError(() => of([] as ApiRecord[]))),
      status:     this.api.tradingStatus().pipe(catchError(() => of(null))),
      strategies: this.api.getStrategies().pipe(catchError(() => of([] as StrategyDto[]))),
      scorecard:  this.api.strategyScorecard(this.scorecardPeriod).pipe(catchError(() => of([] as ScorecardRow[])))
    }).subscribe(r => {
      this.runtime        = r.config?.runtime as unknown as RuntimeStatus;
      this.positions      = r.positions;
      this.orders         = r.orders;
      this.trades         = r.trades;
      this.pnl            = r.pnl ?? undefined;
      this.signals        = r.signals as ApiRecord[];
      this.tradingStatus  = r.status ?? undefined;
      this.strategies     = r.strategies;
      this.strategiesLoaded = true;
      this.scorecard      = r.scorecard;

      const params  = r.config?.parameters ?? [];
      const capital = Number(params.find(p => p.path === 'trading.risk.total-capital')?.value ?? 0);
      const pct     = Number(params.find(p => p.path === 'trading.risk.max-daily-loss-percent')?.value ?? 0);
      this.dailyLossLimit = capital * pct / 100;

      const realized = Number(r.pnl?.realizedPnl ?? 0);
      this.dailyLossUsed = realized < 0 ? Math.abs(realized) : 0;

      this.lastRefreshed = new Date();
      this.startHealthPolling();
      this.cd.detectChanges();
    });
  }

  setScorecardPeriod(period: string): void {
    this.scorecardPeriod = period;
    this.api.strategyScorecard(period)
      .pipe(catchError(() => of([] as ScorecardRow[])))
      .subscribe(data => { this.scorecard = data; this.cd.detectChanges(); });
  }

  fmtStrategy(s: unknown): string {
    if (!s) return '-';
    return String(s).toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  fmtTime(v: unknown): string {
    if (!v) return '-';
    try {
      const d = new Date(String(v));
      return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
    } catch { return String(v); }
  }

  fmtNum(v: unknown): string {
    if (v === null || v === undefined || v === '') return '-';
    const n = Number(v);
    return isNaN(n) ? '-' : n.toFixed(2);
  }

  num(v: unknown): number { return Number(v) || 0; }

  str(v: unknown): string { return v == null ? '' : String(v); }

  private isToday(isoTimestamp: string): boolean {
    if (!isoTimestamp) return false;
    const date = new Date(isoTimestamp);
    if (isNaN(date.getTime())) return false;
    const istOffset = 5.5 * 60 * 60 * 1000;
    const istDate = new Date(date.getTime() + istOffset);
    const today = new Date(Date.now() + istOffset);
    return istDate.toDateString() === today.toDateString();
  }

  truncate(v: unknown, max = 45): string {
    const s = v == null ? '' : String(v);
    return s.length > max ? s.slice(0, max) + '…' : s;
  }

  typeCls(type: unknown): string {
    return String(type ?? '').toUpperCase() === 'CE' ? 'type-ce' : 'type-pe';
  }

}
