import { Component, OnInit, computed, signal } from '@angular/core';
import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../core/api.service';

interface MarketContext { vix: number; pcr: number; vixStatus: string; pcrBias: string; }
interface TradeSummary { totalTrades: number; closedTrades: number; wins: number; losses: number; avgPnl: number; exitReasons: Record<string, number>; }
interface SignalSummary { totalScans: number; entries: number; rejections: number; topBlocker: string; ceBuys: number; peBuys: number; }
interface Finding { severity: 'INFO' | 'WARN' | 'ALERT'; text: string; }
interface Suggestion { parameter: string; currentValue: string; suggestedValue: string; reason: string; }
export interface AiRecommendationDto {
  id: number;
  generatedAt: string;
  runType: 'MARKET_OPEN' | 'HOURLY' | 'MANUAL';
  marketContext?: MarketContext;
  tradeSummary?: TradeSummary;
  signalSummary?: SignalSummary;
  findings?: Finding[];
  suggestions?: Suggestion[];
  overallAssessment: string;
}

@Component({
  selector: 'app-ai-insights-page',
  standalone: true,
  imports: [NgClass, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">AI Insights</h1>
          <p class="page-subtitle">Rule-based market analysis — runs every hour during market hours.</p>
        </div>
        <div class="actions">
          <button mat-stroked-button (click)="load()" [disabled]="loading()">
            <mat-icon>refresh</mat-icon> Refresh
          </button>
          <button mat-flat-button color="primary" (click)="runNow()" [disabled]="running()">
            <mat-icon>play_arrow</mat-icon> Run Now
          </button>
        </div>
      </div>

      <!-- Backtest Sync & Tune Panel -->
      <div class="tune-panel">
        <div class="tune-left">
          <mat-icon class="tune-icon">model_training</mat-icon>
          <div>
            <div class="tune-title">Backtest Sync & Tune</div>
            <div class="tune-desc">Downloads last 30 days of candle data from Zerodha and runs a parameter grid (SL × target) to find the best combination.</div>
          </div>
        </div>
        <div class="tune-actions">
          <mat-form-field appearance="outline" class="underlying-select">
            <mat-label>Underlying</mat-label>
            <mat-select [(ngModel)]="selectedUnderlying">
              @for (u of underlyings; track u) {
                <mat-option [value]="u">{{ u }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <button mat-flat-button color="accent" (click)="syncAndTune()" [disabled]="tuning()">
            <mat-icon>{{ tuning() ? 'hourglass_top' : 'auto_fix_high' }}</mat-icon>
            {{ tuning() ? 'Running…' : 'Sync & Tune' }}
          </button>
        </div>
      </div>

      @if (loading()) {
        <div class="toast info"><mat-icon>hourglass_top</mat-icon> Loading analysis…</div>
      }
      @if (running()) {
        <div class="toast info"><mat-icon>hourglass_top</mat-icon> Running analysis…</div>
      }
      @if (tuning()) {
        <div class="toast info"><mat-icon>hourglass_top</mat-icon> Downloading data & running parameter grid — this may take 2-5 minutes…</div>
      }
      @if (error()) {
        <div class="toast err"><mat-icon>error_outline</mat-icon> {{ error() }}</div>
      }

      @if (!loading() && recommendations().length === 0 && !error()) {
        <div class="empty-state">
          <mat-icon class="empty-icon">analytics</mat-icon>
          <p>No analysis runs yet today.</p>
          <p class="empty-sub">Scheduled runs happen at 9 AM and every hour until 3 PM IST (weekdays).</p>
          <button mat-flat-button color="primary" (click)="runNow()">Run First Analysis</button>
        </div>
      }

      <div class="cards">
        @for (rec of visibleRecs(); track rec.id) {
          <div class="card" [ngClass]="assessmentClass(rec.overallAssessment)">
            <!-- Card Header -->
            <div class="card-header">
              <div class="card-header-left">
                <span class="run-time">{{ rec.generatedAt | date:'h:mm a' : 'Asia/Kolkata' }}</span>
                <span class="run-badge" [ngClass]="rec.runType === 'MARKET_OPEN' ? 'badge-open' : 'badge-hourly'">
                  {{ rec.runType === 'MARKET_OPEN' ? 'Market Open' : rec.runType === 'MANUAL' ? 'Manual' : 'Hourly' }}
                </span>
              </div>
              <span class="assessment-chip" [ngClass]="assessmentChipClass(rec.overallAssessment)">
                {{ rec.overallAssessment }}
              </span>
            </div>

            <!-- Market Context -->
            <div class="context-row">
              @if ((rec.marketContext?.vix ?? 0) > 0) {
                <span class="ctx-pill" [ngClass]="vixClass(rec.marketContext!.vixStatus)">
                  VIX {{ rec.marketContext!.vix | number:'1.1-1' }} · {{ rec.marketContext!.vixStatus }}
                </span>
              }
              @if ((rec.marketContext?.pcr ?? 0) > 0) {
                <span class="ctx-pill" [ngClass]="pcrClass(rec.marketContext!.pcrBias)">
                  PCR {{ rec.marketContext!.pcr | number:'1.2-2' }} · {{ rec.marketContext!.pcrBias }}
                </span>
              }
              @if (rec.tradeSummary) {
                <span class="ctx-pill ctx-neutral">
                  Trades {{ rec.tradeSummary.wins }}W / {{ rec.tradeSummary.losses }}L
                  @if (rec.tradeSummary.avgPnl !== 0) {
                    · Avg ₹{{ rec.tradeSummary.avgPnl | number:'1.0-0' }}
                  }
                </span>
              }
              @if (rec.signalSummary) {
                <span class="ctx-pill ctx-neutral">
                  {{ rec.signalSummary.entries }} entries · {{ rec.signalSummary.rejections }} rejected
                </span>
              }
            </div>

            <!-- Findings -->
            @if ((rec.findings?.length ?? 0) > 0) {
              <div class="findings">
                @for (f of rec.findings!; track $index) {
                  <div class="finding" [ngClass]="severityClass(f.severity)">
                    <mat-icon class="f-icon">{{ severityIcon(f.severity) }}</mat-icon>
                    <span class="f-severity">{{ f.severity }}</span>
                    <span class="f-text">{{ f.text }}</span>
                  </div>
                }
              </div>
            } @else {
              <div class="no-findings">No findings this run.</div>
            }

            <!-- Suggestions -->
            @if ((rec.suggestions?.length ?? 0) > 0) {
              <div class="suggestions">
                <div class="sugg-label">Suggestions</div>
                @for (s of rec.suggestions!; track $index) {
                  <div class="suggestion">
                    <span class="sugg-param">{{ s.parameter }}</span>
                    @if (s.currentValue !== 'current' && s.suggestedValue !== 'keep' && s.suggestedValue !== 'review') {
                      <span class="sugg-arrow">{{ s.currentValue }} → {{ s.suggestedValue }}</span>
                    }
                    <span class="sugg-reason">{{ s.reason }}</span>
                  </div>
                }
              </div>
            } @else {
              <div class="suggestions">
                <div class="sugg-label">Suggestions</div>
                <div class="sugg-none">No parameter changes suggested for this run.</div>
              </div>
            }
          </div>
        }

        <!-- Pagination footer -->
        @if (recommendations().length > 0) {
          <div class="page-footer">
            <span class="page-count">Showing {{ visibleRecs().length }} of {{ recommendations().length }}</span>
            @if (visibleCount() < recommendations().length) {
              <button mat-stroked-button (click)="showMore()">
                <mat-icon>expand_more</mat-icon> Show next 5
              </button>
            }
            @if (visibleCount() > 5) {
              <button mat-stroked-button (click)="showLess()">
                <mat-icon>expand_less</mat-icon> Show less
              </button>
            }
          </div>
        }
      </div>
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 960px; }
    .top-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }

    .toast { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
    .toast.info { background: rgba(97,168,255,.06); border: 1px solid rgba(97,168,255,.2); color: var(--accent); }
    .toast.err  { background: rgba(255,95,87,.06); border: 1px solid rgba(255,95,87,.2); color: var(--bad); }
    .toast mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .tune-panel {
      display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
      background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
      padding: 16px 20px; margin-bottom: 16px;
    }
    .tune-left { display: flex; align-items: flex-start; gap: 12px; flex: 1; }
    .tune-icon { font-size: 22px; width: 22px; height: 22px; color: var(--accent); margin-top: 2px; flex-shrink: 0; }
    .tune-title { font-size: 13px; font-weight: 700; color: var(--ink); margin-bottom: 2px; }
    .tune-desc { font-size: 11px; color: var(--muted); line-height: 1.5; }
    .tune-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
    .underlying-select { width: 130px; }
    ::ng-deep .underlying-select .mat-mdc-form-field-subscript-wrapper { display: none; }

    .empty-state { text-align: center; padding: 60px 24px; color: var(--muted); }
    .empty-icon { font-size: 48px; width: 48px; height: 48px; margin-bottom: 16px; opacity: .4; }
    .empty-state p { margin: 4px 0; font-size: 14px; }
    .empty-sub { font-size: 12px; margin-bottom: 20px !important; }

    .cards { display: flex; flex-direction: column; gap: 16px; }

    .card {
      background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
      padding: 18px 20px; display: flex; flex-direction: column; gap: 12px;
    }
    .card.card-alert { border-color: rgba(255,95,87,.4); }
    .card.card-warn  { border-color: rgba(255,198,77,.4); }
    .card.card-ok    { border-color: rgba(69,209,140,.3); }

    .card-header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
    .card-header-left { display: flex; align-items: center; gap: 10px; }
    .run-time { font-size: 15px; font-weight: 700; color: var(--ink); }
    .run-badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 4px; }
    .badge-open  { background: rgba(97,168,255,.12); color: var(--accent); }
    .badge-hourly { background: rgba(128,128,128,.12); color: var(--muted); }

    .assessment-chip { font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
    .chip-alert { background: rgba(255,95,87,.15); color: var(--bad); }
    .chip-warn  { background: rgba(255,198,77,.15); color: #c89a00; }
    .chip-ok    { background: rgba(69,209,140,.15); color: var(--ok); }

    .context-row { display: flex; flex-wrap: wrap; gap: 8px; }
    .ctx-pill { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 20px; }
    .ctx-neutral { background: rgba(128,128,128,.1); color: var(--muted); }
    .ctx-alert { background: rgba(255,95,87,.1); color: var(--bad); }
    .ctx-warn  { background: rgba(255,198,77,.1); color: #c89a00; }
    .ctx-ok    { background: rgba(69,209,140,.1); color: var(--ok); }
    .ctx-info  { background: rgba(97,168,255,.1); color: var(--accent); }

    .findings { display: flex; flex-direction: column; gap: 6px; }
    .finding { display: flex; align-items: flex-start; gap: 8px; padding: 8px 10px; border-radius: 8px; font-size: 12px; }
    .finding.sev-alert { background: rgba(255,95,87,.07); color: var(--bad); }
    .finding.sev-warn  { background: rgba(255,198,77,.07); color: #96700a; }
    .finding.sev-info  { background: rgba(97,168,255,.07); color: var(--accent); }
    .f-icon { font-size: 16px; width: 16px; height: 16px; flex-shrink: 0; margin-top: 1px; }
    .f-severity { font-weight: 700; flex-shrink: 0; width: 40px; }
    .f-text { line-height: 1.4; color: var(--ink); }

    .no-findings { font-size: 12px; color: var(--muted); font-style: italic; padding: 4px 0; }

    .suggestions { border-top: 1px solid var(--line); padding-top: 10px; }
    .sugg-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); margin-bottom: 6px; }
    .suggestion { display: flex; align-items: baseline; gap: 8px; font-size: 12px; margin-bottom: 4px; flex-wrap: wrap; }
    .sugg-param { font-weight: 700; color: var(--ink); }
    .sugg-arrow { color: var(--accent); font-family: monospace; }
    .sugg-reason { color: var(--muted); flex: 1; }
    .sugg-none { font-size: 12px; color: var(--muted); }

    .page-footer { display: flex; align-items: center; gap: 12px; padding: 12px 0 4px; }
    .page-count { font-size: 12px; color: var(--muted); flex: 1; }
  `]
})
export class AiInsightsPageComponent implements OnInit {
  readonly underlyings = ['NIFTY', 'BANKNIFTY', 'SENSEX'];
  selectedUnderlying = 'NIFTY';
  recommendations = signal<AiRecommendationDto[]>([]);
  visibleCount = signal(5);
  visibleRecs = computed(() => this.recommendations().slice(0, this.visibleCount()));
  loading = signal(false);
  running = signal(false);
  tuning = signal(false);
  error = signal('');

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.visibleCount.set(5);
    this.api.getAiRecommendations().subscribe({
      next: recs => { this.recommendations.set(recs); this.loading.set(false); },
      error: () => { this.error.set('Failed to load analysis. Is the backend running?'); this.loading.set(false); }
    });
  }

  showMore(): void { this.visibleCount.update(n => n + 5); }
  showLess(): void { this.visibleCount.set(5); }

  runNow(): void {
    this.running.set(true);
    this.error.set('');
    this.api.runAiAnalysis().subscribe({
      next: rec => { this.recommendations.set([rec, ...this.recommendations()]); this.running.set(false); },
      error: () => { this.error.set('Analysis run failed.'); this.running.set(false); }
    });
  }

  syncAndTune(): void {
    this.tuning.set(true);
    this.error.set('');
    this.api.runBacktestTune(this.selectedUnderlying).subscribe({
      next: rec => { this.recommendations.set([rec, ...this.recommendations()]); this.tuning.set(false); },
      error: () => { this.error.set('Backtest tune failed. Check that Kite session is active and market data is available.'); this.tuning.set(false); }
    });
  }

  assessmentClass(a: string): string {
    if (a === 'Review needed') return 'card-alert';
    if (a === 'Caution advised') return 'card-warn';
    return 'card-ok';
  }

  assessmentChipClass(a: string): string {
    if (a === 'Review needed') return 'chip-alert';
    if (a === 'Caution advised') return 'chip-warn';
    return 'chip-ok';
  }

  severityClass(s: string): string {
    if (s === 'ALERT') return 'sev-alert';
    if (s === 'WARN') return 'sev-warn';
    return 'sev-info';
  }

  severityIcon(s: string): string {
    if (s === 'ALERT') return 'warning';
    if (s === 'WARN') return 'info';
    return 'check_circle';
  }

  vixClass(status: string): string {
    if (status === 'HIGH' || status === 'ELEVATED') return 'ctx-warn';
    if (status === 'NORMAL') return 'ctx-ok';
    return 'ctx-neutral';
  }

  pcrClass(bias: string): string {
    if (bias === 'BULLISH') return 'ctx-ok';
    if (bias === 'BEARISH') return 'ctx-alert';
    return 'ctx-neutral';
  }
}
