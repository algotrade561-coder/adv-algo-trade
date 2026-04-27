import { Component, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { catchError, forkJoin, of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService } from '../core/api.service';
import { ApiRecord, JvmHealth, PnlSnapshot, RuntimeStatus, TradingStatus } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';

@Component({
  selector: 'app-monitoring-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule, MatTabsModule, DataTableComponent],
  template: `
    <section class="page">

      <!-- ── Header ─────────────────────────────────────────────────────── -->
      <div class="row">
        <div>
          <h1 class="page-title">Monitoring</h1>
          <p class="page-subtitle">Live and paper trading overview — positions, orders, trades, and PnL.</p>
        </div>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
      </div>

      <!-- ── Halt / Approval Banners ──────────────────────────────────── -->
      @if (runtime?.haltMode === 'HARD') {
        <div class="banner banner-hard">
          <mat-icon>block</mat-icon>
          <strong>HARD HALT ACTIVE</strong> — All trading stopped. Kill switch is enabled.
        </div>
      }
      @if (runtime?.haltMode === 'SOFT') {
        <div class="banner banner-soft">
          <mat-icon>pause_circle</mat-icon>
          <strong>SOFT HALT ACTIVE</strong> — No new entries. Open positions are still being managed.
        </div>
      }
      @if (runtime && !runtime.dailyApproved) {
        <div class="banner banner-info">
          <mat-icon>pending</mat-icon>
          <strong>NOT APPROVED</strong> — Daily trading not approved yet. Auto-approves at 10:30 AM.
        </div>
      }

      <!-- ── Live | Paper Summary ────────────────────────────────────── -->
      <div class="summary-grid">

        <!-- Live column -->
        <div class="summary-col live-col">
          <div class="col-header">
            <mat-icon>wifi</mat-icon>
            <span>Live Trading</span>
            <span class="mode-pill live-pill">LIVE</span>
          </div>
          <div class="stat-row">
            <div class="stat-card">
              <span class="stat-label">Realized PnL</span>
              <span class="stat-val" [class.pos]="livePnl >= 0" [class.neg]="livePnl < 0">
                ₹{{ livePnl | number:'1.2-2' }}
              </span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Unrealized PnL</span>
              <span class="stat-val" [class.pos]="liveUnrealized >= 0" [class.neg]="liveUnrealized < 0">
                ₹{{ liveUnrealized | number:'1.2-2' }}
              </span>
            </div>
            <div class="stat-card stat-total">
              <span class="stat-label">Total PnL</span>
              <span class="stat-val stat-big" [class.pos]="liveTotal >= 0" [class.neg]="liveTotal < 0">
                ₹{{ liveTotal | number:'1.2-2' }}
              </span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Open Positions</span>
              <span class="stat-val">{{ tradingStatus?.openTrades ?? positions.length }}</span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Trades Today</span>
              <span class="stat-val">{{ tradingStatus?.tradesToday ?? liveTrades.length }}</span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Consec. Losses</span>
              <span class="stat-val" [class.neg]="(tradingStatus?.consecutiveLosses ?? 0) > 0">
                {{ tradingStatus?.consecutiveLosses ?? 0 }}
              </span>
            </div>
          </div>
        </div>

        <!-- Paper column -->
        <div class="summary-col paper-col">
          <div class="col-header">
            <mat-icon>description</mat-icon>
            <span>Paper Trading</span>
            <span class="mode-pill paper-pill">PAPER</span>
          </div>
          <div class="stat-row">
            <div class="stat-card">
              <span class="stat-label">Paper PnL</span>
              <span class="stat-val" [class.pos]="paperPnl >= 0" [class.neg]="paperPnl < 0">
                ₹{{ paperPnl | number:'1.2-2' }}
              </span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Open Paper Trades</span>
              <span class="stat-val">{{ tradingStatus?.openPaperTrades ?? openPaperPositions.length }}</span>
            </div>
            <div class="stat-card">
              <span class="stat-label">Paper Orders Today</span>
              <span class="stat-val">{{ paperOrders.length }}</span>
            </div>
          </div>
        </div>

      </div>

      <!-- ── Daily Loss Progress ─────────────────────────────────────── -->
      @if (dailyLossLimit > 0) {
        <div class="loss-bar-wrap">
          <div class="loss-bar-hdr">
            <span class="loss-bar-label">
              <mat-icon>monitor_heart</mat-icon> Daily Loss Used
            </span>
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
            <div class="loss-bar-fill"
              [style.width.%]="lossUsedPercent"
              [class.fill-ok]="lossUsedPercent < 50"
              [class.fill-warn]="lossUsedPercent >= 50 && lossUsedPercent < 80"
              [class.fill-bad]="lossUsedPercent >= 80">
            </div>
          </div>
        </div>
      }

      <!-- ── JVM Health ──────────────────────────────────────────────── -->
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
              <span class="jvm-label">Heap Committed</span>
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
              <span class="jvm-sub-label">{{ jvm.gcCollections }} collections total</span>
            </div>
          </div>
        </div>
      }

      <!-- ── Mode Toggle ─────────────────────────────────────────────── -->
      <div class="mode-toggle-row">
        <div class="mode-toggle">
          <button class="mode-btn" [class.mode-live-active]="activeMode === 'live'" (click)="activeMode = 'live'">
            <mat-icon>wifi</mat-icon> Live
          </button>
          <button class="mode-btn" [class.mode-paper-active]="activeMode === 'paper'" (click)="activeMode = 'paper'">
            <mat-icon>description</mat-icon> Paper
          </button>
        </div>
        <span class="mode-hint">
          Showing <strong>{{ activeMode === 'live' ? 'live' : 'paper' }}</strong> data in the tables below
        </span>
      </div>

      <!-- ── Detail Tabs ─────────────────────────────────────────────── -->
      <div class="panel tab-panel">
        <mat-tab-group animationDuration="200ms">

          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>receipt_long</mat-icon> Positions
              <span class="tab-count">{{ activePositions.length }}</span>
            </ng-template>
            @if (activePositions.length === 0) {
              <div class="empty-state">
                <mat-icon>inbox</mat-icon>
                <span>No open {{ activeMode }} positions</span>
                @if (activeMode === 'paper') {
                  <span class="empty-hint">Paper positions are simulated open trades — none are currently open.</span>
                }
              </div>
            } @else {
              <app-data-table [rows]="activePositions"></app-data-table>
            }
          </mat-tab>

          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>list_alt</mat-icon> Orders
              <span class="tab-count">{{ activeOrders.length }}</span>
            </ng-template>
            @if (activeOrders.length === 0) {
              <div class="empty-state">
                <mat-icon>inbox</mat-icon>
                <span>No {{ activeMode }} orders today</span>
              </div>
            } @else {
              <app-data-table [rows]="activeOrders"></app-data-table>
            }
          </mat-tab>

          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>swap_vert</mat-icon> Trades
              <span class="tab-count">{{ activeTrades.length }}</span>
            </ng-template>
            @if (activeTrades.length === 0) {
              <div class="empty-state">
                <mat-icon>inbox</mat-icon>
                <span>No {{ activeMode }} trades today</span>
              </div>
            } @else {
              <app-data-table [rows]="activeTrades"></app-data-table>
            }
          </mat-tab>

          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>notifications</mat-icon> Signals
              <span class="tab-count">{{ filteredSignals.length }}</span>
            </ng-template>
            <div class="signal-filters">
              @for (st of signalTypeOptions; track st) {
                <button class="filter-chip" [class.chip-active]="selectedSignalTypes.has(st)" (click)="toggleSignalType(st)">
                  {{ st }}
                </button>
              }
            </div>
            @if (filteredSignals.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No signals match the selected filters</span></div>
            } @else {
              <app-data-table [rows]="filteredSignals"></app-data-table>
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

    /* ── Banners ──────────────────────────────────────────────── */
    .banner {
      display: flex; align-items: center; gap: 10px;
      padding: 12px 16px; border-radius: 10px; font-size: 13px; margin-bottom: 12px;
    }
    .banner mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .banner-hard  { background: rgba(255,113,106,.1);  border: 1px solid rgba(255,113,106,.4); color: var(--bad); }
    .banner-soft  { background: rgba(242,189,75,.08);  border: 1px solid rgba(242,189,75,.35); color: var(--warn); }
    .banner-info  { background: rgba(97,168,255,.07);  border: 1px solid rgba(97,168,255,.3);  color: var(--accent); }

    /* ── Summary Grid ─────────────────────────────────────────── */
    .summary-grid {
      display: grid;
      grid-template-columns: 2fr 1fr;
      gap: 14px;
      margin-bottom: 16px;
    }
    @media (max-width: 900px) {
      .summary-grid { grid-template-columns: 1fr; }
    }

    .summary-col {
      border-radius: 12px;
      border: 1px solid var(--line);
      padding: 16px;
    }
    .live-col  { border-color: rgba(97,168,255,.3);  background: rgba(97,168,255,.04); }
    .paper-col { border-color: rgba(230,183,76,.28); background: rgba(230,183,76,.04); }

    .col-header {
      display: flex; align-items: center; gap: 8px;
      font-size: 12px; font-weight: 800; text-transform: uppercase;
      letter-spacing: .06em; margin-bottom: 14px;
    }
    .live-col  .col-header { color: var(--accent); }
    .paper-col .col-header { color: var(--gold); }
    .col-header mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .mode-pill {
      margin-left: auto; padding: 2px 8px; border-radius: 20px;
      font-size: 10px; font-weight: 800; letter-spacing: .08em;
    }
    .live-pill  { background: rgba(97,168,255,.15);  color: var(--accent); border: 1px solid rgba(97,168,255,.3); }
    .paper-pill { background: rgba(230,183,76,.14);  color: var(--gold);   border: 1px solid rgba(230,183,76,.28); }

    .stat-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 10px;
    }
    .stat-card {
      display: flex; flex-direction: column; gap: 4px;
      padding: 10px 12px; border-radius: 8px;
      background: rgba(255,255,255,.03); border: 1px solid var(--line);
    }
    .stat-total { border-color: rgba(97,168,255,.22); background: rgba(97,168,255,.06); }
    .stat-label {
      font-size: 10px; font-weight: 700; text-transform: uppercase;
      letter-spacing: .05em; color: var(--muted);
    }
    .stat-val { font-size: 17px; font-weight: 700; color: var(--ink); }
    .stat-big { font-size: 20px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }

    /* ── Daily Loss ───────────────────────────────────────────── */
    .loss-bar-wrap {
      background: var(--panel); border: 1px solid var(--line);
      border-radius: 10px; padding: 14px 16px; margin-bottom: 16px;
    }
    .loss-bar-hdr {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 10px; flex-wrap: wrap; gap: 8px;
    }
    .loss-bar-label {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; font-weight: 700; color: var(--muted);
      text-transform: uppercase; letter-spacing: .05em;
    }
    .loss-bar-label mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .loss-bar-values { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--ink); }
    .loss-critical { color: var(--bad) !important; }
    .loss-warn     { color: var(--warn) !important; }
    .loss-pct      { font-size: 11px; color: var(--muted); }
    .ext-badge {
      font-size: 11px; padding: 2px 8px; border-radius: 20px;
      background: rgba(242,189,75,.12); color: var(--warn); border: 1px solid rgba(242,189,75,.3);
    }
    .loss-bar-track { height: 8px; background: rgba(255,255,255,.06); border-radius: 4px; overflow: hidden; }
    .loss-bar-fill  { height: 100%; border-radius: 4px; transition: width 600ms ease, background 400ms; }
    .fill-ok   { background: var(--ok); }
    .fill-warn { background: var(--warn); }
    .fill-bad  { background: var(--bad); }

    /* ── Mode Toggle ──────────────────────────────────────────── */
    .mode-toggle-row {
      display: flex; align-items: center; gap: 14px;
      margin-bottom: 16px; flex-wrap: wrap;
    }
    .mode-toggle {
      display: flex; border-radius: 10px;
      border: 1px solid var(--line); overflow: hidden;
      background: rgba(255,255,255,.03);
    }
    .mode-btn {
      display: flex; align-items: center; gap: 6px;
      padding: 8px 18px; border: none; cursor: pointer;
      background: transparent; color: var(--muted);
      font-size: 13px; font-weight: 700; font-family: inherit;
      transition: background 180ms, color 180ms;
    }
    .mode-btn mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .mode-btn:hover { background: rgba(255,255,255,.06); color: var(--ink); }
    .mode-live-active  { background: rgba(97,168,255,.16) !important; color: var(--accent) !important; }
    .mode-paper-active { background: rgba(230,183,76,.16) !important; color: var(--gold) !important; }
    .mode-hint { font-size: 12px; color: var(--muted); }
    .mode-hint strong { color: var(--ink); }

    /* ── Tabs ─────────────────────────────────────────────────── */
    .tab-panel { padding: 0; }
    .tab-panel mat-tab-group { min-height: 200px; }
    .tab-panel mat-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 6px; vertical-align: middle; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }

    .tab-count {
      display: inline-flex; align-items: center; justify-content: center;
      min-width: 20px; height: 18px; padding: 0 5px;
      margin-left: 6px; border-radius: 10px;
      background: rgba(255,255,255,.1); border: 1px solid var(--line);
      font-size: 10px; font-weight: 800; color: var(--muted);
      vertical-align: middle;
    }

    /* ── Signal filters ───────────────────────────────────────── */
    .signal-filters {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
      margin: 14px 16px; padding: 10px 14px;
      border: 1px solid var(--line); border-radius: 8px;
      background: rgba(255,255,255,.03);
    }
    .filter-chip {
      padding: 4px 12px; border-radius: 20px; cursor: pointer;
      background: transparent; border: 1px solid var(--line);
      color: var(--muted); font-size: 12px; font-weight: 700;
      font-family: inherit; transition: background 160ms, color 160ms, border-color 160ms;
    }
    .filter-chip:hover { border-color: var(--accent); color: var(--ink); }
    .chip-active { background: rgba(97,168,255,.14) !important; border-color: rgba(97,168,255,.4) !important; color: var(--accent) !important; }

    /* ── JVM Health ──────────────────────────────────────────── */
    .jvm-bar-wrap {
      background: var(--panel); border: 1px solid var(--line);
      border-radius: 10px; padding: 14px 16px; margin-bottom: 16px;
    }
    .jvm-header {
      display: flex; align-items: center; gap: 8px; margin-bottom: 12px;
      font-size: 12px; font-weight: 700; color: var(--muted);
      text-transform: uppercase; letter-spacing: .05em;
    }
    .jvm-header mat-icon { font-size: 15px; width: 15px; height: 15px; color: var(--accent); }
    .jvm-title { color: var(--ink); }
    .jvm-uptime { margin-left: auto; font-size: 11px; color: var(--muted); font-weight: 400; text-transform: none; letter-spacing: 0; }
    .jvm-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
    .jvm-card {
      display: flex; flex-direction: column; gap: 4px;
      padding: 10px 12px; border-radius: 8px;
      background: rgba(255,255,255,.03); border: 1px solid var(--line);
    }
    .jvm-warn { border-color: rgba(242,189,75,.35) !important; }
    .jvm-bad  { border-color: rgba(255,113,106,.35) !important; }
    .jvm-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .jvm-val   { font-size: 18px; font-weight: 700; color: var(--ink); }
    .jvm-sub-label { font-size: 10px; color: var(--muted); }
    .jvm-sub-bar { height: 4px; background: rgba(255,255,255,.06); border-radius: 2px; overflow: hidden; margin: 2px 0; }
    .jvm-sub-fill { height: 100%; border-radius: 2px; transition: width 600ms ease; }

    /* ── Empty state ──────────────────────────────────────────── */
    .empty-state {
      display: flex; flex-direction: column; align-items: center;
      gap: 8px; padding: 48px 0; color: var(--muted); font-size: 14px;
    }
    .empty-state mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .4; }
    .empty-hint { font-size: 12px; color: var(--muted); opacity: .7; text-align: center; max-width: 300px; }
  `]
})
export class MonitoringPageComponent implements OnInit {
  activeMode: 'live' | 'paper' = 'live';

