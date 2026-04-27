import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { ApiService, StrategyDto } from '../core/api.service';
import { ApiRecord, StrategyDecision } from '../core/models';

@Component({
  selector: 'app-entry-signals-page',
  standalone: true,
  imports: [MatButtonModule, MatCheckboxModule, MatIconModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div><h1 class="title">Entry Signals</h1><p class="sub">BUY_CE / BUY_PE signals that passed evaluation.</p></div>
        <span class="spacer"></span>
        <span class="count">{{ totalElements }} total · Page {{ currentPage + 1 }}/{{ totalPages || 1 }}</span>
      </div>

      <div class="filter-bar">
        <label class="fi"><span class="fl">Date</span>
          <select [value]="selectedPeriod" (change)="onPeriodChange($any($event.target).value)">
            @for (p of periodOpts; track p.value) { <option [value]="p.value">{{ p.label }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Index</span>
          <select [value]="selectedUnderlying" (change)="selectedUnderlying = $any($event.target).value">
            <option value="ALL">All</option>
            @for (u of underlyingOpts; track u) { <option [value]="u">{{ u }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Strategy</span>
          <select [value]="selectedStrategy" (change)="selectedStrategy = $any($event.target).value">
            <option value="ALL">All</option>
            @for (s of strategyOpts; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
        <label class="fi"><span class="fl">Mode</span>
          <select [value]="selectedMode" (change)="selectedMode = $any($event.target).value">
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
      </div>

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
            @if (paperStrategyTypes.size > 0) {
              <span [class]="paperStrategyTypes.has(str(s['strategyType'])) ? 'badge badge-paper' : 'badge badge-live'">
                {{ paperStrategyTypes.has(str(s['strategyType'])) ? 'PAPER' : 'LIVE' }}
              </span>
            }
            <span class="spacer"></span>
            <span class="ts">{{ s['_display_ts'] }}</span>
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
      @if (totalPages > 1) {
        <div class="pager">
          <button mat-stroked-button [disabled]="currentPage === 0" (click)="loadPage(currentPage - 1)"><mat-icon>chevron_left</mat-icon> Prev</button>
          <span class="pager-info">Page {{ currentPage + 1 }} of {{ totalPages }}</span>
          <button mat-stroked-button [disabled]="currentPage >= totalPages - 1" (click)="loadPage(currentPage + 1)">Next <mat-icon>chevron_right</mat-icon></button>
        </div>
      }
    </section>
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
    .ts { font-size: 12px; color: var(--muted); }
    .field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 6px; }
    .f { padding: 7px 10px; border-radius: 6px; background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); }
    .f span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .f strong { display: block; font-size: 13px; color: var(--ink); margin-top: 2px; word-break: break-all; }
    .mono { font-family: monospace; font-size: 11px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }
    .sl { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--accent); margin: 10px 0 4px; }
    .reasons { font-size: 12px; color: var(--muted); margin-top: 10px; padding: 10px 12px; border-radius: 6px; background: rgba(255,255,255,.02); border: 1px solid rgba(255,255,255,.04); line-height: 1.5; word-break: break-word; }
    .warn { color: var(--warn, #f2bd4b) !important; }
    .exec-reason { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: var(--muted); margin-top: 6px; padding: 8px 10px; border-radius: 6px; background: rgba(97,168,255,.03); border: 1px solid rgba(97,168,255,.1); line-height: 1.5; word-break: break-word; }
    .exec-ri { font-size: 14px; width: 14px; height: 14px; color: var(--accent); margin-top: 1px; flex-shrink: 0; }
    .pager { display: flex; align-items: center; justify-content: center; gap: 16px; margin-top: 20px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
    .pager-info { font-size: 13px; font-weight: 600; color: var(--muted); }
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
  paperStrategyTypes = new Set<string>();
  signals: ApiRecord[] = [];
  currentPage = 0;
  totalPages = 0;
  totalElements = 0;
  private readonly pageSize = 50;
  private readonly dr = inject(DestroyRef);
  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  get filtered(): ApiRecord[] {
    return this.signals
      .filter(s => this.selTypes.has(String(s['signalType'] ?? '')))
      .filter(s => this.selectedStrategy === 'ALL' || String(s['strategyType'] ?? '') === this.selectedStrategy)
      .filter(s => this.selectedUnderlying === 'ALL' || String(s['underlying'] ?? '') === this.selectedUnderlying)
      .filter(s => {
        if (this.selectedMode === 'ALL' || this.paperStrategyTypes.size === 0) return true;
        const isPaper = this.paperStrategyTypes.has(String(s['strategyType'] ?? ''));
        return this.selectedMode === 'PAPER' ? isPaper : !isPaper;
      });
  }

  ngOnInit(): void {
    this.api.getStrategies().subscribe(strategies => {
      this.paperStrategyTypes = new Set(strategies.filter((s: StrategyDto) => s.paperTrading).map((s: StrategyDto) => s.type));
    });
    setTimeout(() => this.loadPage(0), 0);
  }

  onPeriodChange(val: string): void { this.selectedPeriod = val; this.loadPage(0); }

  str(v: unknown): string { return String(v ?? ''); }

  loadPage(page: number): void {
    this.api.entrySignalsPaged(page, this.pageSize, this.selectedPeriod).pipe(takeUntilDestroyed(this.dr)).subscribe({
      next: r => {
        this.signals = (r.content as ApiRecord[]).map((s, i) => norm(s, page * this.pageSize + i));
        this.currentPage = r.number;
        this.totalPages = r.totalPages;
        this.totalElements = r.totalElements;
        this.cd.detectChanges();
      }
    });
  }

  toggle(st: string, v: boolean): void { v ? this.selTypes.add(st) : this.selTypes.delete(st); }

  fmt(v: unknown): string { return v != null && v !== '' ? '₹' + v : '-'; }

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
