import { ChangeDetectorRef, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService, UnderlyingConfigDto } from '../core/api.service';

@Component({
  selector: 'app-underlying-config-page',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatSlideToggleModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Index Configuration</h1>
          <p class="page-subtitle">Per-underlying settings that apply across all strategies. Changes take effect immediately.</p>
        </div>
      </div>

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      @if (!loaded) {
        <div style="text-align:center;padding:40px;color:var(--muted)">Loading index configs…</div>
      }

      @if (loaded && configs.length > 0) {
        <mat-tab-group animationDuration="200ms" (selectedIndexChange)="onTabChange($event)">
          @for (cfg of configs; track cfg.underlying) {
            <mat-tab [label]="cfg.displayName">
              <div class="tab-content">
                <!-- Status -->
                <div class="section-hdr">
                  <mat-icon class="icon-status">{{ cfg.enabled ? 'check_circle' : 'cancel' }}</mat-icon>
                  <div>
                    <h2>{{ cfg.displayName }}</h2>
                    <p>{{ cfg.hasWeeklyExpiry ? 'Weekly expiry' : 'Monthly expiry only' }} · Max DTE: {{ cfg.maxDteForBuying }}d</p>
                  </div>
                  <span class="spacer"></span>
                  <mat-slide-toggle [(ngModel)]="cfg.enabled" color="primary">Enabled</mat-slide-toggle>
                </div>

                <!-- Expiry & DTE -->
                <h3 class="group-title">Expiry & DTE</h3>
                <div class="form-grid">
                  <div class="toggle-row">
                    <span>Has Weekly Expiry</span>
                    <mat-slide-toggle [(ngModel)]="cfg.hasWeeklyExpiry" color="primary"></mat-slide-toggle>
                  </div>
                  <mat-form-field appearance="outline">
                    <mat-label>Expiry Preference</mat-label>
                    <mat-select [(ngModel)]="cfg.expiryPreference">
                      <mat-option value="NEAREST">Nearest</mat-option>
                      <mat-option value="NEAREST_WEEKLY">Nearest Weekly</mat-option>
                      <mat-option value="NEAREST_MONTHLY">Nearest Monthly</mat-option>
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Max DTE for Buying</mat-label>
                    <input matInput type="number" min="1" max="60" [(ngModel)]="cfg.maxDteForBuying">
                    <mat-hint>Block directional buys beyond this DTE</mat-hint>
                  </mat-form-field>
                </div>

                <!-- Breakout -->
                <h3 class="group-title">Breakout & Entry</h3>
                <div class="form-grid">
                  <mat-form-field appearance="outline">
                    <mat-label>Breakout Buffer %</mat-label>
                    <input matInput type="number" step="0.01" min="0" [(ngModel)]="cfg.breakoutBufferPercent">
                    <mat-hint>0 = use global default</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Min Breakout Points</mat-label>
                    <input matInput type="number" step="1" min="0" [(ngModel)]="cfg.minBreakoutPoints">
                    <mat-hint>Absolute points (0 = disabled)</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Max Entry Premium (₹)</mat-label>
                    <input matInput type="number" step="10" min="0" [(ngModel)]="cfg.maxEntryPremium">
                    <mat-hint>0 = no cap. Rejects options above this price.</mat-hint>
                  </mat-form-field>
                </div>

                <!-- Volume -->
                <h3 class="group-title">Volume & OI</h3>
                <div class="form-grid">
                  <mat-form-field appearance="outline">
                    <mat-label>Volume Spike Mode</mat-label>
                    <mat-select [(ngModel)]="cfg.volumeSpikeMode">
                      <mat-option value="NORMAL">Normal (option volume)</mat-option>
                      <mat-option value="OI_PROXY">OI Proxy (for index spots)</mat-option>
                      <mat-option value="DISABLED">Disabled</mat-option>
                    </mat-select>
                    <mat-hint>OI_PROXY for indices without spot volume</mat-hint>
                  </mat-form-field>
                  <div class="toggle-row">
                    <div>
                      <span>Normalize Score (No Volume)</span>
                      <span class="toggle-hint">Adjust confidence score when spot has no volume data</span>
                    </div>
                    <mat-slide-toggle [(ngModel)]="cfg.normalizeScoreForNoVolume" color="primary"></mat-slide-toggle>
                  </div>
                </div>

                <!-- Session Overrides -->
                <h3 class="group-title">Session Overrides</h3>
                <div class="form-grid">
                  <mat-form-field appearance="outline">
                    <mat-label>Entry Cutoff Time</mat-label>
                    <input matInput type="text" placeholder="HH:mm" [(ngModel)]="cfg.entryCutoffTime">
                    <mat-hint>Leave empty to use global</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Midday Chop Start</mat-label>
                    <input matInput type="text" placeholder="HH:mm" [(ngModel)]="cfg.middayChopStart">
                    <mat-hint>Leave empty to use global</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Midday Chop End</mat-label>
                    <input matInput type="text" placeholder="HH:mm" [(ngModel)]="cfg.middayChopEnd">
                    <mat-hint>Leave empty to use global</mat-hint>
                  </mat-form-field>
                </div>

                <!-- Save button per tab -->
                <div class="actions">
                  <button mat-flat-button color="primary" (click)="save(cfg)">
                    <mat-icon>save</mat-icon> Save {{ cfg.displayName }}
                  </button>
                </div>
              </div>
            </mat-tab>
          }
        </mat-tab-group>
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

    .tab-content { padding: 24px 0; }

    .section-hdr { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; padding: 12px 16px; border-radius: 8px; border: 1px solid var(--line); background: rgba(255,255,255,.04); }
    .section-hdr h2 { font-size: 16px; font-weight: 700; color: var(--ink); margin: 0 0 2px; }
    .section-hdr p { color: var(--muted); font-size: 12px; margin: 0; }
    .icon-status { font-size: 22px; width: 22px; height: 22px; }

    .group-title { font-size: 13px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.5px; margin: 20px 0 8px; }

    .form-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px;
      padding: 16px; margin-bottom: 8px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(0,0,0,.1);
    }

    .toggle-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 16px; border: 1px solid var(--line); border-radius: 8px;
      background: rgba(255,255,255,.04); font-size: 13px; color: var(--text);
    }
    .toggle-hint { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }

    .actions { display: flex; gap: 12px; margin-top: 24px; padding-bottom: 16px; }
  `]
})
export class UnderlyingConfigPageComponent implements OnInit {
  configs: UnderlyingConfigDto[] = [];
  loaded = false;
  msg = signal('');
  error = signal('');

  constructor(private api: ApiService, private cd: ChangeDetectorRef) {}

  ngOnInit() { setTimeout(() => this.load(), 0); }

  load() {
    this.msg.set(''); this.error.set('');
    this.api.getUnderlyingConfigs().subscribe({
      next: cfgs => { this.configs = cfgs; this.loaded = true; this.cd.detectChanges(); },
      error: () => { this.error.set('Failed to load underlying configs'); this.cd.detectChanges(); }
    });
  }

  save(cfg: UnderlyingConfigDto) {
    this.msg.set(''); this.error.set('');
    this.api.updateUnderlyingConfig(cfg.underlying, cfg).subscribe({
      next: updated => {
        const idx = this.configs.findIndex(c => c.underlying === updated.underlying);
        if (idx >= 0) this.configs[idx] = updated;
        this.msg.set(`${cfg.displayName} config saved successfully`);
        this.cd.detectChanges();
      },
      error: (e: any) => { this.error.set(e?.error?.error ?? `Failed to save ${cfg.displayName} config`); this.cd.detectChanges(); }
    });
  }

  onTabChange(_index: number) {
    this.msg.set(''); this.error.set('');
  }
}
