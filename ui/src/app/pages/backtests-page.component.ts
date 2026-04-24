import { HttpErrorResponse } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../core/api.service';
import { ApiRecord, Timeframe, UnderlyingSymbol } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-backtests-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, JsonViewComponent],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="title">Backtest</h1>
          <p class="sub">Run strategy backtests using Global Data Feeds CSV data.</p>
        </div>
      </div>

      @if (running()) { <div class="toast info"><mat-icon>hourglass_top</mat-icon> Running backtest…</div> }

      <!-- ── Run All Strategies ────────────────────────────────────── -->
      <div class="pnl">
        <h2><mat-icon class="hi">science</mat-icon> Run All Strategies</h2>
        <p class="sub" style="margin-bottom:14px">Tests Directional Buy, Scalping (EMA 9/21), and Volatility Breakout (Bollinger) across CE + PE. Generates per-strategy metrics and HTML reports.</p>
        <form [formGroup]="allForm" class="fg">
          <div class="fg-row">
            <mat-form-field appearance="outline">
              <mat-label>Underlying</mat-label>
              <mat-select formControlName="underlying">
                @for (u of underlyings; track u) { <mat-option [value]="u">{{ u }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Timeframe</mat-label>
              <mat-select formControlName="timeframe">
                @for (t of timeframes; track t) { <mat-option [value]="t">{{ t }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>From</mat-label>
              <input matInput type="date" formControlName="from">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>To</mat-label>
              <input matInput type="date" formControlName="to">
            </mat-form-field>
          </div>
          <button mat-flat-button color="primary" type="button" [disabled]="running()" (click)="runAll()">
            <mat-icon>play_arrow</mat-icon> Run All Strategies (Directional + Scalping + Volatility Breakout)
          </button>
        </form>
      </div>

      <!-- ── Run All Results ───────────────────────────────────────── -->
      @if (result && result['runs']) {
        <div class="pnl" style="margin-top:16px">
          <div class="top-row" style="margin-bottom:10px">
            <h2 style="margin:0"><mat-icon class="hi">assessment</mat-icon> Results — {{ result['totalRuns'] }} runs</h2>
            <span class="spacer"></span>
            <span class="count">Total PnL: ₹{{ result['totalPnl'] }} · {{ result['totalTrades'] }} trades</span>
            <button mat-stroked-button (click)="result = undefined"><mat-icon>close</mat-icon> Clear</button>
          </div>

          @for (run of asArray(result['runs']); track run['id'] ?? $index) {
            <div class="card" [class.card-ce]="run['optionType'] === 'CE'" [class.card-pe]="run['optionType'] === 'PE'" [class.card-no]="run['status'] === 'error'">
              <div class="card-hdr">
                <span class="badge badge-accent">{{ run['strategy'] }}</span>
                <span class="badge">{{ run['optionType'] }}</span>
                @if (run['status'] !== 'error') {
                  <span class="badge badge-ok">{{ run['totalTrades'] }} trades</span>
                  <span class="spacer"></span>
                  <strong [class.pos]="num(run['cumulativePnl']) >= 0" [class.neg]="num(run['cumulativePnl']) < 0">₹{{ run['cumulativePnl'] }}</strong>
                } @else {
                  <span class="badge badge-bad">Error</span>
                  <span class="spacer"></span>
                }
              </div>
              @if (run['status'] !== 'error') {
                <div class="card-body">
                  <div class="field-grid">
                    <div class="f"><span>Win Rate</span><strong>{{ run['winRatePercent'] }}%</strong></div>
                    <div class="f"><span>Expectancy</span><strong [class.pos]="num(run['expectancy']) >= 0" [class.neg]="num(run['expectancy']) < 0">{{ run['expectancy'] }}</strong></div>
                    <div class="f"><span>Max Drawdown</span><strong class="neg">{{ run['maxDrawdown'] }}</strong></div>
                    <div class="f"><span>Trades</span><strong>{{ run['totalTrades'] }}</strong></div>
                  </div>
                </div>
              } @else {
                <div class="card-body"><div class="reasons reasons-err"><mat-icon class="ri">error_outline</mat-icon> {{ run['message'] }}</div></div>
              }
            </div>
          }

          <app-json-view [value]="result"></app-json-view>
        </div>
      }

      <!-- ── Single Run Result (from single backtest) ──────────────── -->
      @if (result && !result['runs'] && result['totalTrades'] !== undefined) {
        <div class="pnl" style="margin-top:16px">
          <div class="top-row" style="margin-bottom:10px">
            <h2 style="margin:0"><mat-icon class="hi">assessment</mat-icon> Result</h2>
            <span class="spacer"></span>
            <button mat-stroked-button (click)="result = undefined"><mat-icon>close</mat-icon> Clear</button>
          </div>
          <div class="metric-row">
            <div class="mc"><span>Trades</span><strong>{{ result['totalTrades'] }}</strong></div>
            <div class="mc"><span>Win Rate</span><strong>{{ result['winRatePercent'] }}%</strong></div>
            <div class="mc"><span>Expectancy</span><strong [class.pos]="num(result['expectancy']) >= 0" [class.neg]="num(result['expectancy']) < 0">{{ result['expectancy'] }}</strong></div>
            <div class="mc mc-big"><span>PnL</span><strong [class.pos]="num(result['cumulativePnl']) >= 0" [class.neg]="num(result['cumulativePnl']) < 0">₹{{ result['cumulativePnl'] }}</strong></div>
            <div class="mc"><span>Drawdown</span><strong class="neg">{{ result['maxDrawdown'] }}</strong></div>
          </div>
          <app-json-view [value]="result"></app-json-view>
        </div>
      }

      <!-- ── Error result ──────────────────────────────────────────── -->
      @if (result && result['status'] === 'error' && !result['runs']) {
        <div class="pnl" style="margin-top:16px">
          <div class="err-bar"><mat-icon>error_outline</mat-icon> {{ result['message'] }}</div>
        </div>
      }
    </section>
  `,
  styles: []
})
export class BacktestsPageComponent {
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY'];
  readonly timeframes: Timeframe[] = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'DAY'];
  result?: ApiRecord;
  running = signal(false);

  readonly allForm = this.fb.nonNullable.group({
    underlying: ['NIFTY'],
    timeframe: ['FIVE_MINUTE'],
    from: [''],
    to: ['']
  });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder) {}

  num(v: unknown): number { return Number(v ?? 0); }
  asArray(v: unknown): ApiRecord[] { return Array.isArray(v) ? v as ApiRecord[] : []; }

  runAll(): void {
    this.running.set(true);
    this.result = undefined;
    this.api.runAllBacktest(this.allForm.getRawValue()).subscribe({
      next: (r: ApiRecord) => { this.result = r; this.running.set(false); },
      error: (e: unknown) => { this.handleError(e); this.running.set(false); }
    });
  }

  private handleError(e: unknown): void {
    const err = e as HttpErrorResponse;
    this.result = (err.error as ApiRecord) ?? { status: 'error', message: err.message };
  }
}
