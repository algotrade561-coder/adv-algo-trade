import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../core/api.service';
import { catchError, of } from 'rxjs';

/**
 * Microstructure Explorer — READ-ONLY validation surface for the high-frequency ATM
 * capture and the shadow early-detection signals. Backed by the DuckDB-powered
 * /microstructure REST endpoints. No trading actions are possible from this page.
 *
 * Charts are hand-rolled inline SVG sparklines (no charting dependency added).
 */
@Component({
  selector: 'app-microstructure-page',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule],
  template: `
    <section class="page">
      <div class="hdr">
        <div>
          <h1 class="title">Microstructure Explorer</h1>
          <p class="subtitle">Read-only research view over per-second ATM option ticks and shadow early-detection signals. No orders are placed from here.</p>
        </div>
        <button mat-flat-button color="primary" (click)="load()" [disabled]="!date || !strike">
          <mat-icon>refresh</mat-icon> Load
        </button>
      </div>

      <!-- ── Filters ── -->
      <div class="filters">
        <mat-form-field appearance="outline">
          <mat-label>Index</mat-label>
          <mat-select [(ngModel)]="index" (selectionChange)="onIndexOrDate()">
            @for (ix of indices; track ix) { <mat-option [value]="ix">{{ ix }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Date</mat-label>
          <mat-select [(ngModel)]="date" (selectionChange)="onIndexOrDate()">
            @for (d of days; track d) { <mat-option [value]="d">{{ d }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Strike</mat-label>
          <mat-select [(ngModel)]="strike">
            @for (s of strikes; track s) { <mat-option [value]="s">{{ s }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Leg (liquidity)</mat-label>
          <mat-select [(ngModel)]="optionType">
            <mat-option value="CE">CE</mat-option>
            <mat-option value="PE">PE</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Bucket</mat-label>
          <mat-select [(ngModel)]="bucketSec">
            @for (b of buckets; track b) { <mat-option [value]="b">{{ b }}s</mat-option> }
          </mat-select>
        </mat-form-field>
      </div>

      @if (loading) { <p class="muted">Loading…</p> }
      @if (!days.length && !loading) {
        <p class="muted">No microstructure data found yet. Capture writes to <code>data/tuning/atm-microstructure-&lt;date&gt;.csv</code>; past days are archived to Parquet by the nightly roller.</p>
      }

      <!-- ── GATE-0 cadence ── -->
      @if (cadence) {
        <div class="card">
          <h2 class="sec-title"><mat-icon class="sec-icon">timer</mat-icon> OI refresh cadence (GATE-0)</h2>
          <div class="kpis">
            <div class="kpi"><span class="k">Median gap</span><span class="v">{{ cadence['medianGapSec'] | number:'1.0-2' }} s</span></div>
            <div class="kpi"><span class="k">Mean gap</span><span class="v">{{ cadence['meanGapSec'] | number:'1.0-2' }} s</span></div>
            <div class="kpi"><span class="k">OI change events</span><span class="v">{{ cadence['oiChangeEvents'] | number }}</span></div>
          </div>
          <p class="muted small">Per-second OI velocity is only as fine as this cadence — interpret leading signals accordingly.</p>
        </div>
      }

      <!-- ── Strike timeline (OI buildup / unwinding) ── -->
      @if (timeline.length) {
        <div class="card">
          <h2 class="sec-title"><mat-icon class="sec-icon">show_chart</mat-icon> Strike {{ strike }} — OI &amp; price ({{ timeline.length }} buckets)</h2>
          <div class="charts">
            <div class="chart">
              <span class="clabel">CE OI</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark ce"><polyline [attr.points]="spark(col(timeline,'ceOi'))"/></svg>
            </div>
            <div class="chart">
              <span class="clabel">PE OI</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark pe"><polyline [attr.points]="spark(col(timeline,'peOi'))"/></svg>
            </div>
            <div class="chart">
              <span class="clabel">CE LTP</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark ce"><polyline [attr.points]="spark(col(timeline,'ceLtp'))"/></svg>
            </div>
            <div class="chart">
              <span class="clabel">PE LTP</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark pe"><polyline [attr.points]="spark(col(timeline,'peLtp'))"/></svg>
            </div>
          </div>
          <div class="tbl-wrap">
            <table class="tbl">
              <thead><tr><th>Time</th><th>CE LTP</th><th>PE LTP</th><th>CE OI</th><th>PE OI</th><th>CE Vol Δ</th><th>PE Vol Δ</th></tr></thead>
              <tbody>
                @for (r of timeline; track r.bucketTs) {
                  <tr>
                    <td>{{ hhmmss(r.bucketTs) }}</td>
                    <td>{{ r.ceLtp | number:'1.0-2' }}</td>
                    <td>{{ r.peLtp | number:'1.0-2' }}</td>
                    <td>{{ r.ceOi | number }}</td>
                    <td>{{ r.peOi | number }}</td>
                    <td>{{ r.ceVolDelta | number }}</td>
                    <td>{{ r.peVolDelta | number }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ── Liquidity / quote pressure ── -->
      @if (liquidity.length) {
        <div class="card">
          <h2 class="sec-title"><mat-icon class="sec-icon">water_drop</mat-icon> Liquidity &amp; pressure — {{ strike }} {{ optionType }}</h2>
          <div class="charts">
            <div class="chart"><span class="clabel">Spread (bps)</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark ce"><polyline [attr.points]="spark(col(liquidity,'spreadBps'))"/></svg></div>
            <div class="chart"><span class="clabel">Bid imbalance</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark pe"><polyline [attr.points]="spark(col(liquidity,'bidImbalance'))"/></svg></div>
            <div class="chart"><span class="clabel">Microprice drift</span>
              <svg viewBox="0 0 100 30" preserveAspectRatio="none" class="spark ce"><polyline [attr.points]="spark(col(liquidity,'micropriceDrift'))"/></svg></div>
          </div>
          <div class="tbl-wrap">
            <table class="tbl">
              <thead><tr><th>Time</th><th>Spread bps</th><th>Microprice drift</th><th>Bid imbalance</th></tr></thead>
              <tbody>
                @for (r of liquidity; track r.bucketTs) {
                  <tr>
                    <td>{{ hhmmss(r.bucketTs) }}</td>
                    <td>{{ r.spreadBps | number:'1.0-1' }}</td>
                    <td>{{ r.micropriceDrift | number:'1.0-3' }}</td>
                    <td>{{ r.bidImbalance | number:'1.0-3' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ── Shadow early-detection signals ── -->
      <div class="card">
        <h2 class="sec-title"><mat-icon class="sec-icon">science</mat-icon> Shadow early-detection signals (virtual — no orders)</h2>
        @if (shadow.length) {
          <div class="tbl-wrap">
            <table class="tbl">
              <thead><tr><th>Time</th><th>Dir</th><th>OI vel %</th><th>Side</th><th>ATM</th><th>Coil %</th><th>Imbalance</th><th>Trigger</th></tr></thead>
              <tbody>
                @for (s of shadow; track s.recvEpochMs) {
                  <tr>
                    <td>{{ hhmmssMs(s.recvEpochMs) }}</td>
                    <td [class.up]="s.direction > 0" [class.down]="s.direction < 0">{{ s.direction > 0 ? 'BULL' : 'BEAR' }}</td>
                    <td>{{ s.oiVelocityPct | number:'1.0-2' }}</td>
                    <td>{{ s.dominantSide }}</td>
                    <td>{{ s.atmStrike }}</td>
                    <td>{{ s.coilRangePct | number:'1.0-3' }}</td>
                    <td>{{ s.imbalance | number:'1.0-2' }}</td>
                    <td>{{ s.trigger }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <p class="muted small">No virtual signals for this day. The detector is shadow-only and ships disabled — enable <code>oi-momentum.early-detection.enabled</code> to start logging.</p>
        }
      </div>
    </section>
  `,
  styles: [`
    .page { padding: 16px; }
    .hdr { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; }
    .title { margin:0; font-size:1.5rem; }
    .subtitle { margin:.25rem 0 0; color:var(--mat-sys-on-surface-variant, #888); max-width:60ch; }
    .filters { display:flex; flex-wrap:wrap; gap:12px; margin:16px 0; }
    .filters mat-form-field { width:150px; }
    .card { border:1px solid rgba(127,127,127,.25); border-radius:10px; padding:14px 16px; margin-bottom:16px; }
    .sec-title { display:flex; align-items:center; gap:8px; font-size:1.05rem; margin:0 0 10px; }
    .sec-icon { font-size:20px; height:20px; width:20px; }
    .kpis { display:flex; gap:24px; flex-wrap:wrap; }
    .kpi { display:flex; flex-direction:column; }
    .kpi .k { font-size:.75rem; color:#999; }
    .kpi .v { font-size:1.2rem; font-weight:600; }
    .charts { display:flex; flex-wrap:wrap; gap:18px; margin-bottom:12px; }
    .chart { display:flex; flex-direction:column; gap:4px; width:220px; }
    .clabel { font-size:.72rem; color:#999; }
    .spark { width:100%; height:40px; background:rgba(127,127,127,.06); border-radius:4px; }
    .spark polyline { fill:none; stroke-width:1.2; vector-effect:non-scaling-stroke; }
    .spark.ce polyline { stroke:#2e7d32; }
    .spark.pe polyline { stroke:#c62828; }
    .tbl-wrap { overflow:auto; max-height:360px; }
    .tbl { border-collapse:collapse; width:100%; font-size:.82rem; }
    .tbl th, .tbl td { text-align:right; padding:4px 10px; border-bottom:1px solid rgba(127,127,127,.15); white-space:nowrap; }
    .tbl th:first-child, .tbl td:first-child { text-align:left; }
    .tbl thead th { position:sticky; top:0; background:var(--mat-sys-surface, #1e1e1e); }
    .up { color:#2e7d32; font-weight:600; }
    .down { color:#c62828; font-weight:600; }
    .muted { color:#999; }
    .small { font-size:.78rem; }
  `]
})
export class MicrostructurePageComponent implements OnInit {
  indices = ['NIFTY', 'BANKNIFTY', 'SENSEX'];
  buckets = [15, 30, 60, 300];
  days: string[] = [];
  strikes: number[] = [];

