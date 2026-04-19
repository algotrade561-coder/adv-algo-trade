import { Component, OnInit } from '@angular/core';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService } from '../core/api.service';
import { ApiRecord, PnlSnapshot, StrategyDecision } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-monitoring-page',
  standalone: true,
  imports: [MatButtonModule, MatCheckboxModule, MatTabsModule, DataTableComponent, JsonViewComponent],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Monitoring</h1>
          <p class="page-subtitle">Positions, orders, trades, PnL, and recent strategy signals.</p>
        </div>
        <span class="spacer"></span>
        <button mat-flat-button color="primary" (click)="load()">Refresh</button>
      </div>

      <div class="grid three">
        <div class="panel metric">
          <span class="metric-label">Realized PnL</span>
          <span class="metric-value">{{ pnl?.realizedPnl ?? 0 }}</span>
        </div>
        <div class="panel metric">
          <span class="metric-label">Unrealized PnL</span>
          <span class="metric-value">{{ pnl?.unrealizedPnl ?? 0 }}</span>
        </div>
        <div class="panel metric">
          <span class="metric-label">Total PnL</span>
          <span class="metric-value">{{ pnl?.totalPnl ?? 0 }}</span>
        </div>
      </div>

      <div class="panel monitoring-panel" style="margin-top: 16px;">
        <mat-tab-group>
          <mat-tab label="Positions">
            <app-data-table [rows]="positions"></app-data-table>
          </mat-tab>
          <mat-tab label="Orders">
            <app-data-table [rows]="orders"></app-data-table>
          </mat-tab>
          <mat-tab label="Trades">
            <app-data-table [rows]="trades"></app-data-table>
          </mat-tab>
          <mat-tab label="Signals">
            <div class="signal-filters">
              <span class="metric-label">Signal type</span>
              @for (signalType of signalTypeOptions; track signalType) {
                <mat-checkbox
                  [checked]="selectedSignalTypes.has(signalType)"
                  (change)="setSignalTypeFilter(signalType, $event.checked)">
                  {{ signalType }}
                </mat-checkbox>
              }
            </div>
            <app-data-table [rows]="filteredSignals"></app-data-table>
          </mat-tab>
          <mat-tab label="Latest Signal">
            <app-json-view [value]="latestSignal"></app-json-view>
          </mat-tab>
        </mat-tab-group>
      </div>
    </section>
  `,
  styles: [`
    .monitoring-panel {
      min-height: 0;
    }

    .signal-filters {
      display: flex;
      align-items: center;
      gap: 14px;
      flex-wrap: wrap;
      margin: 14px 0;
      padding: 12px 14px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.035);
    }
  `]
})
export class MonitoringPageComponent implements OnInit {
  readonly signalTypeOptions = ['BUY_CE', 'BUY_PE', 'NO_TRADE'];
  readonly selectedSignalTypes = new Set<string>(this.signalTypeOptions);
  positions: ApiRecord[] = [];
  orders: ApiRecord[] = [];
  trades: ApiRecord[] = [];
  signals: ApiRecord[] = [];
  latestSignal?: StrategyDecision | null;
  pnl?: PnlSnapshot;

  constructor(private readonly api: ApiService) {
  }

  get filteredSignals(): ApiRecord[] {
    return this.signals.filter((signal) => {
      const signalType = String(signal['signalType'] ?? '');
      return this.selectedSignalTypes.has(signalType);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    forkJoin({
      positions: this.api.positions(),
      orders: this.api.orders(),
      trades: this.api.trades(),
      pnl: this.api.pnl(),
      latestSignal: this.api.latestSignal(),
      signals: this.api.recentSignals()
    }).subscribe((result) => {
      this.positions = result.positions;
      this.orders = result.orders;
      this.trades = result.trades;
      this.pnl = result.pnl;
      this.latestSignal = result.latestSignal;
      this.signals = result.signals as ApiRecord[];
    });
  }

  setSignalTypeFilter(signalType: string, checked: boolean): void {
    if (checked) {
      this.selectedSignalTypes.add(signalType);
    } else {
      this.selectedSignalTypes.delete(signalType);
    }
  }
}
