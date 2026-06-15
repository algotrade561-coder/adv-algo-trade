import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { interval, Subscription } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../core/api.service';

interface PcrPoint { time: string; pcr: number; }
interface PcrSeries { latest: number; series: PcrPoint[]; }

/**
 * Intraday PCR chart — per-index full-chain Put-Call Ratio, today only,
 * 09:00–15:30 IST. Self-contained SVG (no chart library). Polls /api/pcr/intraday
 * every 2 minutes (matching the backend compute cycle).
 */
@Component({
  selector: 'app-pcr-chart',
  standalone: true,
  imports: [DecimalPipe, MatIconModule],
  template: `
    <div class="panel">
      <div class="hdr">
        <h2><mat-icon class="hi">show_chart</mat-icon> Intraday PCR (full chain)</h2>
        <span class="spacer"></span>
        @for (ix of indices; track ix.key) {
          <button class="leg" [class.leg-off]="!visible[ix.key]" (click)="toggle(ix.key)">
            <span class="dot" [style.background]="ix.color"></span>
            {{ ix.key }}
            <span class="leg-val">{{ latest(ix.key) > 0 ? (latest(ix.key) | number:'1.2-2') : '–' }}</span>
          </button>
        }
      </div>

      @if (hasData()) {
        <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" preserveAspectRatio="none" class="chart">
          <!-- horizontal gridlines + y labels -->
          @for (g of yTicks; track g.v) {
            <line [attr.x1]="PL" [attr.x2]="W - PR" [attr.y1]="g.y" [attr.y2]="g.y" class="grid"/>
            <text [attr.x]="PL - 6" [attr.y]="g.y + 3" class="lbl" text-anchor="end">{{ g.v }}</text>
          }
          <!-- PCR = 1.0 reference -->
          @if (yOf(1) !== null) {
            <line [attr.x1]="PL" [attr.x2]="W - PR" [attr.y1]="yOf(1)" [attr.y2]="yOf(1)" class="ref"/>
          }
          <!-- hour gridlines + x labels -->
          @for (t of xTicks; track t.label) {
            <line [attr.x1]="t.x" [attr.x2]="t.x" [attr.y1]="PT" [attr.y2]="H - PB" class="grid"/>
            <text [attr.x]="t.x" [attr.y]="H - 6" class="lbl" text-anchor="middle">{{ t.label }}</text>
          }
          <!-- series -->
          @for (ix of indices; track ix.key) {
            @if (visible[ix.key] && paths[ix.key]) {
              <polyline [attr.points]="paths[ix.key]" [attr.stroke]="ix.color" class="line"/>
            }
          }
        </svg>
      } @else {
        <div class="empty">No PCR samples yet today — data starts ~09:18 once the option chain is live.</div>
      }
    </div>
  `,
  styles: [`
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; margin-top: 16px; }
    .hdr { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
    .hdr h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; display: flex; align-items: center; gap: 6px; }
    .hi { font-size: 18px; width: 18px; height: 18px; color: var(--accent); }
    .spacer { flex: 1; }
    .leg { display: flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--line);
           border-radius: 14px; background: rgba(255,255,255,.02); color: var(--ink); font-size: 11px;
           font-weight: 700; cursor: pointer; }
    .leg-off { opacity: .35; }
    .dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
    .leg-val { color: var(--muted); font-weight: 600; }
    .chart { width: 100%; height: 240px; display: block; }
    .grid { stroke: rgba(255,255,255,.06); stroke-width: 1; }
    .ref { stroke: rgba(255,255,255,.22); stroke-width: 1; stroke-dasharray: 4 4; }
    .lbl { fill: var(--muted); font-size: 10px; }
    .line { fill: none; stroke-width: 2; vector-effect: non-scaling-stroke; }
    .empty { padding: 28px; text-align: center; color: var(--muted); font-size: 12px; }
  `]
})
export class PcrChartComponent implements OnInit, OnDestroy {
  // Chart geometry (SVG user units)
  readonly W = 860; readonly H = 240;
  readonly PL = 42; readonly PR = 10; readonly PT = 10; readonly PB = 22;

