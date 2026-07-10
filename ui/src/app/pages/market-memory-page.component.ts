import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, DatePipe } from '@angular/common';
import { interval, Subscription, catchError, of } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService } from '../core/api.service';
import { ApiRecord } from '../core/models';

/**
 * Market-Memory V5 insight page (docs/MARKET-MEMORY-V5-DESIGN.md §16) — read-only views of the
 * memory engine: live per-strike states, avalanche feed, decision/audit records, episode memory
 * and the week-memory mountains/pain. Poll-based (states 5s, rest 10s); zero trading impact.
 */
@Component({
  selector: 'app-market-memory-page',
  standalone: true,
  imports: [DecimalPipe, DatePipe, MatIconModule, MatTabsModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Market Memory</h1>
          <p class="page-subtitle">V5 engine insights — states, avalanches, decisions, memory &amp; mountains.</p>
        </div>
        <select class="idx-sel" [value]="index" (change)="onIndexChange($event)">
          @for (i of indices; track i) { <option [value]="i">{{ i }}</option> }
        </select>
      </div>

      <!-- System health summary bar -->
      @if (summary) {
        <div class="summary-bar">
          <div class="sm"><span class="sm-label">Engine</span><span class="sm-val" [class.ok]="summary['enabled']">{{ summary['enabled'] ? 'LIVE ✓' : 'OFF' }}</span></div>
          <div class="sm"><span class="sm-label">Strikes</span><span class="sm-val">{{ summary['strikesWarmed'] }}/{{ summary['strikesTracked'] }} warmed</span></div>
          <div class="sm"><span class="sm-label">States</span><span class="sm-val sm-states">
            @if (stateCount('AVALANCHE') > 0) { <span class="st st-AVALANCHE">{{ stateCount('AVALANCHE') }} AVAL</span> }
            @if (stateCount('SHORT_COVER_RUN') > 0) { <span class="st st-SHORT_COVER_RUN">{{ stateCount('SHORT_COVER_RUN') }} SCR</span> }
            @if (stateCount('WRITER_PRESS') > 0) { <span class="st st-WRITER_PRESS">{{ stateCount('WRITER_PRESS') }} WP</span> }
            <span class="st st-DEAD">{{ stateCount('DEAD') }} dead</span>
            <span class="st st-NEUTRAL">{{ stateCount('NEUTRAL') }} neutral</span>
          </span></div>
          <div class="sm"><span class="sm-label">Avalanches</span><span class="sm-val">{{ summary['avalanchesToday'] }}</span></div>
          <div class="sm"><span class="sm-label">Events</span><span class="sm-val">{{ summary['eventsToday'] }}</span></div>
          <div class="sm"><span class="sm-label">Episodes</span><span class="sm-val" [class.pos]="num(summary['episodesNetPct']) > 0" [class.neg]="num(summary['episodesNetPct']) < 0">{{ summary['episodesToday'] }} ({{ num(summary['episodesNetPct']) > 0 ? '+' : '' }}{{ summary['episodesNetPct'] }}%)</span></div>
          @if (hasSuspensions()) {
            <div class="sm"><span class="sm-label">Suspended</span><span class="sm-val neg">{{ asSuspensions(summary['suspensions']) }}</span></div>
          }
          <div class="sm"><span class="sm-label">Top Pain</span><span class="sm-val">{{ summary['topWriterPain'] }}</span></div>
        </div>
      }

      <mat-tab-group animationDuration="0ms">

        <mat-tab label="Live States">
          @if (stateRows.length === 0) {
            <div class="empty">No memory yet for {{ index }} — engine warming or market closed.</div>
          } @else {
            <div class="filter-row">
              <select class="idx-sel" [value]="stateFilter" (change)="stateFilter = asStr($event)">
                <option value="">All states</option>
                <option value="AVALANCHE">AVALANCHE</option>
                <option value="SHORT_COVER_RUN">SHORT_COVER_RUN</option>
                <option value="WRITER_PRESS">WRITER_PRESS</option>
                <option value="DEAD">DEAD</option>
                <option value="NEUTRAL">NEUTRAL</option>
              </select>
              <select class="idx-sel" [value]="typeFilter" (change)="typeFilter = asStr($event)">
                <option value="">CE + PE</option>
                <option value="CE">CE only</option>
                <option value="PE">PE only</option>
              </select>
              <span class="hint">{{ filteredStateRows.length }} / {{ stateRows.length }} strikes</span>
            </div>
            <div class="table-wrap"><table class="mm-table">
              <thead><tr>
                <th class="sortable" (click)="sortStates('strike')">Strike {{ sortIcon('strike') }}</th>
                <th>Type</th>
                <th class="sortable" (click)="sortStates('state')">State {{ sortIcon('state') }}</th>
                <th>Tier</th>
                <th class="sortable" (click)="sortStates('zOi')">zOI {{ sortIcon('zOi') }}</th>
                <th class="sortable" (click)="sortStates('dOi5mPct')">dOI 5m% {{ sortIcon('dOi5mPct') }}</th>
                <th class="sortable" (click)="sortStates('dP5mPct')">dP 5m% {{ sortIcon('dP5mPct') }}</th>
                <th>volUnit%</th>
                <th class="sortable" (click)="sortStates('mountainBuild')">Mountain {{ sortIcon('mountainBuild') }}</th>
                <th class="sortable" (click)="sortStates('painPct')">Pain% {{ sortIcon('painPct') }}</th>
                <th>Flags</th>
              </tr></thead>
              <tbody>
                @for (r of filteredStateRows; track r['strike'] + '' + r['type']) {
                  <tr>
                    <td class="mono">{{ r['strike'] }}</td>
                    <td>{{ r['type'] }}</td>
                    <td><span [class]="'st st-' + r['state']">{{ r['state'] }}</span></td>
                    <td>{{ r['tier'] }}</td>
                    <td class="mono">{{ r['zOi'] }}</td>
                    <td class="mono" [class.neg]="num(r['dOi5mPct']) < 0" [class.pos]="num(r['dOi5mPct']) > 0">{{ r['dOi5mPct'] }}</td>
                    <td class="mono">{{ r['dP5mPct'] }}</td>
                    <td class="mono">{{ r['volUnitPct'] }}</td>
                    <td class="mono">{{ r['mountainBuild'] }}</td>
                    <td class="mono" [class.neg]="num(r['painPct']) > 20">{{ r['painPct'] }}</td>
                    <td>@if (r['battle']) { <span class="chip chip-battle">BATTLE</span> }
                        @if (r['warming']) { <span class="chip">WARMING</span> }</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        </mat-tab>

        <mat-tab label="Avalanche Feed">
          @if (avalanches.length === 0) { <div class="empty">No avalanche events yet today.</div> }
          @else {
            <div class="table-wrap"><table class="mm-table">
              <thead><tr><th>Time</th><th>Kind</th><th>Instrument</th><th>Detail</th></tr></thead>
              <tbody>
                @for (e of avalanches; track e['tsMs']) {
                  <tr>
                    <td class="mono">{{ num(e['tsMs']) | date:'HH:mm:ss' }}</td>
                    <td><span [class]="'chip ' + chipFor(e['category'])">{{ e['category'] }}</span></td>
                    <td class="mono">{{ e['instrument'] }}</td>
                    <td class="detail">{{ e['detail'] }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        </mat-tab>

        <mat-tab label="Decisions &amp; Audit">
          <div class="filter-row">
            <select class="idx-sel" [value]="decisionFilter" (change)="onFilterChange($event)">
              <option value="">All</option>
              <option value="ENTRY">Entry pipeline (PASS/VETO)</option>
              <option value="EXIT">Exit arbiter (WPRESS / suppressed)</option>
              <option value="EPISODE">Episodes</option>
            </select>
            <span class="hint">rows: {{ decisions.length }}</span>
          </div>
          <div class="table-wrap"><table class="mm-table">
            <thead><tr><th>Time</th><th>Category</th><th>Instrument</th><th>Detail</th></tr></thead>
            <tbody>
              @for (e of decisions; track e['tsMs']) {
                <tr>
                  <td class="mono">{{ num(e['tsMs']) | date:'HH:mm:ss' }}</td>
                  <td><span [class]="'chip ' + chipFor(e['category'])">{{ e['category'] }}</span></td>
                  <td class="mono">{{ e['instrument'] }}</td>
                  <td class="detail">{{ e['detail'] }}</td>
                </tr>
              }
            </tbody>
          </table></div>
        </mat-tab>

        <mat-tab label="Memory &amp; Mountains">
          <h3 class="sec">Episode memory (suspension after {{ 3 }} losers)</h3>
          @if (episodes.length === 0) { <div class="empty">No closed memory-pattern trades yet today.</div> }
          @else {
            <table class="mm-table slim">
              <thead><tr><th>Index</th><th>Side</th><th>Pattern</th><th>n</th><th>Avg net %</th><th>Status</th></tr></thead>
              <tbody>
                @for (e of episodes; track e['index'] + '' + e['side']) {
                  <tr>
                    <td>{{ e['index'] }}</td><td>{{ e['side'] }}</td><td>{{ e['pattern'] }}</td>
                    <td class="mono">{{ e['n'] }}</td>
                    <td class="mono" [class.neg]="num(e['avgNetPct']) < 0" [class.pos]="num(e['avgNetPct']) > 0">{{ e['avgNetPct'] }}</td>
                    <td>@if (e['suspended']) { <span class="chip chip-veto">SUSPENDED</span> } @else { <span class="chip chip-ok">active</span> }</td>
                  </tr>
                }
              </tbody>
            </table>
          }
          <h3 class="sec">Mountains — where the money sits ({{ index }}, expiry-week OI build + writers' pain)</h3>
          @if (mountains.length === 0) { <div class="empty">No mountain data yet (builds as the expiry week progresses).</div> }
          @else {
            <table class="mm-table slim">
              <thead><tr>
                <th class="sortable" (click)="sortMtn('strike')">Strike {{ mtnSortIcon('strike') }}</th>
                <th>Type</th><th>Expiry</th>
                <th class="sortable" (click)="sortMtn('cumBuild')">Cum build {{ mtnSortIcon('cumBuild') }}</th>
                <th class="sortable" (click)="sortMtn('avgWritePremium')">Avg write ₹ {{ mtnSortIcon('avgWritePremium') }}</th>
                <th class="sortable" (click)="sortMtn('painPct')">Pain % {{ mtnSortIcon('painPct') }}</th>
              </tr></thead>
              <tbody>
                @for (m of sortedMountains; track m['strike'] + '' + m['type']) {
                  <tr>
                    <td class="mono">{{ m['strike'] }}</td><td>{{ m['type'] }}</td>
                    <td class="mono">{{ m['expiry'] }}</td>
                    <td class="mono">{{ num(m['cumBuild']) | number:'1.0-0' }}</td>
                    <td class="mono">{{ m['avgWritePremium'] }}</td>
                    <td class="mono" [class.neg]="num(m['painPct']) > 20" [class.pos]="num(m['painPct']) < 0">{{ m['painPct'] }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </mat-tab>

      </mat-tab-group>
    </section>
  `,
  styles: [`
    .page { padding: 16px; }
    .row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
    .page-title { margin: 0; font-size: 20px; }
    .page-subtitle { margin: 2px 0 12px; color: var(--muted, #9aa); font-size: 12px; }
    .idx-sel { background: transparent; color: inherit; border: 1px solid rgba(128,128,128,.4); border-radius: 6px; padding: 6px 10px; }
    .table-wrap { overflow-x: auto; margin-top: 10px; }
    .mm-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .mm-table.slim { max-width: 900px; }
    .mm-table th { text-align: left; padding: 6px 8px; color: var(--muted, #9aa); font-weight: 500; border-bottom: 1px solid rgba(128,128,128,.25); }
    .mm-table th.sortable { cursor: pointer; user-select: none; }
    .mm-table th.sortable:hover { color: #fff; }
    .mm-table td { padding: 5px 8px; border-bottom: 1px solid rgba(128,128,128,.12); }
    .mono { font-family: var(--mono, monospace); }
    .pos { color: #66bb6a; } .neg { color: #ef5350; }
    .empty { padding: 24px; color: var(--muted, #9aa); font-size: 13px; }
    .detail { font-size: 11px; color: var(--muted, #bbb); max-width: 640px; }
    .sec { margin: 18px 0 6px; font-size: 14px; }
    .filter-row { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
    .hint { color: var(--muted, #9aa); font-size: 11px; }
    .st { padding: 2px 8px; border-radius: 10px; font-size: 11px; }
    .st-DEAD { background: rgba(128,128,128,.2); color: #999; }
    .st-NEUTRAL { color: var(--muted, #9aa); }
    .st-SHORT_COVER_RUN { background: rgba(102,187,106,.18); color: #66bb6a; }
    .st-WRITER_PRESS { background: rgba(239,83,80,.18); color: #ef5350; }
    .st-AVALANCHE { background: rgba(171,71,188,.25); color: #ce93d8; font-weight: 600; }
    .st-WARMING { color: #888; font-style: italic; }
    .chip { padding: 1px 8px; border-radius: 10px; font-size: 10px; background: rgba(128,128,128,.15); }
    .chip-battle { background: rgba(255,183,77,.2); color: #ffb74d; }
    .chip-ok { background: rgba(102,187,106,.15); color: #66bb6a; }
    .chip-veto { background: rgba(239,83,80,.18); color: #ef5350; }
    .chip-entry { background: rgba(102,187,106,.2); color: #66bb6a; }
    .chip-state { background: rgba(171,71,188,.2); color: #ce93d8; }
    .summary-bar { display: flex; flex-wrap: wrap; gap: 12px; margin: 12px 0; padding: 10px 14px;
      background: rgba(128,128,128,.08); border-radius: 8px; border: 1px solid rgba(128,128,128,.18); }
    .sm { display: flex; flex-direction: column; gap: 2px; min-width: 80px; }
    .sm-label { font-size: 10px; color: var(--muted, #9aa); text-transform: uppercase; letter-spacing: 0.5px; }
    .sm-val { font-size: 13px; font-weight: 500; }
    .sm-val.ok { color: #66bb6a; }
    .sm-states { display: flex; gap: 6px; flex-wrap: wrap; }
  `]
})
export class MarketMemoryPageComponent implements OnInit, OnDestroy {
  indices = ['NIFTY', 'SENSEX', 'BANKNIFTY'];
  index = 'NIFTY';
  decisionFilter = '';
  stateFilter = '';
  typeFilter = '';
  sortCol = '';
  sortAsc = true;
  stateRows: ApiRecord[] = [];
  avalanches: ApiRecord[] = [];
  decisions: ApiRecord[] = [];
  episodes: ApiRecord[] = [];
  mountains: ApiRecord[] = [];
  summary: ApiRecord | null = null;
  private fastSub?: Subscription;
  private slowSub?: Subscription;

  constructor(private readonly api: ApiService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.loadFast(); this.loadSlow();
    this.fastSub = interval(5000).subscribe(() => this.loadFast());
    this.slowSub = interval(10000).subscribe(() => this.loadSlow());
  }
  ngOnDestroy(): void { this.fastSub?.unsubscribe(); this.slowSub?.unsubscribe(); }

  onIndexChange(ev: Event): void { this.index = (ev.target as HTMLSelectElement).value; this.loadFast(); this.loadSlow(); }
  onFilterChange(ev: Event): void { this.decisionFilter = (ev.target as HTMLSelectElement).value; this.loadSlow(); }

  private loadFast(): void {
    const forIndex = this.index;
    this.api.memorySummary(forIndex).pipe(catchError(() => of(null))).subscribe(s => {
      if (forIndex !== this.index) return;
      this.summary = s as ApiRecord | null;
      this.cd.detectChanges();
    });
    this.api.memoryState(forIndex).pipe(catchError(() => of(null))).subscribe(s => {
      if (forIndex !== this.index || !s) return;
      this.stateRows = (s['rows'] as ApiRecord[]) ?? [];
      this.cd.detectChanges();
    });
  }
  private loadSlow(): void {
    const forIndex = this.index;
    this.api.memoryAvalanches().pipe(catchError(() => of([] as ApiRecord[]))).subscribe(a => { this.avalanches = a; this.cd.detectChanges(); });
    this.api.memoryDecisions(this.decisionFilter || undefined).pipe(catchError(() => of([] as ApiRecord[]))).subscribe(d => { this.decisions = d; this.cd.detectChanges(); });
    this.api.memoryEpisodes().pipe(catchError(() => of([] as ApiRecord[]))).subscribe(e => { this.episodes = e; this.cd.detectChanges(); });
    this.api.memoryMountain(forIndex).pipe(catchError(() => of([] as ApiRecord[]))).subscribe(m => {
      if (forIndex !== this.index) return;
      this.mountains = m; this.cd.detectChanges();
    });
  }

  chipFor(cat: unknown): string {
    const c = String(cat ?? '');
    if (c.includes('VETO') || c.includes('SUPPRESSED') || c.includes('NO_ENTRY')) return 'chip-veto';
    if (c.includes('ENTRY') || c.includes('PASS')) return 'chip-entry';
    if (c.includes('STATE')) return 'chip-state';
    return '';
  }
  asSuspensions(v: unknown): string { return Array.isArray(v) ? v.join(', ') : String(v ?? ''); }
  hasSuspensions(): boolean { const s = this.summary?.['suspensions']; return Array.isArray(s) && s.length > 0; }
  stateCount(state: string): number {
    const states = this.summary?.['states'];
    if (!states || typeof states !== 'object') return 0;
    return Number((states as Record<string, number>)[state] ?? 0);
  }
  asStr(ev: Event): string { return (ev.target as HTMLSelectElement).value; }

  get filteredStateRows(): ApiRecord[] {
    let rows = this.stateRows;
    if (this.stateFilter) rows = rows.filter(r => String(r['state']) === this.stateFilter);
    if (this.typeFilter) rows = rows.filter(r => String(r['type']) === this.typeFilter);
    if (this.sortCol) {
      const col = this.sortCol;
      const dir = this.sortAsc ? 1 : -1;
      rows = [...rows].sort((a, b) => {
        const va = this.num(a[col]), vb = this.num(b[col]);
        return (va - vb) * dir;
      });
    }
    return rows;
  }

  sortStates(col: string): void {
    if (this.sortCol === col) { this.sortAsc = !this.sortAsc; }
    else { this.sortCol = col; this.sortAsc = true; }
  }

  sortIcon(col: string): string {
    if (this.sortCol !== col) return '';
    return this.sortAsc ? '▲' : '▼';
  }

  // Mountains sorting
  mtnSortCol = 'cumBuild';
  mtnSortAsc = false; // default: biggest mountain first

  get sortedMountains(): ApiRecord[] {
    if (!this.mtnSortCol) return this.mountains;
    const col = this.mtnSortCol;
    const dir = this.mtnSortAsc ? 1 : -1;
    return [...this.mountains].sort((a, b) => (this.num(a[col]) - this.num(b[col])) * dir);
  }

  sortMtn(col: string): void {
    if (this.mtnSortCol === col) { this.mtnSortAsc = !this.mtnSortAsc; }
    else { this.mtnSortCol = col; this.mtnSortAsc = false; } // default descending for mountains
  }

  mtnSortIcon(col: string): string {
    if (this.mtnSortCol !== col) return '';
    return this.mtnSortAsc ? '▲' : '▼';
  }

  num(v: unknown): number { const n = Number(v); return Number.isFinite(n) ? n : 0; }
}
