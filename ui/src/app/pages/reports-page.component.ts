import { Component, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { DailyBundleSummary } from '../core/models';

@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <section class="page">
      <h1 class="page-title">Reports</h1>
      <p class="page-subtitle">Download today's analysis files and browse signals.</p>

      <div class="link-bar">
        <a class="link-card" routerLink="/entry-signals">
          <mat-icon class="lc-icon lc-ok">trending_up</mat-icon>
          <div><span class="lc-title">Entry Signals</span><span class="lc-desc">Browse BUY_CE / BUY_PE signals</span></div>
          <mat-icon class="lc-arrow">chevron_right</mat-icon>
        </a>
        <a class="link-card" routerLink="/rejected-signals">
          <mat-icon class="lc-icon lc-warn">block</mat-icon>
          <div><span class="lc-title">Rejected Signals</span><span class="lc-desc">Browse NO_TRADE decisions</span></div>
          <mat-icon class="lc-arrow">chevron_right</mat-icon>
        </a>
      </div>

      <div class="section-hdr">
        <h2 class="section-title">Today's analysis pack (IST)</h2>
        <p class="section-desc">
          Download everything recorded today for local analysis. Source files stay on the server.
          @if (bundleSummary) { <span class="date-tag">{{ bundleSummary.date }}</span> }
        </p>
      </div>

      <div class="grid two">
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">analytics</mat-icon>
            <div>
              <h2>Entry signals &amp; CSVs</h2>
              <p>All signal/outcome CSV files from today under reports/entry-signals.</p>
            </div>
          </div>
          @if (bundleSummary) {
            <p class="file-meta">{{ bundleSummary.signals.fileCount }} files · {{ formatBytes(bundleSummary.signals.totalBytes) }}</p>
          }
          <button mat-flat-button color="primary" (click)="downloadToday('signals')" [disabled]="downloading">
            <mat-icon>file_download</mat-icon> Download ZIP
          </button>
        </div>

        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">description</mat-icon>
            <div>
              <h2>Application logs</h2>
              <p>Today's application, error, and audit logs (all rotated files for today).</p>
            </div>
          </div>
          @if (bundleSummary) {
            <p class="file-meta">{{ bundleSummary.logs.fileCount }} files · {{ formatBytes(bundleSummary.logs.totalBytes) }}</p>
          }
          <button mat-flat-button color="primary" (click)="downloadToday('logs')" [disabled]="downloading">
            <mat-icon>file_download</mat-icon> Download ZIP
          </button>
        </div>

        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">hub</mat-icon>
            <div>
              <h2>Chain snapshots</h2>
              <p>All option-chain snapshot files captured today (data/chain-snapshots).</p>
            </div>
          </div>
          @if (bundleSummary) {
            <p class="file-meta">{{ bundleSummary.chainSnapshots.fileCount }} files · {{ formatBytes(bundleSummary.chainSnapshots.totalBytes) }}</p>
          }
          <button mat-flat-button color="primary" (click)="downloadToday('chain')" [disabled]="downloading">
            <mat-icon>file_download</mat-icon> Download ZIP
          </button>
        </div>

        <div class="panel panel-highlight">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">folder_zip</mat-icon>
            <div>
              <h2>Full analysis pack</h2>
              <p>Signals, logs, and chain snapshots in one ZIP for the current IST date.</p>
            </div>
          </div>
          @if (bundleSummary) {
            <p class="file-meta">{{ bundleSummary.totalFiles }} files · {{ formatBytes(bundleSummary.totalBytes) }}</p>
          }
          <button mat-flat-button color="accent" (click)="downloadToday('all')" [disabled]="downloading">
            <mat-icon>archive</mat-icon> Download all
          </button>
        </div>
      </div>

      @if (downloadError) {
        <p class="download-error">{{ downloadError }}</p>
      }

    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .section-hdr { margin-bottom: 12px; }
    .section-title { font-size: 15px; font-weight: 700; color: var(--ink); margin: 0 0 4px; }
    .section-desc { font-size: 12px; color: var(--muted); margin: 0; }
    .date-tag {
      display: inline-block; margin-left: 6px; padding: 1px 8px; border-radius: 6px;
      background: rgba(97,168,255,.12); color: var(--accent); font-weight: 600;
    }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel-highlight { border-color: rgba(97,168,255,.35); }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 14px; }
    .hdr-icon { font-size: 20px; width: 20px; height: 20px; color: var(--accent); margin-top: 1px; }
    .file-meta { font-size: 12px; color: var(--muted); margin: 0 0 12px; }

    .link-bar { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 20px; }
    .link-card {
      display: flex; align-items: center; gap: 12px;
      padding: 14px 18px; border-radius: 12px;
      border: 1px solid var(--line); background: var(--panel);
      text-decoration: none; color: inherit; cursor: pointer;
      transition: border-color 200ms, box-shadow 200ms;
    }
    .link-card:hover { border-color: rgba(97,168,255,.4); box-shadow: 0 4px 16px rgba(0,0,0,.2); }
    .lc-icon { font-size: 22px; width: 22px; height: 22px; }
    .lc-ok { color: var(--ok); }
    .lc-warn { color: var(--warn); }
    .lc-title { display: block; font-size: 13px; font-weight: 700; color: var(--ink); }
    .lc-desc { display: block; font-size: 11px; color: var(--muted); margin-top: 1px; }
    .lc-arrow { margin-left: auto; color: var(--muted); font-size: 20px; width: 20px; height: 20px; }

    .download-error { color: var(--bad); font-size: 12px; margin-top: 8px; }
  `]
})
export class ReportsPageComponent implements OnInit {
  bundleSummary?: DailyBundleSummary;
  downloading = false;
  downloadError?: string;

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.refreshBundleSummary();
  }

  refreshBundleSummary(): void {
    this.api.todayAnalysisDownloadSummary().subscribe({
      next: s => this.bundleSummary = s,
      error: () => { /* summary optional */ }
    });
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  downloadToday(kind: 'signals' | 'logs' | 'chain' | 'all'): void {
    const date = this.bundleSummary?.date ?? 'today';
    const filenameByKind: Record<typeof kind, string> = {
      signals: `entry-signals-${date}.zip`,
      logs: `application-logs-${date}.zip`,
      chain: `chain-snapshots-${date}.zip`,
      all: `analysis-pack-${date}.zip`
    };
    const requestByKind = {
      signals: () => this.api.downloadTodaySignalsZip(),
      logs: () => this.api.downloadTodayLogsZip(),
      chain: () => this.api.downloadTodayChainSnapshotsZip(),
      all: () => this.api.downloadTodayAnalysisPackZip()
    } as const;

    this.downloading = true;
    this.downloadError = undefined;
    requestByKind[kind]().subscribe({
      next: blob => {
        this.saveBlob(blob, filenameByKind[kind]);
        this.downloading = false;
      },
      error: () => {
        this.downloading = false;
        this.downloadError = 'No files found for today yet, or download failed. Try again after market data has been recorded.';
      }
    });
  }

  private saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