  // X axis: 09:00 (540 min) → 15:30 (930 min)
  private readonly X_START = 9 * 60;
  private readonly X_END = 15 * 60 + 30;

  readonly indices = [
    { key: 'NIFTY', color: '#61a8ff' },
    { key: 'BANKNIFTY', color: '#f2bd4b' },
    { key: 'SENSEX', color: '#45d18c' }
  ];

  data: Record<string, PcrSeries> = {};
  visible: Record<string, boolean> = { NIFTY: true, BANKNIFTY: true, SENSEX: true };
  paths: Record<string, string> = {};
  yTicks: { v: string; y: number }[] = [];
  xTicks: { label: string; x: number }[] = [];
  private yMin = 0.6; private yMax = 1.6;
  private pollSub?: Subscription;

  constructor(private readonly api: ApiService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.refresh();
    this.pollSub = interval(120_000).subscribe(() => this.refresh());
  }
  ngOnDestroy(): void { this.pollSub?.unsubscribe(); }

  refresh(): void {
    this.api.pcrIntraday().subscribe({
      next: d => { this.data = d ?? {}; this.rebuild(); this.cd.detectChanges(); },
      error: () => { /* keep last data */ }
    });
  }

  toggle(key: string): void { this.visible[key] = !this.visible[key]; }
  latest(key: string): number { return this.data[key]?.latest ?? 0; }
  hasData(): boolean { return this.indices.some(ix => (this.data[ix.key]?.series?.length ?? 0) > 0); }

  yOf(v: number): number | null {
    if (v < this.yMin || v > this.yMax) return null;
    const h = this.H - this.PT - this.PB;
    return this.PT + h * (1 - (v - this.yMin) / (this.yMax - this.yMin));
  }

  private xOf(minOfDay: number): number {
    const w = this.W - this.PL - this.PR;
    const f = (minOfDay - this.X_START) / (this.X_END - this.X_START);
    return this.PL + w * Math.min(1, Math.max(0, f));
  }

  private rebuild(): void {
    // Y range from data, padded; sane defaults when sparse
    let lo = Number.POSITIVE_INFINITY, hi = Number.NEGATIVE_INFINITY;
    for (const ix of this.indices) {
      for (const p of this.data[ix.key]?.series ?? []) {
        if (p.pcr < lo) lo = p.pcr;
        if (p.pcr > hi) hi = p.pcr;
      }
    }
    if (!isFinite(lo)) { lo = 0.8; hi = 1.2; }
    this.yMin = Math.max(0, Math.floor((lo - 0.08) * 10) / 10);
    this.yMax = Math.ceil((hi + 0.08) * 10) / 10;
    if (this.yMax - this.yMin < 0.2) this.yMax = this.yMin + 0.2;

    // Y ticks: 5 evenly spaced
    this.yTicks = [];
    for (let i = 0; i <= 4; i++) {
      const v = this.yMin + (i * (this.yMax - this.yMin)) / 4;
      this.yTicks.push({ v: v.toFixed(2), y: this.yOf(v) ?? 0 });
    }

    // X ticks: every hour 09:00 → 15:00 plus 15:30
    this.xTicks = [];
    for (let h = 9; h <= 15; h++) {
      this.xTicks.push({ label: `${String(h).padStart(2, '0')}:00`, x: this.xOf(h * 60) });
    }
    this.xTicks.push({ label: '15:30', x: this.xOf(15 * 60 + 30) });

    // Polyline points per index
    this.paths = {};
    for (const ix of this.indices) {
      const pts = (this.data[ix.key]?.series ?? [])
        .map(p => {
          const [hh, mm] = p.time.split(':').map(Number);
          const x = this.xOf(hh * 60 + mm);
          const y = this.PT + (this.H - this.PT - this.PB)
              * (1 - (p.pcr - this.yMin) / (this.yMax - this.yMin));
          return `${x.toFixed(1)},${y.toFixed(1)}`;
        });
      if (pts.length > 0) this.paths[ix.key] = pts.join(' ');
    }
  }
}
