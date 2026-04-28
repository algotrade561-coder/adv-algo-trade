import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ApiRecord, SignalFilters } from '../core/models';
import { catchError, of } from 'rxjs';

@Component({
  selector: 'app-entry-signals-page',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatIconModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div><h1 class="title">Entry Signals</h1><p class="sub">BUY_CE / BUY_PE signals that passed evaluation.</p></div>
        <span class="spacer"></span>
        <span class="count">{{ filteredTotalElements }} total · Page {{ currentPage + 1 }}/{{ filteredTotalPages || 1 }}</span>
        <button mat-stroked-button (click)="funnelVisible = !funnelVisible">
          <mat-icon>filter_alt</mat-icon> {{ funnelVisible ? 'Hide' : 'Filter Analysis' }}
        </button>
      </div>

      <div class="filter-bar">
        <label class="fi"><span class="fl">Date</span>
          <select [(ngModel)]="selectedPeriod">
            @for (p of periodOpts; track p.value) { <option [value]="p.value">{{ p.label }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Index</span>
          <select [(ngModel)]="selectedUnderlying">
            <option value="ALL">All</option>
            @for (u of underlyingOpts; track u) { <option [value]="u">{{ u }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Strategy</span>
          <select [(ngModel)]="selectedStrategy">
            <option value="ALL">All</option>
            @for (s of strategyOpts; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Mode</span>
          <select [(ngModel)]="selectedMode">
            <option value="ALL">All</option>
            <option value="LIVE">Live</option>
            <option value="PAPER">Paper</option>
          </select>
        </label>
        <div class="fi"><span class="fl">Symbol</span>
          <div class="cb-row">
            @for (st of signalOpts; track st) {
              <mat-checkbox [checked]="selTypes.has(st)" (change)="toggle(st, $event.checked)">{{ st }}</mat-checkbox>
            }
          </div>
        </div>
        <button mat-flat-button color="primary" class="apply-btn" (click)="applyFilters()">
          <mat-icon>search</mat-icon> Apply Filters
        </button>
      </div>

      <!-- Filter Funnel Panel -->
      @if (funnelVisible) {
        <div class="funnel-panel">
          <div class="funnel-title">
            <mat-icon>filter_alt</mat-icon>
            Filter Funnel — Why NO_TRADE for {{ selectedPeriod }}?
            @if (filterFunnel.length === 0) { <span class="funnel-empty">(no NO_TRADE signals with filter data)</span> }
          </div>
          @if (filterFunnel.length > 0) {
            <div class="funnel-rows">
              @for (item of filterFunnel; track item.filter) {
                <div class="funnel-row">
                  <span class="funnel-label" [title]="item.filter">{{ item.filter }}</span>
                  <div class="funnel-bar-wrap">
                    <div class="funnel-bar" [style.width.%]="funnelBarPct(item.count)"></div>
                  </div>
                  <span class="funnel-count">{{ item.count }}</span>
                </div>
              }
            </div>
          }
        </div>
      }

      @if (filtered.length === 0) {
        <div class="empty-panel"><mat-icon>inbox</mat-icon><span>No entry signals matching filters</span></div>
      }

      @for (s of filtered; track s['_id']) {
        <div class="card" [class.card-ce]="s['signalType'] === 'BUY_CE'" [class.card-pe]="s['signalType'] === 'BUY_PE'">
          <div class="card-hdr">
            <span class="badge badge-signal">{{ s['signalType'] }}</span>
            <span class="badge badge-strat">{{ s['strategyType'] }}</span>
            <span class="badge">{{ s['underlying'] }}</span>
            @if (s['optionType']) { <span class="badge">{{ s['optionType'] }}</span> }
            @if (s['ivRankSource']) {
              <span [class]="s['ivRankSource'] === 'TRACKER' ? 'badge badge-tracker' : 'badge badge-neutral'">
                IV {{ s['ivRankSource'] }}
              </span>
            }
            @if (s['paperTrade'] != null) {
              <span [class]="s['paperTrade'] ? 'badge badge-paper' : 'badge badge-live'">
                {{ s['paperTrade'] ? 'PAPER' : 'LIVE' }}
              </span>
            }
            <span class="spacer"></span>
            <span class="ts">{{ s['_display_ts'] }}</span>
            <button mat-icon-button class="audit-btn" (click)="openAudit(s)" title="Full signal audit">
              <mat-icon>open_in_new</mat-icon>
            </button>
          </div>
          <div class="card-body">
            <div class="field-grid">
              <div class="f"><span>Spot Price</span><strong>₹{{ s['underlyingPrice'] ?? '-' }}</strong></div>
              <div class="f"><span>Option Price</span><strong>{{ fmt(s['optionPrice']) }}</strong></div>
              <div class="f"><span>Strike</span><strong>{{ s['selectedStrike'] ?? '-' }}</strong></div>
              <div class="f"><span>Instrument</span><strong class="mono">{{ s['selectedInstrumentKey'] ?? '-' }}</strong></div>
              <div class="f"><span>Confidence</span><strong>{{ s['confidenceScore'] ?? '-' }}</strong></div>
              <div class="f"><span>Lot Size</span><strong>{{ s['lotSize'] ?? '-' }}</strong></div>
              <div class="f"><span>Lot Price</span><strong>{{ fmt(s['lotPrice']) }}</strong></div>
              <div class="f"><span>Open Interest</span><strong>{{ s['optionOpenInterest'] ?? '-' }}</strong></div>
            </div>
            @if (s['sellLegInstrumentKey'] || s['netPremium'] || s['spreadStrikes']) {
              <div class="sl">Spread</div>
              <div class="field-grid">
                <div class="f"><span>Sell Leg</span><strong class="mono">{{ s['sellLegInstrumentKey'] ?? '-' }}</strong></div>
                <div class="f"><span>Sell Strike</span><strong>{{ s['sellLegStrike'] ?? '-' }}</strong></div>
                <div class="f"><span>Net Premium</span><strong>{{ fmt(s['netPremium']) }}</strong></div>
                <div class="f"><span>Width</span><strong>{{ s['spreadStrikes'] ?? '-' }}</strong></div>
              </div>
            }
            @if (s['ivRank'] != null || s['bollingerBandwidth'] != null || s['fastEma'] != null || s['vwapConditionPassed'] != null) {
              <div class="sl">Indicators</div>
              <div class="field-grid">
                @if (s['ivRank'] != null) { <div class="f"><span>IV Rank</span><strong>{{ s['ivRank'] }}</strong></div> }
                @if (s['bollingerBandwidth'] != null) { <div class="f"><span>BB Width</span><strong>{{ s['bollingerBandwidth'] }}</strong></div> }
                @if (s['fastEma'] != null) { <div class="f"><span>Fast EMA</span><strong>{{ s['fastEma'] }}</strong></div> }
                @if (s['slowEma'] != null) { <div class="f"><span>Slow EMA</span><strong>{{ s['slowEma'] }}</strong></div> }
                @if (s['vwapConditionPassed'] != null) { <div class="f"><span>VWAP</span><strong [class.pos]="s['vwapConditionPassed']" [class.neg]="!s['vwapConditionPassed']">{{ s['vwapConditionPassed'] ? 'Passed' : 'Failed' }}</strong></div> }
                @if (s['volumeSpike'] != null) { <div class="f"><span>Vol Spike</span><strong [class.pos]="s['volumeSpike']">{{ s['volumeSpike'] ? 'Yes' : 'No' }}</strong></div> }
                @if (s['imbalance'] != null) { <div class="f"><span>OI Imbalance</span><strong>{{ s['imbalance'] }}</strong></div> }
              </div>
            }
            <div class="sl">Execution Outcome</div>
            <div class="field-grid">
              <div class="f"><span>Stage</span><strong [class]="execClass(s['executionStage'])">{{ s['executionStage'] || '—' }}</strong></div>
            </div>
            @if (s['executionReason']) {
              <div class="exec-reason"><mat-icon class="exec-ri">info_outline</mat-icon>{{ s['executionReason'] }}</div>
            }
            @if (s['reasons']) { <div class="reasons">{{ s['reasons'] }}</div> }
          </div>
        </div>
      }

      <!-- Pagination -->
      @if (filteredTotalPages > 1) {
        <div class="pager">
          <button mat-stroked-button [disabled]="currentPage === 0" (click)="loadPage(currentPage - 1)"><mat-icon>chevron_left</mat-icon> Prev</button>
          <span class="pager-info">Page {{ currentPage + 1 }} of {{ filteredTotalPages }}</span>
          <button mat-stroked-button [disabled]="currentPage >= filteredTotalPages - 1" (click)="loadPage(currentPage + 1)">Next <mat-icon>chevron_right</mat-icon></button>
        </div>
      }
    </section>

    <!-- Signal Audit Drawer overlay -->
    @if (showDrawer && selectedSignal) {
      <div class="drawer-overlay" (click)="closeDrawer()"></div>
      <aside class="drawer">
        <div class="drawer-hdr">
          <div class="drawer-title">
            <mat-icon>receipt_long</mat-icon>
            Signal Audit
            <span class="badge badge-signal" style="margin-left:8px">{{ selectedSignal['signalType'] }}</span>
            <span class="badge badge-strat" style="margin-left:4px">{{ selectedSignal['strategyType'] }}</span>
          </div>
          <button mat-icon-button (click)="closeDrawer()"><mat-icon>close</mat-icon></button>
        </div>
        <div class="drawer-body">

          <div class="ds">Core</div>
          <div class="dfield-grid">
            <div class="df"><span>Underlying</span><strong>{{ selectedSignal['underlying'] ?? '-' }}</strong></div>
            <div class="df"><span>Time (IST)</span><strong>{{ selectedSignal['_display_ts'] ?? '-' }}</strong></div>
            <div class="df"><span>Confidence</span><strong>{{ selectedSignal['confidenceScore'] ?? '-' }}</strong></div>
            <div class="df"><span>Option Type</span><strong>{{ selectedSignal['optionType'] ?? '-' }}</strong></div>
          </div>

          <div class="ds">Prices &amp; Option</div>
          <div class="dfield-grid">
            <div class="df"><span>Spot ₹</span><strong>{{ selectedSignal['underlyingPrice'] ?? '-' }}</strong></div>
            <div class="df"><span>Option ₹</span><strong>{{ fmt(selectedSignal['optionPrice']) }}</strong></div>
            <div class="df"><span>Strike</span><strong>{{ selectedSignal['selectedStrike'] ?? '-' }}</strong></div>
            <div class="df"><span>Bid ₹</span><strong>{{ fmt(selectedSignal['optionBid']) }}</strong></div>
            <div class="df"><span>Ask ₹</span><strong>{{ fmt(selectedSignal['optionAsk']) }}</strong></div>
            <div class="df"><span>ATP ₹</span><strong>{{ fmt(selectedSignal['optionAtp']) }}</strong></div>
            <div class="df"><span>Spread ₹</span><strong [class.warn]="spreadVal(selectedSignal) > 3" [class.neg]="spreadVal(selectedSignal) > 6">{{ spreadVal(selectedSignal) > 0 ? spreadVal(selectedSignal).toFixed(2) : '-' }}</strong></div>
            <div class="df"><span>Open Interest</span><strong>{{ selectedSignal['optionOpenInterest'] ?? '-' }}</strong></div>
          </div>

          @if (selectedSignal['ivRank'] != null || selectedSignal['bollingerBandwidth'] != null || selectedSignal['fastEma'] != null) {
            <div class="ds">Indicators</div>
            <div class="dfield-grid">
              @if (selectedSignal['ivRank'] != null) {
                <div class="df"><span>IV Rank</span>
                  <strong>{{ selectedSignal['ivRank'] }}
                    @if (selectedSignal['ivRankSource']) {
                      <span [class]="selectedSignal['ivRankSource'] === 'TRACKER' ? 'badge badge-tracker' : 'badge badge-neutral'" style="margin-left:4px;font-size:9px">{{ selectedSignal['ivRankSource'] }}</span>
                    }
                  </strong>
                </div>
              }
              @if (selectedSignal['bbUpper'] != null) { <div class="df"><span>BB Upper</span><strong>{{ selectedSignal['bbUpper'] }}</strong></div> }
              @if (selectedSignal['bbLower'] != null) { <div class="df"><span>BB Lower</span><strong>{{ selectedSignal['bbLower'] }}</strong></div> }
              @if (selectedSignal['bbSqueeze'] != null) {
                <div class="df"><span>BB Squeeze</span><strong [class.pos]="selectedSignal['bbSqueeze']">{{ selectedSignal['bbSqueeze'] ? 'Yes' : 'No' }}</strong></div>
              }
              @if (selectedSignal['bollingerBandwidth'] != null) { <div class="df"><span>BB Width</span><strong>{{ selectedSignal['bollingerBandwidth'] }}</strong></div> }
              @if (selectedSignal['fastEma'] != null) { <div class="df"><span>Fast EMA</span><strong>{{ selectedSignal['fastEma'] }}</strong></div> }
              @if (selectedSignal['slowEma'] != null) { <div class="df"><span>Slow EMA</span><strong>{{ selectedSignal['slowEma'] }}</strong></div> }
              @if (selectedSignal['emaCrossType']) { <div class="df"><span>EMA Cross</span><strong>{{ selectedSignal['emaCrossType'] }}</strong></div> }
              @if (selectedSignal['emaCrossConfirmCount'] != null) { <div class="df"><span>EMA Confirms</span><strong>{{ selectedSignal['emaCrossConfirmCount'] }}</strong></div> }
              @if (selectedSignal['vwapConditionPassed'] != null) {
                <div class="df"><span>VWAP</span><strong [class.pos]="selectedSignal['vwapConditionPassed']" [class.neg]="!selectedSignal['vwapConditionPassed']">{{ selectedSignal['vwapConditionPassed'] ? 'Passed' : 'Failed' }}</strong></div>
              }
              @if (selectedSignal['volumeSpike'] != null) { <div class="df"><span>Vol Spike</span><strong [class.pos]="selectedSignal['volumeSpike']">{{ selectedSignal['volumeSpike'] ? 'Yes' : 'No' }}</strong></div> }
              @if (selectedSignal['imbalance'] != null) { <div class="df"><span>OI Imbalance</span><strong>{{ selectedSignal['imbalance'] }}</strong></div> }
            </div>
          }

          <div class="ds">Execution</div>
          <div class="dfield-grid">
            <div class="df"><span>Stage</span><strong [class]="execClass(selectedSignal['executionStage'])">{{ selectedSignal['executionStage'] || '—' }}</strong></div>
            @if (selectedSignal['firstFailedFilter']) {
              <div class="df"><span>First Filter Failed</span><strong class="warn">{{ selectedSignal['firstFailedFilter'] }}</strong></div>
            }
          </div>
          @if (selectedSignal['executionReason']) {
            <div class="exec-reason"><mat-icon class="exec-ri">info_outline</mat-icon>{{ selectedSignal['executionReason'] }}</div>
          }

          @if (selectedSignal['reasons']) {
            <div class="ds">Strategy Reasons</div>
            <div class="reasons">{{ selectedSignal['reasons'] }}</div>
          }

          @if (selectedSignal['configSnapshot']) {
            <div class="ds">Config Snapshot</div>
            <pre class="config-snap">{{ fmtConfig(selectedSignal['configSnapshot']) }}</pre>
          }

        </div>
      </aside>
    }
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .top-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
    .spacer { flex: 1; }
    .title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .sub { color: var(--muted); font-size: 13px; margin: 0; }
    .count { font-size: 12px; font-weight: 600; color: var(--muted); }
    .filter-bar { display: flex; align-items: flex-end; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; padding: 12px 16px; border: 1px solid var(--line); border-radius: 10px; background: rgba(255,255,255,.025); }
    .fi { display: flex; flex-direction: column; gap: 4px; }
    .fl { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .fi select { min-height: 36px; min-width: 150px; border: 1px solid var(--line); border-radius: 8px; background: rgba(14,20,28,.88); color: var(--text); padding: 0 10px; font-size: 13px; }
    .cb-row { display: flex; gap: 10px; }
    .apply-btn { min-height: 36px; align-self: flex-end; }

    /* Filter Funnel */
    .funnel-panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
    .funnel-title { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 700; color: var(--ink); margin-bottom: 10px; }
    .funnel-title mat-icon { font-size: 14px; width: 14px; height: 14px; color: var(--accent); }
    .funnel-empty { color: var(--muted); font-weight: 400; font-size: 11px; }
    .funnel-rows { display: flex; flex-direction: column; gap: 6px; }
    .funnel-row { display: flex; align-items: center; gap: 10px; }
    .funnel-label { font-size: 11px; font-weight: 600; color: var(--muted); min-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .funnel-bar-wrap { flex: 1; height: 12px; background: rgba(255,255,255,.05); border-radius: 6px; overflow: hidden; }
    .funnel-bar { height: 100%; background: rgba(97,168,255,.5); border-radius: 6px; transition: width 500ms ease; }
    .funnel-count { font-size: 12px; font-weight: 700; color: var(--ink); min-width: 30px; text-align: right; }

    .empty-panel { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 0; color: var(--muted); font-size: 14px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
    .empty-panel mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .35; }
    .card { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; margin-bottom: 10px; overflow: hidden; }
    .card-ce { border-left: 3px solid var(--ok); }
    .card-pe { border-left: 3px solid var(--bad); }
    .card-hdr { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--line); background: rgba(255,255,255,.015); flex-wrap: wrap; }
    .card-body { padding: 14px 16px; }
    .badge { padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; background: rgba(255,255,255,.04); border: 1px solid var(--line); color: var(--muted); }
    .badge-signal { background: rgba(69,209,140,.1); border-color: rgba(69,209,140,.3); color: var(--ok); }
    .badge-strat { background: rgba(97,168,255,.1); border-color: rgba(97,168,255,.3); color: var(--accent); }
    .badge-live { background: rgba(69,209,140,.1); border-color: rgba(69,209,140,.3); color: var(--ok); }
    .badge-paper { background: rgba(242,189,75,.1); border-color: rgba(242,189,75,.3); color: var(--warn, #f2bd4b); }
    .badge-tracker { background: rgba(69,209,140,.1); border-color: rgba(69,209,140,.3); color: var(--ok); }
    .badge-neutral { background: rgba(242,189,75,.1); border-color: rgba(242,189,75,.3); color: var(--warn, #f2bd4b); }
    .audit-btn { width: 28px; height: 28px; line-height: 28px; }
    .audit-btn mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .ts { font-size: 12px; color: var(--muted); }
    .field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 6px; }
    .f { padding: 7px 10px; border-radius: 6px; background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); }
    .f span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .f strong { display: block; font-size: 13px; color: var(--ink); margin-top: 2px; word-break: break-all; }
    .mono { font-family: monospace; font-size: 11px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }
    .warn { color: var(--warn, #f2bd4b) !important; }
    .sl { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--accent); margin: 10px 0 4px; }
    .reasons { font-size: 12px; color: var(--muted); margin-top: 10px; padding: 10px 12px; border-radius: 6px; background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); line-height: 1.5; word-break: break-word; }
    .exec-reason { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: var(--muted); margin-top: 6px; padding: 8px 10px; border-radius: 6px; background: rgba(97,168,255,.03); border: 1px solid rgba(97,168,255,.1); line-height: 1.5; word-break: break-word; }
    .exec-ri { font-size: 14px; width: 14px; height: 14px; color: var(--accent); margin-top: 1px; flex-shrink: 0; }
    .pager { display: flex; align-items: center; justify-content: center; gap: 16px; margin-top: 20px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
    .pager-info { font-size: 13px; font-weight: 600; color: var(--muted); }

    /* Drawer */
    .drawer-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 200; }
    .drawer { position: fixed; top: 0; right: 0; width: min(520px, 100vw); height: 100vh; background: var(--panel, #181b1d); border-left: 1px solid var(--line); z-index: 201; display: flex; flex-direction: column; overflow: hidden; }
    .drawer-hdr { display: flex; align-items: center; gap: 8px; padding: 14px 16px; border-bottom: 1px solid var(--line); flex-shrink: 0; }
    .drawer-title { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 700; color: var(--ink); flex: 1; flex-wrap: wrap; }
    .drawer-title mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--accent); }
    .drawer-body { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 2px; }
    .ds { font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: .07em; color: var(--accent); margin: 12px 0 6px; }
    .ds:first-child { margin-top: 0; }
    .dfield-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 6px; }
    .df { padding: 7px 10px; border-radius: 6px; background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); }
    .df span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .df strong { display: block; font-size: 12px; color: var(--ink); margin-top: 2px; word-break: break-all; }
    .config-snap { font-size: 11px; color: var(--muted); background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); border-radius: 6px; padding: 10px 12px; white-space: pre-wrap; word-break: break-word; line-height: 1.5; margin: 0; font-family: 'JetBrains Mono', monospace; }
  `]
})
export class EntrySignalsPageComponent implements OnInit {
  readonly signalOpts = ['BUY_CE', 'BUY_PE'];
  readonly strategyOpts = ['DIRECTIONAL_BUY','SCALPING','VOLATILITY_BREAKOUT','EVENT_DRIVEN_BUY','BULL_CALL_SPREAD','BEAR_PUT_SPREAD','LONG_STRADDLE','LONG_STRANGLE','SHORT_STRADDLE','SHORT_STRANGLE','IRON_CONDOR','BUTTERFLY','CALENDAR_SPREAD','DIAGONAL_SPREAD','JADE_LIZARD','SYNTHETIC_FUTURES'];
  readonly underlyingOpts = ['NIFTY', 'BANKNIFTY', 'SENSEX'];
  readonly periodOpts = [
    { value: 'TODAY', label: 'Today' },
    { value: 'YESTERDAY', label: 'Yesterday' },
    { value: 'LAST7', label: 'Last 7 days' },
    { value: 'LAST30', label: 'Last 30 days' },
    { value: 'ALL', label: 'All time' }
  ];
  readonly selTypes = new Set<string>(this.signalOpts);
  selectedStrategy = 'ALL';
  selectedUnderlying = 'ALL';
  selectedMode = 'ALL';
  selectedPeriod = 'TODAY';
  filtered: ApiRecord[] = [];
  filteredTotalElements = 0;
  filteredTotalPages = 1;
  currentPage = 0;
  filterFunnel: Array<{ filter: string; count: number }> = [];
  funnelVisible = false;
  selectedSignal: ApiRecord | null = null;
  showDrawer = false;
  private readonly pageSize = 50;
  private readonly dr = inject(DestroyRef);
  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    setTimeout(() => this.applyFilters(), 0);
  }

  applyFilters(): void {
    this.currentPage = 0;
    this.loadPage(0);
    this.loadFunnel();
  }

  private buildFilters(): SignalFilters {
    return {
      strategyType: this.selectedStrategy !== 'ALL' ? this.selectedStrategy : undefined,
      underlying: this.selectedUnderlying !== 'ALL' ? this.selectedUnderlying : undefined,
      mode: this.selectedMode !== 'ALL' ? this.selectedMode : undefined
    };
  }

  str(v: unknown): string { return String(v ?? ''); }

  loadPage(page: number): void {
    const filters = this.buildFilters();
    this.api.entrySignalsPaged(page, this.pageSize, this.selectedPeriod, filters)
      .pipe(takeUntilDestroyed(this.dr))
      .subscribe({
        next: r => {
          let items = (r.content as ApiRecord[]).map((s, i) => norm(s, page * this.pageSize + i));
          items = items.filter(s => this.selTypes.has(String(s['signalType'] ?? '')));
          this.filtered = items;
          this.currentPage = r.number;
          this.filteredTotalPages = r.totalPages;
          this.filteredTotalElements = r.totalElements;
          this.cd.detectChanges();
        }
      });
  }

  toggle(st: string, v: boolean): void { v ? this.selTypes.add(st) : this.selTypes.delete(st); }

  loadFunnel(): void {
    this.api.filterFunnel(this.selectedPeriod)
      .pipe(catchError(() => of([] as Array<{ filter: string; count: number }>)))
      .subscribe(data => { this.filterFunnel = data; this.cd.detectChanges(); });
  }

  funnelBarPct(count: number): number {
    const max = this.filterFunnel.length > 0 ? this.filterFunnel[0].count : 1;
    return max > 0 ? Math.round((count / max) * 100) : 0;
  }

  openAudit(signal: ApiRecord): void { this.selectedSignal = signal; this.showDrawer = true; }
  closeDrawer(): void { this.showDrawer = false; }

  fmt(v: unknown): string { return v != null && v !== '' ? '₹' + v : '-'; }

  spreadVal(s: ApiRecord): number {
    const ask = Number(s['optionAsk'] ?? 0);
    const bid = Number(s['optionBid'] ?? 0);
    return ask > 0 && bid > 0 ? ask - bid : 0;
  }

  fmtConfig(raw: unknown): string {
    if (!raw) return '';
    const s = String(raw);
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s.replace(/,/g, ',\n'); }
  }

  execClass(stage: unknown): string {
    const s = String(stage ?? '');
    if (!s || s === 'null' || s === 'undefined') return '';
    if (s === 'ORDER_FILLED' || s === 'PAPER_FILLED') return 'pos';
    if (s === 'ORDER_OPEN') return 'warn';
    return 'neg';
  }
}

function norm(s: ApiRecord, idx: number): ApiRecord {
  const ts = String(s['timestamp'] ?? ''); const d = ts ? new Date(ts) : null;
  return { ...s, strategyType: s['strategyType'] ?? 'DIRECTIONAL_BUY', _display_ts: d ? istDT(d) : '-', _id: s['id'] ?? idx };
}
function istDT(d: Date): string { return d.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }); }

