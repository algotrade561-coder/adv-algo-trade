import { Component } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { EntrySignalReplayResult, EntrySignalReplayTrade, ReportArchiveResult } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';

@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [DecimalPipe, MatButtonModule, MatIconModule, RouterLink, DataTableComponent],
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

      <!-- Replay -->
      <div class="panel" style="margin-top:16px">
        <div class="row">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">replay</mat-icon>
            <div><h2>Entry Signal Replay</h2><p>Replay signals with current filters and sizing settings.</p></div>
          </div>
          <span class="spacer"></span>
          <button mat-flat-button color="primary" (click)="runReplay()"><mat-icon>play_arrow</mat-icon> Run Replay</button>
          <button mat-stroked-button [disabled]="!replayResult" (click)="openHtmlReport()"><mat-icon>open_in_new</mat-icon> HTML Report</button>
        </div>

        @if (replayError) { <p class="error-text">{{ replayError }}</p> }

        @if (replayResult; as r) {
          <div class="metric-grid">
            <div class="mc"><span>Evaluations</span><strong>{{ r.summary.totalEvaluations }}</strong></div>
            <div class="mc"><span>Accepted</span><strong>{{ r.summary.acceptedByFilters }}</strong></div>
            <div class="mc"><span>Executed</span><strong>{{ r.summary.executedTrades }}</strong></div>
            <div class="mc"><span>Winners</span><strong class="pos">{{ r.summary.winningTrades }}</strong></div>
            <div class="mc"><span>Losers</span><strong class="neg">{{ r.summary.losingTrades }}</strong></div>
            <div class="mc mc-big"><span>Total PnL</span><strong [class.pos]="r.summary.totalPnl >= 0" [class.neg]="r.summary.totalPnl < 0">₹{{ r.summary.totalPnl }}</strong></div>
          </div>

          <div class="detail-grid">
            <div class="dg-item"><span>Capital</span><strong>₹{{ r.totalCapital }}</strong></div>
            <div class="dg-item"><span>Risk %</span><strong>{{ r.maxRiskPerTradePercent }}%</strong></div>
            <div class="dg-item"><span>Stop / Target</span><strong>{{ r.stopLossPercent }}% / {{ r.targetPercent }}%</strong></div>
            <div class="dg-item"><span>Trailing</span><strong>{{ r.trailingStopActivationPercent }}% / {{ r.trailingGapPercent }}%</strong></div>
            <div class="dg-item"><span>Forced Exit</span><strong>{{ r.forcedExitTime }}</strong></div>
            <div class="dg-item"><span>Max Hold</span><strong>{{ r.maxHoldMinutes }} min</strong></div>
            <div class="dg-item"><span>Blocked: Base</span><strong>{{ r.summary.blockedByBaseConditions }}</strong></div>
            <div class="dg-item"><span>Blocked: Breakout</span><strong>{{ r.summary.blockedByBreakoutConfirmation }}</strong></div>
            <div class="dg-item"><span>Blocked: OI</span><strong>{{ r.summary.blockedByOiSupport }}</strong></div>
            <div class="dg-item"><span>Blocked: Headroom</span><strong>{{ r.summary.blockedByHeadroom }}</strong></div>
            <div class="dg-item"><span>Blocked: Open Trade</span><strong>{{ r.summary.blockedByOpenTrade }}</strong></div>
            <div class="dg-item"><span>Sizing Rejected</span><strong>{{ r.summary.sizingRejected }}</strong></div>
          </div>

          @if (tradeRows.length > 0) {
            <h3 style="margin:20px 0 10px; font-size:14px; color:var(--ink)">Replay Trades</h3>
            <app-data-table [rows]="tradeRows"></app-data-table>
          }
        }
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

    /* Metrics */
    .metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; margin: 18px 0; }
    .mc {
      border: 1px solid var(--line); border-radius: 10px;
      background: rgba(255,255,255,.03); padding: 14px;
    }
    .mc span { display: block; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
    .mc strong { display: block; font-size: 22px; color: var(--ink); margin-top: 4px; }
    .mc-big { border-color: rgba(97,168,255,.3); background: rgba(97,168,255,.04); }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }

    /* Detail grid */
    .detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 8px; }
    .dg-item {
      padding: 10px 12px; border-radius: 8px;
      background: rgba(255,255,255,.02); border: 1px solid var(--line);
    }
    .dg-item span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .dg-item strong { display: block; font-size: 13px; color: var(--ink); margin-top: 3px; }

    .error-text { color: #ff9a9a; margin: 14px 0 0; }
  `]
})
export class ReportsPageComponent {
  archiveResult?: ReportArchiveResult;
  replayResult?: EntrySignalReplayResult;
  replayError = '';

  constructor(private readonly api: ApiService) {}

  get tradeRows(): Array<Record<string, unknown>> {
    return (this.replayResult?.summary.trades ?? []).map(t => this.normalizeTrade(t));
  }

  downloadJournal(): void {
    this.api.tradeJournalCsv().subscribe(csv => {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'trade-journal.csv'; a.click();
      URL.revokeObjectURL(url);
    });
  }

  archive(): void {
    this.api.archiveEntrySignals().subscribe(r => this.archiveResult = r);
  }

  runReplay(): void {
    this.replayError = '';
    this.api.replayEntrySignals().subscribe({
      next: (r: EntrySignalReplayResult) => this.replayResult = r,
      error: () => this.replayError = 'Unable to generate the replay report.'
    });
  }

  openHtmlReport(): void {
    if (!this.replayResult?.htmlReportPath) return;
    window.open(`/advalgotrade/reports/entry-signals/replay/report?path=${encodeURIComponent(this.replayResult.htmlReportPath)}`, '_blank');
  }

  formatIst(v?: string): string {
    if (!v) return '-';
    return new Date(v).toLocaleString('en-IN', {
      timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    });
  }

  private normalizeTrade(t: EntrySignalReplayTrade): Record<string, unknown> {
    return {
      instrument: t.instrument, optionType: t.optionType, quantity: t.quantity,
      entryTime: this.formatIst(t.entryTime), entryPrice: t.entryPrice,
      exitTime: this.formatIst(t.exitTime), exitPrice: t.exitPrice,
      pnl: t.pnl, exitReason: t.exitReason
    };
  }
}