  index = 'NIFTY';
  date = '';
  strike: number | null = null;
  optionType = 'CE';
  bucketSec = 60;

  loading = false;
  cadence: Record<string, any> | null = null;
  timeline: any[] = [];
  liquidity: any[] = [];
  shadow: any[] = [];

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.api.microDays().pipe(catchError(() => of([] as string[]))).subscribe(d => {
      this.days = d || [];
      if (this.days.length) { this.date = this.days[0]; this.onIndexOrDate(); }
      this.cdr.markForCheck();
    });
  }

  onIndexOrDate(): void {
    if (!this.index || !this.date) return;
    this.strikes = [];
    this.strike = null;
    this.api.microStrikes(this.index, this.date)
      .pipe(catchError(() => of([] as number[])))
      .subscribe(s => {
        this.strikes = s || [];
        if (this.strikes.length) {
          // default to the middle strike (closest to ATM, roughly)
          this.strike = this.strikes[Math.floor(this.strikes.length / 2)];
        }
        this.cdr.markForCheck();
      });
  }

  load(): void {
    if (!this.date || this.strike == null) return;
    this.loading = true;
    this.cadence = null; this.timeline = []; this.liquidity = []; this.shadow = [];
    const idx = this.index, dt = this.date, st = this.strike, b = this.bucketSec, ot = this.optionType;

    this.api.microOiCadence(idx, dt).pipe(catchError(() => of({}))).subscribe(c => {
      this.cadence = c && Object.keys(c).length ? c : null; this.cdr.markForCheck();
    });
    this.api.microStrikeTimeline(idx, dt, st, b).pipe(catchError(() => of([]))).subscribe(t => {
      this.timeline = t || []; this.loading = false; this.cdr.markForCheck();
    });
    this.api.microLiquidity(idx, dt, st, ot, b).pipe(catchError(() => of([]))).subscribe(l => {
      this.liquidity = l || []; this.cdr.markForCheck();
    });
    this.api.microShadowSignals(idx, dt).pipe(catchError(() => of([]))).subscribe(s => {
      this.shadow = s || []; this.cdr.markForCheck();
    });
  }

  /** Extract a numeric column from row objects, coercing nulls to NaN (skipped in spark). */
  col(rows: any[], key: string): number[] {
    return (rows || []).map(r => (r[key] == null ? NaN : Number(r[key])));
  }

  /** Build an SVG polyline points string scaled into a 100x30 viewBox. */
  spark(values: number[]): string {
    const vals = (values || []).filter(v => !isNaN(v));
    if (vals.length < 2) return '';
    const min = Math.min(...vals), max = Math.max(...vals);
    const range = max - min || 1;
    const n = values.length;
    const pts: string[] = [];
    for (let i = 0; i < n; i++) {
      const v = values[i];
      if (isNaN(v)) continue;
      const x = (i / (n - 1)) * 100;
      const y = 28 - ((v - min) / range) * 26; // padding top/bottom
      pts.push(`${x.toFixed(2)},${y.toFixed(2)}`);
    }
    return pts.join(' ');
  }

  hhmmss(epochSec: number): string {
    if (epochSec == null) return '';
    return new Date(epochSec * 1000).toLocaleTimeString('en-GB', { timeZone: 'Asia/Kolkata' });
  }

  hhmmssMs(epochMs: number): string {
    if (epochMs == null) return '';
    return new Date(epochMs).toLocaleTimeString('en-GB', { timeZone: 'Asia/Kolkata' });
  }
}
