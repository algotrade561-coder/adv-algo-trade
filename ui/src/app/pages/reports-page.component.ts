import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AdminService } from '../core/admin.service';
import { DailyBundleSummary } from '../core/models';

@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule
  ],
  template: `
    <section class="page">
      <h1 class="page-title">Reports</h1>
      <p class="page-subtitle">Download today's analysis files, browse signals, and generate tuning reports.</p>

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
        <a class="link-card" routerLink="/microstructure">
          <mat-icon class="lc-icon lc-info">insights</mat-icon>
          <div><span class="lc-title">Microstructure Explorer</span><span class="lc-desc">OI cadence &amp; strike timeline</span></div>
          <mat-icon class="lc-arrow">chevron_right</mat-icon>
        </a>
        @if (admin.isSuperUser()) {
          <a class="link-card" routerLink="/tuning-capture">
            <mat-icon class="lc-icon lc-info">science</mat-icon>
            <div><span class="lc-title">Tuning Capture</span><span class="lc-desc">Per-strategy capture toggles</span></div>
            <mat-icon class="lc-arrow">chevron_right</mat-icon>
          </a>
        }
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

        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">block</mat-icon>
            <div>
              <h2>Rejected signals</h2>
              <p>All NO_TRADE decisions with rejection reasons for today (from DB).</p>
            </div>
          </div>
          <button mat-flat-button color="primary" (click)="downloadToday('rejected')" [disabled]="downloading">
            <mat-icon>file_download</mat-icon> Download CSV
          </button>
        </div>
      </div>

      @if (downloadError) {
        <p class="download-error">{{ downloadError }}</p>
      }

      <!-- ── Signal tuning report ──────────────────────────────────────── -->
      <div class="section-hdr tuning-hdr">
        <h2 class="section-title">Signal tuning report</h2>
        <p class="section-desc">
          Generate an HTML tuning report over unified capture events (DuckDB on reports/tuning/events).
          Runs in-process after market close — scheduled at 15:30 IST when the scanner is idle.
        </p>
      </div>

      <div class="tuning-warn">
        <mat-icon>warning_amber</mat-icon>
        <span>
          Avoid market hours unless necessary (09:15–15:30 IST). 15-minute cool-down between jobs.
        </span>
      </div>

      <div class="panel panel-tuning">
        <div class="panel-hdr">
          <mat-icon class="hdr-icon">tune</mat-icon>
          <div>
            <h2>Generate report</h2>
            <p>Picks the date range and strategies to feed the analyzer.</p>
          </div>
        </div>

        <div class="form-row">
          <mat-form-field appearance="outline">
            <mat-label>From</mat-label>
            <input matInput type="date" [(ngModel)]="from" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>To</mat-label>
            <input matInput type="date" [(ngModel)]="to" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="wide">
            <mat-label>Strategies</mat-label>
            <input matInput [(ngModel)]="strategies" placeholder="* or OI_MOMENTUM,OI_SHIFT_TRAP" />
          </mat-form-field>
        </div>

        <mat-checkbox [(ngModel)]="force">Force during market hours</mat-checkbox>
        @if (force) {
          <mat-form-field appearance="outline" class="wide" style="display:block;margin-top:8px">
            <mat-label>Force reason (required)</mat-label>
            <input matInput [(ngModel)]="forceReason" placeholder="e.g. backfill 27 May expiry" />
          </mat-form-field>
        }

        <div class="tuning-actions">
          <button mat-flat-button color="primary" (click)="generateTuningReport()" [disabled]="tuningGenerating">
            <mat-icon>{{ tuningGenerating ? 'hourglass_top' : 'play_circle' }}</mat-icon>
            {{ tuningGenerating ? 'Running…' : 'Generate report' }}
          </button>
          <button mat-stroked-button (click)="loadJobs()">
            <mat-icon>refresh</mat-icon> Refresh jobs
          </button>
        </div>

        @if (tuningJobId) {
          <div class="job-strip">
            <span class="job-id">Job <strong>{{ tuningJobId }}</strong></span>
            <span class="job-status" [class.s-ok]="tuningJobStatus === 'COMPLETE'"
                                     [class.s-warn]="tuningJobStatus === 'QUEUED' || tuningJobStatus === 'RUNNING'"
                                     [class.s-bad]="tuningJobStatus === 'FAILED' || tuningJobStatus === 'KILLED'">
              {{ tuningJobStatus || 'QUEUED' }}
            </span>
            @if (tuningJobStatus === 'COMPLETE') {
              <a class="report-link" [routerLink]="['/tuning/reports', tuningJobId]">
                <mat-icon>open_in_new</mat-icon> Open report
              </a>
              <a class="report-link" href="javascript:void(0)" (click)="downloadReport(tuningJobId)">
                <mat-icon>download</mat-icon> Download report
              </a>
            }
          </div>
        }
        @if (tuningMessage) { <p class="toast-ok">{{ tuningMessage }}</p> }
        @if (tuningError) { <p class="download-error">{{ tuningError }}</p> }
      </div>

      <div class="section-hdr">
        <h2 class="section-title">Recent jobs</h2>
        <p class="section-desc">Most-recent 20 tuning-report runs. <strong>View</strong> opens the HTML; <strong>Download</strong> saves a single self-contained HTML with the recommended actions merged in.</p>
      </div>

      @if (jobsLoading) {
        <p class="muted"><mat-icon style="vertical-align:middle;font-size:16px;width:16px;height:16px">hourglass_top</mat-icon> Loading recent jobs…</p>
      } @else if (jobsLoadError) {
        <p class="download-error">{{ jobsLoadError }}</p>
      } @else if (jobs.length === 0) {
        <p class="muted">No tuning reports yet — generate one above.</p>
      } @else {
        <div class="panel">
          <table class="jobs-table">
            <thead>
              <tr>
                <th>Job</th>
                <th>Status</th>
                <th>Period</th>
                <th>Requested</th>
                <th>Duration</th>
                <th></th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (j of jobs; track j.jobId) {
                <tr>
                  <td class="mono">{{ shortJobId(j.jobId) }}</td>
                  <td>
                    <span class="job-status"
                          [class.s-ok]="j.status === 'COMPLETE'"
                          [class.s-warn]="j.status === 'QUEUED' || j.status === 'RUNNING'"
                          [class.s-bad]="j.status === 'FAILED' || j.status === 'KILLED'">
                      {{ j.status }}
                    </span>
                  </td>
                  <td>{{ j.fromDate }} → {{ j.toDate }}</td>
                  <td>{{ formatTs(j.requestedAt) }}</td>
                  <td>{{ j.durationSec != null ? j.durationSec + 's' : '—' }}</td>
                  <td>
                    @if (j.status === 'COMPLETE') {
                      <a class="report-link" [routerLink]="['/tuning/reports', j.jobId]">
                        <mat-icon>visibility</mat-icon> View
                      </a>
                    } @else if (j.errorMessage) {
                      <span class="err-text">{{ j.errorMessage }}</span>
                    }
                  </td>
                  <td>
                    @if (j.status === 'COMPLETE') {
                      <a class="report-link" href="javascript:void(0)"
                         [class.disabled]="downloadingReport === j.jobId" (click)="downloadReport(j.jobId)">
                        <mat-icon>download</mat-icon>
                        {{ downloadingReport === j.jobId ? 'Downloading…' : 'Download' }}
                      </a>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <div class="section-hdr" style="margin-top:34px">
        <h2 class="section-title">Edge Research</h2>
        <p class="section-desc">Automated post-close edge studies (Bonferroni multiple-testing + cross-day persistence + net-of-cost). <strong>View</strong> opens the brief; <strong>Download</strong> saves it. Runs daily ~15:35 IST.
          <a class="report-link" href="javascript:void(0)" [class.disabled]="researchRunning" (click)="runResearchNow()">
            <mat-icon>{{ researchRunning ? 'hourglass_top' : 'play_arrow' }}</mat-icon> {{ researchRunning ? 'Running…' : 'Run now' }}
          </a>
        </p>
        @if (researchStatus) {
          <p class="muted" style="margin-top:2px">
            <mat-icon style="vertical-align:middle;font-size:16px;width:16px;height:16px">info</mat-icon>
            {{ researchStatus }}
          </p>
        }
      </div>
      @if (researchLoading) {
        <p class="muted"><mat-icon style="vertical-align:middle;font-size:16px;width:16px;height:16px">hourglass_top</mat-icon> Loading research briefs…</p>
      } @else if (researchJobs.length === 0) {
        <p class="muted">No research briefs yet — the daily run produces one each evening, or click <strong>Run now</strong>.</p>
      } @else {
        <div class="panel">
          <table class="jobs-table">
            <thead>
              <tr><th>Date</th><th>Coverage</th><th>Verdict</th><th></th><th></th></tr>
            </thead>
            <tbody>
              @for (r of researchJobs; track r.date) {
                <tr>
                  <td class="mono">{{ r.date }}</td>
                  <td>{{ researchDays(r) }}d</td>
                  <td class="verdict-cell">{{ r.verdict || '—' }}</td>
                  <td>
                    @if (r.hasHtml) {
                      <a class="report-link" href="javascript:void(0)" (click)="viewResearch(r.date)">
                        <mat-icon>visibility</mat-icon> View
                      </a>
                    }
                  </td>
                  <td>
                    @if (r.hasHtml) {
                      <a class="report-link" href="javascript:void(0)" (click)="downloadResearch(r.date)">
                        <mat-icon>download</mat-icon> Download
                      </a>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (researchError) { <p class="download-error">{{ researchError }}</p> }

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
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; margin-bottom: 16px; }
    .panel-highlight { border-color: rgba(97,168,255,.35); }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 14px; }
    .hdr-icon { font-size: 20px; width: 20px; height: 20px; color: var(--accent); margin-top: 1px; }
    .file-meta { font-size: 12px; color: var(--muted); margin: 0 0 12px; }

    .link-bar { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-bottom: 20px; }
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
    .lc-info { color: var(--accent); }
    .lc-title { display: block; font-size: 13px; font-weight: 700; color: var(--ink); }
    .lc-desc { display: block; font-size: 11px; color: var(--muted); margin-top: 1px; }
    .lc-arrow { margin-left: auto; color: var(--muted); font-size: 20px; width: 20px; height: 20px; }

    .download-error { color: var(--bad); font-size: 12px; margin-top: 8px; }
    .toast-ok { color: var(--ok); font-size: 12px; margin-top: 8px; }
    .muted { color: var(--muted); font-size: 13px; }

    .tuning-hdr { margin-top: 28px; }
    .tuning-warn {
      display: flex; align-items: flex-start; gap: 10px;
      padding: 12px 16px; margin-bottom: 16px; border-radius: 10px;
      background: rgba(242,189,75,.08); border: 1px solid rgba(242,189,75,.35); color: var(--warn);
      font-size: 12px; line-height: 1.5;
    }
    .tuning-warn mat-icon { font-size: 20px; width: 20px; height: 20px; flex-shrink: 0; margin-top: 1px; }
    .panel-tuning { max-width: 760px; }
    .form-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; margin-bottom: 8px; }
    .wide { min-width: 240px; flex: 1; }
    .tuning-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 12px; }
    .report-link {
      display: inline-flex; align-items: center; gap: 6px;
      font-size: 13px; font-weight: 600; color: var(--accent); text-decoration: none;
    }
    .report-link:hover { text-decoration: underline; }
    .report-link.disabled { pointer-events: none; opacity: 0.6; }
    .report-link mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .job-strip {
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
      margin-top: 12px; padding: 10px 12px; border-radius: 8px;
      background: rgba(255,255,255,.03); border: 1px solid var(--line);
      font-size: 13px;
    }
    .job-id { color: var(--muted); }
    .job-id strong { color: var(--ink); }
    .job-status {
      padding: 2px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; letter-spacing: .03em;
      background: rgba(255,255,255,.05); color: var(--muted);
    }
    .s-ok   { background: rgba(69,209,140,.15);  color: var(--ok); }
    .s-warn { background: rgba(242,189,75,.15);  color: var(--warn); }
    .s-bad  { background: rgba(255,113,106,.15); color: var(--bad); }

    .jobs-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .jobs-table th { text-align: left; padding: 8px; border-bottom: 1px solid var(--line); font-size: 11px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .jobs-table td { padding: 10px 8px; border-bottom: 1px solid rgba(255,255,255,.04); vertical-align: middle; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .err-text { color: var(--bad); font-size: 11px; }
  `]
})
export class ReportsPageComponent implements OnInit {
  bundleSummary?: DailyBundleSummary;
  downloading = false;
  downloadError?: string;

