import { DecimalPipe } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  ApiService,
  GlobalConfigDto,
  OiMomentumHaltStatusDto,
  OiMomentumIndexHaltDto,
  OiMomentumRuntimeConfigDto
} from '../core/api.service';

@Component({
  selector: 'app-settings-page',
  standalone: true,
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatIconModule, MatSlideToggleModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Settings</h1>
          <p class="page-subtitle">OI Momentum V3 operator controls and global entry/exit/risk parameters. Runtime changes apply immediately.</p>
        </div>
      </div>

      @if (oiMsg()) { <div class="toast-ok">{{ oiMsg() }}</div> }
      @if (oiError()) { <div class="toast-warn">{{ oiError() }}</div> }

      @if (!oiLoaded) {
        <div style="text-align:center;padding:24px;color:var(--muted)">Loading OI Momentum settings…</div>
      }

      @if (oiConfig) {
        <div class="section-hdr clickable" (click)="oiOpen = !oiOpen">
          <mat-icon class="icon-v3">bolt</mat-icon>
          <div>
            <h2>OI Momentum V3</h2>
            <p>Global enable, shadow/live V3, and safety limits — no app restart</p>
          </div>
          <span class="v3-mode-pill" [class.live]="v3Mode() === 'live'" [class.shadow]="v3Mode() === 'shadow'">{{ v3ModeLabel() }}</span>
          <span class="spacer"></span>
          <mat-icon>{{ oiOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>

        @if (oiOpen) {
          <div class="v3-panel">
            <div class="form-grid">
              <div class="toggle-row">
                <span>Strategy enabled<small class="toggle-hint">Master switch for OI Momentum entries</small></span>
                <mat-slide-toggle [(ngModel)]="oiConfig.enabled" color="primary"></mat-slide-toggle>
              </div>
              <div class="toggle-row">
                <span>Paper trading<small class="toggle-hint">Log orders without broker execution</small></span>
                <mat-slide-toggle [(ngModel)]="oiConfig.paperTrading" color="primary"></mat-slide-toggle>
              </div>
              <div class="toggle-row v3-toggle">
                <span>V3 enabled<small class="toggle-hint">Use V3 entry pipeline when on</small></span>
                <mat-slide-toggle [(ngModel)]="oiConfig.v3Enabled" color="primary"></mat-slide-toggle>
              </div>
              <div class="toggle-row v3-toggle">
                <span>V3 shadow mode<small class="toggle-hint">Evaluate and log V3 decisions without live entries</small></span>
                <mat-slide-toggle [(ngModel)]="oiConfig.v3ShadowMode" color="primary"
                  [disabled]="!oiConfig.v3Enabled"></mat-slide-toggle>
              </div>
            </div>

            <div class="section-hdr clickable sub-hdr" (click)="v3SafetyOpen = !v3SafetyOpen">
              <mat-icon class="icon-risk">shield</mat-icon>
              <div>
                <h2>Safety limits</h2>
                <p>Loss halts, anti-pyramid, expiry cutoff, trade cap</p>
              </div>
              <span class="spacer"></span>
              <mat-icon>{{ v3SafetyOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
            </div>
            @if (v3SafetyOpen) {
              <div class="form-grid">
                <div class="toggle-row">
                  <span>Anti-pyramid<small class="toggle-hint">Block re-entry on same strike after exit</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.antiPyramidEnabled" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Anti-pyramid cooldown (min)</mat-label>
                  <input matInput type="number" min="0" max="240" [(ngModel)]="oiConfig.antiPyramidCooldownMinutes">
                </mat-form-field>
                <div class="toggle-row">
                  <span>Expiry OTM cutoff<small class="toggle-hint">Stop far-OTM entries near expiry</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.expiryOtmCutoffEnabled" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Expiry OTM cutoff time</mat-label>
                  <input matInput placeholder="14:30" [(ngModel)]="oiConfig.expiryOtmCutoffTime">
                  <mat-hint>HH:mm IST</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Daily loss limit (₹)</mat-label>
                  <input matInput type="number" min="0" step="500" [(ngModel)]="oiConfig.dailyLossLimitRupees">
                  <mat-hint>0 = use percent-based limit from YAML</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Daily loss × avg loser</mat-label>
                  <input matInput type="number" min="0" step="0.5" [(ngModel)]="oiConfig.dailyLossMultiplierOfAvgLoser">
                  <mat-hint>0 = disabled</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Consecutive loss halt</mat-label>
                  <input matInput type="number" min="0" max="20" [(ngModel)]="oiConfig.consecutiveLossHaltCount">
                  <mat-hint>0 = disabled</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Break-even trigger %</mat-label>
                  <input matInput type="number" min="0" max="50" step="0.5" [(ngModel)]="oiConfig.breakEvenTriggerPercent">
                  <mat-hint>0 = disabled</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Max trades per day</mat-label>
                  <input matInput type="number" min="1" max="50" [(ngModel)]="oiConfig.maxTradesPerDay">
                </mat-form-field>
              </div>
            }

            <!-- ── Legacy Enhancements (29 May 2026 — data-validated) ── -->
            <div class="section-hdr clickable sub-hdr" (click)="legacyEnhOpen = !legacyEnhOpen">
              <mat-icon class="icon-v3">insights</mat-icon>
              <div>
                <h2>Legacy enhancements <small style="color:var(--muted);font-weight:400">(data-validated 29 May)</small></h2>
                <p>Time-of-day filter, CASE 0 OI-led entry, CASE 4 watch-list bonus</p>
              </div>
              <span class="spacer"></span>
              <mat-icon>{{ legacyEnhOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
            </div>

            @if (legacyEnhOpen) {
              <div class="form-grid">
                <div class="toggle-row">
                  <span>Time-of-day mode filter
                    <small class="toggle-hint">Skip afternoon + last-hour entries; require 4-of-4 in mid-day. Replay 48% → 58% win%.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.legacyTimeOfDayModeEnabled" color="primary"></mat-slide-toggle>
                </div>
                <div class="toggle-row">
                  <span>CASE 0 — OI-led entry
                    <small class="toggle-hint">Fires before any price breakout when chain is screaming. 91% 30m win in replay.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.case0Enabled" color="primary"></mat-slide-toggle>
                </div>
                <div class="toggle-row">
                  <span>CASE 0 shadow mode
                    <small class="toggle-hint">Log-only — recommended for first 5 sessions.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.case0ShadowMode" color="primary"
                    [disabled]="!oiConfig.case0Enabled"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>CASE 0 op-score threshold</mat-label>
                  <input matInput type="number" min="50" max="100" [(ngModel)]="oiConfig.case0OpScoreThreshold">
                  <mat-hint>Strict=80 (91% win). Loose=70 (56% win, 3+/day).</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>CASE 0 max 20-min coil (%)</mat-label>
                  <input matInput type="number" min="0.01" max="1.0" step="0.01" [(ngModel)]="oiConfig.case0CoilMaxPct">
                  <mat-hint>Strict=0.10. Loose=0.15.</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>CASE 0 min |PCR slope 5m|</mat-label>
                  <input matInput type="number" min="0" max="1" step="0.01" [(ngModel)]="oiConfig.case0PcrSlopeMinAbs">
                  <mat-hint>Direction-of-tilt requirement.</mat-hint>
                </mat-form-field>
                <div class="toggle-row">
                  <span>CASE 4 watch-list bonus
                    <small class="toggle-hint">+5 bias when next signal aligns with prior OI flip (≤20 min).</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.case4WatchlistBonusEnabled" color="primary"></mat-slide-toggle>
                </div>

                <!-- R3 — Adaptive CASE 0 for low-VIX (replay 82% 60m win) -->
                <div class="toggle-row">
                  <span>CASE 0 low-VIX tier (R3)
                    <small class="toggle-hint">Loosen CASE 0 thresholds when VIX is calm. Replay: 71.7% 30m / 82.1% 60m win.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.case0LowVixEnabled" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Low-VIX activation threshold</mat-label>
                  <input matInput type="number" min="10" max="25" step="0.5"
                         [(ngModel)]="oiConfig.case0LowVixVixThreshold">
                  <mat-hint>VIX below this → use loose tier (default 17)</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Low-VIX op-score threshold</mat-label>
                  <input matInput type="number" min="30" max="100" step="5"
                         [(ngModel)]="oiConfig.case0LowVixOpScoreThreshold">
                  <mat-hint>Looser than strict 80 (default 65)</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Low-VIX coil max (%)</mat-label>
                  <input matInput type="number" min="0.05" max="0.50" step="0.01"
                         [(ngModel)]="oiConfig.case0LowVixCoilMaxPct">
                  <mat-hint>Looser than strict 0.10 (default 0.20)</mat-hint>
                </mat-form-field>

                <!-- R2 — Range-edge fade for range-bound markets -->
                <div class="toggle-row">
                  <span>Range-edge fade (R2)
                    <small class="toggle-hint">Fade top/bottom of tight 30M range with OI confirmation. Replay: ~14/day @ 54% 30m / 56% 60m win. Fills range-bound gap.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.rangeEdgeFadeEnabled" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Max 30M range to consider ranging (%)</mat-label>
                  <input matInput type="number" min="0.10" max="0.80" step="0.05"
                         [(ngModel)]="oiConfig.rangeEdgeFadeRangeMaxPct">
                  <mat-hint>Default 0.30</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Edge band (top/bottom fraction)</mat-label>
                  <input matInput type="number" min="0.10" max="0.40" step="0.05"
                         [(ngModel)]="oiConfig.rangeEdgeFadeEdgePct">
                  <mat-hint>0.20 = top/bottom 20% of range</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Min OI build to confirm fade</mat-label>
                  <input matInput type="number" min="500" max="50000" step="500"
                         [(ngModel)]="oiConfig.rangeEdgeFadeOiBuildMin">
                  <mat-hint>Default 3000 contracts</mat-hint>
                </mat-form-field>

                <!-- Theta-decay gate -->
                <div class="toggle-row">
                  <span>Theta-decay check
                    <small class="toggle-hint">Reject entries whose expected theta cost exceeds % of expected gain. Protects late-day / near-expiry entries.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.thetaDecayCheckEnabled" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Max theta cost % of expected gain</mat-label>
                  <input matInput type="number" min="10" max="80" step="5"
                         [(ngModel)]="oiConfig.thetaDecayMaxCostPct">
                  <mat-hint>30 = theta eats max 30% of expected gain</mat-hint>
                </mat-form-field>

                <div class="section-hdr sub-hdr inline-hdr">
                  <mat-icon class="icon-risk">trending_up</mat-icon>
                  <div>
                    <h2>D2 - Sustained drift (trend capture)</h2>
                    <p>Catches slow one-way grind days. To go live: Overrides V3 = ON and Shadow = OFF.</p>
                  </div>
                </div>
                <div class="toggle-row">
                  <span>SUSTAINED_DRIFT enabled
                    <small class="toggle-hint">Detector on. Fires when 60-min drift &gt;= threshold with operator agreement.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.sustainedDriftEnabled" color="primary"></mat-slide-toggle>
                </div>
                <div class="toggle-row">
                  <span>SUSTAINED_DRIFT shadow mode
                    <small class="toggle-hint">Log-only. Keep ON until you have a clean validation session.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.sustainedDriftShadowMode" color="primary"
                    [disabled]="!oiConfig.sustainedDriftEnabled"></mat-slide-toggle>
                </div>
                <div class="toggle-row">
                  <span>Overrides V3 (GO-LIVE - real money)
                    <small class="toggle-hint">Lets D2 bind live even while V3 is on (V3 still sizes/vets). Default OFF. Requires shadow OFF.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.sustainedDriftOverridesV3" color="warn"
                    [disabled]="!oiConfig.sustainedDriftEnabled || oiConfig.sustainedDriftShadowMode"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Min 60-min drift (%)</mat-label>
                  <input matInput type="number" min="0.05" max="2" step="0.01"
                         [(ngModel)]="oiConfig.sustainedDriftMinPct">
                  <mat-hint>0.20 = fire when spot drifts >= 0.20% over the window</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Min operator score</mat-label>
                  <input matInput type="number" min="0" max="100" step="5"
                         [(ngModel)]="oiConfig.sustainedDriftOpScoreMin">
                  <mat-hint>50 = require operator conviction >= 50 aligned with drift</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Drift window (minutes)</mat-label>
                  <input matInput type="number" min="5" max="240" step="5"
                         [(ngModel)]="oiConfig.sustainedDriftWindowMinutes">
                  <mat-hint>60 = measure drift over the last 60 minutes</mat-hint>
                </mat-form-field>

                <div class="section-hdr sub-hdr inline-hdr">
                  <mat-icon class="icon-risk">analytics</mat-icon>
                  <div>
                    <h2>Tuning instrumentation</h2>
                    <p>CSV + log capture for data-backed gate tuning (disable sampling after review week)</p>
                  </div>
                </div>
                <div class="toggle-row">
                  <span>Record every reject
                    <small class="toggle-hint">Default ON at deploy (from application.yml). Writes every reject to oi-momentum-rejects.csv; turn off after the data week to save disk.</small></span>
                  <mat-slide-toggle [(ngModel)]="oiConfig.recordEveryReject" color="primary"></mat-slide-toggle>
                </div>
                <mat-form-field appearance="outline">
                  <mat-label>Reject sample interval (seconds)</mat-label>
                  <input matInput type="number" min="5" max="300" step="5"
                         [(ngModel)]="oiConfig.rejectSampleIntervalSeconds">
                  <mat-hint>Throttles non-matrix rejects when record-every-reject is off</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Matrix reject sample interval (seconds)</mat-label>
                  <input matInput type="number" min="1" max="60" step="1"
                         [(ngModel)]="oiConfig.matrixRejectSampleIntervalSeconds">
                  <mat-hint>matrix_skip:* rows — default 5s</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Top reject reasons in 60s log</mat-label>
                  <input matInput type="number" min="1" max="50" step="1"
                         [(ngModel)]="oiConfig.summaryRejectTopN">
                  <mat-hint>Per-reason counters reset at IST day rollover</mat-hint>
                </mat-form-field>
              </div>
            }

            <div class="section-hdr clickable sub-hdr" (click)="haltsOpen = !haltsOpen">
              <mat-icon class="icon-risk">pause_circle</mat-icon>
              <div>
                <h2>Session halts (per index)</h2>
                <p>Day halt, loss pause, SL cooldown, trade cap — resume or extend</p>
              </div>
              @if (haltStatus && anyIndexBlocked()) {
                <span class="halt-alert-pill">Blocked</span>
              }
              <span class="spacer"></span>
              <button mat-stroked-button type="button" class="hdr-btn" (click)="loadHalts($event)">
                <mat-icon>refresh</mat-icon>
              </button>
              <mat-icon>{{ haltsOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
            </div>

            @if (haltsOpen) {
              <div class="halt-panel">
                @if (!haltStatus) {
                  <p class="yaml-note">Loading halt status…</p>
                } @else if (!haltStatus.strategyEnabled) {
                  <p class="yaml-note">Strategy master switch is off — enable above to trade.</p>
                }

                <div class="halt-options">
                  <span class="halt-options-label">On resume, clear:</span>
                  <mat-checkbox [(ngModel)]="resumeClearHalted" color="primary">Day halt</mat-checkbox>
                  <mat-checkbox [(ngModel)]="resumeClearLosses" color="primary">Consecutive losses</mat-checkbox>
                  <mat-checkbox [(ngModel)]="resumeClearSlCooldown" color="primary">SL cooldown</mat-checkbox>
                  <mat-checkbox [(ngModel)]="resumeResetTrades" color="primary">Trade count</mat-checkbox>
                </div>

                <table class="halt-table">
                  <thead>
                    <tr>
                      <th>Index</th>
                      <th>Day halt</th>
                      <th>Losses</th>
                      <th>SL cooldown</th>
                      <th>Trades</th>
                      <th>P&amp;L</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of haltIndexRows(); track row.name) {
                      <tr [class.row-blocked]="row.h.blocked">
                        <td class="idx-name">{{ row.name }}</td>
                        <td>
                          <span class="halt-chip" [class.active]="row.h.haltedForDay">Day halt</span>
                        </td>
                        <td>
                          <span class="halt-chip" [class.active]="row.h.inLossPause">
                            {{ row.h.consecutiveLosses }}/{{ row.h.consecutiveLossPauseThreshold }}
                            @if (row.h.consecutiveLossHaltThreshold > 0) {
                              <small>(halt {{ row.h.consecutiveLossHaltThreshold }})</small>
                            }
                          </span>
                        </td>
                        <td>
                          @if (row.h.slCooldownRemainingSeconds > 0) {
                            <span class="halt-chip active">{{ row.h.slCooldownRemainingSeconds }}s</span>
                          } @else {
                            <span class="halt-chip">—</span>
                          }
                        </td>
                        <td>
                          <span class="halt-chip" [class.active]="row.h.atTradeCap">
                            {{ row.h.tradesToday }}/{{ row.h.maxTradesPerDay }}
                          </span>
                        </td>
                        <td class="pnl-cell" [class.neg]="row.h.dailyPnl < 0">{{ row.h.dailyPnl | number:'1.0-0' }}</td>
                        <td class="halt-actions">
                          <button mat-stroked-button type="button" (click)="resumeOi([row.name])"
                            [disabled]="!row.h.blocked">
                            Resume
                          </button>
                          <button mat-stroked-button type="button" color="warn" (click)="extendOi([row.name])">
                            Extend
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>

                <p class="yaml-note halt-help">
                  <strong>Resume</strong> clears selected flags (uses change reason above).
                  <strong>Extend</strong> sets day-halt for the index until manual resume or midnight rollover.
                  Trade cap: raise <em>Max trades per day</em> and Save, or resume with <em>Trade count</em> checked.
                </p>

                <div class="actions oi-actions halt-bulk-actions">
                  <button mat-stroked-button type="button" (click)="resumeOi()" [disabled]="!anyIndexBlocked()">
                    <mat-icon>play_arrow</mat-icon> Resume all blocked
                  </button>
                  <button mat-stroked-button type="button" color="warn" (click)="extendOi()">
                    <mat-icon>pause</mat-icon> Extend halt (all indices)
                  </button>
                  <button mat-stroked-button type="button" (click)="bumpMaxTrades()">
                    <mat-icon>trending_up</mat-icon> +5 max trades (save)
                  </button>
                </div>
              </div>
            }

            <p class="yaml-note">
              Momentum thresholds, PCR, stop/trail %, entry windows, and V3 pipeline constants stay in
              <code>application.yml</code> — restart required to change those.
            </p>

            <mat-form-field appearance="outline" class="reason-field">
              <mat-label>Change reason (required)</mat-label>
              <input matInput [(ngModel)]="oiChangeReason" minlength="5" placeholder="e.g. shadow week after poor NIFTY session">
              <mat-hint>Min 5 characters — recorded in audit log</mat-hint>
            </mat-form-field>

            @if (oiConfig.updatedAt) {
              <p class="audit-line">
                Last update: {{ oiConfig.updatedAt }} by {{ oiConfig.updatedBy || '—' }}
                @if (oiConfig.updatedReason) { — {{ oiConfig.updatedReason }} }
              </p>
            }

            <div class="actions oi-actions">
              <button mat-flat-button color="primary" (click)="saveOi()">
                <mat-icon>save</mat-icon> Save OI Momentum
              </button>
              <button mat-stroked-button color="warn" (click)="killOi()">
                <mat-icon>block</mat-icon> Emergency kill
              </button>
              <button mat-stroked-button (click)="loadOi()">
                <mat-icon>refresh</mat-icon> Reload
              </button>
            </div>
          </div>
        }

        <hr class="section-divider" />
      }

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      @if (!loaded) {
        <div style="text-align:center;padding:40px;color:var(--muted)">Loading settings…</div>
      }

      @if (config) {
        <!-- Entry Filters -->
        <div class="section-hdr clickable" (click)="entryOpen = !entryOpen">
          <mat-icon class="icon-entry">filter_alt</mat-icon>
          <div>
            <h2>Entry Filters</h2>
            <p>Timeframes, breakout, volume, OI, RSI, and signal thresholds</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ entryOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (entryOpen) {
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Timeframe</mat-label>
              <mat-select [(ngModel)]="config.timeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trend Timeframe</mat-label>
              <mat-select [(ngModel)]="config.trendTimeframe">
                <mat-option value="ONE_MINUTE">1 Minute</mat-option>
                <mat-option value="FIVE_MINUTE">5 Minutes</mat-option>
                <mat-option value="FIFTEEN_MINUTE">15 Minutes</mat-option>
              </mat-select>
            </mat-form-field>
            <div class="toggle-row">
              <span>VWAP Filter</span>
              <mat-slide-toggle [(ngModel)]="config.vwapFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>Trend Filter</span>
              <mat-slide-toggle [(ngModel)]="config.trendFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>Volume Spike Multiplier</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.volumeSpikeMultiplier">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Breakout Buffer %</mat-label>
              <input matInput type="number" step="0.01" [(ngModel)]="config.breakoutBufferPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Breakout Lookback</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.breakoutLookback">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Volume Lookback</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.volumeLookback">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Bullish Imbalance Threshold</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.bullishImbalanceThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Bearish Imbalance Threshold</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.bearishImbalanceThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Min Liquidity Volume</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.minLiquidityVolume">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max IV %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxIvPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Min Signal Score %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.minSignalScorePercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Min Environment Score (0–100)</mat-label>
              <input matInput type="number" min="0" max="100" step="5" [(ngModel)]="config.minEnvironmentScore">
            </mat-form-field>
            <div class="toggle-row">
              <span>CE OI Support Required</span>
              <mat-slide-toggle [(ngModel)]="config.ceOiSupportRequired" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>PE OI Support Required</span>
              <mat-slide-toggle [(ngModel)]="config.peOiSupportRequired" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>CE OI Divergence Filter</span>
              <mat-slide-toggle [(ngModel)]="config.ceOiDivergenceFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>PE OI Divergence Filter</span>
              <mat-slide-toggle [(ngModel)]="config.peOiDivergenceFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>OI Divergence Multiplier</mat-label>
              <input matInput type="number" step="0.1" [(ngModel)]="config.oiDivergenceMultiplier">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>OI Divergence Min Change</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.oiDivergenceMinChange">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>CE Breakout Confirmation Candles</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.ceBreakoutConfirmationCandles">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>PE Breakout Confirmation Candles</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.peBreakoutConfirmationCandles">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Entry Start Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.entryStartTime">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Entry Cutoff Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.entryCutoffTime">
            </mat-form-field>
            <div class="toggle-row">
              <span>Allow First Minutes Entry</span>
              <mat-slide-toggle [(ngModel)]="config.allowFirstMinutesEntry" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>No Entry First Minutes</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.noEntryFirstMinutes">
            </mat-form-field>
            <div class="toggle-row">
              <span>RSI Filter</span>
              <mat-slide-toggle [(ngModel)]="config.rsiFilterEnabled" color="primary"></mat-slide-toggle>
            </div>
            <mat-form-field appearance="outline">
              <mat-label>RSI Period</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.rsiPeriod">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>RSI CE Buy Threshold</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.rsiCeBuyThreshold">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>RSI PE Sell Threshold</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.rsiPeSellThreshold">
            </mat-form-field>
          </div>
        }

        <!-- Exit Rules -->
        <div class="section-hdr clickable" (click)="exitOpen = !exitOpen">
          <mat-icon class="icon-exit">exit_to_app</mat-icon>
          <div>
            <h2>Exit Rules</h2>
            <p>Stop-loss, target, trailing stop, forced exit, and hold limits</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ exitOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (exitOpen) {
          <div class="form-grid">
            <div class="toggle-row override-row">
              <span>Override Strategy Exit Config <span class="toggle-hint">When ON, these global exit values replace per-strategy SL/target/trailing/maxHold for ALL trades</span></span>
              <mat-slide-toggle [(ngModel)]="config.globalExitOverride" color="warn"></mat-slide-toggle>
            </div>
            @if (config.globalExitOverride) {
              <div class="override-banner">⚠️ Global exit override is ACTIVE — all strategies will use the values below instead of their own exit config</div>
            }
            <mat-form-field appearance="outline">
              <mat-label>Stop Loss %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.stopLossPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Target %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.targetPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trailing Stop Activation %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.trailingStopActivationPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Trailing Gap %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.trailingGapPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Forced Exit Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.forcedExitTime">
            </mat-form-field>
            <div class="toggle-row">
              <span>Partial Profit Booking</span>
              <mat-slide-toggle [(ngModel)]="config.partialProfitBookingEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>VWAP Reversal Exit <span class="toggle-hint">Close profitable long positions when spot crosses back through VWAP</span></span>
              <mat-slide-toggle [(ngModel)]="config.vwapExitEnabled" color="primary"></mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <span>Manage Synced (Manual) Trades
                <span class="toggle-hint">When ON, broker-imported SYNC trades are managed by exit monitors (SL/target/trailing/maxHold/squareoff). Turn OFF if you place manual orders alongside the algo and don't want exit monitors touching them — FailSafe squareoff still runs.</span>
              </span>
              <mat-slide-toggle [(ngModel)]="config.manageSyncedTrades" color="primary"></mat-slide-toggle>
            </div>
            @if (!config.manageSyncedTrades) {
              <div class="override-banner">ℹ️ Manual-trade management is OFF — broker-imported SYNC- trades will be tracked but not auto-closed by SL/target/trailing/maxHold. EOD FailSafe squareoff still applies.</div>
            }
            <mat-form-field appearance="outline">
              <mat-label>Max Hold Minutes (0 = no limit)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.maxHoldMinutes">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Limit Order Cancel Minutes</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.limitOrderCancelMinutes">
              <mat-hint>Auto-cancel unfilled limit orders after this many minutes</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>FailSafe Squareoff Time (HH:mm)</mat-label>
              <input matInput type="text" [(ngModel)]="config.failSafeSquareoffTime">
              <mat-hint>Force-close ALL positions after this time (safety net)</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>IV Collapse Exit Threshold %</mat-label>
              <input matInput type="number" step="1" min="5" max="50" [(ngModel)]="config.ivCollapseExitThresholdPercent">
              <mat-hint>Exit when IV drops this % from entry</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>IV Collapse Max Profit %</mat-label>
              <input matInput type="number" step="1" min="0" max="50" [(ngModel)]="config.ivCollapseMaxProfitPercent">
              <mat-hint>Don't trigger IV collapse exit if profit is above this %</mat-hint>
            </mat-form-field>
          </div>
        }

        <!-- Risk Limits -->
        <div class="section-hdr clickable" (click)="riskOpen = !riskOpen">
          <mat-icon class="icon-risk">shield</mat-icon>
          <div>
            <h2>Risk Limits</h2>
            <p>Capital, daily loss, trade limits, cooldown, and re-entry rules</p>
          </div>
          <span class="spacer"></span>
          <mat-icon>{{ riskOpen ? 'expand_less' : 'expand_more' }}</mat-icon>
        </div>
        @if (riskOpen) {
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Total Capital</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.totalCapital">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Risk Per Trade %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxRiskPerTradePercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Daily Loss %</mat-label>
              <input matInput type="number" step="1" [(ngModel)]="config.maxDailyLossPercent">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Trades Per Day</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxTradesPerDay">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Consecutive Losses</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.maxConsecutiveLosses">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Open Trades</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxOpenTrades">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Indices Per Strategy</mat-label>
              <input matInput type="number" min="1" max="5" [(ngModel)]="config.maxOpenPositionsPerStrategy">
              <mat-hint>How many indices one strategy can trade simultaneously</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Cooldown (min)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.cooldownMinutes">
              <mat-hint>Per instrument after trade</mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Direction Flip Cooldown (min)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.directionFlipCooldownMinutes">
              <mat-hint>CE↔PE flip block (0=disabled)</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Daily Profit Target (₹)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="config.dailyProfitTarget">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Lots Per Trade</mat-label>
              <input matInput type="number" min="1" [(ngModel)]="config.maxLotsPerTrade">
              <mat-hint>Safety cap per order</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Entries Per Scan</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.maxEntriesPerScan">
              <mat-hint>Total per scan cycle</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Entries Per Index</mat-label>
              <input matInput type="number" min="1" max="5" [(ngModel)]="config.maxEntriesPerScanPerUnderlying">
              <mat-hint>Per index per scan cycle</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Pending Orders</mat-label>
              <input matInput type="number" min="1" max="10" [(ngModel)]="config.maxPendingOrders">
              <mat-hint>Unfilled limit orders cap</mat-hint>
            </mat-form-field>
          </div>
        }

        <!-- Action buttons -->
        <div class="actions">
          <button mat-flat-button color="primary" (click)="save()">
            <mat-icon>save</mat-icon> Save
          </button>
          <button mat-stroked-button (click)="resetDefaults()">
            <mat-icon>restart_alt</mat-icon> Reset to Defaults
          </button>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 24px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }

    .toast-ok { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--ok-bg); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .toast-warn { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--warn-bg); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    .section-hdr { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; margin-top: 24px; }
    .section-hdr h2 { font-size: 15px; font-weight: 700; color: var(--ink); margin: 0 0 2px; }
    .section-hdr p { color: var(--muted); font-size: 12px; margin: 0; }
    .clickable { cursor: pointer; user-select: none; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--line); background: rgba(255,255,255,.04); transition: background 160ms; }
    .clickable:hover { background: rgba(255,255,255,.08); }
    .icon-entry { color: var(--accent); font-size: 22px; width: 22px; height: 22px; }
    .icon-exit { color: var(--warn); font-size: 22px; width: 22px; height: 22px; }
    .icon-risk { color: var(--bad); font-size: 22px; width: 22px; height: 22px; }
    .icon-v3 { color: var(--accent); font-size: 22px; width: 22px; height: 22px; }
    .v3-mode-pill {
      font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 999px;
      background: rgba(255,255,255,.08); color: var(--muted); border: 1px solid var(--line);
    }
    .v3-mode-pill.shadow { color: var(--warn); border-color: rgba(242,189,75,.4); background: rgba(242,189,75,.08); }
    .v3-mode-pill.live { color: var(--ok); border-color: rgba(69,209,140,.4); background: rgba(69,209,140,.08); }
    .v3-panel { margin-bottom: 8px; }
    .sub-hdr { margin-top: 8px; border: none; background: transparent; padding-left: 0; }
    .yaml-note { font-size: 12px; color: var(--muted); margin: 12px 0; line-height: 1.5; }
    .yaml-note code { font-size: 11px; color: var(--text); }
    .reason-field { width: 100%; max-width: 560px; display: block; margin-top: 8px; }
    .audit-line { font-size: 11px; color: var(--muted); margin: 0 0 12px; }
    .oi-actions { margin-top: 0; padding-bottom: 8px; }
    .section-divider { border: none; border-top: 1px solid var(--line); margin: 28px 0 24px; }
    .halt-alert-pill {
      font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 999px;
      color: var(--bad); border: 1px solid rgba(255,100,100,.4); background: rgba(255,100,100,.08);
    }
    .hdr-btn { margin-right: 4px; min-width: auto; padding: 0 8px; }
    .halt-panel { margin-bottom: 16px; }
    .halt-options {
      display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px;
      padding: 12px 16px; margin-bottom: 12px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(0,0,0,.08); font-size: 13px;
    }
    .halt-options-label { color: var(--muted); font-size: 12px; margin-right: 4px; }
    .halt-table {
      width: 100%; border-collapse: collapse; font-size: 12px; margin-bottom: 12px;
    }
    .halt-table th, .halt-table td {
      padding: 10px 12px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle;
    }
    .halt-table th { color: var(--muted); font-weight: 600; font-size: 11px; text-transform: uppercase; }
    .halt-table tr.row-blocked { background: rgba(255,100,100,.04); }
    .idx-name { font-weight: 700; color: var(--ink); }
    .halt-chip {
      display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 11px;
      color: var(--muted); border: 1px solid var(--line); background: rgba(255,255,255,.03);
    }
    .halt-chip.active { color: var(--warn); border-color: rgba(242,189,75,.45); background: rgba(242,189,75,.1); }
    .halt-chip small { opacity: .8; margin-left: 4px; }
    .pnl-cell { font-variant-numeric: tabular-nums; }
    .pnl-cell.neg { color: var(--bad); }
    .halt-actions { white-space: nowrap; }
    .halt-actions button { margin-right: 6px; font-size: 12px; }
    .halt-help { margin-top: 0; }
    .halt-bulk-actions { flex-wrap: wrap; }

    .form-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px;
      padding: 16px; margin-bottom: 16px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(0,0,0,.1);
    }

    .toggle-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 16px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(255,255,255,.04); font-size: 13px; color: var(--text);
    }
    .toggle-hint { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }
    .override-row { border: 1px solid rgba(242,189,75,.3); border-radius: 8px; padding: 12px 16px; background: rgba(242,189,75,.04); }
    .override-banner { grid-column: 1 / -1; padding: 8px 14px; border-radius: 6px; font-size: 12px; font-weight: 600; background: rgba(242,189,75,.1); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    .actions { display: flex; gap: 12px; margin-top: 24px; padding-bottom: 32px; }
    .actions button:first-child { min-width: 120px; }
  `]
})
export class SettingsPageComponent implements OnInit {
  config!: GlobalConfigDto;
  oiConfig!: OiMomentumRuntimeConfigDto;
  loaded = false;
  oiLoaded = false;
  msg = signal('');
  error = signal('');
  oiMsg = signal('');
  oiError = signal('');
  oiChangeReason = '';
  entryOpen = true;
  exitOpen = true;
  riskOpen = true;
  oiOpen = true;
  v3SafetyOpen = false;
  legacyEnhOpen = false;   // collapsed by default; toggle when configuring legacy CASE 0 + TOD filter
  haltsOpen = true;
  haltStatus: OiMomentumHaltStatusDto | null = null;
  resumeClearHalted = true;
  resumeClearLosses = true;
  resumeClearSlCooldown = false;
  resumeResetTrades = false;

  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  ngOnInit() {
    setTimeout(() => {
      this.load();
      this.loadOi();
      this.loadHalts();
    }, 0);
  }

  haltIndexRows(): { name: string; h: OiMomentumIndexHaltDto }[] {
    if (!this.haltStatus?.indices) return [];
    return Object.entries(this.haltStatus.indices)
      .map(([name, h]) => ({ name, h }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  anyIndexBlocked(): boolean {
    return this.haltIndexRows().some(r => r.h.blocked);
  }

  loadHalts(event?: Event) {
    event?.stopPropagation();
    this.api.getOiMomentumHalts().subscribe({
      next: s => { this.haltStatus = s; this.cd.detectChanges(); },
      error: () => { /* non-fatal */ }
    });
  }

  v3Mode(): 'off' | 'legacy' | 'shadow' | 'live' {
    if (!this.oiConfig?.enabled) return 'off';
    if (!this.oiConfig.v3Enabled) return 'legacy';
    if (this.oiConfig.v3ShadowMode) return 'shadow';
    return 'live';
  }

  v3ModeLabel(): string {
    switch (this.v3Mode()) {
      case 'off': return 'Disabled';
      case 'legacy': return 'V2 path';
      case 'shadow': return 'V3 shadow';
      case 'live': return 'V3 live';
    }
  }

  private oiReasonOrError(): string | null {
    const reason = (this.oiChangeReason ?? '').trim();
    if (reason.length < 5) {
      this.oiError.set('Change reason is required (at least 5 characters)');
      return null;
    }
    return reason;
  }

  resumeOi(indices?: string[]) {
    const reason = this.oiReasonOrError();
    if (!reason) return;
    this.oiMsg.set('');
    this.oiError.set('');
    this.api.resumeOiMomentumHalts({
      indices: indices?.length ? indices : undefined,
      clearHaltedForDay: this.resumeClearHalted,
      clearConsecutiveLosses: this.resumeClearLosses,
      clearSlCooldown: this.resumeClearSlCooldown,
      resetTradesToday: this.resumeResetTrades,
      reason
    }).subscribe({
      next: r => {
        this.haltStatus = r.haltStatus;
        this.oiMsg.set(indices?.length
          ? `Resumed halts for ${indices.join(', ')}`
          : 'Resumed halts for all indices');
        this.cd.detectChanges();
      },
      error: (e: any) => this.oiError.set(e?.error?.error ?? 'Resume failed')
    });
  }

  extendOi(indices?: string[]) {
    const reason = this.oiReasonOrError();
    if (!reason) return;
    const label = indices?.length ? indices.join(', ') : 'all indices';
    if (!confirm(`Extend day-halt for ${label} for the rest of the session?`)) return;
    this.oiMsg.set('');
    this.oiError.set('');
    this.api.extendOiMomentumHalts({
      indices: indices?.length ? indices : undefined,
      reason
    }).subscribe({
      next: r => {
        this.haltStatus = r.haltStatus;
        this.oiMsg.set(`Extended day-halt: ${label}`);
        this.cd.detectChanges();
      },
      error: (e: any) => this.oiError.set(e?.error?.error ?? 'Extend halt failed')
    });
  }

  bumpMaxTrades() {
    if (!this.oiConfig) return;
    const reason = this.oiReasonOrError();
    if (!reason) return;
    this.oiConfig.maxTradesPerDay = (this.oiConfig.maxTradesPerDay ?? 0) + 5;
    this.saveOi();
  }

  loadOi() {
    this.oiMsg.set('');
    this.oiError.set('');
    this.api.getOiMomentumSettings().subscribe({
      next: c => { this.oiConfig = c; this.oiLoaded = true; this.loadHalts(); this.cd.detectChanges(); },
      error: (e: any) => {
        this.oiError.set(e?.error?.error ?? 'Failed to load OI Momentum settings');
        this.cd.detectChanges();
      }
    });
  }

  saveOi() {
    this.oiMsg.set('');
    this.oiError.set('');
    const reason = this.oiReasonOrError();
    if (!reason) return;
    const body = { ...this.oiConfig, reason };
    this.api.updateOiMomentumSettings(body).subscribe({
      next: c => {
        this.oiConfig = c;
        this.oiChangeReason = '';
        this.oiMsg.set('OI Momentum settings saved');
        this.loadHalts();
        this.cd.detectChanges();
      },
      error: (e: any) => this.oiError.set(e?.error?.error ?? 'Failed to save OI Momentum settings')
    });
  }

  killOi() {
    if (!confirm('Emergency kill: disable OI Momentum and V3 immediately. Continue?')) return;
    this.oiMsg.set('');
    this.oiError.set('');
    const reason = (this.oiChangeReason ?? '').trim();
    this.api.killOiMomentum(reason.length >= 5 ? reason : undefined).subscribe({
      next: c => {
        this.oiConfig = c;
        this.oiChangeReason = '';
        this.oiMsg.set('OI Momentum kill switch applied');
        this.cd.detectChanges();
      },
      error: (e: any) => this.oiError.set(e?.error?.error ?? 'Kill switch failed')
    });
  }

  load() {
    this.msg.set(''); this.error.set('');
    this.api.getGlobalConfig().subscribe({
      next: c => { this.config = c; this.loaded = true; this.cd.detectChanges(); },
      error: () => { this.error.set('Failed to load global config'); this.cd.detectChanges(); }
    });
  }

  save() {
    this.msg.set(''); this.error.set('');
    this.api.updateGlobalConfig(this.config).subscribe({
      next: c => { this.config = c; this.msg.set('Global config saved successfully'); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Failed to save global config')
    });
  }

  resetDefaults() {
    this.msg.set(''); this.error.set('');
    this.api.resetGlobalConfig().subscribe({
      next: c => { this.config = c; this.msg.set('Global config reset to defaults'); },
      error: (e: any) => this.error.set(e?.error?.error ?? 'Failed to reset global config')
    });
  }
}
