import { TitleCasePipe } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  ApiService,
  EffectiveTradingConfigDto,
  RiskProfileDto,
  TradingSettingsResponse
} from '../core/api.service';
import { AdminService } from '../core/admin.service';

/**
 * Redesigned Trading Settings.
 *
 * Regular users: only the operational essentials (capital, profit target, session, manage-synced).
 * Risk posture is set by the SUPERUSER via the assigned profile — users do NOT pick a profile and
 * do NOT see the derived parameters.
 *
 * Superuser: additionally gets the Risk Profile DEFINITIONS editor (defines what Conservative /
 * Balanced / Aggressive mean) and the Derived parameters preview. Per-user profile ASSIGNMENT lives
 * in the User Management screen.
 */
@Component({
  selector: 'app-trading-settings-page',
  standalone: true,
  imports: [TitleCasePipe, FormsModule, RouterLink, MatButtonModule, MatIconModule, MatSlideToggleModule,
    MatFormFieldModule, MatInputModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Trading Settings</h1>
          <p class="page-subtitle">Set the essentials for your account. Your risk profile is assigned by an administrator.</p>
        </div>
      </div>

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      @if (!loaded) {
        <div style="text-align:center;padding:40px;color:var(--muted)">Loading your settings…</div>
      }

      @if (loaded) {
        <!-- ── Essentials (everyone) ────────────────────────────── -->
        <div class="section-hdr">
          <mat-icon class="icon-entry">tune</mat-icon>
          <div><h2>Essentials</h2><p>Your account inputs</p></div>
        </div>
        <div class="card">
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Capital (₹)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="model.totalCapital">
              <mat-hint>Your trading capital — drives sizing</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Daily profit target (₹, optional)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="model.dailyProfitTarget">
              <mat-hint>0 / blank = none</mat-hint>
            </mat-form-field>
          </div>
          <div class="toggle-row">
            <span>Trading session
              <span class="toggle-hint">Standard = 09:25 start, 15:10 cutoff, 15:15 forced exit, 15:20 failsafe</span>
            </span>
            <mat-slide-toggle [(ngModel)]="sessionCustom" (ngModelChange)="onSessionToggle()" color="primary">{{ sessionCustom ? 'Custom' : 'Standard' }}</mat-slide-toggle>
          </div>
          @if (sessionCustom) {
            <div class="form-grid">
              <mat-form-field appearance="outline"><mat-label>Entry start (HH:mm)</mat-label><input matInput [(ngModel)]="model.entryStartTime"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Entry cutoff (HH:mm)</mat-label><input matInput [(ngModel)]="model.entryCutoffTime"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Forced exit (HH:mm)</mat-label><input matInput [(ngModel)]="model.forcedExitTime"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Failsafe squareoff (HH:mm)</mat-label><input matInput [(ngModel)]="model.failSafeSquareoffTime"></mat-form-field>
            </div>
          }
          <div class="toggle-row">
            <span>Manage synced (manual) trades
              <span class="toggle-hint">When ON, the algo's exit monitors also manage broker-imported manual (SYNC-) trades</span>
            </span>
            <mat-slide-toggle [(ngModel)]="model.manageSyncedTrades" color="primary"></mat-slide-toggle>
          </div>
          <div class="actions">
            <button mat-flat-button color="primary" (click)="save()"><mat-icon>save</mat-icon> Save</button>
          </div>
        </div>

        <!-- ── SUPERUSER: profile definitions editor ────────────── -->
        @if (isSuper) {
          <div class="section-hdr">
            <mat-icon class="icon-risk">admin_panel_settings</mat-icon>
            <div><h2>Risk profile definitions</h2><p>Define what each profile means. Assign profiles to users in User Management. Changes apply to all assigned users immediately.</p></div>
          </div>
          <div class="card">
            <div class="profile-editor">
              <div class="pe-row pe-head">
                <span>Profile</span><span>Risk %/trade</span><span>Daily loss %</span><span>Trades/day</span>
                <span>Consec. losses</span><span>Open trades</span><span>Lots/trade</span><span>Signal score</span><span>Env score</span>
              </div>
              @for (p of editProfiles; track p.name) {
                <div class="pe-row">
                  <span class="pe-name">{{ p.name | titlecase }}</span>
                  <input type="number" step="0.5" [(ngModel)]="p.maxRiskPerTradePercent">
                  <input type="number" step="0.5" [(ngModel)]="p.maxDailyLossPercent">
                  <input type="number" [(ngModel)]="p.maxTradesPerDay">
                  <input type="number" [(ngModel)]="p.maxConsecutiveLosses">
                  <input type="number" [(ngModel)]="p.maxOpenTrades">
                  <input type="number" [(ngModel)]="p.maxLotsPerTrade">
                  <input type="number" [(ngModel)]="p.minSignalScorePercent">
                  <input type="number" [(ngModel)]="p.minEnvironmentScore">
                </div>
              }
            </div>
            <div class="pe-subhead">Exit style</div>
            <div class="profile-editor">
              <div class="pe-row pe-head pe-exit">
                <span>Profile</span><span>Stop loss %</span><span>Target %</span><span>Trail activ %</span><span>Trail gap %</span>
                <span>Max hold (min)</span><span>Partial book</span><span>VWAP exit</span><span>IV-collapse max %</span>
              </div>
              @for (p of editProfiles; track p.name) {
                <div class="pe-row pe-exit">
                  <span class="pe-name">{{ p.name | titlecase }}</span>
                  <input type="number" step="0.5" [(ngModel)]="p.stopLossPercent">
                  <input type="number" step="0.5" [(ngModel)]="p.targetPercent">
                  <input type="number" step="0.5" [(ngModel)]="p.trailingStopActivationPercent">
                  <input type="number" step="0.5" [(ngModel)]="p.trailingGapPercent">
                  <input type="number" [(ngModel)]="p.maxHoldMinutes">
                  <mat-slide-toggle [(ngModel)]="p.partialProfitBookingEnabled" color="primary"></mat-slide-toggle>
                  <mat-slide-toggle [(ngModel)]="p.vwapExitEnabled" color="primary"></mat-slide-toggle>
                  <input type="number" step="0.5" [(ngModel)]="p.ivCollapseMaxProfitPercent">
                </div>
              }
            </div>
            <div class="actions">
              <button mat-flat-button color="primary" (click)="saveProfiles()"><mat-icon>save</mat-icon> Save profiles</button>
              <button mat-stroked-button color="warn" (click)="reseedProfiles()"><mat-icon>restart_alt</mat-icon> Reset to code defaults</button>
              <a mat-stroked-button routerLink="/admin/users"><mat-icon>group</mat-icon> Assign profiles to users</a>
            </div>
          </div>

          <!-- SUPERUSER: derived preview of own resolved config -->
          @if (preview) {
            <div class="section-hdr">
              <mat-icon class="icon-derived">auto_awesome</mat-icon>
              <div><h2>Derived parameters (your account)</h2><p>What your assigned profile + capital resolve to</p></div>
            </div>
            <div class="derived-grid">
              <div class="d-item"><span class="d-k">Profile</span><span class="d-v">{{ preview.riskProfile | titlecase }}</span></div>
              <div class="d-item"><span class="d-k">Max risk / trade</span><span class="d-v">{{ preview.maxRiskPerTradePercent }}%</span></div>
              <div class="d-item"><span class="d-k">Max lots / trade</span><span class="d-v">{{ preview.maxLotsPerTrade }}</span></div>
              <div class="d-item"><span class="d-k">Max trades / day</span><span class="d-v">{{ preview.maxTradesPerDay }}</span></div>
              <div class="d-item"><span class="d-k">Daily loss limit</span><span class="d-v">{{ preview.maxDailyLossPercent }}%</span></div>
              <div class="d-item"><span class="d-k">Min signal score</span><span class="d-v">{{ preview.minSignalScorePercent }}%</span></div>
            </div>
          }

          <div class="actions">
            <a mat-stroked-button routerLink="/settings/advanced"><mat-icon>settings</mat-icon> Advanced / global defaults</a>
          </div>
        }
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 24px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .toast-ok { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--ok-bg); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .toast-warn { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; background: var(--warn-bg); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }
    .section-hdr { display: flex; align-items: center; gap: 12px; margin: 24px 0 16px; }
    .section-hdr h2 { font-size: 15px; font-weight: 700; color: var(--ink); margin: 0 0 2px; }
    .section-hdr p { color: var(--muted); font-size: 12px; margin: 0; }
    .icon-entry { color: var(--accent); }
    .icon-derived { color: var(--ok); }
    .icon-risk { color: var(--bad); }
    .card { padding: 16px; margin-bottom: 8px; border: 1px solid var(--line); border-radius: 8px; background: rgba(0,0,0,.1); }
    .form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; margin: 0 0 12px; }
    .toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; margin-top: 8px; border: 1px solid var(--line); border-radius: 8px; background: rgba(255,255,255,.04); font-size: 13px; color: var(--text); }
    .toggle-hint { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }
    .profile-editor { display: flex; flex-direction: column; gap: 6px; overflow-x: auto; }
    .pe-row { display: grid; grid-template-columns: 110px repeat(8, minmax(72px, 1fr)); gap: 8px; align-items: center; }
    .pe-head { font-size: 11px; color: var(--muted); text-transform: uppercase; padding-bottom: 4px; }
    .pe-subhead { font-size: 12px; font-weight: 700; color: var(--muted); margin: 14px 0 6px; text-transform: uppercase; }
    .pe-name { font-weight: 700; color: var(--ink); font-size: 13px; }
    .pe-row input { width: 100%; padding: 6px 8px; border-radius: 6px; border: 1px solid var(--line); background: rgba(255,255,255,.04); color: var(--text); font-size: 13px; }
    .derived-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; padding: 16px; border: 1px solid var(--line); border-radius: 8px; background: rgba(0,0,0,.1); }
    .d-item { display: flex; flex-direction: column; gap: 2px; }
    .d-k { font-size: 11px; color: var(--muted); }
    .d-v { font-size: 15px; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums; }
    .actions { display: flex; gap: 12px; margin-top: 16px; flex-wrap: wrap; }
    .actions button:first-child, .actions a:first-child { min-width: 120px; }
  `]
})
export class TradingSettingsPageComponent implements OnInit {
  loaded = false;
  msg = signal('');
  error = signal('');
  preview: EffectiveTradingConfigDto | null = null;
  editProfiles: RiskProfileDto[] = [];
  sessionCustom = false;

  model = {
    totalCapital: null as number | null,
    dailyProfitTarget: null as number | null,
    sessionPreset: 'STANDARD',
    entryStartTime: '',
    entryCutoffTime: '',
    forcedExitTime: '',
    failSafeSquareoffTime: '',
    manageSyncedTrades: false
  };

  constructor(private api: ApiService, private admin: AdminService, private cd: ChangeDetectorRef) {}

  get isSuper(): boolean { return this.admin.isSuperUser(); }

  ngOnInit() {
    if (this.isSuper) {
      this.api.getRiskProfiles().subscribe({ next: p => { this.editProfiles = p; this.cd.detectChanges(); }, error: () => {} });
    }
    this.load();
  }

  load() {
    this.api.getTradingSettings().subscribe({
      next: (r: TradingSettingsResponse) => {
        this.preview = r.resolved;
        const raw = r.raw;
        this.model.totalCapital = raw?.totalCapital ?? r.resolved.totalCapital ?? null;
        this.model.dailyProfitTarget = raw?.dailyProfitTarget ?? null;
        this.model.sessionPreset = raw?.sessionPreset ?? 'STANDARD';
        this.sessionCustom = this.model.sessionPreset === 'CUSTOM';
        this.model.entryStartTime = raw?.entryStartTime ?? r.resolved.entryStartTime ?? '';
        this.model.entryCutoffTime = raw?.entryCutoffTime ?? r.resolved.entryCutoffTime ?? '';
        this.model.forcedExitTime = raw?.forcedExitTime ?? r.resolved.forcedExitTime ?? '';
        this.model.failSafeSquareoffTime = raw?.failSafeSquareoffTime ?? r.resolved.failSafeSquareoffTime ?? '';
        this.model.manageSyncedTrades = raw?.manageSyncedTrades ?? r.resolved.manageSyncedTrades ?? false;
        this.loaded = true;
        this.cd.detectChanges();
      },
      error: () => { this.error.set('Failed to load settings'); this.loaded = true; this.cd.detectChanges(); }
    });
  }

  onSessionToggle() {
    this.model.sessionPreset = this.sessionCustom ? 'CUSTOM' : 'STANDARD';
  }

  save() {
    this.msg.set(''); this.error.set('');
    const body: Record<string, unknown> = {
      totalCapital: this.model.totalCapital,
      dailyProfitTarget: this.model.dailyProfitTarget,
      sessionPreset: this.sessionCustom ? 'CUSTOM' : 'STANDARD',
      entryStartTime: this.model.entryStartTime,
      entryCutoffTime: this.model.entryCutoffTime,
      forcedExitTime: this.model.forcedExitTime,
      failSafeSquareoffTime: this.model.failSafeSquareoffTime,
      manageSyncedTrades: this.model.manageSyncedTrades
    };
    this.api.updateTradingSettings(body).subscribe({
      next: (r: TradingSettingsResponse) => { this.preview = r.resolved; this.msg.set('Settings saved'); this.cd.detectChanges(); },
      error: (e) => { this.error.set(e?.error?.error ?? 'Save failed'); this.cd.detectChanges(); }
    });
  }

  saveProfiles() {
    this.msg.set(''); this.error.set('');
    let pending = this.editProfiles.length;
    if (pending === 0) return;
    for (const p of this.editProfiles) {
      this.api.updateRiskProfile(p.name, { ...p }).subscribe({
        next: () => { if (--pending === 0) { this.msg.set('Profiles saved'); this.load(); this.cd.detectChanges(); } },
        error: (e) => { this.error.set(e?.error?.error ?? 'Profile save failed'); this.cd.detectChanges(); }
      });
    }
  }

  reseedProfiles() {
    if (!confirm('Reset all risk profiles to code defaults? This overwrites any manual edits.')) return;
    this.msg.set(''); this.error.set('');
    this.api.reseedRiskProfiles().subscribe({
      next: () => {
        this.msg.set('Profiles reset to code defaults');
        this.api.getRiskProfiles().subscribe({ next: p => { this.editProfiles = p; this.cd.detectChanges(); } });
      },
      error: (e) => { this.error.set(e?.error?.error ?? 'Reseed failed'); this.cd.detectChanges(); }
    });
  }
}