  // Tuning report state
  from = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
  to = new Date().toISOString().slice(0, 10);
  strategies = '*';
  force = false;
  forceReason = '';
  tuningGenerating = false;
  tuningError?: string;
  tuningMessage?: string;
  tuningJobId?: string;
  tuningJobStatus = '';
  jobs: TuningJobRow[] = [];
  jobsLoading = true;     // start in loading state so the page renders the spinner, not "No jobs"
  jobsLoadError?: string;

  // ── Edge Research (separate from the tuning report) ──
  researchJobs: ResearchJobRow[] = [];
  researchLoading = true;
  researchError?: string;
  researchRunning = false;
  researchStatus?: string;
  private researchPollTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: ApiService,
              readonly admin: AdminService,
              private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.refreshBundleSummary();
    this.loadJobs();
    this.loadResearchJobs();
  }

  loadResearchJobs(): void {
    this.researchLoading = true;
    this.api.listResearchJobs(30).subscribe({
      next: list => { this.researchJobs = (list as unknown as ResearchJobRow[]) ?? []; this.researchLoading = false; this.cd.detectChanges(); },
      error: () => { this.researchLoading = false; this.cd.detectChanges(); }
    });
  }

  researchDays(r: ResearchJobRow): number {
    return r?.coverage?.days ?? 0;
  }

  viewResearch(date: string): void {
    this.researchError = undefined;
    this.api.getResearchHtml(date).subscribe({
      next: htmlStr => {
        const url = URL.createObjectURL(new Blob([htmlStr], { type: 'text/html' }));
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      },
      error: err => { this.researchError = extractApiError(err) ?? 'Failed to open brief.'; this.cd.detectChanges(); }
    });
  }

  downloadResearch(date: string): void {
    this.researchError = undefined;
    this.api.getResearchHtml(date).subscribe({
      next: htmlStr => this.saveBlob(new Blob([htmlStr], { type: 'text/html' }), `research-brief-${date}.html`),
      error: err => { this.researchError = extractApiError(err) ?? 'Failed to download brief.'; this.cd.detectChanges(); }
    });
  }

  runResearchNow(): void {
    if (this.researchRunning) { return; }
    this.researchRunning = true;
    this.researchError = undefined;
    // The orchestrator is long-running (~5–7 min); the POST returns STARTED in ms. Remember today's
    // current report timestamp so we can poll and detect when the NEW brief lands, instead of the old
    // single 4s refresh that always fired minutes too early (→ "Run now does nothing").
    const today = this.istToday();
    const prevUpdatedAt = this.researchJobs.find(r => r.date === today)?.updatedAt;
    this.api.runResearch().subscribe({
      next: () => {
        this.researchStatus = 'Research started — generating the brief (~5 min). This panel refreshes automatically.';
        this.pollForResearch(today, prevUpdatedAt, 0);
        this.cd.detectChanges();
      },
      error: err => {
        this.researchRunning = false;
        this.researchStatus = undefined;
        this.researchError = extractApiError(err) ?? 'Run failed.';
        this.cd.detectChanges();
      }
    });
  }

  /** Today's date in IST (YYYY-MM-DD) — matches the server's report-dir naming. */
  private istToday(): string {
    // en-CA gives ISO YYYY-MM-DD; force the IST zone so it matches the box's report date.
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
  }

  /** Poll the jobs list until today's brief timestamp changes (new report landed) or we time out (~8 min). */
  private pollForResearch(today: string, prevUpdatedAt: string | undefined, attempt: number): void {
    const MAX_ATTEMPTS = 32; // 32 × 15s ≈ 8 min — comfortably longer than a full run
    if (this.researchPollTimer) { clearTimeout(this.researchPollTimer); }
    this.researchPollTimer = setTimeout(() => {
      this.api.listResearchJobs(30).subscribe({
        next: list => {
          this.researchJobs = (list as unknown as ResearchJobRow[]) ?? [];
          const cur = this.researchJobs.find(r => r.date === today);
          const landed = !!cur?.updatedAt && cur.updatedAt !== prevUpdatedAt;
          if (landed) {
            this.researchRunning = false;
            this.researchStatus = 'Research brief updated — open it with View.';
          } else if (attempt + 1 >= MAX_ATTEMPTS) {
            this.researchRunning = false;
            this.researchStatus = 'Still running — it will appear shortly; refresh the page if needed.';
          } else {
            this.pollForResearch(today, prevUpdatedAt, attempt + 1);
          }
          this.cd.detectChanges();
        },
        error: () => {
          if (attempt + 1 >= MAX_ATTEMPTS) { this.researchRunning = false; }
          else { this.pollForResearch(today, prevUpdatedAt, attempt + 1); }
          this.cd.detectChanges();
        }
      });
    }, 15000);
  }

  refreshBundleSummary(): void {
    this.api.todayAnalysisDownloadSummary().subscribe({
      next: s => {
        this.bundleSummary = s;
        this.cd.detectChanges();
      },
      error: () => { /* summary optional */ }
    });
  }

  loadJobs(): void {
    this.jobsLoading = true;
    this.jobsLoadError = undefined;
    this.cd.detectChanges();
    this.api.listTuningReportJobs(20).subscribe({
      next: list => {
        this.jobsLoading = false;
        this.jobs = (list as unknown as TuningJobRow[]) ?? [];
        this.cd.detectChanges();
      },
      error: err => {
        this.jobsLoading = false;
        this.jobsLoadError = extractApiError(err) ?? 'Failed to load recent jobs.';
        this.cd.detectChanges();
      }
    });
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  formatTs(ts?: string | null): string {
    if (!ts) return '—';
    try { return new Date(ts).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' }); } catch { return ts; }
  }

  shortJobId(id?: string): string {
    if (!id) return '—';
    return id.length > 12 ? id.slice(0, 8) + '…' : id;
  }

  generateTuningReport(): void {
    if (this.force && !this.forceReason.trim()) {
      this.tuningError = 'A reason is required when forcing during market hours.';
      return;
    }
    this.tuningGenerating = true;
    this.tuningError = undefined;
    this.tuningMessage = undefined;
    this.tuningJobId = undefined;
    this.tuningJobStatus = '';
    this.api.submitTuningReport(
      this.from, this.to, this.strategies, this.force,
      this.force ? this.forceReason : undefined
    ).subscribe({
      next: ({ jobId }) => {
        this.tuningJobId = jobId;
        this.tuningJobStatus = 'QUEUED';
        this.tuningMessage = `Job ${this.shortJobId(jobId)} queued — analyzer starting.`;
        this.cd.detectChanges();
        this.pollTuningJob(jobId, 0);
        this.loadJobs();
      },
      error: err => {
        this.tuningGenerating = false;
        this.tuningError = extractApiError(err)
          ?? 'Report submission failed. Avoid market hours or wait for cool-down.';
        this.cd.detectChanges();
      }
    });
  }

  /**
   * Downloads a SINGLE self-contained HTML report for a completed job, with the ranked actions JSON merged in:
   * the report HTML gets an appended "Recommended Actions" section (pretty-printed) plus a machine-readable
   * <script type="application/json"> block. One click → one file that holds both the report and the actions.
   */
  downloadingReport?: string; // jobId currently downloading (disables the row's button)
  downloadReport(jobId: string | undefined): void {
    if (!jobId || this.downloadingReport === jobId) { return; }
    this.downloadingReport = jobId;
    this.tuningError = undefined;
    forkJoin({
      html: this.api.getTuningReportHtml(jobId),
      actions: this.api.getTuningReportActions(jobId)
    }).subscribe({
      next: ({ html, actions }) => {
        const merged = this.mergeActionsIntoHtml(html, actions);
        this.saveBlob(new Blob([merged], { type: 'text/html' }), `tuning-report-${this.shortJobId(jobId)}.html`);
        this.downloadingReport = undefined;
        this.cd.detectChanges();
      },
      error: err => {
        this.downloadingReport = undefined;
        this.tuningError = extractApiError(err) ?? 'Failed to download report.';
        this.cd.detectChanges();
      }
    });
  }

  /** Appends the actions JSON to the report HTML as a readable section + an embedded JSON block, returning a
   *  valid standalone document (wraps the body if the report is only a fragment). */
  private mergeActionsIntoHtml(html: string, actions: string): string {
    let pretty = actions;
    try { pretty = JSON.stringify(JSON.parse(actions), null, 2); } catch { /* keep raw text */ }
    const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const safeForScript = pretty.replace(/<\/script>/gi, '<\\/script>');
    const section =
      '\n<section style="margin-top:32px;padding:16px 0;border-top:2px solid #d0d7de;'
      + 'font-family:system-ui,Segoe UI,Arial,sans-serif">'
      + '<h2 style="font-size:18px;margin:0 0 8px">Recommended Actions</h2>'
      + '<pre style="white-space:pre-wrap;background:#f6f8fa;padding:12px;border-radius:6px;'
      + 'font-size:12px;line-height:1.45;overflow:auto">' + esc(pretty) + '</pre>'
      + '</section>\n'
      + '<script id="tuning-actions" type="application/json">' + safeForScript + '</script>\n';
    if (/<\/body>/i.test(html)) {
      return html.replace(/<\/body>/i, section + '</body>');
    }
    // Report is a fragment — wrap it into a minimal standalone document.
    return '<!DOCTYPE html><html><head><meta charset="utf-8"><title>Tuning Report '
      + this.shortJobId(this.tuningJobId ?? '') + '</title></head><body>' + html + section + '</body></html>';
  }

  private pollTuningJob(jobId: string, attempt: number): void {
    if (attempt > 200) {
      this.tuningGenerating = false;
      this.tuningError = 'Timed out — check the Recent jobs table for the final status.';
      this.cd.detectChanges();
      return;
    }
    this.api.getTuningReportJob(jobId).subscribe({
      next: j => {
        this.tuningJobStatus = String(j['status'] ?? '');
        if (this.tuningJobStatus === 'COMPLETE') {
          this.tuningGenerating = false;
          this.tuningMessage = 'Report ready — open it from the job strip or the table.';
          this.cd.detectChanges();
          this.loadJobs();
          return;
        }
        if (this.tuningJobStatus === 'FAILED' || this.tuningJobStatus === 'KILLED') {
          this.tuningGenerating = false;
          this.tuningError = String(j['errorMessage'] ?? `Job ${this.tuningJobStatus}`);
          this.cd.detectChanges();
          this.loadJobs();
          return;
        }
        this.cd.detectChanges();
        setTimeout(() => this.pollTuningJob(jobId, attempt + 1), 3000);
      },
      error: err => {
        this.tuningGenerating = false;
        this.tuningError = extractApiError(err) ?? 'Failed to poll job status';
        this.cd.detectChanges();
      }
    });
  }

  downloadToday(kind: 'signals' | 'logs' | 'chain' | 'all' | 'rejected'): void {
    const date = this.bundleSummary?.date ?? 'today';
    const filenameByKind: Record<typeof kind, string> = {
      signals: `entry-signals-${date}.zip`,
      logs: `application-logs-${date}.zip`,
      chain: `chain-snapshots-${date}.zip`,
      all: `analysis-pack-${date}.zip`,
      rejected: `rejected-signals-${date}.csv`
    };
    const requestByKind = {
      signals: () => this.api.downloadTodaySignalsZip(),
      logs: () => this.api.downloadTodayLogsZip(),
      chain: () => this.api.downloadTodayChainSnapshotsZip(),
      all: () => this.api.downloadTodayAnalysisPackZip(),
      rejected: () => this.api.downloadTodayRejectedSignalsCsv()
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

interface TuningJobRow {
  jobId: string;
  status: string;
  fromDate: string;
  toDate: string;
  requestedAt?: string;
  durationSec?: number;
  errorMessage?: string;
}

interface ResearchJobRow {
  jobId: string;
  date: string;
  hasHtml?: boolean;
  verdict?: string;
  coverage?: { days?: number; from?: string; to?: string; combos?: number };
  counts?: { candidateEdge?: number; persisting?: number; rawSig?: number; bonfSig?: number };
  generatedAt?: string;
  updatedAt?: string;
  error?: string;
}

function extractApiError(err: { error?: unknown; message?: string }): string | undefined {
  const body = err?.error;
  if (typeof body === 'string' && body.trim()) {
    return body.trim();
  }
  if (body && typeof body === 'object') {
    const o = body as Record<string, unknown>;
    if (o['message']) {
      return String(o['message']);
    }
    if (o['error'] === 'MARKET_HOURS' && o['nextEligible']) {
      return `Blocked during market hours. Next eligible: ${o['nextEligible']}`;
    }
    if (o['error'] === 'COOLDOWN') {
      return `Cool-down — retry in ${o['remainingSeconds']}s`;
    }
  }
  return err?.message?.trim() || undefined;
}