  signalTypeOptions: string[] = ['BUY_CE', 'BUY_PE', 'NO_TRADE'];
  selectedSignalTypes = new Set<string>(this.signalTypeOptions);

  positions: ApiRecord[] = [];
  orders: ApiRecord[] = [];
  trades: ApiRecord[] = [];
  signals: ApiRecord[] = [];
  pnl?: PnlSnapshot;
  runtime?: RuntimeStatus;
  tradingStatus?: TradingStatus;
  jvm?: JvmHealth;

  dailyLossLimit = 0;
  dailyLossUsed = 0;

  get uptimeLabel(): string {
    if (!this.jvm) return '';
    const s = Math.floor(this.jvm.uptimeMs / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  get livePnl(): number      { return Number(this.pnl?.realizedPnl ?? 0); }
  get liveUnrealized(): number { return Number(this.pnl?.unrealizedPnl ?? 0); }
  get liveTotal(): number    { return Number(this.pnl?.totalPnl ?? 0); }
  get paperPnl(): number     { return this.tradingStatus?.paperPnl ?? 0; }

  get liveTrades(): ApiRecord[] {
    return this.trades.filter(t => !String(t['tradeId'] ?? '').startsWith('PAPER-'));
  }

  get paperTrades(): ApiRecord[] {
    return this.trades.filter(t => String(t['tradeId'] ?? '').startsWith('PAPER-'));
  }

  get openPaperPositions(): ApiRecord[] {
    return this.paperTrades.filter(t => String(t['status'] ?? '').toUpperCase() === 'OPEN');
  }

  get liveOrders(): ApiRecord[] {
    return this.orders.filter(o => !String(o['clientOrderId'] ?? '').startsWith('PAPER-'));
  }

  get paperOrders(): ApiRecord[] {
    return this.orders.filter(o => String(o['clientOrderId'] ?? '').startsWith('PAPER-'));
  }

  get activePositions(): ApiRecord[] {
    return this.activeMode === 'live' ? this.positions : this.openPaperPositions;
  }

  get activeOrders(): ApiRecord[] {
    return this.activeMode === 'live' ? this.liveOrders : this.paperOrders;
  }

  get activeTrades(): ApiRecord[] {
    return this.activeMode === 'live' ? this.liveTrades : this.paperTrades;
  }

  get effectiveLossLimit(): number {
    return this.dailyLossLimit + (this.runtime?.dailyLossExtension ?? 0);
  }

  get lossUsedPercent(): number {
    if (this.effectiveLossLimit <= 0) return 0;
    return Math.min(100, (this.dailyLossUsed / this.effectiveLossLimit) * 100);
  }

  get filteredSignals(): ApiRecord[] {
    return this.signals.filter(s => this.selectedSignalTypes.has(String(s['signalType'] ?? '')));
  }

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    forkJoin({
      config: this.api.config(),
      positions: this.api.positions(),
      orders: this.api.orders(),
      trades: this.api.trades(),
      pnl: this.api.pnl(),
      signals: this.api.recentSignals(),
      status: this.api.tradingStatus(),
      jvm: this.api.jvmHealth().pipe(catchError(() => of(null as JvmHealth | null)))
    }).subscribe(r => {
      this.runtime = r.config.runtime as unknown as RuntimeStatus;
      this.positions = r.positions;
      this.orders = r.orders;
      this.trades = r.trades;
      this.pnl = r.pnl;
      this.signals = r.signals as ApiRecord[];
      this.tradingStatus = r.status;
      if (r.jvm) this.jvm = r.jvm;

      const params = r.config.parameters ?? [];
      const capital = Number(params.find(p => p.path === 'trading.risk.total-capital')?.value ?? 0);
      const pct = Number(params.find(p => p.path === 'trading.risk.max-daily-loss-percent')?.value ?? 0);
      this.dailyLossLimit = capital * pct / 100;

      const realized = Number(r.pnl?.realizedPnl ?? 0);
      this.dailyLossUsed = realized < 0 ? Math.abs(realized) : 0;

      const types = new Set<string>(this.signalTypeOptions);
      this.signals.forEach(s => { if (s['signalType']) types.add(String(s['signalType'])); });
      this.signalTypeOptions = Array.from(types).sort();
      types.forEach(t => this.selectedSignalTypes.add(t));
    });
  }

  toggleSignalType(st: string): void {
    this.selectedSignalTypes.has(st) ? this.selectedSignalTypes.delete(st) : this.selectedSignalTypes.add(st);
  }
}
