import { Component } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ReportArchiveResult } from '../core/models';

@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule, RouterLink],
  template: `
    <section class="page">
      <h1 class="page-title">Reports</h1>
      <p class="page-subtitle">Trade journal, signal archives, replay analysis, and signal browsing.</p>

      <!-- Quick Links to Signal Pages -->
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

      <!-- Journal & Archive -->
      <div class="grid two">
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">download</mat-icon>
            <div><h2>Trade Journal</h2><p>Download the current trade journal as CSV.</p></div>
          </div>
          <button mat-flat-button color="primary" (click)="downloadJournal()"><mat-icon>file_download</mat-icon> Download CSV</button>
        </div>
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">archive</mat-icon>
            <div><h2>Signal Archive</h2><p>Archive entry-signal report files to zip.</p></div>
          </div>
          <button mat-flat-button color="primary" (click)="archive()"><mat-icon>inventory_2</mat-icon> Archive Reports</button>
          @if (archiveResult) {
            <div class="result-strip">
              <span class="rs-item"><strong>{{ archiveResult.fileCount }}</strong> files</span>
              <span class="rs-item"><strong>{{ (archiveResult.archiveBytes / 1024) | number:'1.0-0' }}</strong> KB</span>
              <span class="rs-item rs-path">{{ archiveResult.archivePath }}</span>
            </div>
          }
        </div>
      </div>

    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .spacer { flex: 1; }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 14px; }
    .hdr-icon { font-size: 20px; width: 20px; height: 20px; color: var(--accent); margin-top: 1px; }

    /* Link bar */
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

    /* Archive result */
    .result-strip {
      display: flex; gap: 12px; flex-wrap: wrap; margin-top: 12px;
      padding: 10px 14px; border-radius: 8px;
      background: rgba(69,209,140,.06); border: 1px solid rgba(69,209,140,.2);
      font-size: 12px; color: var(--ok);
    }
    .rs-item strong { font-weight: 700; }
    .rs-path { color: var(--muted); word-break: break-all; }

    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }
  `]
})
export class ReportsPageComponent {
  archiveResult?: ReportArchiveResult;

  constructor(private readonly api: ApiService) {}

  downloadJournal(): void {
    this.api.tradeJournalCsv().subscribe(csv => {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'trade-journal.csv';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  archive(): void {
    this.api.archiveEntrySignals().subscribe(r => this.archiveResult = r);
  }
}
