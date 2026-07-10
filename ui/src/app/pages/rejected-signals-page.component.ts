import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ApiRecord, SignalFilters } from '../core/models';

@Component({
  selector: 'app-rejected-signals-page',
  standalone: true,
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatIconModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div><h1 class="title">Rejected Signals</h1><p class="sub">NO_TRADE decisions with rejection reasons.</p></div>
        <span class="spacer"></span>
        <span class="count">{{ filteredTotalElements }} total · Page {{ currentPage + 1 }}/{{ filteredTotalPages || 1 }}</span>
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
        <label class="fi"><span class="fl">Symbol</span>
          <select [(ngModel)]="selectedOptionType">
            <option value="ALL">All</option>
            <option value="CE">CE</option>
            <option value="PE">PE</option>
          </select>
        </label>
        <label class="fi"><span class="fl">Mode</span>
          <select [(ngModel)]="selectedMode">
            <option value="ALL">All</option>
            <option value="LIVE">Live</option>
            <option value="PAPER">Paper</option>
          </select>
        </label>
        <button mat-flat-button color="primary" class="apply-btn" (click)="applyFilters()">
          <mat-icon>search</mat-icon> Apply Filters
        </button>
      </div>

      @if (filtered.length === 0) {
        <div class="empty-panel"><mat-icon>inbox</mat-icon><span>No rejected signals matching filters</span></div>
      }

      @for (s of filtered; track s['_id']) {
        <div class="card">
          <div class="card-hdr">
            <span class="badge badge-no">NO_TRADE</span>
            <span class="badge badge-strat">{{ s['strategyType'] }}</span>
            <span class="badge">{{ s['underlying'] }}</span>
            @if (s['optionType']) { <span class="badge">{{ s['optionType'] }}</span> }
            @if (s['paperTrade'] != null) {
              <span [class]="s['paperTrade'] ? 'badge badge-paper' : 'badge badge-live'">
                {{ s['paperTrade'] ? 'PAPER' : 'LIVE' }}
              </span>
            }
            <span class="spacer"></span>
            <span class="ts">{{ s['_display_ts'] }}</span>
          </div>
          <div class="card-body">
            <div class="field-grid">
              <div class="f"><span>Spot Price</span><strong>{{ fmt(s['underlyingPrice']) }}</strong></div>
              @if (s['optionPrice']) { <div class="f"><span>Option Price</span><strong>{{ fmt(s['optionPrice']) }}</strong></div> }
              @if (s['selectedStrike']) { <div class="f"><span>Strike</span><strong>{{ s['selectedStrike'] }}</strong></div> }
              @if (s['selectedInstrumentKey']) { <div class="f"><span>Instrument</span><strong class="mono">{{ s['selectedInstrumentKey'] }}</strong></div> }
              @if (s['confidenceScore'] != null && s['confidenceScore'] !== 0) { <div class="f"><span>Confidence</span><strong>{{ s['confidenceScore'] }}</strong></div> }
              @if (s['optionOpenInterest']) { <div class="f"><span>Open Interest</span><strong>{{ s['optionOpenInterest'] }}</strong></div> }
            </div>
            @if (s['vwapConditionPassed'] != null || s['volumeSpike'] != null || s['imbalance'] != null || s['ivRank'] != null || s['fastEma'] != null || s['bollingerBandwidth'] != null) {
              <div class="sl">Indicators</div>
              <div class="field-grid">
                @if (s['vwapConditionPassed'] != null) { <div class="f"><span>VWAP</span><strong [class.pos]="s['vwapConditionPassed']" [class.neg]="!s['vwapConditionPassed']">{{ s['vwapConditionPassed'] ? 'Passed' : 'Failed' }}</strong></div> }
                @if (s['volumeSpike'] != null) { <div class="f"><span>Vol Spike</span><strong>{{ s['volumeSpike'] ? 'Yes' : 'No' }}</strong></div> }
                @if (s['imbalance'] != null) { <div class="f"><span>OI Imbalance</span><strong>{{ s['imbalance'] }}</strong></div> }
                @if (s['ivRank'] != null) { <div class="f"><span>IV Rank</span><strong>{{ s['ivRank'] }}</strong></div> }
                @if (s['fastEma'] != null) { <div class="f"><span>EMA 9</span><strong>{{ $any(s['fastEma']) | number:'1.1-1' }}</strong></div> }
                @if (s['slowEma'] != null) { <div class="f"><span>EMA 21</span><strong>{{ $any(s['slowEma']) | number:'1.1-1' }}</strong></div> }
                @if (s['bollingerBandwidth'] != null) { <div class="f"><span>BB Width</span><strong>{{ $any(s['bollingerBandwidth']) | number:'1.2-2' }}%</strong></div> }
              </div>
            }
            @if (s['executionStage']) {
              <div class="sl">Execution Outcome</div>
              <div class="field-grid">
                <div class="f"><span>Stage</span><strong [class]="execClass(s['executionStage'])">{{ s['executionStage'] }}</strong></div>
              </div>
              @if (s['executionReason']) {
                <div class="exec-reason"><mat-icon class="exec-ri">info_outline</mat-icon>{{ s['executionReason'] }}</div>
              }
            }
            <div class="reasons"><mat-icon class="ri">info_outline</mat-icon> {{ s['reasons'] ?? 'No reason recorded' }}</div>
          </div>
        </div>
      }

      @if (filteredTotalPages > 1) {
        <div class="pager">
          <button mat-stroked-button [disabled]="currentPage === 0" (click)="loadPage(currentPage - 1)"><mat-icon>chevron_left</mat-icon> Prev</button>
          <span class="pager-info">Page {{ currentPage + 1 }} of {{ filteredTotalPages }}</span>
          <button mat-stroked-button [disabled]="currentPage >= filteredTotalPages - 1" (click)="loadPage(currentPage + 1)">Next <mat-icon>chevron_right</mat-icon></button>
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
    .apply-btn { min-height: 36px; align-self: flex-end; }
    .empty-panel { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 0; color: var(--muted); font-size: 14px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
    .empty-panel mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .35; }
    .card { background: var(--panel); border: 1px solid var(--line); border-left: 3px solid rgba(255,113,106,.5); border-radius: 12px; margin-bottom: 10px; overflow: hidden; }
    .card-hdr { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--line); background: rgba(255,255,255,.015); flex-wrap: wrap; }
    .card-body { padding: 14px 16px; }
    .badge { padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; background: rgba(255,255,255,.04); border: 1px solid var(--line); color: var(--muted); }
    .badge-no { background: rgba(255,113,106,.1); border-color: rgba(255,113,106,.3); color: var(--bad); }
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
    .reasons { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: var(--muted); margin-top: 10px; padding: 10px 12px; border-radius: 6px; background: rgba(255,113,106,.03); border: 1px solid rgba(255,113,106,.1); line-height: 1.5; word-break: break-word; }
    .warn { color: var(--warn, #f2bd4b) !important; }
    .exec-reason { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: var(--muted); margin-top: 6px; padding: 8px 10px; border-radius: 6px; background: rgba(97,168,255,.03); border: 1px solid rgba(97,168,255,.1); line-height: 1.5; word-break: break-word; }
    .exec-ri { font-size: 14px; width: 14px; height: 14px; color: var(--accent); margin-top: 1px; flex-shrink: 0; }
    .ri { font-size: 16px; width: 16px; height: 16px; color: var(--bad); margin-top: 1px; flex-shrink: 0; }
    .pager { display: flex; align-items: center; justify-content: center; gap: 16px; margin-top: 20px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
    .pager-info { font-size: 13px; font-weight: 600; color: var(--muted); }
  `]
})
export class RejectedSignalsPageComponent implements OnInit {
  strategyOpts: string[] = [];
  readonly underlyingOpts = ['NIFTY', 'BANKNIFTY', 'SENSEX'];
  readonly periodOpts = [
    { value: 'TODAY', label: 'Today' },
    { value: 'YESTERDAY', label: 'Yesterday' },
    { value: 'LAST7', label: 'Last 7 days' },
    { value: 'LAST30', label: 'Last 30 days' },
    { value: 'ALL', label: 'All time' }
  ];
  selectedStrategy = 'ALL';
  selectedUnderlying = 'ALL';
  selectedOptionType = 'ALL';
  selectedMode = 'ALL';
  selectedPeriod = 'TODAY';
  filtered: ApiRecord[] = [];
  filteredTotalElements = 0;
  filteredTotalPages = 1;
  currentPage = 0;
  private readonly pageSize = 50;
  private readonly dr = inject(DestroyRef);
  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.api.getStrategyTypes().subscribe(types => {
      this.strategyOpts = types.map(t => t.type).sort();
      this.cd.detectChanges();
    });
    setTimeout(() => this.applyFilters(), 0);
  }

  applyFilters(): void {
    this.currentPage = 0;
    this.loadPage(0);
  }

  private buildFilters(): SignalFilters {
    return {
      strategyType: this.selectedStrategy !== 'ALL' ? this.selectedStrategy : undefined,
      underlying: this.selectedUnderlying !== 'ALL' ? this.selectedUnderlying : undefined,
      optionType: this.selectedOptionType !== 'ALL' ? this.selectedOptionType : undefined,
      mode: this.selectedMode !== 'ALL' ? this.selectedMode : undefined
    };
  }

  str(v: unknown): string { return String(v ?? ''); }

  execClass(stage: unknown): string {
    const s = String(stage ?? '');
    if (s === 'ORDER_FILLED' || s === 'PAPER_FILLED') return 'pos';
    if (s === 'ORDER_OPEN') return 'warn';
    return 'neg';
  }

  loadPage(page: number): void {
    const filters = this.buildFilters();
    this.api.rejectedSignalsPaged(page, this.pageSize, this.selectedPeriod, filters)
      .pipe(takeUntilDestroyed(this.dr))
      .subscribe({
        next: r => {
          this.filtered = (r.content as ApiRecord[]).map((s, i) => norm(s, page * this.pageSize + i));
          this.currentPage = r.number;
          this.filteredTotalPages = r.totalPages;
          this.filteredTotalElements = r.totalElements;
          this.cd.detectChanges();
        }
      });
  }

  fmt(v: unknown): string {
    if (v == null || v === '' || v === 0) return '-';
    const n = Number(v);
    return isNaN(n) ? '₹' + v : '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}

function norm(s: ApiRecord, idx: number): ApiRecord {
  const ts = String(s['timestamp'] ?? ''); const d = ts ? new Date(ts) : null;
  return { ...s, strategyType: s['strategyType'] ?? 'DIRECTIONAL_BUY', _display_ts: d ? istDT(d) : '-', _id: s['id'] ?? idx };
}
function istDT(d: Date): string { return d.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }); }
