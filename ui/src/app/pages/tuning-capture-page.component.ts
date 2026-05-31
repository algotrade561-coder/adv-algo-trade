import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { ApiService, TuningCaptureDto, TuningCaptureUpdate } from '../core/api.service';

/**
 * /tuning-capture — per-strategy capture toggle page (Phase 1, Commit 10).
 *
 * Renders one row per StrategyType with a master toggle + per-event-type checkboxes.
 * Save writes through to the REST API which records audit rows for changed fields.
 *
 * Phase 1 minimal styling — the page is functional, not polished. Phase 6 layout
 * pass will fold this into the broader settings design.
 */
@Component({
  selector: 'app-tuning-capture-page',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSlideToggleModule, MatTableModule, MatCheckboxModule, MatDialogModule
  ],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Tuning Capture</h1>
          <p class="page-subtitle">
            Per-strategy capture toggles for the unified tuning pipeline.
            Defaults are OFF — flip to ON only for the strategies you are actively tuning.
            All changes are audited.
          </p>
        </div>
      </div>

      @if (msg()) { <div class="toast-ok">{{ msg() }}</div> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }

      @if (!loaded()) {
        <div style="text-align:center;padding:40px;color:var(--muted)">Loading…</div>
      }

      @if (loaded()) {
        <table mat-table [dataSource]="rows()" class="capture-table" style="width:100%;margin-top:12px">
          <ng-container matColumnDef="strategy">
            <th mat-header-cell *matHeaderCellDef>Strategy</th>
            <td mat-cell *matCellDef="let r">
              <strong>{{ r.displayName }}</strong>
              <div style="font-size:11px;color:var(--muted)">{{ r.strategy }}</div>
            </td>
          </ng-container>

          <ng-container matColumnDef="enabled">
            <th mat-header-cell *matHeaderCellDef>Capture</th>
            <td mat-cell *matCellDef="let r">
              <mat-slide-toggle [(ngModel)]="r.captureEnabled" color="primary"></mat-slide-toggle>
            </td>
          </ng-container>

          <ng-container matColumnDef="eval">
            <th mat-header-cell *matHeaderCellDef>Eval</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureEvaluations" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="signal">
            <th mat-header-cell *matHeaderCellDef>Signal</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureSignals" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="exec">
            <th mat-header-cell *matHeaderCellDef>Exec</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureExecutions" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="exit">
            <th mat-header-cell *matHeaderCellDef>Exit</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureExits" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="fwd">
            <th mat-header-cell *matHeaderCellDef>Forward</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureForward" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="shadow">
            <th mat-header-cell *matHeaderCellDef>Shadow</th>
            <td mat-cell *matCellDef="let r">
              <mat-checkbox [(ngModel)]="r.captureShadow" [disabled]="!r.captureEnabled"></mat-checkbox>
            </td>
          </ng-container>

          <ng-container matColumnDef="window">
            <th mat-header-cell *matHeaderCellDef>Window (s)</th>
            <td mat-cell *matCellDef="let r">
              <mat-form-field appearance="outline" style="width:80px">
                <input matInput type="number" min="1" [(ngModel)]="r.episodeWindowSec" [disabled]="!r.captureEnabled">
              </mat-form-field>
            </td>
          </ng-container>

          <ng-container matColumnDef="notes">
            <th mat-header-cell *matHeaderCellDef>Notes</th>
            <td mat-cell *matCellDef="let r">
              <mat-form-field appearance="outline" style="width:180px">
                <input matInput [(ngModel)]="r.notes" placeholder="optional">
              </mat-form-field>
            </td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef>Actions</th>
            <td mat-cell *matCellDef="let r">
              <button mat-button color="primary" (click)="save(r)">Save</button>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns;"></tr>
        </table>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; }
    .page-title { font-size: 24px; margin: 0 0 8px; }
    .page-subtitle { color: var(--muted); margin: 0 0 16px; max-width: 720px; }
    .toast-ok { background: #d1fae5; color: #064e3b; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; }
    .toast-warn { background: #fee2e2; color: #7f1d1d; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; }
    .capture-table th { font-size: 11px; text-transform: uppercase; color: var(--muted); }
    .capture-table td { vertical-align: middle; }
  `]
})
export class TuningCapturePageComponent implements OnInit {

  readonly columns = ['strategy', 'enabled', 'eval', 'signal', 'exec', 'exit', 'fwd', 'shadow', 'window', 'notes', 'actions'];

  loaded = signal(false);
  rows = signal<TuningCaptureDto[]>([]);
  msg = signal('');
  error = signal('');

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.api.listTuningCapture().subscribe({
      next: (rs) => {
        this.rows.set(rs);
        this.loaded.set(true);
      },
      error: (e) => {
        this.error.set('Failed to load tuning capture: ' + (e?.message || e));
        this.loaded.set(true);
      }
    });
  }

  save(row: TuningCaptureDto): void {
    const reason = window.prompt('Reason for this change (audit log):', '') || '';
    const body: TuningCaptureUpdate = {
      captureEnabled: row.captureEnabled,
      captureEvaluations: row.captureEvaluations,
      captureSignals: row.captureSignals,
      captureExecutions: row.captureExecutions,
      captureExits: row.captureExits,
      captureForward: row.captureForward,
      captureShadow: row.captureShadow,
      episodeWindowSec: row.episodeWindowSec || 60,
      notes: row.notes ?? null,
      reason
    };
    this.api.updateTuningCapture(row.strategy, body).subscribe({
      next: (saved) => {
        Object.assign(row, saved);
        this.msg.set(`${row.displayName} updated.`);
        setTimeout(() => this.msg.set(''), 3000);
      },
      error: (e) => {
        this.error.set('Save failed: ' + (e?.message || e));
        setTimeout(() => this.error.set(''), 5000);
      }
    });
  }
}
