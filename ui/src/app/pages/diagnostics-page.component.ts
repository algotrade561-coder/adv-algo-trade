import { Component, OnInit, OnDestroy, ChangeDetectorRef, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../core/api.service';
import { interval, Subscription, catchError, of } from 'rxjs';

@Component({
  selector: 'app-diagnostics-page',
  standalone: true,
  imports: [CommonModule, FormsModule, DecimalPipe, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatAutocompleteModule, MatSlideToggleModule],
  template: `
    <section class="page">
      <div class="hdr">
        <div>
          <h1 class="title">System Diagnostics</h1>
          <p class="subtitle">Real-time system health, order audit trail, and failure analysis</p>
        </div>
        <button mat-flat-button color="primary" (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      <!-- ── System Health Cards ── -->
      @if (health) {
        <h2 class="sec-title"><mat-icon class="sec-icon">monitor_heart</mat-icon> System Health</h2>

        <!-- Alerts Banner -->
        @if (health.alerts?.length) {
          <div class="alerts-banner">
            @for (alert of health.alerts; track alert) {
              <div class="alert-item">{{ alert }}</div>
            }
          </div>
        }

        <div class="cards">
          <div class="card" [class.card-ok]="health.systemHealth?.wsConnected" [class.card-bad]="!health.systemHealth?.wsConnected">
            <div class="card-label">WebSocket</div>
            <div class="card-value">{{ health.systemHealth?.wsConnected ? 'CONNECTED' : 'DISCONNECTED' }}</div>
            <div class="card-sub">Tokens: {{ health.systemHealth?.wsSubscribedTokens }} | Ticks: {{ health.systemHealth?.wsTickCount | number }} | Last: {{ health.systemHealth?.wsLastTickAge }}</div>
          </div>
          <div class="card" [class.card-ok]="health.systemHealth?.brokerAuthenticated" [class.card-bad]="!health.systemHealth?.brokerAuthenticated">
            <div class="card-label">Broker Auth</div>
            <div class="card-value">{{ health.systemHealth?.brokerAuthenticated ? 'OK' : 'NOT AUTH' }}</div>
          </div>
          <div class="card" [class.card-ok]="health.systemHealth?.scannerRunning" [class.card-bad]="!health.systemHealth?.scannerRunning">
            <div class="card-label">Scanner</div>
            <div class="card-value">{{ health.systemHealth?.scannerRunning ? 'RUNNING' : 'STOPPED' }}</div>
            <div class="card-sub">Last scan: {{ health.systemHealth?.scanGapSeconds >= 0 ? health.systemHealth?.scanGapSeconds + 's ago' : 'never' }}</div>
          </div>
          <div class="card">
            <div class="card-label">WS Reconnects</div>
            <div class="card-value">{{ health.systemHealth?.wsReconnectCount }}</div>
            <div class="card-sub">Subscribes: {{ health.systemHealth?.wsSubscribeCount }} | Disconnects: {{ health.systemHealth?.wsDisconnectCount }}</div>
          </div>
          <div class="card" [class.card-bad]="health.systemHealth?.errorsToday > 5">
            <div class="card-label">Errors Today</div>
            <div class="card-value">{{ health.systemHealth?.errorsToday }}</div>
            <div class="card-sub">REST: {{ health.systemHealth?.restErrorCount }} | DB: {{ health.systemHealth?.dbErrorCount }}</div>
          </div>
          <div class="card">
            <div class="card-label">Halt / Kill</div>
            <div class="card-value">{{ health.systemHealth?.haltMode }} | {{ health.systemHealth?.killSwitch ? 'KILL ON' : 'OK' }}</div>
            <div class="card-sub">Daily approved: {{ health.systemHealth?.dailyApproved ? 'YES' : 'NO' }}</div>
          </div>
        </div>

        <!-- ── Data Health ── -->
        <h2 class="sec-title"><mat-icon class="sec-icon">bar_chart</mat-icon> Data Health (Scanner Dependencies)</h2>
        <div class="cards">
          @for (idx of enabledUnderlyings(); track idx) {
            <div class="card" [class.card-ok]="health.dataHealth?.[idx.toLowerCase() + 'Spot'] > 0" [class.card-bad]="!health.dataHealth?.[idx.toLowerCase() + 'Spot']">
              <div class="card-label">{{ idx }} Spot</div>
              <div class="card-value">{{ health.dataHealth?.[idx.toLowerCase() + 'Spot'] > 0 ? (health.dataHealth?.[idx.toLowerCase() + 'Spot'] | number:'1.2-2') : 'NO DATA' }}</div>
              <div class="card-sub">Candles: 1m={{ health.dataHealth?.[idx.toLowerCase() + 'Candles1m'] }} 5m={{ health.dataHealth?.[idx.toLowerCase() + 'Candles5m'] }} 15m={{ health.dataHealth?.[idx.toLowerCase() + 'Candles15m'] }}</div>
            </div>
          }
          <div class="card" [class.card-ok]="health.dataHealth?.vix > 0" [class.card-bad]="!health.dataHealth?.vix">
            <div class="card-label">VIX</div>
            <div class="card-value">{{ health.dataHealth?.vix > 0 ? (health.dataHealth?.vix | number:'1.2-2') : 'NO DATA' }}</div>
          </div>
          <div class="card" [class.card-ok]="health.dataHealth?.pcr > 0">
            <div class="card-label">PCR</div>
            <div class="card-value">{{ health.dataHealth?.pcr > 0 ? (health.dataHealth?.pcr | number:'1.3-3') : 'NO DATA' }}</div>
          </div>
        </div>
        <div class="cards">
          @for (idx of enabledUnderlyings(); track idx) {
            <div class="card" [class.card-ok]="health.dataHealth?.[idx.toLowerCase() + 'OptionDataStatus'] === 'OK'"
                              [class.card-bad]="health.dataHealth?.[idx.toLowerCase() + 'OptionDataStatus'] === 'NO_DATA' || health.dataHealth?.[idx.toLowerCase() + 'OptionDataStatus'] === 'ERROR'"
                              [class.card-warn]="health.dataHealth?.[idx.toLowerCase() + 'OptionDataStatus'] === 'PARTIAL'">
              <div class="card-label">{{ idx }} Option Chain</div>
              <div class="card-value">{{ health.dataHealth?.[idx.toLowerCase() + 'OptionDataStatus'] }}</div>
              <div class="card-sub">Chain: {{ health.dataHealth?.[idx.toLowerCase() + 'OptionChainSize'] }} | Live OI: {{ health.dataHealth?.[idx.toLowerCase() + 'OptionsWithLiveOI'] }} | Live Price: {{ health.dataHealth?.[idx.toLowerCase() + 'OptionsWithLivePrice'] }}</div>
            </div>
          }
        </div>
        <div class="cards">
          @for (idx of enabledUnderlyings(); track idx) {
            <div class="card" [class.card-ok]="health.dataHealth?.[idx.toLowerCase() + 'CandleStatus'] === 'FRESH'"
                              [class.card-bad]="health.dataHealth?.[idx.toLowerCase() + 'CandleStatus'] === 'VERY_STALE' || health.dataHealth?.[idx.toLowerCase() + 'CandleStatus'] === 'NO_DATA'"
                              [class.card-warn]="health.dataHealth?.[idx.toLowerCase() + 'CandleStatus'] === 'STALE'">
              <div class="card-label">{{ idx }} Candle Freshness</div>
              <div class="card-value">{{ health.dataHealth?.[idx.toLowerCase() + 'CandleStatus'] }}</div>
              <div class="card-sub">Last candle: {{ health.dataHealth?.[idx.toLowerCase() + 'LastCandleAge'] ?? '—' }}</div>
            </div>
          }
          <div class="card" [class.card-ok]="health.dataHealth?.safeForLongPremium" [class.card-bad]="!health.dataHealth?.safeForLongPremium">
            <div class="card-label">Market Guard</div>
            <div class="card-value">{{ health.dataHealth?.safeForLongPremium ? 'SAFE' : 'BLOCKED' }}</div>
            <div class="card-sub">{{ health.dataHealth?.longPremiumBlockReason ?? 'All clear' }}</div>
          </div>
        </div>

        <!-- ── Order Stats ── -->
        <h2 class="sec-title"><mat-icon class="sec-icon">receipt_long</mat-icon> Orders &amp; Trades</h2>
        <div class="cards">
          <div class="card">
            <div class="card-label">Orders Today</div>
            <div class="card-value">{{ health.orderStats?.totalOrders }}</div>
            <div class="card-sub">Pending: {{ health.orderStats?.pendingOrders }} | Filled: {{ health.orderStats?.completedOrders }} | Rejected: {{ health.orderStats?.rejectedOrders }}</div>
          </div>
          <div class="card">
            <div class="card-label">Live Trades</div>
            <div class="card-value">Open: {{ health.orderStats?.openLiveTrades }} | Closed: {{ health.orderStats?.closedLiveTrades }}</div>
          </div>
          <div class="card">
            <div class="card-label">Paper Trades</div>
            <div class="card-value">Open: {{ health.orderStats?.openPaperTrades }} | Closed: {{ health.orderStats?.closedPaperTrades }}</div>
          </div>
        </div>
      }

      <!-- ── Scheduled Tasks ── -->
      <h2 class="sec-title"><mat-icon class="sec-icon">schedule</mat-icon> Scheduled Tasks</h2>
      @if (schedulers.length) {
        <table class="audit-table">
          <thead><tr><th>Task</th><th>Description</th><th>Interval</th><th>Last Run</th><th>Runs</th><th>Errors</th><th>Health</th><th>Enabled</th><th>Action</th></tr></thead>
          <tbody>
            @for (s of schedulers; track s.name) {
              <tr [class.row-ok]="s.health === 'OK'" [class.row-bad]="s.health === 'STALE' || s.health === 'ERROR'" [class.row-warn]="s.health === 'DISABLED' || s.health === 'NEVER_RUN'">
                <td><strong>{{ s.name }}</strong></td>
                <td class="desc-cell">{{ s.description }}</td>
                <td class="mono">{{ s.intervalMs > 0 ? (s.intervalMs / 1000) + 's' : 'cron' }}</td>
                <td class="mono">{{ s.lastRunAgeSec >= 0 ? s.lastRunAgeSec + 's ago' : 'never' }}</td>
                <td class="mono">{{ s.runCount }}</td>
                <td class="mono" [class.err-count]="s.errorCount > 0">{{ s.errorCount }}</td>
                <td><span class="badge" [class.badge-ok]="s.health === 'OK'" [class.badge-bad]="s.health === 'STALE' || s.health === 'ERROR'" [class.badge-warn]="s.health === 'DISABLED'" [class.badge-muted]="s.health === 'NEVER_RUN'">{{ s.health }}</span></td>
                <td><mat-slide-toggle [checked]="s.enabled" (change)="toggleTask(s.name, $event.checked)" color="primary" class="sm-toggle"></mat-slide-toggle></td>
                <td><button mat-stroked-button class="trigger-btn" (click)="triggerTask(s.name)"><mat-icon>play_arrow</mat-icon></button></td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <div class="no-data">No scheduled tasks registered</div>
      }

      <!-- ── Order Audit Trail ── -->
      <h2 class="sec-title"><mat-icon class="sec-icon">search</mat-icon> Order Audit Trail</h2>
      <div class="search-row">
        <mat-form-field appearance="outline" class="search-field">
          <mat-label>Trade ID</mat-label>
          <input matInput [(ngModel)]="searchTradeId" [matAutocomplete]="tradeIdAuto"
                 (input)="cd.detectChanges()" (focus)="cd.detectChanges()" placeholder="TRD-xxx">
          <mat-autocomplete #tradeIdAuto="matAutocomplete" autoActiveFirstOption>
            @for (id of filteredTradeIds(); track id) {
              <mat-option [value]="id">{{ id }}</mat-option>
            }
          </mat-autocomplete>
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="searchAudit()" [disabled]="!searchTradeId">
          <mat-icon>search</mat-icon> Trace
        </button>
        <mat-form-field appearance="outline" class="search-field">
          <mat-label>Instrument Key</mat-label>
          <input matInput [(ngModel)]="searchInstrument" [matAutocomplete]="instrAuto"
                 (input)="cd.detectChanges()" (focus)="cd.detectChanges()" placeholder="NFO:NIFTY...">
          <mat-autocomplete #instrAuto="matAutocomplete" autoActiveFirstOption>
            @for (k of filteredInstruments(); track k) {
              <mat-option [value]="k">{{ k }}</mat-option>
            }
          </mat-autocomplete>
        </mat-form-field>
        <button mat-flat-button (click)="searchByInstrument()" [disabled]="!searchInstrument">
          <mat-icon>search</mat-icon> Search
        </button>
      </div>

      @if (auditResult) {
        <div class="audit-section">
          @if (auditResult.lifecycle?.length) {
            <h3>Order Lifecycle</h3>
            <table class="audit-table">
              <thead><tr><th>Stage</th><th>Time</th><th>Detail</th><th>Status</th></tr></thead>
              <tbody>
                @for (step of auditResult.lifecycle; track step.stage) {
                  <tr [class.row-ok]="step.status === 'OPEN' || step.status === 'COMPLETE' || step.status === 'ORDER_FILLED'"
                      [class.row-bad]="step.status === 'REJECTED' || step.status === 'CANCELLED'">
                    <td><strong>{{ step.stage }}</strong></td>
                    <td class="mono">{{ formatTime(step.time) }}</td>
                    <td>{{ step.detail }}</td>
                    <td><span class="badge" [class.badge-ok]="isGoodStatus(step.status)" [class.badge-bad]="isBadStatus(step.status)">{{ step.status }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          }
          @if (auditResult.trade) {
            <h3>Trade Details</h3>
            <pre class="json-block">{{ auditResult.trade | json }}</pre>
          }
          @if (auditResult.orders?.length) {
            <h3>Orders ({{ auditResult.orders.length }})</h3>
            <pre class="json-block">{{ auditResult.orders | json }}</pre>
          }
          @if (auditResult.error) {
            <div class="error-msg">{{ auditResult.error }}</div>
          }
        </div>
      }

      <!-- ── Recent Errors (from ErrorEventService) ── -->
      <h2 class="sec-title"><mat-icon class="sec-icon">bug_report</mat-icon> Recent Errors (Today)</h2>
      <div class="cards">
        <div class="card" [class.card-bad]="(errorCounts?.CRITICAL ?? 0) > 0">
          <div class="card-label">Critical</div>
          <div class="card-value">{{ errorCounts?.CRITICAL ?? 0 }}</div>
        </div>
        <div class="card" [class.card-warn]="(errorCounts?.HIGH ?? 0) > 0">
          <div class="card-label">High</div>
          <div class="card-value">{{ errorCounts?.HIGH ?? 0 }}</div>
        </div>
        <div class="card">
          <div class="card-label">Medium</div>
          <div class="card-value">{{ errorCounts?.MEDIUM ?? 0 }}</div>
        </div>
      </div>
      @if (recentErrors.length) {
        <table class="audit-table">
          <thead><tr><th>Time</th><th>Severity</th><th>Component</th><th>Message</th></tr></thead>
          <tbody>
            @for (e of recentErrors; track e.id) {
              <tr [class.row-bad]="e.severity === 'CRITICAL'" [class.row-warn]="e.severity === 'HIGH'">
                <td class="mono">{{ formatTime(e.timestamp) }}</td>
                <td><span class="badge" [class.badge-bad]="e.severity === 'CRITICAL'" [class.badge-warn]="e.severity === 'HIGH'" [class.badge-muted]="e.severity === 'MEDIUM'">{{ e.severity }}</span></td>
                <td><strong>{{ e.component }}</strong></td>
                <td class="err-msg-cell">{{ e.message }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <div class="no-data">No errors recorded today</div>
      }

      <!-- ── Failures ── -->
      <h2 class="sec-title"><mat-icon class="sec-icon">error_outline</mat-icon> Failures &amp; Blocked Entries</h2>
      <div class="search-row">
        <mat-form-field appearance="outline" class="search-field">
          <mat-label>Period</mat-label>
          <mat-select [(ngModel)]="failurePeriod">
            <mat-option value="TODAY">Today</mat-option>
            <mat-option value="YESTERDAY">Yesterday</mat-option>
            <mat-option value="LAST7">Last 7 days</mat-option>
          </mat-select>
        </mat-form-field>
        <button mat-flat-button color="warn" (click)="loadFailures()">
          <mat-icon>warning</mat-icon> Load Failures
        </button>
      </div>

      @if (failures) {
        <div class="cards">
          <div class="card card-bad">
            <div class="card-label">Failed Orders</div>
            <div class="card-value">{{ failures.summary?.failedOrders ?? 0 }}</div>
          </div>
          <div class="card card-bad">
            <div class="card-label">Error Events</div>
            <div class="card-value">{{ failures.summary?.errorEvents ?? 0 }}</div>
          </div>
          <div class="card">
            <div class="card-label">Blocked Entries</div>
            <div class="card-value">{{ failures.summary?.blockedEntries ?? 0 }}</div>
          </div>
        </div>

        @if (failures.failedOrders?.length) {
          <h3>Failed Orders</h3>
          <table class="audit-table">
            <thead><tr><th>Time</th><th>Instrument</th><th>Side</th><th>Status</th><th>Reason</th><th>Strategy</th></tr></thead>
            <tbody>
              @for (o of failures.failedOrders; track o.clientOrderId) {
                <tr class="row-bad">
                  <td class="mono">{{ formatTime(o.updatedAt) }}</td>
                  <td>{{ o.instrumentKey }}</td>
                  <td>{{ o.side }}</td>
                  <td><span class="badge badge-bad">{{ o.status }}</span></td>
                  <td>{{ o.rejectionReason ?? '—' }}</td>
                  <td>{{ o.strategyType ?? '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }

        @if (failures.errorEvents?.length) {
          <h3>Error Events</h3>
          <table class="audit-table">
            <thead><tr><th>Time</th><th>Source</th><th>Message</th></tr></thead>
            <tbody>
              @for (e of failures.errorEvents; track e.timestamp) {
                <tr class="row-bad">
                  <td class="mono">{{ formatTime(e.timestamp) }}</td>
                  <td>{{ e.source }}</td>
                  <td>{{ e.message }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </section>
  `,
  styles: [`
    .page { padding: 16px; max-width: 1400px; margin: 0 auto; color: #c9d1d9; }
    .hdr { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .title { font-size: 1.4rem; font-weight: 700; margin: 0; color: #e6edf3; }
    .subtitle { font-size: 0.85rem; color: #8b949e; margin: 4px 0 0; }
    .sec-title { display: flex; align-items: center; gap: 8px; font-size: 1.05rem; margin: 20px 0 10px; color: #8b949e; border-bottom: 1px solid #30363d; padding-bottom: 6px; }
    .sec-icon { font-size: 18px; width: 18px; height: 18px; color: #58a6ff; }
    .cards { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
    .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 10px 14px; min-width: 150px; flex: 1; }
    .card-ok { border-left: 3px solid #3fb950; }
    .card-bad { border-left: 3px solid #f85149; }
    .card-warn { border-left: 3px solid #d29922; }
    .card-label { font-size: 0.7rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.3px; }
    .card-value { font-size: 1rem; font-weight: 700; font-family: Consolas, monospace; margin: 3px 0; color: #e6edf3; }
    .card-sub { font-size: 0.7rem; color: #6e7681; }
    .alerts-banner { background: #3d2e00; border: 1px solid #d29922; border-radius: 8px; padding: 10px 14px; margin-bottom: 12px; }
    .alert-item { font-size: 0.82rem; color: #d29922; padding: 2px 0; }
    .search-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
    .search-field { min-width: 200px; }
    .audit-section { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
    .audit-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
    .audit-table th { background: #21262d; padding: 6px 10px; text-align: left; font-size: 0.72rem; color: #8b949e; }
    .audit-table td { padding: 6px 10px; border-top: 1px solid #21262d; color: #c9d1d9; }
    .row-ok { background: #0d1f0d; }
    .row-bad { background: #2d1215; }
    .badge { padding: 2px 8px; border-radius: 10px; font-size: 0.68rem; font-weight: 700; }
    .badge-ok { background: #1a4731; color: #3fb950; }
    .badge-bad { background: #3d1f1f; color: #f85149; }
    .mono { font-family: Consolas, monospace; font-size: 0.78rem; }
    .json-block { background: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 10px; font-size: 0.75rem; overflow-x: auto; max-height: 300px; color: #8b949e; }
    .error-msg { color: #f85149; font-weight: 600; padding: 12px; background: #2d1215; border-radius: 6px; }
    .err-msg-cell { max-width: 500px; word-break: break-word; font-size: 0.75rem; }
    .row-warn { background: #2d2600; }
    .badge-warn { background: #3d2e00; color: #d29922; }
    .badge-muted { background: rgba(255,255,255,.05); color: #8b949e; }
    .no-data { color: #6e7681; font-size: 0.82rem; padding: 12px; text-align: center; }
    .desc-cell { font-size: 0.72rem; color: #8b949e; max-width: 250px; }
    .err-count { color: #f85149; font-weight: 700; }
    .row-warn { background: #2d2600; }
    .sm-toggle { transform: scale(0.8); }
    .trigger-btn { min-width: 32px !important; padding: 0 4px !important; line-height: 28px !important; }
    .trigger-btn mat-icon { font-size: 16px; width: 16px; height: 16px; }
    h3 { color: #c9d1d9; font-size: 0.95rem; margin: 14px 0 8px; }
  `]
})
export class DiagnosticsPageComponent implements OnInit, OnDestroy {
  health: any = null;
  auditResult: any = null;
  failures: any = null;
  recentErrors: any[] = [];
  errorCounts: any = {};
  schedulers: any[] = [];
  searchTradeId = '';
  searchInstrument = '';
  failurePeriod = 'TODAY';
  lookupTradeIds: string[] = [];
  lookupInstruments: string[] = [];
  private pollSub?: Subscription;

  constructor(private api: ApiService, public cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.load();
    this.pollSub = interval(10000).subscribe(() => { this.loadHealth(); this.loadSchedulers(); });
  }

  ngOnDestroy(): void { this.pollSub?.unsubscribe(); }

  load(): void {
    this.loadHealth();
    this.loadLookup();
    this.loadRecentErrors();
    this.loadSchedulers();
  }

  loadHealth(): void {
    this.api.diagnosticsHealth().pipe(catchError(() => of(null))).subscribe(d => {
      if (d) this.health = d;
      this.cd.detectChanges();
    });
  }

  searchAudit(): void {
    if (!this.searchTradeId) return;
    this.api.diagnosticsAudit(this.searchTradeId).subscribe({
      next: d => { this.auditResult = d; this.cd.detectChanges(); },
      error: () => { this.auditResult = { error: 'Failed to load audit trail' }; this.cd.detectChanges(); }
    });
  }

  searchByInstrument(): void {
    if (!this.searchInstrument) return;
    this.api.diagnosticsSearchInstrument(this.searchInstrument).subscribe({
      next: d => { this.auditResult = d; this.cd.detectChanges(); },
      error: () => { this.auditResult = { error: 'Search failed' }; this.cd.detectChanges(); }
    });
  }

  loadFailures(): void {
    this.api.diagnosticsFailures(this.failurePeriod).subscribe({
      next: d => { this.failures = d; this.cd.detectChanges(); },
      error: () => {}
    });
  }

  loadLookup(): void {
    this.api.diagnosticsLookup('LAST7').pipe(catchError(() => of(null))).subscribe(d => {
      if (d) {
        this.lookupTradeIds = d.tradeIds ?? [];
        this.lookupInstruments = d.instrumentKeys ?? [];
        this.cd.detectChanges();
      }
    });
  }

  loadRecentErrors(): void {
    this.api.diagnosticsRecentErrors(50).pipe(catchError(() => of(null))).subscribe(d => {
      if (d) {
        this.recentErrors = d.errors ?? [];
        this.errorCounts = d.counts ?? {};
        this.cd.detectChanges();
      }
    });
  }

  loadSchedulers(): void {
    this.api.diagnosticsSchedulers().pipe(catchError(() => of([]))).subscribe(d => {
      this.schedulers = d ?? [];
      this.cd.detectChanges();
    });
  }

  toggleTask(name: string, enabled: boolean): void {
    this.api.diagnosticsToggleScheduler(name, enabled).subscribe(() => this.loadSchedulers());
  }

  triggerTask(name: string): void {
    this.api.diagnosticsTriggerScheduler(name).subscribe(() => {
      setTimeout(() => this.loadSchedulers(), 1000); // refresh after trigger completes
    });
  }

  filteredTradeIds(): string[] {
    const q = this.searchTradeId.toLowerCase();
    return q ? this.lookupTradeIds.filter(id => id.toLowerCase().includes(q)).slice(0, 20) : this.lookupTradeIds.slice(0, 20);
  }

  filteredInstruments(): string[] {
    const q = this.searchInstrument.toLowerCase();
    return q ? this.lookupInstruments.filter(k => k.toLowerCase().includes(q)).slice(0, 20) : this.lookupInstruments.slice(0, 20);
  }

  enabledUnderlyings(): string[] {
    return this.health?.dataHealth?.enabledUnderlyings ?? [];
  }

  isGoodStatus(s: string): boolean { return ['OPEN', 'COMPLETE', 'ORDER_FILLED', 'CLOSED'].includes(s); }
  isBadStatus(s: string): boolean { return ['REJECTED', 'CANCELLED', 'BROKER_ERROR', 'RISK_REJECTED'].includes(s); }

  formatTime(ts: string): string {
    if (!ts) return '—';
    try { return new Date(ts).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }); }
    catch { return ts; }
  }
}
