import { ChangeDetectorRef, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, GlobalConfigDto } from '../core/api.service';

@Component({
  selector: 'app-settings-page',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatSlideToggleModule,
    MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Settings</h1>
          <p class="page-subtitle">Global entry, exit, and risk parameters. Changes take effect immediately.</p>
        </div>
      </div>

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      @if (!loaded) {
        <div style="text-align:center;padding:40px;color:var(--muted)">Loading settings…</div>
      }

      @if (config) {
        <!-- Entry Filters -->
        <div class="section-hdr clickable" (click)="entryOpen = !entryOpen">
          <mat-icon class="icon-entry">filter_alt</mat-icon>
          <div>
            <h2>Entry Filters</h2>
            <p>Timeframes, breakout, volume, OI, RSI, and signal thresholds</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ entryOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (entryOpen) {
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Timeframe</mat-label>
              <mat-select [(ngModel)]="config.timeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trend Timeframe</mat-label>
              <mat-select [(ngModel)]="config.trendTimeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
            </mat-form-field>
            <div class="toggle-row">
              <span>VWAP Filter</span>
              <mat-slide-toggle [(ngModel)]="config.vwapFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>Trend Filter</span>
              <mat-slide-toggle [(ngModel)]="config.trendFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>Volume Spike Multiplier</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.volumeSpikeMultiplier">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Breakout Buffer %</mat-label>
              <input matInput type="number" step="0.01" [(ngModel)]="config.breakoutBufferPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Breakout Lookback</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.breakoutLookback">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Volume Lookback</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.volumeLookback">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Bullish Imbalance Threshold</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.bullishImbalanceThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Bearish Imbalance Threshold</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.bearishImbalanceThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Min Liquidity Volume</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.minLiquidityVolume">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max IV %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxIvPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Min Signal Score %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.minSignalScorePercent">
            </mat-form-field>
            <div class="toggle-row">
              <span>CE OI Support Required</span>
              <mat-slide-toggle [(ngModel)]="config.ceOiSupportRequired" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>PE OI Support Required</span>
              <mat-slide-toggle [(ngModel)]="config.peOiSupportRequired" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>CE OI Divergence Filter</span>
              <mat-slide-toggle [(ngModel)]="config.ceOiDivergenceFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>PE OI Divergence Filter</span>
              <mat-slide-toggle [(ngModel)]="config.peOiDivergenceFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>OI Divergence Multiplier</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.oiDivergenceMultiplier">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>OI Divergence Min Change</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.oiDivergenceMinChange">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>CE Breakout Confirmation Candles</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.ceBreakoutConfirmationCandles">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>PE Breakout Confirmation Candles</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.peBreakoutConfirmationCandles">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Entry Start Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.entryStartTime">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Entry Cutoff Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.entryCutoffTime">
            </mat-form-field>
            <div class="toggle-row">
              <span>Allow First Minutes Entry</span>
              <mat-slide-toggle [(ngModel)]="config.allowFirstMinutesEntry" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>No Entry First Minutes</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.noEntryFirstMinutes">
            </mat-form-field>
            <div class="toggle-row">
              <span>RSI Filter</span>
              <mat-slide-toggle [(ngModel)]="config.rsiFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>RSI Period</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.rsiPeriod">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>RSI CE Buy Threshold</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.rsiCeBuyThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>RSI PE Sell Threshold</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.rsiPeSellThreshold">
            </mat-form-field>
          </div>
        }

        <!-- Exit Rules -->
        <div class="section-hdr clickable" (click)="exitOpen = !exitOpen">
          <mat-icon class="icon-exit">exit_to_app</mat-icon>
          <div>
            <h2>Exit Rules</h2>
            <p>Stop-loss, target, trailing stop, forced exit, and hold limits</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ exitOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (exitOpen) {
          <div class="form-grid">
            <div class="toggle-row override-row">
              <span>Override Strategy Exit Config <span class="toggle-hint">When ON, these global exit values replace per-strategy SL/target/trailing/maxHold for ALL trades</span></span>
              <mat-slide-toggle [(ngModel)]="config.globalExitOverride" color="warn"></mat-slide-toggle>
            </div>
            @if (config.globalExitOverride) {
              <div class="override-banner">⚠️ Global exit override is ACTIVE — all strategies will use the values below instead of their own exit config</div>
            }
            <mat-form-field appearance="outline">
              <mat-label>Stop Loss %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.stopLossPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Target %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.targetPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trailing Stop Activation %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.trailingStopActivationPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trailing Gap %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.trailingGapPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Forced Exit Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.forcedExitTime">
            </mat-form-field>
            <div class="toggle-row">
              <span>Partial Profit Booking</span>
              <mat-slide-toggle [(ngModel)]="config.partialProfitBookingEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>VWAP Reversal Exit <span class="toggle-hint">Close profitable long positions when spot crosses back through VWAP</span></span>
              <mat-slide-toggle [(ngModel)]="config.vwapExitEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>Max Hold Minutes (0 = no limit)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.maxHoldMinutes">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Limit Order Cancel Minutes</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.limitOrderCancelMinutes">
              <mat-hint>Auto-cancel unfilled limit orders after this many minutes</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>FailSafe Squareoff Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.failSafeSquareoffTime">
              <mat-hint>Force-close ALL positions after this time (safety net)</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>IV Collapse Exit Threshold %</mat-label>
              <input matInput type="number" step="1" min="5" max="50" [(ngModel)]="config.ivCollapseExitThresholdPercent">
              <mat-hint>Exit when IV drops this % from entry</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>IV Collapse Max Profit %</mat-label>
              <input matInput type="number" step="1" min="0" max="50" [(ngModel)]="config.ivCollapseMaxProfitPercent">
              <mat-hint>Don't trigger IV collapse exit if profit is above this %</mat-hint>
            </mat-form-field>
          </div>
        }

        <!-- Risk Limits -->
        <div class="section-hdr clickable" (click)="riskOpen = !riskOpen">
          <mat-icon class="icon-risk">shield</mat-icon>
          <div>
            <h2>Risk Limits</h2>
            <p>Capital, daily loss, trade limits, cooldown, and re-entry rules</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ riskOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (riskOpen) {
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Total Capital</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.totalCapital">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Risk Per Trade %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxRiskPerTradePercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Daily Loss %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxDailyLossPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Trades Per Day</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxTradesPerDay">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Orders Per Day</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxOrdersPerDay">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Consecutive Losses</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.maxConsecutiveLosses">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Open Trades</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxOpenTrades">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Same Instrument Re-entry Min Price Move %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.sameInstrumentReentryMinPriceMovePercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Cooldown Minutes</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.cooldownMinutes">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Daily Profit Target (₹)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.dailyProfitTarget">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Lots Per Trade</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxLotsPerTrade">
              <mat-hint>Hard cap on lots per single order — safety limit</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Trades Per Hour</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.maxTradesPerHour">
              <mat-hint>Hourly trade cap (0 = disabled). Prevents overtrading in volatile sessions.</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>ML Virtual Trade Threshold</mat-label>
              <input matInput type="number" step="1" min="0" max="100" [(ngModel)]="config.mlVirtualTradeThreshold">
              <mat-hint>ML score threshold for virtual trades (independent of signal score). Lower = more virtual trades.</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Entries Per Scan (Total)</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.maxEntriesPerScan">
              <mat-hint>Total entries across all underlyings per scan cycle. Overall ceiling.</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Entries Per Scan Per Underlying</mat-label>
              <input matInput type="number" min="1" max="5" [(ngModel)]="config.maxEntriesPerScanPerUnderlying">
              <mat-hint>How many strategies can enter the same index in one scan cycle. 1 = conservative, 2+ = allow stacking.</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Pending Orders</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.maxPendingOrders">
              <mat-hint>Max simultaneous unfilled limit orders. Prevents order pile-up.</mat-hint>
            </mat-form-field>
          </div>
        }

        <!-- Action buttons -->
        <div class="actions">
          <button mat-flat-button color="primary" (click)="save()">
            <mat-icon>save</mat-icon> Save
          </button>
          <button mat-stroked-button (click)="resetDefaults()">
            <mat-icon>restart_alt</mat-icon> Reset to Defaults
          </button>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 24px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }

    .toast-ok { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--ok-bg); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .toast-warn { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--warn-bg); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    .section-hdr { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; margin-top: 24px; }
    .section-hdr h2 { font-size: 15px; font-weight: 700; color: var(--ink); margin: 0 0 2px; }
    .section-hdr p { color: var(--muted); font-size: 12px; margin: 0; }
    .clickable { cursor: pointer; user-select: none; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--line); background: rgba(255,255,255,.04); transition: background 160ms; }
    .clickable:hover { background: rgba(255,255,255,.08); }
    .icon-entry { color: var(--accent); font-size: 22px; width: 22px; height: 22px; }
    .icon-exit { color: var(--warn); font-size: 22px; width: 22px; height: 22px; }
    .icon-risk { color: var(--bad); font-size: 22px; width: 22px; height: 22px; }

    .form-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px;
      padding: 16px; margin-bottom: 16px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(0,0,0,.1);
    }

    .toggle-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 16px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(255,255,255,.04); font-size: 13px; color: var(--text);
    }
    .toggle-hint { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }
    .override-row { border: 1px solid rgba(242,189,75,.3); border-radius: 8px; padding: 12px 16px; background: rgba(242,189,75,.04); }
    .override-banner { grid-column: 1 / -1; padding: 8px 14px; border-radius: 6px; font-size: 12px; font-weight: 600; background: rgba(242,189,75,.1); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    .actions { display: flex; gap: 12px; margin-top: 24px; padding-bottom: 32px; }
    .actions button:first-child { min-width: 120px; }
  `]
})
export class SettingsPageComponent implements OnInit {
  config!: GlobalConfigDto;
  loaded = false;
  msg = signal('');
  error = signal('');
  entryOpen = true;
  exitOpen = true;
  riskOpen = true;

  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  ngOnInit() { setTimeout(() => this.load(), 0); }

  load() {
    this.msg.set(''); this.error.set('');
    this.api.getGlobalConfig().subscribe({
      next: c => { this.config = c; this.loaded = true; this.cd.detectChanges(); },
      error: () => { this.error.set('Failed to load global config'); this.cd.detectChanges(); }
    });
  }

  save() {
    this.msg.set(''); this.error.set('');
    this.api.updateGlobalConfig(this.config).subscribe({
      next: c => { this.config = c; this.msg.set('Global config saved successfully'); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Failed to save global config')
    });
  }

  resetDefaults() {
    this.msg.set(''); this.error.set('');
    this.api.resetGlobalConfig().subscribe({
      next: c => { this.config = c; this.msg.set('Global config reset to defaults'); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Failed to reset global config')
    });
  }
}
