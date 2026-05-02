import { Component, OnInit, signal } from '@angular/core';
import { DecimalPipe, NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../core/api.service';

@Component({
  selector: 'app-ml-scorecard-page',
  standalone: true,
  imports: [NgClass, DecimalPipe, FormsModule,
    MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">ML Scorecard</h1>
          <p class="page-subtitle">Shadow comparison — ML scores every signal without affecting decisions.</p>
        </div>
        <div class="actions">
          <mat-form-field appearance="outline" class="period-select">
            <mat-label>Period</mat-label>
            <mat-select [(ngModel)]="selectedPeriod" (selectionChange)="load()">
              <mat-option value="TODAY">Today</mat-option>
              <mat-option value="7D">Last 7 Days</mat-option>
              <mat-option value="30D">Last 30 Days</mat-option>
              <mat-option value="ALL">All Time</mat-option>
            </mat-select>
          </mat-form-field>
          <button mat-stroked-button (click)="load()" [disabled]="loading()">
            <mat-icon>refresh</mat-icon> Refresh
          </button>
        </div>
      </div>

      <!-- Tab Selector -->
      <div class="tab-bar">
        <button class="tab-btn" [class.tab-active]="activeTab === 'entry'" (click)="activeTab = 'entry'">
          <mat-icon>login</mat-icon> Entry Shadow
        </button>
        <button class="tab-btn" [class.tab-active]="activeTab === 'exit'" (click)="activeTab = 'exit'">
          <mat-icon>exit_to_app</mat-icon> Exit Shadow
        </button>
        <button class="tab-btn" [class.tab-active]="activeTab === 'virtual'" (click)="activeTab = 'virtual'">
          <mat-icon>science</mat-icon> Virtual Trades
        </button>
      </div>

      <!-- Model Status (always visible) -->
      @if (data()) {
        <div class="model-status" [ngClass]="data()!.modelStatus?.modelLoaded ? 'status-ok' : 'status-warn'">
          <mat-icon>{{ data()!.modelStatus?.modelLoaded ? 'check_circle' : 'warning' }}</mat-icon>
          <span>
            Model: {{ data()!.modelStatus?.modelLoaded ? 'Loaded' : 'Not loaded' }}
            @if (data()!.modelStatus?.treeCount) {
              · {{ data()!.modelStatus.treeCount }} trees
            }
            · Shadow observation mode
          </span>
          <button mat-stroked-button class="reload-btn" (click)="reloadModel()">
            <mat-icon>sync</mat-icon> Reload Model
          </button>
          <button mat-stroked-button class="reload-btn" (click)="generateTrainingData()">
            <mat-icon>model_training</mat-icon> Generate Training Data
          </button>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════ -->
      <!-- TAB: Entry Shadow                                              -->
      <!-- ═══════════════════════════════════════════════════════════════ -->
      @if (activeTab === 'entry' && data()) {
        <div class="filter-bar">
          <mat-form-field appearance="outline" class="period-select">
            <mat-label>Filter</mat-label>
            <mat-select [(ngModel)]="selectedFilter" (selectionChange)="applyFilter()">
              <mat-option value="ALL">All Signals</mat-option>
              <mat-option value="BOTH_ENTER">Both ENTER</mat-option>
              <mat-option value="ML_ENTER">ML ENTER · System SKIP</mat-option>
              <mat-option value="SYS_ENTER">System ENTER · ML SKIP</mat-option>
              <mat-option value="BOTH_SKIP">Both SKIP</mat-option>
              <mat-option value="DISAGREE">All Disagreements</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
        <div class="summary-grid">
          <div class="card">
            <div class="card-label">Total Signals Scored</div>
            <div class="card-value">{{ data()!.totalSignals | number }}</div>
          </div>
          <div class="card">
            <div class="card-label">Decisions Agree</div>
            <div class="card-value agree">{{ data()!.decisionsAgree | number }}</div>
            <div class="card-sub">{{ agreePct() }}%</div>
          </div>
          <div class="card">
            <div class="card-label">Decisions Disagree</div>
            <div class="card-value disagree">{{ data()!.decisionsDisagree | number }}</div>
            <div class="card-sub">{{ disagreePct() }}%</div>
          </div>
          <div class="card" [ngClass]="data()!.outcomesKnown > 0 ? '' : 'card-muted'">
            <div class="card-label">Outcomes Known</div>
            <div class="card-value">{{ data()!.outcomesKnown | number }}</div>
          </div>
        </div>

        <!-- 4-Category Agreement Breakdown -->
        <h2 class="section-title">Decision Agreement Breakdown</h2>
        <div class="category-grid">
          <div class="cat-card cat-both-enter">
            <div class="cat-icon">✅✅</div>
            <div class="cat-count">{{ data()!.bothEnter | number }}</div>
            <div class="cat-label">Both ENTER</div>
            <div class="cat-desc">System and ML both say trade — strongest conviction</div>
          </div>
          <div class="cat-card cat-ml-only">
            <div class="cat-icon">🤖✅</div>
            <div class="cat-count">{{ data()!.mlEnterSystemSkip | number }}</div>
            <div class="cat-label">ML ENTER · System SKIP</div>
            <div class="cat-desc">ML sees opportunity the system missed</div>
          </div>
          <div class="cat-card cat-sys-only">
            <div class="cat-icon">⚙️✅</div>
            <div class="cat-count">{{ data()!.systemEnterMlSkip | number }}</div>
            <div class="cat-label">System ENTER · ML SKIP</div>
            <div class="cat-desc">ML thinks the system is wrong</div>
          </div>
          <div class="cat-card cat-both-skip">
            <div class="cat-icon">⏸️⏸️</div>
            <div class="cat-count">{{ data()!.bothSkip | number }}</div>
            <div class="cat-label">Both SKIP</div>
            <div class="cat-desc">Both agree to stay out</div>
          </div>
        </div>

        <!-- Accuracy Comparison (only when outcomes are known) -->
        @if (data()!.outcomesKnown > 0) {
          <div class="accuracy-section">
            <h2 class="section-title">Accuracy Comparison</h2>
            <div class="accuracy-grid">
              <div class="accuracy-card">
                <div class="accuracy-label">System Accuracy</div>
                <div class="accuracy-bar">
                  <div class="bar-fill system-bar" [style.width.%]="data()!.systemAccuracyPercent"></div>
                </div>
                <div class="accuracy-value">{{ data()!.systemAccuracyPercent | number:'1.1-1' }}%</div>
                <div class="accuracy-detail">{{ data()!.systemCorrect }}/{{ data()!.outcomesKnown }} correct</div>
              </div>
              <div class="accuracy-card">
                <div class="accuracy-label">ML Accuracy</div>
                <div class="accuracy-bar">
                  <div class="bar-fill ml-bar" [style.width.%]="data()!.mlAccuracyPercent"></div>
                </div>
                <div class="accuracy-value">{{ data()!.mlAccuracyPercent | number:'1.1-1' }}%</div>
                <div class="accuracy-detail">{{ data()!.mlCorrect }}/{{ data()!.outcomesKnown }} correct</div>
              </div>
            </div>
          </div>
        }

        <!-- Signal Comparison Table -->
        @if (data()!.recentSignals?.length) {
          <h2 class="section-title">Recent Signals (System vs ML) · {{ filteredSignals().length }} total · Page {{ currentPage + 1 }}/{{ totalPages() }}</h2>
          <div class="filter-row">
            <input class="filter-input" placeholder="Search underlying..." [(ngModel)]="filterUnderlying" (input)="resetPage()">
            <input class="filter-input" placeholder="Search instrument..." [(ngModel)]="filterInstrument" (input)="resetPage()">
            <input class="filter-input sm" placeholder="Strategy..." [(ngModel)]="filterStrategy" (input)="resetPage()">
            <div class="page-btns">
              <button mat-icon-button [disabled]="currentPage === 0" (click)="currentPage = currentPage - 1"><mat-icon>chevron_left</mat-icon></button>
              <span class="page-label">{{ currentPage + 1 }} / {{ totalPages() }}</span>
              <button mat-icon-button [disabled]="currentPage >= totalPages() - 1" (click)="currentPage = currentPage + 1"><mat-icon>chevron_right</mat-icon></button>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Strategy</th>
                  <th>Instrument</th>
                  <th>Underlying</th>
                  <th>Type</th>
                  <th>Premium</th>
                  <th>System Score</th>
                  <th>ML Score</th>
                  <th>ML Prob</th>
                  <th>System</th>
                  <th>ML Would</th>
                  <th>Agree?</th>
                  <th>Outcome</th>
                  <th>P&L</th>
                </tr>
              </thead>
              <tbody>
                @for (row of pagedSignals(); track $index) {
                  <tr [ngClass]="rowClass(row)">
                    <td class="mono">{{ formatTime(row['timestamp']) }}</td>
                    <td>{{ row['strategyType'] }}</td>
                    <td class="inst-cell">{{ row['instrumentKey'] }}</td>
                    <td>{{ row['underlying'] }}</td>
                    <td>{{ row['optionType'] }}</td>
                    <td class="mono">{{ row['optionPremium'] }}</td>
                    <td class="mono">{{ row['systemScore'] }}</td>
                    <td class="mono" [ngClass]="scoreClass(row)">{{ row['mlScore'] }}</td>
                    <td class="mono">{{ row['mlProbability'] }}</td>
                    <td [ngClass]="row['systemDecision'] === 'ENTER' ? 'enter' : 'skip'">
                      {{ row['systemDecision'] }}
                    </td>
                    <td [ngClass]="row['mlWouldDecide'] === 'ENTER' ? 'enter' : 'skip'">
                      {{ row['mlWouldDecide'] }}
                    </td>
                    <td>
                      @if (row['decisionsAgree'] === 'true') {
                        <mat-icon class="agree-icon">check</mat-icon>
                      } @else {
                        <mat-icon class="disagree-icon">close</mat-icon>
                      }
                    </td>
                    <td [ngClass]="outcomeClass(row)">{{ row['tradeOutcome'] || '—' }}</td>
                    <td class="mono" [ngClass]="pnlClass(row)">{{ row['tradePnl'] || '—' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty-state">
            <mat-icon>analytics</mat-icon>
            <p>No ML shadow data yet. Signals will appear here as the system evaluates them during market hours.</p>
          </div>
        }
      }

      @if (activeTab === 'virtual') {
      @if (vtData()) {
        <h2 class="section-title">
          <mat-icon class="vt-icon">science</mat-icon>
          ML Virtual Trades — What If ML Decided?
        </h2>
        <p class="vt-desc">Signals where ML said ENTER but system said SKIP. Tracked with real prices, same SL/target/trailing rules.</p>

        <div class="summary-grid">
          <div class="card">
            <div class="card-label">Open Virtual Trades</div>
            <div class="card-value">{{ vtData()!.openTrades }}</div>
          </div>
          <div class="card">
            <div class="card-label">Completed</div>
            <div class="card-value">{{ vtData()!.completedTrades }}</div>
          </div>
          <div class="card">
            <div class="card-label">Profitable</div>
            <div class="card-value agree">{{ vtData()!.profitable }}</div>
          </div>
          <div class="card">
            <div class="card-label">Losses</div>
            <div class="card-value disagree">{{ vtData()!.losses }}</div>
          </div>
          <div class="card">
            <div class="card-label">Win Rate</div>
            <div class="card-value" [ngClass]="vtData()!.winRatePercent >= 50 ? 'agree' : 'disagree'">
              {{ vtData()!.winRatePercent | number:'1.1-1' }}%
            </div>
          </div>
          <div class="card">
            <div class="card-label">Total P&L</div>
            <div class="card-value" [ngClass]="vtData()!.totalPnlPercent >= 0 ? 'agree' : 'disagree'">
              {{ vtData()!.totalPnlPercent | number:'1.1-1' }}%
            </div>
            <div class="card-sub">Avg: {{ vtData()!.avgPnlPercent | number:'1.1-1' }}% per trade</div>
          </div>
        </div>

        <!-- Open Virtual Trades -->
        @if (vtData()!.openTradesList?.length) {
          <h3 class="subsection-title">Open (Live Tracking)</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Entry Time</th>
                  <th>Strategy</th>
                  <th>Instrument</th>
                  <th>Entry ₹</th>
                  <th>Current ₹</th>
                  <th>Peak ₹</th>
                  <th>P&L %</th>
                  <th>P&L ₹</th>
                  <th>Sys Score</th>
                  <th>ML Score</th>
                  <th>Hold</th>
                </tr>
              </thead>
              <tbody>
                @for (t of vtData()!.openTradesList; track t['virtualTradeId']) {
                  <tr>
                    <td class="mono">{{ formatTime(t['entryTime']) }}</td>
                    <td><span class="badge badge-strat">{{ t['strategyType'] }}</span></td>
                    <td>{{ t['instrumentKey'] }}</td>
                    <td class="mono">{{ t['entryPrice'] }}</td>
                    <td class="mono">{{ t['currentPrice'] }}</td>
                    <td class="mono">{{ t['peakPrice'] }}</td>
                    <td class="mono" [ngClass]="pnlClass(t)">{{ t['profitPercent'] }}%</td>
                    <td class="mono" [ngClass]="pnlClass(t)">₹{{ t['pnlAmount'] }}</td>
                    <td class="mono">{{ t['systemScore'] }}</td>
                    <td class="mono score-higher">{{ t['mlScore'] }}</td>
                    <td class="mono">{{ t['holdMinutes'] }}m</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <!-- Completed Virtual Trades -->
        @if (vtData()!.completedTradesList?.length) {
          <h3 class="subsection-title">Completed</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Entry Time</th>
                  <th>Strategy</th>
                  <th>Instrument</th>
                  <th>Entry ₹</th>
                  <th>Exit ₹</th>
                  <th>P&L %</th>
                  <th>P&L ₹</th>
                  <th>Exit Reason</th>
                  <th>Sys Score</th>
                  <th>ML Score</th>
                  <th>Hold</th>
                  <th>Result</th>
                </tr>
              </thead>
              <tbody>
                @for (t of vtData()!.completedTradesList; track t['virtualTradeId']) {
                  <tr [ngClass]="t['status'] === 'PROFIT' ? 'row-profit' : 'row-loss'">
                    <td class="mono">{{ formatTime(t['entryTime']) }}</td>
                    <td><span class="badge badge-strat">{{ t['strategyType'] }}</span></td>
                    <td>{{ t['instrumentKey'] }}</td>
                    <td class="mono">{{ t['entryPrice'] }}</td>
                    <td class="mono">{{ t['exitPrice'] }}</td>
                    <td class="mono" [ngClass]="pnlClass(t)">{{ t['profitPercent'] }}%</td>
                    <td class="mono" [ngClass]="pnlClass(t)">₹{{ t['pnlAmount'] }}</td>
                    <td>{{ t['exitReason'] }}</td>
                    <td class="mono">{{ t['systemScore'] }}</td>
                    <td class="mono score-higher">{{ t['mlScore'] }}</td>
                    <td class="mono">{{ t['holdMinutes'] }}m</td>
                    <td [ngClass]="t['status'] === 'PROFIT' ? 'outcome-profit' : 'outcome-loss'">
                      {{ t['status'] }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (!vtData()!.openTradesList?.length && !vtData()!.completedTradesList?.length) {
          <div class="empty-state">
            <mat-icon>science</mat-icon>
            <p>No virtual trades yet. When ML disagrees with the system (ML says enter, system skips), virtual trades will appear here.</p>
          </div>
        }
      }
      }

      <!-- ═══════════════════════════════════════════════════════════════ -->
      <!-- TAB: Exit Shadow                                               -->
      <!-- ═══════════════════════════════════════════════════════════════ -->
      @if (activeTab === 'exit') {

      <!-- ML Exit Shadow — Exit Evaluation Observations -->
      <h2 class="section-title">
        <mat-icon class="vt-icon">exit_to_app</mat-icon>
        ML Exit Shadow — Exit Evaluation Observations
      </h2>
      <p class="vt-desc">Every exit evaluation is recorded: what the system decided (exit or hold) and the market state at that moment.</p>

      @if (exitData()) {
        <div class="summary-grid">
          <div class="card">
            <div class="card-label">Total Evaluations</div>
            <div class="card-value">{{ exitData()!.totalEvaluations | number }}</div>
          </div>
          <div class="card">
            <div class="card-label">Exit Decisions</div>
            <div class="card-value disagree">{{ exitData()!.exitDecisions | number }}</div>
          </div>
          <div class="card">
            <div class="card-label">Hold Decisions</div>
            <div class="card-value agree">{{ exitData()!.holdDecisions | number }}</div>
          </div>
          <div class="card">
            <div class="card-label">Exit Rate</div>
            <div class="card-value">{{ exitData()!.exitRatePercent | number:'1.1-1' }}%</div>
          </div>
          <div class="card">
            <div class="card-label">Avg Hold at Exit</div>
            <div class="card-value">{{ exitData()!.avgHoldMinutesAtExit | number:'1.0-0' }}m</div>
          </div>
          <div class="card">
            <div class="card-label">Avg Profit at Exit</div>
            <div class="card-value" [ngClass]="exitData()!.avgProfitAtExit >= 0 ? 'agree' : 'disagree'">{{ exitData()!.avgProfitAtExit | number:'1.1-1' }}%</div>
          </div>
          <div class="card">
            <div class="card-label">Avg Drawdown at Exit</div>
            <div class="card-value">{{ exitData()!.avgDrawdownAtExit | number:'1.1-1' }}%</div>
            <div class="card-sub">From peak profit to exit point</div>
          </div>
        </div>

        @if (exitData()!.exitReasonCounts && objectKeys(exitData()!.exitReasonCounts).length) {
          <h3 class="subsection-title">Exit Reason Breakdown</h3>
          <div class="summary-grid">
            @for (reason of objectKeys(exitData()!.exitReasonCounts); track reason) {
              <div class="card">
                <div class="card-label">{{ reason }}</div>
                <div class="card-value">{{ exitData()!.exitReasonCounts[reason] }}</div>
              </div>
            }
          </div>
        }

        @if (exitData()!.recentEvaluations?.length) {
          <h3 class="subsection-title">Recent Exit Evaluations</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Trade</th>
                  <th>Strategy</th>
                  <th>Profit %</th>
                  <th>Peak %</th>
                  <th>Drawdown</th>
                  <th>Hold Min</th>
                  <th>VIX</th>
                  <th>IV Change %</th>
                  <th>Trail Active</th>
                  <th>Decision</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                @for (row of exitData()!.recentEvaluations.slice(0, 50); track $index) {
                  <tr [ngClass]="row['systemDecision'] === 'EXIT' ? 'row-loss' : ''">
                    <td class="mono">{{ formatTime(row['timestamp']) }}</td>
                    <td class="mono inst-cell">{{ row['tradeId'] }}</td>
                    <td>{{ row['strategyType'] }}</td>
                    <td class="mono" [ngClass]="parseFloat(row['profitPercent']) >= 0 ? 'pnl-positive' : 'pnl-negative'">{{ row['profitPercent'] }}</td>
                    <td class="mono">{{ row['peakProfitPercent'] }}</td>
                    <td class="mono">{{ row['drawdownFromPeak'] }}</td>
                    <td class="mono">{{ row['holdMinutes'] }}</td>
                    <td class="mono">{{ row['vixLevel'] }}</td>
                    <td class="mono">{{ row['ivChangePercent'] }}</td>
                    <td>{{ row['trailingStopActive'] === '1.0000' ? '✅' : '—' }}</td>
                    <td [ngClass]="row['systemDecision'] === 'EXIT' ? 'disagree' : 'agree'">{{ row['systemDecision'] }}</td>
                    <td>{{ row['exitReason'] }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty-state">
            <mat-icon>exit_to_app</mat-icon>
            <p>No exit evaluations yet. Data will appear here as the exit monitor evaluates open trades during market hours.</p>
          </div>
        }
      } @else {
        <div class="empty-state">
          <mat-icon>exit_to_app</mat-icon>
          <p>Loading exit shadow data…</p>
        </div>
      }
      }

      @if (error()) {
        <div class="toast error"><mat-icon>error</mat-icon> {{ error() }}</div>
      }
      @if (actionMsg()) {
        <div class="toast info"><mat-icon>info</mat-icon> {{ actionMsg() }}</div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1400px; margin: 0 auto; }
    .top-row { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 16px; margin-bottom: 24px; }
    .page-title { margin: 0; font-size: 24px; font-weight: 500; }
    .page-subtitle { margin: 4px 0 0; color: #888; font-size: 14px; }
    .tab-bar { display: flex; gap: 4px; margin-bottom: 20px; border-bottom: 2px solid #2a2a3a; padding-bottom: 0; }
    .tab-btn { background: none; border: none; color: #888; font-size: 14px; font-weight: 500; padding: 10px 20px; cursor: pointer; display: flex; align-items: center; gap: 6px; border-bottom: 2px solid transparent; margin-bottom: -2px; transition: all 0.2s; }
    .tab-btn:hover { color: #ccc; background: rgba(255,255,255,0.03); }
    .tab-btn.tab-active { color: #90caf9; border-bottom-color: #90caf9; }
    .tab-btn mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .filter-bar { margin-bottom: 16px; }
    .actions { display: flex; gap: 8px; align-items: center; }
    .period-select { width: 140px; }
    ::ng-deep .period-select .mat-mdc-form-field-subscript-wrapper { display: none; }

    .model-status { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-radius: 8px; margin-bottom: 20px; font-size: 14px; }
    .status-ok { background: #1b3a1b; color: #4caf50; }
    .status-warn { background: #3a2e1b; color: #ff9800; }
    .reload-btn { margin-left: auto; font-size: 12px; }

    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 24px; }
    .card { background: #1e1e2e; border-radius: 8px; padding: 16px; text-align: center; }
    .card-muted { opacity: 0.5; }
    .card-label { font-size: 12px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
    .card-value { font-size: 28px; font-weight: 600; margin: 4px 0; }
    .card-sub { font-size: 12px; color: #aaa; }
    .agree { color: #4caf50; }
    .disagree { color: #ff9800; }

    .section-title { font-size: 16px; font-weight: 500; margin: 24px 0 12px; }

    .category-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 24px; }
    .cat-card { background: #1e1e2e; border-radius: 8px; padding: 16px; text-align: center; border-left: 3px solid transparent; }
    .cat-both-enter { border-left-color: #4caf50; }
    .cat-ml-only { border-left-color: #ab47bc; }
    .cat-sys-only { border-left-color: #42a5f5; }
    .cat-both-skip { border-left-color: #666; }
    .cat-icon { font-size: 20px; margin-bottom: 4px; }
    .cat-count { font-size: 28px; font-weight: 600; }
    .cat-label { font-size: 12px; font-weight: 500; color: #ccc; margin: 4px 0; }
    .cat-desc { font-size: 11px; color: #888; }
    @media (max-width: 768px) { .category-grid { grid-template-columns: repeat(2, 1fr); } }

    .accuracy-section { margin-bottom: 24px; }
    .accuracy-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .accuracy-card { background: #1e1e2e; border-radius: 8px; padding: 20px; }
    .accuracy-label { font-size: 14px; color: #aaa; margin-bottom: 8px; }
    .accuracy-bar { height: 8px; background: #333; border-radius: 4px; overflow: hidden; margin-bottom: 8px; }
    .bar-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
    .system-bar { background: #42a5f5; }
    .ml-bar { background: #ab47bc; }
    .accuracy-value { font-size: 24px; font-weight: 600; }
    .accuracy-detail { font-size: 12px; color: #888; }

    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; padding: 8px 10px; border-bottom: 2px solid #333; color: #aaa; font-weight: 500; white-space: nowrap; }
    td { padding: 6px 10px; border-bottom: 1px solid #2a2a3a; white-space: nowrap; }
    .mono { font-family: 'JetBrains Mono', monospace; }
    .inst-cell { font-size: 11px; max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
    .enter { color: #4caf50; font-weight: 500; }
    .skip { color: #888; }
    .agree-icon { color: #4caf50; font-size: 18px; }
    .disagree-icon { color: #ff9800; font-size: 18px; }
    .score-higher { color: #4caf50; }
    .score-lower { color: #ef5350; }
    .outcome-profit { color: #4caf50; }
    .outcome-loss { color: #ef5350; }
    .pnl-positive { color: #4caf50; }
    .pnl-negative { color: #ef5350; }
    tr.row-disagree { background: rgba(255, 152, 0, 0.05); }

    .empty-state { text-align: center; padding: 60px 20px; color: #666; }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; margin-bottom: 12px; }

    .filter-row { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
    .filter-input { background: #1e1e2e; border: 1px solid #333; border-radius: 6px; padding: 6px 10px; color: #ccc; font-size: 12px; width: 160px; outline: none; }
    .filter-input.sm { width: 120px; }
    .filter-input:focus { border-color: #555; }
    .page-btns { display: flex; align-items: center; gap: 4px; margin-left: auto; }
    .page-label { font-size: 12px; color: #888; min-width: 60px; text-align: center; }

    .toast { padding: 12px 16px; border-radius: 8px; margin-top: 12px; display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .toast.info { background: #1a237e; color: #90caf9; }
    .toast.error { background: #3e1111; color: #ef9a9a; }

    .vt-icon { vertical-align: middle; margin-right: 4px; }
    .vt-desc { color: #888; font-size: 13px; margin: -8px 0 16px; }
    .subsection-title { font-size: 14px; font-weight: 500; margin: 16px 0 8px; color: #aaa; }
    tr.row-profit { background: rgba(76, 175, 80, 0.05); }
    tr.row-loss { background: rgba(239, 83, 80, 0.05); }
  `]
})
export class MlScorecardPageComponent implements OnInit {
  data = signal<any>(null);
  vtData = signal<any>(null);
  exitData = signal<any>(null);
  loading = signal(false);
  error = signal('');
  actionMsg = signal('');
  selectedPeriod = 'TODAY';
  selectedFilter = 'ALL';
  activeTab = 'entry';

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.mlShadow(this.selectedPeriod).subscribe({
      next: d => { this.data.set(d); this.loading.set(false); },
      error: () => { this.error.set('Failed to load ML data'); this.loading.set(false); }
    });
    this.api.mlVirtualTrades().subscribe({
      next: d => this.vtData.set(d),
      error: () => {} // silent — virtual trades are optional
    });
    this.api.mlExitShadow(this.selectedPeriod).subscribe({
      next: d => this.exitData.set(d),
      error: () => {} // silent — exit shadow is optional
    });
  }

  // Text filters
  filterUnderlying = '';
  filterInstrument = '';
  filterStrategy = '';
  currentPage = 0;
  pageSize = 25;

  resetPage(): void { this.currentPage = 0; }

  filteredSignals(): any[] {
    const d = this.data();
    if (!d?.recentSignals) return [];
    let rows = d.recentSignals;

    // Category filter
    if (this.selectedFilter !== 'ALL') {
      rows = rows.filter((row: any) => {
        const sys = row['systemDecision'] === 'ENTER';
        const ml = row['mlWouldDecide'] === 'ENTER';
        switch (this.selectedFilter) {
          case 'BOTH_ENTER': return sys && ml;
          case 'ML_ENTER': return ml && !sys;
          case 'SYS_ENTER': return sys && !ml;
          case 'BOTH_SKIP': return !sys && !ml;
          case 'DISAGREE': return sys !== ml;
          default: return true;
        }
      });
    }

    // Text filters
    if (this.filterUnderlying) {
      const q = this.filterUnderlying.toUpperCase();
      rows = rows.filter((r: any) => String(r['underlying'] ?? '').toUpperCase().includes(q));
    }
    if (this.filterInstrument) {
      const q = this.filterInstrument.toUpperCase();
      rows = rows.filter((r: any) => String(r['instrumentKey'] ?? '').toUpperCase().includes(q));
    }
    if (this.filterStrategy) {
      const q = this.filterStrategy.toUpperCase();
      rows = rows.filter((r: any) => String(r['strategyType'] ?? '').toUpperCase().includes(q));
    }

    return rows;
  }

  totalPages(): number {
    return Math.max(1, Math.ceil(this.filteredSignals().length / this.pageSize));
  }

  pagedSignals(): any[] {
    const all = this.filteredSignals();
    const start = this.currentPage * this.pageSize;
    return all.slice(start, start + this.pageSize);
  }

  applyFilter(): void { this.resetPage(); }

  reloadModel(): void {
    this.api.mlReload().subscribe({
      next: r => { this.actionMsg.set('Model reloaded: ' + (r.success ? 'OK' : 'Failed')); this.load(); },
      error: () => this.actionMsg.set('Reload failed')
    });
  }

  generateTrainingData(): void {
    this.actionMsg.set('Generating training data…');
    this.api.mlGenerateTrainingData().subscribe({
      next: r => this.actionMsg.set(`Training data: ${r.positiveExamples} positive, ${r.negativeExamples} negative, ${r.unlabeled} unlabeled`),
      error: () => this.actionMsg.set('Training data generation failed')
    });
  }

  agreePct(): string {
    const d = this.data();
    if (!d || d.totalSignals === 0) return '0';
    return (d.decisionsAgree / d.totalSignals * 100).toFixed(1);
  }

  disagreePct(): string {
    const d = this.data();
    if (!d || d.totalSignals === 0) return '0';
    return (d.decisionsDisagree / d.totalSignals * 100).toFixed(1);
  }

  formatTime(ts: string): string {
    if (!ts) return '';
    try { return new Date(ts).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
    catch { return ts; }
  }

  rowClass(row: any): string {
    return row['decisionsAgree'] === 'false' ? 'row-disagree' : '';
  }

  scoreClass(row: any): string {
    const sys = parseInt(row['systemScore'] || '0');
    const ml = parseInt(row['mlScore'] || '0');
    if (ml > sys + 5) return 'score-higher';
    if (ml < sys - 5) return 'score-lower';
    return '';
  }

  outcomeClass(row: any): string {
    if (row['tradeOutcome'] === 'PROFIT') return 'outcome-profit';
    if (row['tradeOutcome'] === 'LOSS') return 'outcome-loss';
    return '';
  }

  pnlClass(row: any): string {
    const pnl = parseFloat(row['tradePnl'] || row['profitPercent'] || '0');
    if (pnl > 0) return 'pnl-positive';
    if (pnl < 0) return 'pnl-negative';
    return '';
  }

  objectKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  parseFloat(v: string): number {
    return globalThis.parseFloat(v) || 0;
  }
}
