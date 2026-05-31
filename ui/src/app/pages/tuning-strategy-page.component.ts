import { CommonModule } from '@angular/common';
import { Component, OnInit, signal, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../core/api.service';

interface FileRow { type: string; count: number; }

@Component({
  selector: 'app-tuning-strategy-page',
  standalone: true,
  imports: [CommonModule, RouterLink, MatButtonModule, MatIconModule],
  template: `
    <section class="page">
      <div class="hdr">
        <div>
          <h1 class="page-title">Strategy: {{ name() }}</h1>
          <p class="page-subtitle">Today's unified event-file counts for {{ name() }}.</p>
        </div>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
        <a mat-stroked-button routerLink="/tuning-capture">
          <mat-icon>science</mat-icon> Capture toggles
        </a>
      </div>

      @if (error()) {
        <div class="err-bar"><mat-icon>error_outline</mat-icon> {{ error() }}</div>
      }

      @if (rows().length === 0 && !error()) {
        <div class="empty">
          <mat-icon>hourglass_empty</mat-icon>
          <p>No event files captured today for this strategy.</p>
          <p class="muted">
            Capture may be OFF — check <a routerLink="/tuning-capture">Tuning Capture</a> to enable it.
          </p>
        </div>
      } @else if (rows().length > 0) {
        <div class="panel">
          <table class="files-table">
            <thead>
              <tr>
                <th>Event type</th>
                <th class="num">Files today</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.type) {
                <tr>
                  <td><span class="badge" [class.b-on]="r.count > 0">{{ r.type }}</span></td>
                  <td class="num">{{ r.count }}</td>
                  <td class="muted">{{ describe(r.type) }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th>Total files</th>
                <th class="num">{{ totalFiles() }}</th>
                <th></th>
              </tr>
            </tfoot>
          </table>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 960px; }
    .hdr { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; margin: 0 0 4px; color: var(--ink); }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }

    .err-bar {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 14px; border-radius: 8px;
      background: rgba(255,113,106,.06); border: 1px solid rgba(255,113,106,.25); color: var(--bad);
      font-size: 13px; margin-bottom: 16px;
    }
    .err-bar mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .empty {
      display: flex; flex-direction: column; align-items: center; gap: 6px;
      padding: 48px 0; color: var(--muted); font-size: 14px;
    }
    .empty mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: .35; }
    .muted { color: var(--muted); font-size: 12px; }
    .muted a { color: var(--accent); text-decoration: none; }
    .muted a:hover { text-decoration: underline; }

    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .files-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .files-table th { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--line); font-size: 11px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .files-table td { padding: 10px 8px; border-bottom: 1px solid rgba(255,255,255,.04); }
    .files-table tfoot th { border-top: 2px solid var(--line); border-bottom: none; font-size: 12px; color: var(--ink); }
    .num { text-align: right; font-variant-numeric: tabular-nums; }

    .badge {
      display: inline-block; padding: 3px 10px; border-radius: 20px;
      font-size: 11px; font-weight: 700; letter-spacing: .03em;
      background: rgba(255,255,255,.05); color: var(--muted); border: 1px solid var(--line);
    }
    .b-on { background: rgba(69,209,140,.12); border-color: rgba(69,209,140,.35); color: var(--ok); }
  `]
})
export class TuningStrategyPageComponent implements OnInit {
  name = signal('');
  rows = signal<FileRow[]>([]);
  error = signal<string | null>(null);
  totalFiles = computed(() => this.rows().reduce((acc, r) => acc + r.count, 0));

  private readonly descriptions: Record<string, string> = {
    EVALUATION: 'Per-strategy evaluation episodes (rejections grouped by blocker)',
    SIGNAL: 'Fired BUY signals before order routing',
    EXECUTION: 'Broker fills (entry order outcomes)',
    EXIT: 'Trade exits with PnL + MAE/MFE',
    FORWARD_CHECKPOINT: 'Post-signal price checkpoints at +30s/1m/5m/15m/30m',
    SHADOW_GATE: 'Counterfactual gate decisions (would-have-fired tracking)',
    LEG: 'Per-leg events for spread strategies'
  };

  constructor(private route: ActivatedRoute, private api: ApiService) {}

  ngOnInit(): void {
    this.route.paramMap.subscribe(m => {
      this.name.set(m.get('name') ?? '');
      this.load();
    });
  }

  load(): void {
    const n = this.name();
    if (!n) return;
    this.error.set(null);
    this.api.getTuningStrategyToday(n).subscribe({
      next: d => {
        const files = (d['eventFiles'] as Record<string, number>) ?? {};
        const rows: FileRow[] = Object.entries(files).map(([type, count]) => ({ type, count: Number(count) }));
        // Sort by canonical order (descriptions key order is the natural flow)
        const order = Object.keys(this.descriptions);
        rows.sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type));
        this.rows.set(rows);
      },
      error: e => this.error.set(e?.message ?? 'Load failed')
    });
  }

  describe(type: string): string {
    return this.descriptions[type] ?? '';
  }
}
