import { Component, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService, StrategyDto } from '../core/api.service';

@Component({
  selector: 'app-strategies-page',
  standalone: true,
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatSlideToggleModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatTooltipModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Strategies</h1>
          <p class="page-subtitle">Configure and control all trading strategies. Option selling is disabled by default.</p>
        </div>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
      </div>

      <mat-button-toggle-group [value]="selectedUnderlying()" (change)="selectUnderlying($event.value)" class="underlying-tabs">
        @for (u of underlyings; track u) {
          <mat-button-toggle [value]="u">{{ u }}</mat-button-toggle>
        }
      </mat-button-toggle-group>

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      <!-- Spread execution warning -->
      @if (hasEnabledSpreadStrategy()) {
        <div class="spread-warning">
          <mat-icon>warning_amber</mat-icon>
          <div>
            <strong>Spread strategies place single-leg orders only.</strong>
            Bull Call Spread, Bear Put Spread, Long Straddle, Long Strangle and other multi-leg strategies
            currently execute as a single BUY order — the hedge leg is not placed.
            Risk is NOT capped. They behave like a directional buy until multi-leg execution is implemented.
          </div>
        </div>
      }

      <!-- Buying Strategies -->
      <div class="section-hdr">
        <mat-icon class="icon-buy">trending_up</mat-icon>
        <div>
          <h2>Option Buying Strategies</h2>
          <p>Risk limited to premium paid. Enabled by default.</p>
        </div>
      </div>

      <div class="strategy-grid">
        @for (s of buyingStrategies(); track s.id) {
          <div class="strategy-card" [class.card-on]="s.enabled">
            <div class="card-top">
              <div class="card-name-row">
                <span class="card-name">{{ s.displayName }}</span>
                <mat-slide-toggle [checked]="s.enabled" (change)="toggle(s, $event.checked)" color="primary"></mat-slide-toggle>
              </div>
              <p class="card-desc">{{ s.description }}</p>
              <div class="card-tags">
                <span class="tag tag-buy">BUY</span>
                @if (s.paperTrading) {
                  <span class="tag tag-paper">PAPER</span>
                }
                <span class="tag">{{ s.underlying }}</span>
                <span class="tag">{{ s.lots }} lot{{ s.lots > 1 ? 's' : '' }}</span>
                @if (signalCount(s.type) > 0) {
                  <span class="tag tag-signal">{{ signalCount(s.type) }} signals today</span>
                }
              </div>
            </div>
            <div class="card-params">
              <div class="param"><span>Stop Loss</span><strong class="c-bad">{{ s.stopLossPercent }}%</strong></div>
              <div class="param"><span>Target</span><strong class="c-ok">{{ s.targetPercent }}%</strong></div>
              <div class="param"><span>Max Hold</span><strong>{{ s.maxHoldMinutes === 0 ? 'No limit' : s.maxHoldMinutes + ' min' }}</strong></div>
              <div class="param"><span>Scan Timeframe</span><strong class="c-tf">{{ formatTimeframe(s.scanTimeframe) }}</strong></div>
              <div class="param"><span>Candle Timeframe</span><strong class="c-tf">{{ formatTimeframe(s.candleTimeframe) }}</strong></div>
              <div class="param"><span>Trend Timeframe</span><strong class="c-tf">{{ formatTimeframe(s.trendTimeframe) }}</strong></div>
              @if (!s.sellingStrategy) {
                <div class="param"><span>Min Premium (₹)</span><strong>{{ s.minCombinedPremium }}</strong></div>
                <div class="param"><span>Trailing Activation</span><strong>{{ s.trailingStopActivationPercent }}%</strong></div>
                <div class="param"><span>Trailing Gap</span><strong>{{ s.trailingGapPercent }}%</strong></div>
                <div class="param"><span>Squareoff</span><strong>{{ s.squareoffHour }}:{{ s.squareoffMinute | number:'2.0-0' }}</strong></div>
              }
              @if (s.type === 'LONG_STRADDLE' || s.type === 'LONG_STRANGLE' || s.type === 'EVENT_DRIVEN_BUY' || s.type === 'VOLATILITY_BREAKOUT' || s.type === 'GAP_AND_GO' || s.type === 'REVERSAL_BUY' || s.type === 'OI_SHIFT_TRAP') {
                <div class="param"><span>Max IV Rank</span><strong>{{ s.maxIvRankForBuying }}</strong></div>
              }
              @if (s.type === 'LONG_STRANGLE') {
                <div class="param"><span>OTM Strikes</span><strong>{{ s.otmStrikes }}</strong></div>
              }
              @if (s.type === 'ITM_CONVICTION') {
                <div class="param"><span>ITM Depth</span><strong>{{ s.itmDepth }} strike{{ s.itmDepth > 1 ? 's' : '' }}</strong></div>
                <div class="param"><span>Min Move</span><strong>₹{{ s.minimumMove }}</strong></div>
                <div class="param"><span>Min Strength Gap</span><strong>{{ s.minimumStrengthGap }}</strong></div>
                <div class="param"><span>Min Volume</span><strong>{{ s.minimumVolume }}</strong></div>
              }
            </div>
            <div class="card-edit-row">
              <button mat-stroked-button class="edit-btn" (click)="openEdit(s)">
                <mat-icon>tune</mat-icon> Edit Parameters
              </button>
            </div>
          </div>
        }
      </div>

      <!-- Selling Strategies -->
      <div class="section-hdr" style="margin-top:36px">
        <mat-icon class="icon-sell">warning</mat-icon>
        <div>
          <h2>Option Selling Strategies</h2>
          <p>Unlimited downside risk. Disabled by default — enable only if you understand the risks.</p>
        </div>
      </div>

      <div class="sell-warning">
        <mat-icon>info_outline</mat-icon>
        Option selling strategies carry unlimited risk. They are disabled by default.
        Enable only after setting appropriate stop-loss levels and ensuring sufficient margin in your account.
      </div>

      <div class="strategy-grid">
        @for (s of sellingStrategies(); track s.id) {
          <div class="strategy-card card-sell" [class.card-on-sell]="s.enabled">
            <div class="card-top">
              <div class="card-name-row">
                <span class="card-name">{{ s.displayName }}</span>
                <mat-slide-toggle [checked]="s.enabled" (change)="toggle(s, $event.checked)" color="warn"></mat-slide-toggle>
              </div>
              <p class="card-desc">{{ s.description }}</p>
              <div class="card-tags">
                <span class="tag tag-sell">SELL</span>
                <span class="tag">{{ s.underlying }}</span>
                <span class="tag">{{ s.lots }} lot{{ s.lots > 1 ? 's' : '' }}</span>
                @if (signalCount(s.type) > 0) {
                  <span class="tag tag-signal">{{ signalCount(s.type) }} signals today</span>
                }
              </div>
            </div>
            <div class="card-params">
              <div class="param"><span>Stop Loss</span><strong class="c-bad">{{ s.stopLossPercent }}%</strong></div>
              <div class="param"><span>Target</span><strong class="c-ok">{{ s.targetPercent }}%</strong></div>
              @if (s.type !== 'BUTTERFLY' && s.type !== 'CALENDAR_SPREAD' && s.type !== 'BULL_CALL_SPREAD' && s.type !== 'BEAR_PUT_SPREAD') {
                <div class="param"><span>OTM Strikes</span><strong>{{ s.otmStrikes }}</strong></div>
              }
              @if (s.type === 'BULL_CALL_SPREAD' || s.type === 'BEAR_PUT_SPREAD') {
                <div class="param"><span>Spread Strikes</span><strong>{{ s.spreadStrikes }}</strong></div>
              }
              @if (s.type === 'SHORT_STRADDLE' || s.type === 'SHORT_STRANGLE') {
                <div class="param"><span>Min Premium</span><strong>₹{{ s.minCombinedPremium }}</strong></div>
              }
            </div>
            <div class="card-edit-row">
              <button mat-stroked-button class="edit-btn" (click)="openEdit(s)">
                <mat-icon>tune</mat-icon> Edit Parameters
              </button>
            </div>
          </div>
        }
      </div>

      <!-- Edit Slide Panel -->
      @if (editing()) {
        <div class="overlay" (click)="closeEdit()"></div>
        <div class="edit-panel">
          <div class="edit-hdr">
            <div>
              <h3>{{ editing()!.displayName }}</h3>
              <p class="edit-sub">{{ editing()!.description }}</p>
            </div>
            <button mat-icon-button (click)="closeEdit()"><mat-icon>close</mat-icon></button>
          </div>
          <div class="edit-body">
            <div class="underlying-display">
              <span class="underlying-label">Underlying</span>
              <span class="underlying-value">{{ editing()!.underlying }}</span>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>Lots</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="editForm.lots">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Stop Loss %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="editForm.stopLossPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Target %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="editForm.targetPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Hold Minutes (0 = no limit)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="editForm.maxHoldMinutes">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Scan Timeframe</mat-label>
              <mat-select [(ngModel)]="editForm.scanTimeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
              <mat-hint>Candle close event that triggers this strategy</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Candle Timeframe</mat-label>
              <mat-select [(ngModel)]="editForm.candleTimeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
              <mat-hint>Resolution for underlying/option candle data</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trend Timeframe</mat-label>
              <mat-select [(ngModel)]="editForm.trendTimeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
              <mat-hint>Resolution for trend EMA calculation</mat-hint>
            </mat-form-field>
            <div class="paper-toggle">
              <mat-slide-toggle [(ngModel)]="editForm.paperTrading" color="accent">
                Paper Trading Mode
              </mat-slide-toggle>
              <p class="paper-hint">When enabled, signals are tracked with full P&L but no real broker orders are placed.</p>
            </div>
            @if (editing()!.type === 'ITM_CONVICTION') {
              <mat-form-field appearance="outline">
                <mat-label>ITM Depth (strikes)</mat-label>
                <input matInput type="number" min="1" max="5" [(ngModel)]="editForm.itmDepth">
                <mat-hint>How many strikes ITM to compare against ATM</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Minimum Move (₹)</mat-label>
                <input matInput type="number" min="0" step="1" [(ngModel)]="editForm.minimumMove">
                <mat-hint>Minimum underlying price change to confirm direction</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Minimum Strength Gap</mat-label>
                <input matInput type="number" min="0" step="0.5" [(ngModel)]="editForm.minimumStrengthGap">
                <mat-hint>Minimum ATP-LTP gap between ITM and ATM</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Minimum Volume</mat-label>
                <input matInput type="number" min="0" [(ngModel)]="editForm.minimumVolume">
                <mat-hint>Minimum volume on ITM option to confirm conviction</mat-hint>
              </mat-form-field>
            }
            @if (!editing()!.sellingStrategy) {
              <mat-form-field appearance="outline">
                <mat-label>Max IV Rank for Buying (0–100)</mat-label>
                <input matInput type="number" min="0" max="100" [(ngModel)]="editForm.maxIvRankForBuying">
                <mat-hint>Only enter when IV rank is below this value</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Min Combined Premium (₹)</mat-label>
                <input matInput type="number" min="0" [(ngModel)]="editForm.minCombinedPremium">
                <mat-hint>Skip entry if option premium is below this floor</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Trailing Stop Activation %</mat-label>
                <input matInput type="number" min="0" step="1" [(ngModel)]="editForm.trailingStopActivationPercent">
                <mat-hint>Profit % at which trailing stop activates</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Trailing Gap %</mat-label>
                <input matInput type="number" min="0" step="1" [(ngModel)]="editForm.trailingGapPercent">
                <mat-hint>Distance kept from peak to trailing stop level</mat-hint>
              </mat-form-field>
            }
            @if (editing()!.type === 'BULL_CALL_SPREAD' || editing()!.type === 'BEAR_PUT_SPREAD') {
              <mat-form-field appearance="outline">
                <mat-label>Spread Strikes (between buy and sell leg)</mat-label>
                <input matInput type="number" min="1" [(ngModel)]="editForm.spreadStrikes">
              </mat-form-field>
            }
            @if (editing()!.type === 'LONG_STRANGLE' || editing()!.type === 'SHORT_STRANGLE' || editing()!.type === 'IRON_CONDOR') {
              <mat-form-field appearance="outline">
                <mat-label>OTM Strikes from ATM</mat-label>
                <input matInput type="number" min="1" [(ngModel)]="editForm.otmStrikes">
              </mat-form-field>
            }
            @if (editing()!.sellingStrategy) {
              <mat-form-field appearance="outline">
                <mat-label>Min Combined Premium (₹)</mat-label>
                <input matInput type="number" min="0" [(ngModel)]="editForm.minCombinedPremium">
                <mat-hint>Don't enter if combined premium is below this</mat-hint>
              </mat-form-field>
            }
            <div class="squareoff-row">
              <mat-form-field appearance="outline" class="squareoff-field">
                <mat-label>Squareoff Hour</mat-label>
                <input matInput type="number" min="9" max="15" [(ngModel)]="editForm.squareoffHour">
              </mat-form-field>
              <mat-form-field appearance="outline" class="squareoff-field">
                <mat-label>Squareoff Minute</mat-label>
                <mat-select [(ngModel)]="editForm.squareoffMinute">
                  <mat-option [value]="0">:00</mat-option>
                  <mat-option [value]="15">:15</mat-option>
                  <mat-option [value]="30">:30</mat-option>
                  <mat-option [value]="45">:45</mat-option>
                </mat-select>
              </mat-form-field>
            </div>
            <div class="edit-actions">
              <button mat-flat-button color="primary" (click)="saveEdit()">
                <mat-icon>save</mat-icon> Save
              </button>
              <button mat-stroked-button (click)="closeEdit()">Cancel</button>
            </div>
          </div>
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

    .section-hdr { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .section-hdr h2 { font-size: 15px; font-weight: 700; color: var(--ink); margin: 0 0 2px; }
    .section-hdr p { color: var(--muted); font-size: 12px; margin: 0; }
    .icon-buy { color: var(--ok); font-size: 22px; width: 22px; height: 22px; }
    .icon-sell { color: var(--warn); font-size: 22px; width: 22px; height: 22px; }

    .sell-warning {
      display: flex; align-items: center; gap: 10px;
      background: rgba(242,189,75,.07); border: 1px solid rgba(242,189,75,.25);
      border-radius: 8px; padding: 12px 16px; color: var(--warn);
      font-size: 13px; margin-bottom: 16px; line-height: 1.4;
    }

    .spread-warning {
      display: flex; align-items: flex-start; gap: 10px;
      background: rgba(255,113,106,.07); border: 1px solid rgba(255,113,106,.3);
      border-radius: 8px; padding: 12px 16px; color: var(--bad);
      font-size: 13px; margin-bottom: 20px; line-height: 1.5;
    }
    .spread-warning mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; margin-top: 1px; }

    .strategy-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 12px; }

    .strategy-card {
      background: var(--panel); border: 1px solid var(--line);
      border-radius: 12px; overflow: hidden; transition: border-color 200ms, box-shadow 200ms;
    }
    .card-on { border-color: rgba(97,168,255,.4); box-shadow: 0 4px 20px rgba(0,0,0,.2); }
    .card-sell { border-color: rgba(255,113,106,.15); }
    .card-on-sell { border-color: rgba(242,189,75,.4); box-shadow: 0 4px 20px rgba(0,0,0,.2); }

    .card-top { padding: 16px; }
    .card-name-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
    .card-name { font-size: 14px; font-weight: 700; color: var(--ink); }
    .card-desc { color: var(--muted); font-size: 12px; margin: 0 0 10px; line-height: 1.4; }

    .card-tags { display: flex; gap: 6px; flex-wrap: wrap; }
    .tag { padding: 2px 8px; border-radius: 20px; font-size: 11px; font-weight: 600; background: var(--panel-soft); color: var(--muted); border: 1px solid var(--line); }
    .tag-buy { background: var(--ok-bg); color: var(--ok); border-color: rgba(69,209,140,.3); }
    .tag-sell { background: var(--warn-bg); color: var(--warn); border-color: rgba(242,189,75,.3); }
    .tag-signal { background: rgba(97,168,255,.12); color: var(--accent); border-color: rgba(97,168,255,.3); }
    .tag-paper { background: rgba(168,130,255,.12); color: #a882ff; border-color: rgba(168,130,255,.3); }

    .card-params { padding: 12px 16px 16px; border-top: 1px solid var(--line); background: rgba(0,0,0,.1); }
    .param { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; font-size: 12px; }
    .param span { color: var(--muted); }
    .param strong { color: var(--text); }
    .c-ok { color: var(--ok) !important; }
    .c-bad { color: var(--bad) !important; }
    .c-tf { color: var(--accent) !important; }
    .card-edit-row { padding: 8px 16px 12px; }
    .edit-btn { width: 100%; font-size: 12px; }

    .overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 100; }
    .edit-panel {
      position: fixed; right: 0; top: 0; bottom: 0; width: min(400px, 100vw);
      background: var(--panel); border-left: 1px solid var(--line);
      z-index: 101; overflow-y: auto; box-shadow: -8px 0 32px rgba(0,0,0,.4);
    }
    .edit-hdr {
      display: flex; align-items: flex-start; justify-content: space-between;
      padding: 20px 20px 16px; border-bottom: 1px solid var(--line);
      position: sticky; top: 0; background: var(--panel); z-index: 1;
    }
    .edit-hdr h3 { margin: 0 0 4px; font-size: 16px; color: var(--ink); }
    .edit-sub { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.4; max-width: 280px; }
    .edit-body { padding: 20px; display: flex; flex-direction: column; gap: 12px; }
    .edit-actions { display: flex; gap: 10px; margin-top: 8px; }
    .edit-actions button:first-child { flex: 1; }
    .paper-toggle { padding: 8px 0; }
    .paper-hint { color: var(--muted); font-size: 11px; margin: 4px 0 0; line-height: 1.4; }
    .squareoff-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .squareoff-field { width: 100%; }
    .underlying-tabs { margin-bottom: 20px; }
    .underlying-display { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border: 1px solid var(--line); border-radius: 4px; background: rgba(0,0,0,.15); }
    .underlying-label { font-size: 12px; color: var(--muted); }
    .underlying-value { font-size: 13px; font-weight: 700; color: var(--accent); }
  `]
})
export class StrategiesPageComponent implements OnInit {
  strategies = signal<StrategyDto[]>([]);
  signalCounts = signal<Record<string, number>>({});
  editing = signal<StrategyDto | null>(null);
  editForm: Partial<StrategyDto> = {};
  msg = signal('');
  error = signal('');
  selectedUnderlying = signal<string>('NIFTY');
  readonly underlyings = ['NIFTY', 'BANKNIFTY', 'SENSEX', 'FINNIFTY', 'MIDCPNIFTY'];

  constructor(private api: ApiService) {}

  ngOnInit() { this.load(); }

  selectUnderlying(u: string) { this.selectedUnderlying.set(u); this.load(); }

  load() {
    this.msg.set(''); this.error.set('');
    this.api.getStrategiesByUnderlying(this.selectedUnderlying()).subscribe({
      next: s => this.strategies.set(s),
      error: () => this.error.set('Failed to load strategies')
    });
    this.api.signalSummary().subscribe({
      next: summary => {
        const counts: Record<string, number> = {};
        summary.forEach(s => counts[s.strategyType] = s.count);
        this.signalCounts.set(counts);
      }
    });
  }

  readonly SPREAD_TYPES = new Set(['BULL_CALL_SPREAD','BEAR_PUT_SPREAD','LONG_STRADDLE','LONG_STRANGLE','SHORT_STRADDLE','SHORT_STRANGLE','IRON_CONDOR','BUTTERFLY','CALENDAR_SPREAD']);

  hasEnabledSpreadStrategy(): boolean {
    return this.strategies().some(s => s.enabled && this.SPREAD_TYPES.has(s.type));
  }

  signalCount(type: string): number { return this.signalCounts()[type] ?? 0; }

  buyingStrategies() { return this.strategies().filter(s => !s.sellingStrategy); }
  sellingStrategies() { return this.strategies().filter(s => s.sellingStrategy); }

  formatTimeframe(tf: string): string {
    switch (tf) {
      case 'ONE_MINUTE': return '1 min';
      case 'FIVE_MINUTE': return '5 min';
      case 'FIFTEEN_MINUTE': return '15 min';
      default: return tf ?? '15 min';
    }
  }

  toggle(s: StrategyDto, enabled: boolean) {
    this.msg.set(''); this.error.set('');
    const call = enabled ? this.api.enableStrategy(s.type, s.underlying) : this.api.disableStrategy(s.type, s.underlying);
    call.subscribe({
      next: (r: any) => { this.msg.set(r.message); this.load(); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Failed to update strategy')
    });
  }

  openEdit(s: StrategyDto) { this.editing.set(s); this.editForm = { ...s }; }
  closeEdit() { this.editing.set(null); }

  saveEdit() {
    const s = this.editing();
    if (!s) return;
    this.api.updateStrategy(s.type, s.underlying, this.editForm).subscribe({
      next: (r: any) => { this.msg.set(r.message); this.closeEdit(); this.load(); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Save failed')
    });
  }
}
