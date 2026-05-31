import { CommonModule } from '@angular/common';
import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';

interface BucketRow { key: string; value: string; }

@Component({
  selector: 'app-tuning-explore-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule
  ],
  template: `
    <section class="page">
      <h1 class="page-title">Tuning explore</h1>
      <p class="page-subtitle">
        Quick bucket-level lookup. Full breakdowns (heatmaps, MAE/MFE distributions, regime splits) live in the
        generated HTML reports — <a routerLink="/reports">trigger one</a>.
      </p>

      <div class="panel">
        <div class="form-row">
          <mat-form-field appearance="outline">
            <mat-label>Strategy</mat-label>
            <mat-select [(ngModel)]="strategy" name="strategy">
              @for (s of strategyOptions; track s) {
                <mat-option [value]="s">{{ s }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>From</mat-label>
            <input matInput type="date" [(ngModel)]="from" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>To</mat-label>
            <input matInput type="date" [(ngModel)]="to" />
          </mat-form-field>
          <button mat-flat-button color="primary" (click)="query()" [disabled]="loading()">
            <mat-icon>{{ loading() ? 'hourglass_top' : 'search' }}</mat-icon>
            {{ loading() ? 'Querying…' : 'Query' }}
          </button>
        </div>
      </div>

      @if (error()) {
        <div class="err-bar"><mat-icon>error_outline</mat-icon> {{ error() }}</div>
      }

      @if (rows().length > 0) {
        <div class="panel">
          <h2 class="section-title">Result — {{ strategy }} ({{ from }} → {{ to }})</h2>
          <table class="kv-table">
            <thead>
              <tr><th>Field</th><th>Value</th></tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.key) {
                <tr>
                  <td class="k">{{ r.key }}</td>
                  <td class="v">{{ r.value }}</td>
                </tr>
              }
            </tbody>
          </table>
          <p class="hint">
            <mat-icon>info_outline</mat-icon>
            For full bucket-level heatmaps and per-bucket MAE/MFE, generate an HTML report on the
            <a routerLink="/reports">Reports page</a>.
          </p>
        </div>
      } @else if (!loading() && hasQueried()) {
        <div class="empty">
          <mat-icon>search_off</mat-icon>
          <p>No matching events for {{ strategy }} between {{ from }} and {{ to }}.</p>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 960px; }
    .page-title { font-size: 22px; font-weight: 800; margin: 0 0 4px; color: var(--ink); }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .page-subtitle a { color: var(--accent); text-decoration: none; }
    .page-subtitle a:hover { text-decoration: underline; }

    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; margin-bottom: 16px; }
    .section-title { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0 0 12px; }

    .form-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; }

    .err-bar {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 14px; border-radius: 8px; margin-bottom: 16px;
      background: rgba(255,113,106,.06); border: 1px solid rgba(255,113,106,.25); color: var(--bad);
      font-size: 13px;
    }
    .err-bar mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .empty {
      display: flex; flex-direction: column; align-items: center; gap: 6px;
      padding: 48px 0; color: var(--muted); font-size: 14px;
    }
    .empty mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .35; }

    .kv-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .kv-table th { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--line); font-size: 11px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .kv-table td { padding: 8px; border-bottom: 1px solid rgba(255,255,255,.04); vertical-align: top; }
    .k { color: var(--muted); width: 200px; font-weight: 600; }
    .v { color: var(--ink); word-break: break-word; }

    .hint {
      display: flex; align-items: center; gap: 6px;
      margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--line);
      font-size: 12px; color: var(--muted);
    }
    .hint mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .hint a { color: var(--accent); text-decoration: none; }
    .hint a:hover { text-decoration: underline; }
  `]
})
export class TuningExplorePageComponent {
  // 22 supported strategy types — keep in sync with StrategyType.java.
  strategyOptions = [
    'OI_MOMENTUM', 'OI_SHIFT_TRAP',
    'DIRECTIONAL_BUY', 'MOMENTUM', 'SCALPING', 'REVERSAL_BUY', 'VOLATILITY_BREAKOUT',
    'GAP_AND_GO', 'EVENT_DRIVEN_BUY', 'EXPIRY_GAMMA', 'EXPIRY_REVERSAL', 'ITM_CONVICTION',
    'BULL_CALL_SPREAD', 'BEAR_PUT_SPREAD', 'IRON_CONDOR',
    'LONG_STRADDLE', 'SHORT_STRADDLE', 'LONG_STRANGLE', 'SHORT_STRANGLE',
    'CALENDAR_SPREAD', 'DIAGONAL_SPREAD', 'BUTTERFLY', 'JADE_LIZARD', 'SYNTHETIC_FUTURES'
  ];

  strategy = 'OI_MOMENTUM';
  from = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
  to = new Date().toISOString().slice(0, 10);

  rows = signal<BucketRow[]>([]);
  error = signal<string | null>(null);
  loading = signal(false);
  hasQueried = signal(false);

  constructor(private api: ApiService) {}

  query(): void {
    this.error.set(null);
    this.loading.set(true);
    this.hasQueried.set(true);
    this.api.exploreTuningBuckets(this.strategy, this.from, this.to).subscribe({
      next: r => {
        this.loading.set(false);
        // Server returns a flat key→value object (strategy, from, to, note, optional buckets).
        // Render as a structured key/value list — no JSON dump.
        const out: BucketRow[] = [];
        for (const [k, v] of Object.entries(r ?? {})) {
          out.push({ key: this.prettifyKey(k), value: this.formatValue(v) });
        }
        this.rows.set(out);
      },
      error: e => {
        this.loading.set(false);
        this.error.set(e?.error?.message ?? e?.message ?? 'Query failed');
      }
    });
  }

  private prettifyKey(k: string): string {
    return k.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase()).trim();
  }

  private formatValue(v: unknown): string {
    if (v == null) return '—';
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return String(v);
    if (Array.isArray(v)) return v.length === 0 ? '—' : v.map(x => this.formatValue(x)).join(', ');
    if (typeof v === 'object') {
      return Object.entries(v as Record<string, unknown>)
        .map(([k, val]) => `${k}: ${this.formatValue(val)}`)
        .join(' · ');
    }
    return String(v);
  }
}
