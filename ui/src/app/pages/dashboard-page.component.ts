import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { catchError, forkJoin, of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { ApiService } from '../core/api.service';
import { ConfigResponse, PnlSnapshot, RuntimeStatus, StrategyDecision } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatChipsModule, DataTableComponent],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Dashboard</h1>
          <p class="page-subtitle">Current state, risk posture, latest signal, and PnL.</p>
        </div>
        <span class="spacer"></span>
        <span class="badge" [class.ok]="!loadError" [class.warn]="loadError">
          {{ loading ? 'Refreshing' : lastUpdatedAt ? 'Updated ' + lastUpdatedAt : 'Waiting' }}
        </span>
        <button class="action-button" mat-flat-button color="primary" (click)="load()">Refresh</button>
      </div>
      @if (loadError) {
        <p class="page-subtitle status-warn" style="margin-top: -12px;">{{ loadError }}</p>
      }

      @if (!runtime) {
        <div class="panel metric">
          <span class="metric-label">Server status</span>
          <span class="metric-value status-warn">Loading</span>
          <span class="metric-note">Waiting for runtime status from the server.</span>
        </div>
      } @else {
        <div class="grid three">
          <div class="panel metric">
            <span class="metric-label">Scanner</span>
            <span class="metric-value" [class.status-ok]="runtime.running" [class.status-warn]="!runtime.running">
              {{ runtime.running ? 'Running' : 'Stopped' }}
            </span>
            <span class="metric-note">{{ runtime.updatedAt || 'Waiting for runtime' }}</span>
          </div>
          <div class="panel metric">
            <span class="metric-label">Kill switch</span>
            <span class="metric-value" [class.status-bad]="runtime.killSwitch" [class.status-ok]="!runtime.killSwitch">
              {{ runtime.killSwitch ? 'Enabled' : 'Clear' }}
            </span>
            <span class="metric-note">{{ runtime.killSwitch ? 'Trading is blocked' : 'Risk gate is clear' }}</span>
          </div>
          <div class="panel metric">
            <span class="metric-label">Total PnL</span>
            <span class="metric-value" [class.status-ok]="pnlValue >= 0" [class.status-bad]="pnlValue < 0">
              {{ pnlValue | number:'1.2-2' }}
            </span>
            <span class="metric-note">Realized + unrealized</span>
          </div>
        </div>

        <div class="grid two" style="margin-top: 16px;">
          <div class="panel">
            <h2>Runtime</h2>
            <div class="row">
              <span class="badge">{{ runtime.configuredMode }}</span>
              <span class="badge">{{ runtime.marketDataMode }} data</span>
              <span class="badge">{{ runtime.executionMode }} execution</span>
              <span class="badge" [class.ok]="runtime.liveTradingEnabled" [class.warn]="!runtime.liveTradingEnabled">
                {{ runtime.liveTradingEnabled ? 'Live enabled' : 'Live blocked' }}
              </span>
            </div>
            <div style="margin-top: 14px;"></div>
            <app-data-table [rows]="runtimeRows"></app-data-table>
          </div>
          <div class="panel">
            <h2>Latest Signal</h2>
            @if (latestSignal) {
              <div class="action-strip" style="margin-bottom: 14px;">
                <span class="badge">{{ latestSignal.underlying || '-' }}</span>
                <span class="badge">{{ latestSignal.signalType || '-' }}</span>
                <span class="badge">{{ latestSignal.optionType || '-' }}</span>
              </div>
            }
            <app-data-table [rows]="latestSignalRows"></app-data-table>
          </div>
        </div>
      }
    </section>
  `
})
export class DashboardPageComponent implements OnInit {
  runtime?: RuntimeStatus;
  pnl?: PnlSnapshot;
  latestSignal?: StrategyDecision | null;
  loading = false;
  loadError = '';
  lastUpdatedAt = '';
  private statusRequestInFlight = false;
  private supplementalRequestInFlight = false;

  constructor(
    private readonly api: ApiService,
    private readonly changeDetector: ChangeDetectorRef
  ) {
  }

  get pnlValue(): number {
    return Number(this.pnl?.totalPnl ?? 0);
  }

  get runtimeRows(): Array<{ label: string; value: string }> {
    if (!this.runtime) {
      return [];
    }
    return [
      { label: 'Scanner', value: this.runtime.running ? 'Running' : 'Stopped' },
      { label: 'Kill switch', value: this.runtime.killSwitch ? 'Enabled' : 'Clear' },
      { label: 'Requested mode', value: this.runtime.requestedMode },
      { label: 'Configured mode', value: this.runtime.configuredMode },
      { label: 'Market data', value: this.runtime.marketDataMode },
      { label: 'Execution', value: this.runtime.executionMode },
      { label: 'Live trading', value: this.runtime.liveTradingEnabled ? 'Enabled' : 'Blocked' },
      { label: 'Enabled underlyings', value: this.runtime.enabledUnderlyings?.join(', ') || '-' },
      { label: 'Updated at', value: this.runtime.updatedAt || '-' }
    ];
  }

  get latestSignalRows(): Array<{ label: string; value: string }> {
    if (!this.latestSignal) {
      return [{ label: 'Status', value: 'No signal available yet' }];
    }
    return [
      { label: 'Timestamp', value: this.display(this.latestSignal.timestamp) },
      { label: 'Underlying', value: this.display(this.latestSignal.underlying) },
      { label: 'Signal type', value: this.display(this.latestSignal.signalType) },
      { label: 'Option type', value: this.display(this.latestSignal.optionType) },
      { label: 'Underlying price', value: this.display(this.latestSignal.underlyingPrice) },
      { label: 'Selected strike', value: this.display(this.latestSignal.selectedStrike) },
      { label: 'Instrument', value: this.display(this.latestSignal.selectedInstrumentKey) },
      { label: 'Confidence score', value: this.display(this.latestSignal.confidenceScore) },
      { label: 'Reasons', value: this.display(this.latestSignal.reasons) }
    ];
  }

  ngOnInit(): void {
    setTimeout(() => this.load(), 0);
  }

  load(): void {
    this.loadRuntimeStatus();
  }

  private loadRuntimeStatus(): void {
    if (this.statusRequestInFlight) {
      return;
    }
    this.statusRequestInFlight = true;
    this.loading = true;
    this.loadError = '';
    this.api.config().subscribe({
      next: (config) => this.applyRuntimeStatus(config),
      error: () => this.applyStatusLoadFailure()
    });
  }

  private loadSupplementalData(): void {
    if (this.supplementalRequestInFlight) {
      return;
    }
    this.supplementalRequestInFlight = true;
    forkJoin({
      pnl: this.api.pnl().pipe(catchError(() => of(null as PnlSnapshot | null))),
      latestSignal: this.api.latestSignal().pipe(catchError(() => of(null as StrategyDecision | null)))
    }).subscribe(({ pnl, latestSignal }) => {
      if (pnl) {
        this.pnl = pnl;
      }
      this.latestSignal = latestSignal;
      if (!pnl && this.runtime) {
        this.loadError = 'Runtime status loaded. PnL is not available yet.';
      }
      this.supplementalRequestInFlight = false;
      this.changeDetector.detectChanges();
    });
  }

  private applyRuntimeStatus(config: ConfigResponse | RuntimeStatus): void {
    const runtime = this.runtimeFrom(config);
    if (!runtime) {
      this.applyStatusLoadFailure('Server returned /config without runtime status.');
      return;
    }
    this.runtime = runtime;
    this.loading = false;
    this.statusRequestInFlight = false;
    this.lastUpdatedAt = new Date().toLocaleTimeString();
    this.changeDetector.detectChanges();
    this.loadSupplementalData();
  }

  private applyStatusLoadFailure(message = 'Unable to load server runtime status. Click Refresh to try again.'): void {
    this.loading = false;
    this.statusRequestInFlight = false;
    this.loadError = message;
    this.changeDetector.detectChanges();
  }

  private runtimeFrom(config: ConfigResponse | RuntimeStatus): RuntimeStatus | undefined {
    if ('runtime' in config) {
      return config.runtime;
    }
    if ('running' in config && 'killSwitch' in config) {
      return config;
    }
    return undefined;
  }

  private display(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }
    if (Array.isArray(value)) {
      return value.join(', ');
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }
}
