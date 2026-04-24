import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiService } from '../core/api.service';
import { ConfigParameter, ConfigResponse } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';

@Component({
  selector: 'app-config-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, DataTableComponent],
  styles: [`
    .strategy-grid {
      display: grid;
      grid-template-columns: 2fr 1fr;
      gap: 16px;
      margin-bottom: 16px;
    }

    .strategy-panel {
      border: 1px solid rgba(124, 166, 212, 0.18);
      background:
        linear-gradient(180deg, rgba(12, 31, 55, 0.96), rgba(7, 20, 37, 0.98)),
        radial-gradient(circle at top right, rgba(88, 152, 255, 0.14), transparent 42%);
    }

    .strategy-panel.compact {
      align-self: start;
    }

    .section-heading {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 16px;
    }

    .section-note {
      margin: 4px 0 0;
      color: rgba(222, 231, 247, 0.72);
      font-size: 0.93rem;
    }

    .section-badge {
      border-radius: 999px;
      padding: 6px 12px;
      background: rgba(83, 150, 255, 0.16);
      border: 1px solid rgba(112, 173, 255, 0.24);
      color: #dce9ff;
      font-size: 0.8rem;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      white-space: nowrap;
    }

    .control-groups {
      display: grid;
      gap: 16px;
    }

    .control-group h3 {
      margin: 0 0 12px;
      color: #f4f7ff;
      font-size: 0.96rem;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .control-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;
    }

    .control-tile {
      padding: 12px 14px;
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(124, 166, 212, 0.16);
      min-height: 74px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: 6px;
    }

    .control-label {
      color: rgba(222, 231, 247, 0.68);
      font-size: 0.78rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .control-value {
      color: #f5f8ff;
      font-size: 1rem;
      font-weight: 600;
      line-height: 1.3;
      word-break: break-word;
    }

    .control-value.enabled {
      color: #8ce3b0;
    }

    @media (max-width: 1100px) {
      .strategy-grid {
        grid-template-columns: 1fr;
      }
    }

    @media (max-width: 720px) {
      .control-grid {
        grid-template-columns: 1fr;
      }

      .section-heading {
        flex-direction: column;
      }
    }
  `],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Configuration</h1>
          <p class="page-subtitle">Current values, descriptions, and usage notes for each trading parameter.</p>
        </div>
        <span class="spacer"></span>
        <span class="badge" [class.ok]="config && !loadError" [class.warn]="loadError">
          {{ loading ? 'Loading' : lastUpdatedAt ? 'Updated ' + lastUpdatedAt : 'Waiting' }}
        </span>
        <button mat-flat-button color="primary" [disabled]="loading" (click)="load()">Refresh</button>
      </div>
      @if (loadError) {
        <p class="page-subtitle status-warn" style="margin-top: -12px;">{{ loadError }}</p>
      }

      @if (!config) {
        <div class="panel metric">
          <span class="metric-label">Configuration</span>
          <span class="metric-value status-warn">Loading</span>
          <span class="metric-note">Waiting for configuration from the server.</span>
        </div>
      } @else {
      <div class="strategy-grid">
        <div class="panel strategy-panel">
          <div class="section-heading">
            <div>
              <h2>Strategy Controls</h2>
              <p class="section-note">Primary entry, confirmation, and momentum settings currently active in the engine.</p>
            </div>
            <span class="section-badge">{{ strategyTimeframeLabel }}</span>
          </div>

          <div class="control-groups">
            <section class="control-group">
              <h3>Entry Core</h3>
              <div class="control-grid">
                <div class="control-tile">
                  <span class="control-label">Option Sides</span>
                  <span class="control-value">{{ listValue(entry('enabledOptionTypes')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Min Signal Score</span>
                  <span class="control-value">{{ display(entry('minSignalScorePercent')) }}%</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Volume Spike</span>
                  <span class="control-value">{{ display(entry('volumeSpikeMultiplier')) }}x</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Min Liquidity</span>
                  <span class="control-value">{{ display(entry('minLiquidityVolume')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Max IV</span>
                  <span class="control-value">{{ display(entry('maxIvPercent')) }}%</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">VWAP Filter</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('vwapFilterEnabled'))">
                    {{ boolLabel(entry('vwapFilterEnabled')) }}
                  </span>
                </div>
              </div>
            </section>

            <section class="control-group">
              <h3>Breakout And OI</h3>
              <div class="control-grid">
                <div class="control-tile">
                  <span class="control-label">Breakout Buffer</span>
                  <span class="control-value">{{ display(entry('breakoutBufferPercent')) }}%</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Breakout Lookback</span>
                  <span class="control-value">{{ display(entry('breakoutLookback')) }} candles</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">CE Confirm Candles</span>
                  <span class="control-value">{{ display(entry('ceBreakoutConfirmationCandles')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">PE Confirm Candles</span>
                  <span class="control-value">{{ display(entry('peBreakoutConfirmationCandles')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">CE OI Support</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('ceOiSupportRequired'))">
                    {{ boolLabel(entry('ceOiSupportRequired')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">PE OI Support</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('peOiSupportRequired'))">
                    {{ boolLabel(entry('peOiSupportRequired')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">CE OI Divergence</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('ceOiDivergenceFilterEnabled'))">
                    {{ boolLabel(entry('ceOiDivergenceFilterEnabled')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">PE OI Divergence</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('peOiDivergenceFilterEnabled'))">
                    {{ boolLabel(entry('peOiDivergenceFilterEnabled')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Divergence Ratio</span>
                  <span class="control-value">{{ display(entry('oiDivergenceMultiplier')) }}x</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Divergence Min Change</span>
                  <span class="control-value">{{ display(entry('oiDivergenceMinChange')) }}</span>
                </div>
              </div>
            </section>

            <section class="control-group">
              <h3>Timing And Momentum</h3>
              <div class="control-grid">
                <div class="control-tile">
                  <span class="control-label">Trend Timeframe</span>
                  <span class="control-value">{{ display(entry('trendTimeframe')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Entry Window</span>
                  <span class="control-value">{{ display(entry('entryStartTime')) }} - {{ display(entry('entryCutoffTime')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Trend Filter</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('trendFilterEnabled'))">
                    {{ boolLabel(entry('trendFilterEnabled')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">RSI Filter</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('rsiFilterEnabled'))">
                    {{ boolLabel(entry('rsiFilterEnabled')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">RSI Period</span>
                  <span class="control-value">{{ display(entry('rsiPeriod')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">CE RSI Threshold</span>
                  <span class="control-value">{{ display(entry('rsiCeBuyThreshold')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">PE RSI Threshold</span>
                  <span class="control-value">{{ display(entry('rsiPeSellThreshold')) }}</span>
                </div>
                <div class="control-tile">
                  <span class="control-label">First Minutes Entry</span>
                  <span class="control-value" [class.enabled]="boolValue(entry('allowFirstMinutesEntry'))">
                    {{ boolLabel(entry('allowFirstMinutesEntry')) }}
                  </span>
                </div>
                <div class="control-tile">
                  <span class="control-label">Open No-Entry Block</span>
                  <span class="control-value">{{ display(entry('noEntryFirstMinutes')) }} min</span>
                </div>
              </div>
            </section>
          </div>
        </div>

        <div class="panel strategy-panel compact">
          <div class="section-heading">
            <div>
              <h2>Runtime Alignment</h2>
              <p class="section-note">Configured defaults currently served by the app.</p>
            </div>
          </div>
          <div class="control-grid">
            <div class="control-tile">
              <span class="control-label">Mode</span>
              <span class="control-value">{{ runtime('configuredMode') ?? 'N/A' }}</span>
            </div>
            <div class="control-tile">
              <span class="control-label">Market Data</span>
              <span class="control-value">{{ runtime('marketDataMode') ?? 'N/A' }}</span>
            </div>
            <div class="control-tile">
              <span class="control-label">Execution</span>
              <span class="control-value">{{ runtime('executionMode') ?? 'N/A' }}</span>
            </div>
            <div class="control-tile">
              <span class="control-label">Live Trading</span>
              <span class="control-value" [class.enabled]="boolValue(runtime('liveTradingEnabled'))">
                {{ boolLabel(runtime('liveTradingEnabled')) }}
              </span>
            </div>
            <div class="control-tile">
              <span class="control-label">Enabled Underlyings</span>
              <span class="control-value">{{ listValue(runtime('enabledUnderlyings')) }}</span>
            </div>
            <div class="control-tile">
              <span class="control-label">Timezone</span>
              <span class="control-value">{{ display(timezoneConfig) }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="panel">
        <h2>Parameter Catalog</h2>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Search parameter</mat-label>
          <input matInput [formControl]="search" placeholder="risk, broker, breakout, telegram">
        </mat-form-field>
        <app-data-table [rows]="filteredRows"></app-data-table>
      </div>
      }
    </section>
  `
})
export class ConfigPageComponent implements OnInit {
  readonly search = new FormControl('', { nonNullable: true });
  config?: ConfigResponse;
  loading = false;
  loadError = '';
  lastUpdatedAt = '';
  private requestInFlight = false;

  constructor(
    private readonly api: ApiService,
    private readonly changeDetector: ChangeDetectorRef
  ) {
  }

  get filtered(): ConfigParameter[] {
    const term = this.search.value.trim().toLowerCase();
    const rows = this.config?.parameters ?? [];
    if (!term) {
      return rows;
    }
    return rows.filter((row) =>
      row.path.toLowerCase().includes(term)
      || row.description.toLowerCase().includes(term)
      || row.usedBy.toLowerCase().includes(term)
    );
  }

  get filteredRows() {
    return this.filtered.map((row) => ({
      path: row.path,
      value: this.display(row.value),
      description: row.description,
      usedBy: row.usedBy,
      sensitive: row.sensitive ? 'Yes' : 'No'
    }));
  }

  get tradingConfig(): Record<string, unknown> {
    return ((this.config?.configuration?.['trading'] as Record<string, unknown>) ?? {});
  }

  get entryConfig(): Record<string, unknown> {
    return ((this.tradingConfig['entry'] as Record<string, unknown>) ?? {});
  }

  get runtimeConfig(): Record<string, unknown> {
    return (this.config?.runtime as unknown as Record<string, unknown>) ?? {};
  }

  get timezoneConfig(): unknown {
    return this.tradingConfig['timezone'];
  }

  get strategyTimeframeLabel(): string {
    const timeframe = this.entry('timeframe');
    return typeof timeframe === 'string' && timeframe ? timeframe.replaceAll('_', ' ') : 'Strategy';
  }

  ngOnInit(): void {
    setTimeout(() => this.load(), 0);
  }

  load(): void {
    if (this.requestInFlight) {
      return;
    }
    this.requestInFlight = true;
    this.loading = true;
    this.loadError = '';
    this.api.config().subscribe({
      next: (config) => {
        this.config = config;
        this.loading = false;
        this.requestInFlight = false;
        this.lastUpdatedAt = new Date().toLocaleTimeString();
        this.changeDetector.detectChanges();
      },
      error: () => {
        this.loading = false;
        this.requestInFlight = false;
        this.loadError = 'Unable to load configuration. Click Refresh to try again.';
        this.changeDetector.detectChanges();
      }
    });
  }

  display(value: unknown): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }

  boolLabel(value: unknown): string {
    return this.boolValue(value) ? 'Enabled' : 'Disabled';
  }

  boolValue(value: unknown): boolean {
    return value === true;
  }

  listValue(value: unknown): string {
    return Array.isArray(value) ? value.join(', ') : this.display(value);
  }

  entry(key: string): unknown {
    return this.entryConfig[key];
  }

  runtime(key: string): unknown {
    return this.runtimeConfig[key];
  }
}
