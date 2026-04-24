import { Component, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService } from '../core/api.service';
import { ApiRecord, PnlSnapshot, RuntimeStatus, StrategyDecision } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';

@Component({
  selector: 'app-monitoring-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatCheckboxModule, MatIconModule, MatTabsModule, DataTableComponent],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Monitoring</h1>
          <p class="page-subtitle">Live positions, orders, trades, and PnL overview.</p>
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
        <div class="banner banner-warn">
          <mat-icon>pending</mat-icon>
          <strong>NOT APPROVED</strong> — Daily trading not approved yet. Auto-approves at 10:30 AM.
        </div>
      }

      <!-- ── Daily Loss Progress ──────────────────────────────────────── -->
      @if (dailyLossLimit > 0) {
        <div class="loss-bar-wrap">
          <div class="loss-bar-hdr">
            <span class="loss-bar-label">
              <mat-icon>monitor_heart</mat-icon>
              Daily Loss Used
            </span>
            <span class="loss-bar-values" [class.loss-critical]="lossUsedPercent >= 80" [class.loss-warn]="lossUsedPercent >= 50 && lossUsedPercent < 80">
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

      <!-- ── PnL Cards ────────────────────────────────────────────────── -->
      <div class="pnl-bar">
        <div class="pnl-card">
          <mat-icon class="pnl-icon">account_balance_wallet</mat-icon>
          <div>
            <span class="pnl-label">Realized</span>
            <span class="pnl-value" [class.pos]="(pnl?.realizedPnl ?? 0) >= 0" [class.neg]="(pnl?.realizedPnl ?? 0) < 0">
              ₹{{ (pnl?.realizedPnl ?? 0) | number:'1.2-2' }}
            </span>
          </div>
        </div>
        <div class="pnl-card">
          <mat-icon class="pnl-icon">trending_up</mat-icon>
          <div>
            <span class="pnl-label">Unrealized</span>
            <span class="pnl-value" [class.pos]="(pnl?.unrealizedPnl ?? 0) >= 0" [class.neg]="(pnl?.unrealizedPnl ?? 0) < 0">
              ₹{{ (pnl?.unrealizedPnl ?? 0) | number:'1.2-2' }}
            </span>
          </div>
        </div>
        <div class="pnl-card pnl-total">
          <mat-icon class="pnl-icon">assessment</mat-icon>
          <div>
            <span class="pnl-label">Total PnL</span>
            <span class="pnl-value pnl-big" [class.pos]="(pnl?.totalPnl ?? 0) >= 0" [class.neg]="(pnl?.totalPnl ?? 0) < 0">
              ₹{{ (pnl?.totalPnl ?? 0) | number:'1.2-2' }}
            </span>
          </div>
        </div>
        <div class="pnl-card">
          <mat-icon class="pnl-icon">receipt_long</mat-icon>
          <div>
            <span class="pnl-label">Open Positions</span>
            <span class="pnl-value">{{ positions.length }}</span>
          </div>
        </div>
        <div class="pnl-card">
          <mat-icon class="pnl-icon">swap_vert</mat-icon>
          <div>
            <span class="pnl-label">Today's Trades</span>
            <span class="pnl-value">{{ trades.length }}</span>
          </div>
        </div>
      </div>

      <!-- ── Tabs ─────────────────────────────────────────────────────── -->
      <div class="panel tab-panel">
        <mat-tab-group animationDuration="200ms">
          <mat-tab>
            <ng-template mat-tab-label><mat-icon>receipt_long</mat-icon> Positions</ng-template>
            @if (positions.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No open positions</span></div>
            } @else {
              <app-data-table [rows]="positions"></app-data-table>
            }
          </mat-tab>
          <mat-tab>
            <ng-template mat-tab-label><mat-icon>list_alt</mat-icon> Orders</ng-template>
            @if (orders.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No orders today</span></div>
            } @else {
              <app-data-table [rows]="orders"></app-data-table>
            }
          </mat-tab>
          <mat-tab>
            <ng-template mat-tab-label><mat-icon>swap_vert</mat-icon> Trades</ng-template>
            @if (trades.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No trades today</span></div>
            } @else {
              <app-data-table [rows]="trades"></app-data-table>
            }
          </mat-tab>
          <mat-tab>
            <ng-template mat-tab-label><mat-icon>notifications</mat-icon> Recent Signals</ng-template>
            <div class="signal-filters">
              @for (st of signalTypeOptions; track st) {
                <mat-checkbox [checked]="selectedSignalTypes.has(st)" (change)="setSignalTypeFilter(st, $event.checked)">{{ st }}</mat-checkbox>
              }
            </div>
            @if (filteredSignals.length === 0) {
              <div class="empty-state"><mat-icon>inbox</mat-icon><span>No signals matching filter</span></div>
            } @else {
              <app-data-table [rows]="filteredSignals"></app-data-table>
            }
          </mat-tab>
        </mat-tab-group>
      </div>
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }

    /* Banners */
    .banner { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-radius: 10px; font-size: 13px; margin-bottom: 12px; }
    .banner mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .banner-hard { background: rgba(255,113,106,.1); border: 1px solid rgba(255,113,106,.4); color: var(--bad); }
    .banner-soft { background: rgba(242,189,75,.08); border: 1px solid rgba(242,189,75,.35); color: var(--warn); }
    .banner-warn { background: rgba(97,168,255,.07); border: 1px solid rgba(97,168,255,.3); color: var(--accent); }

    /* Daily loss progress */
    .loss-bar-wrap { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
    .loss-bar-hdr { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; flex-wrap: wrap; gap: 8px; }
    .loss-bar-label { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
    .loss-bar-label mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .loss-bar-values { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--ink); }
    .loss-critical { color: var(--bad) !important; }
    .loss-warn { color: var(--warn) !important; }
    .loss-pct { font-size: 11px; color: var(--muted); }
    .ext-badge { font-size: 11px; padding: 2px 8px; border-radius: 20px; background: rgba(242,189,75,.12); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }
    .loss-bar-track { height: 8px; background: rgba(255,255,255,.06); border-radius: 4px; overflow: hidden; }
    .loss-bar-fill { height: 100%; border-radius: 4px; transition: width 600ms ease, background 400ms; }
    .fill-ok { background: var(--ok); }
    .fill-warn { background: var(--warn); }
    .fill-bad { background: var(--bad); }

    /* PnL */
    .pnl-bar { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 20px; }
    .pnl-card { display: flex; align-items: center; gap: 12px; padding: 16px 18px; border-radius: 12px; border: 1px solid var(--line); background: var(--panel); }
    .pnl-total { border-color: rgba(97,168,255,.3); background: rgba(97,168,255,.04); }
    .pnl-icon { font-size: 22px; width: 22px; height: 22px; color: var(--muted); }
    .pnl-total .pnl-icon { color: var(--accent); }
    .pnl-label { display: block; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .pnl-value { display: block; font-size: 18px; font-weight: 700; color: var(--ink); margin-top: 2px; }
    .pnl-big { font-size: 22px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }

    .tab-panel { padding: 0; }
    .tab-panel mat-tab-group { min-height: 200px; }
    .tab-panel mat-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 6px; vertical-align: middle; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }

    .signal-filters { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin: 14px 16px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 8px; background: rgba(255,255,255,.035); }

    .empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 0; color: var(--muted); font-size: 14px; }
    .empty-state mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .4; }
  `]
})
export class MonitoringPageComponent implements OnInit {
  signalTypeOptions: string[] = ['BUY_CE', 'BUY_PE', 'NO_TRADE'];
  selectedSignalTypes = new Set<string>(this.signalTypeOptions);

  positions: ApiRecord[] = [];
  orders: ApiRecord[] = [];
  trades: ApiRecord[] = [];
  signals: ApiRecord[] = [];
  pnl?: PnlSnapshot;
  runtime?: RuntimeStatus;

  dailyLossLimit = 0;      // base limit in INR from config
  dailyLossUsed = 0;       // absolute value of negative PnL

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
      signals: this.api.recentSignals()
    }).subscribe(r => {
      this.runtime = r.config.runtime as unknown as RuntimeStatus;
      this.positions = r.positions;
      this.orders = r.orders;
      this.trades = r.trades;
      this.pnl = r.pnl;
      this.signals = r.signals as ApiRecord[];

      // Derive daily loss limit from config parameters
      const params = r.config.parameters ?? [];
      const capital = Number(params.find(p => p.path === 'trading.risk.total-capital')?.value ?? 0);
      const pct = Number(params.find(p => p.path === 'trading.risk.max-daily-loss-percent')?.value ?? 0);
      this.dailyLossLimit = capital * pct / 100;

      // Daily loss used = absolute value of negative realized PnL
      const realized = r.pnl?.realizedPnl ?? 0;
      this.dailyLossUsed = realized < 0 ? Math.abs(realized) : 0;

      // Build dynamic signal type filter from actual data
      const types = new Set<string>(this.signalTypeOptions);
      this.signals.forEach(s => { if (s['signalType']) types.add(String(s['signalType'])); });
      this.signalTypeOptions = Array.from(types).sort();
      types.forEach(t => this.selectedSignalTypes.add(t));
    });
  }

  setSignalTypeFilter(signalType: string, checked: boolean): void {
    checked ? this.selectedSignalTypes.add(signalType) : this.selectedSignalTypes.delete(signalType);
  }
}
