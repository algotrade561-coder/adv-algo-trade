import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AdminService, EipRow, ReconciliationReport } from '../core/admin.service';

/**
 * Phase 3 — AWS / Source-IP management dashboard (design §13.2). SUPERUSER-only.
 * Resource-centric view: reconciles the allocation table against live AWS to surface
 * capacity, user↔IP mappings, and removable (cost-leaking) Elastic IPs.
 */
@Component({
  selector: 'app-aws-ip-page',
  standalone: true,
  imports: [
    CommonModule, MatButtonModule, MatIconModule, MatTableModule,
    MatProgressSpinnerModule, MatTooltipModule
  ],
  template: `
    <section class="page">
      <div class="hdr">
        <div>
          <h1 class="page-title">AWS / Source IPs</h1>
          <p class="page-subtitle">
            Reconciles allocations against live AWS · finds orphaned Elastic IPs that keep billing
          </p>
        </div>
        <button mat-stroked-button [disabled]="loading" (click)="load()">
          <mat-icon>refresh</mat-icon> Reconcile
        </button>
      </div>

      @if (error) { <div class="banner bad">{{ error }}</div> }
      @if (info) { <div class="banner ok">{{ info }}</div> }
      @if (report && !report.automationEnabled) {
        <div class="banner warn">
          ip-automation is OFF — this is a table-only view (no live AWS reads). Release actions are disabled.
        </div>
      }

      @if (loading) {
        <div class="loading"><mat-spinner diameter="28"></mat-spinner> <span>Reconciling…</span></div>
      } @else if (report) {

        <div class="cards">
          <div class="card">
            <span class="card-label">EIPs in region</span>
            <strong class="card-val" [class.warn]="report.capacity.eipRemaining <= 1">
              {{ report.capacity.eipUsed }} / {{ report.capacity.eipMax }}
            </strong>
            <span class="card-sub">{{ report.capacity.eipRemaining }} free vs quota</span>
          </div>
          <div class="card">
            <span class="card-label">App-managed EIPs</span>
            <strong class="card-val">{{ report.capacity.managedEips }}</strong>
            <span class="card-sub">{{ report.capacity.activeAllocations }} active alloc · {{ report.capacity.externalEips }} external (ALB)</span>
          </div>
          <div class="card" [class.card-bad]="report.removableCount > 0">
            <span class="card-label">Removable (waste)</span>
            <strong class="card-val" [class.bad]="report.removableCount > 0">{{ report.removableCount }}</strong>
            <span class="card-sub">~\${{ report.removableMonthlyCostUsd | number:'1.2-2' }}/mo</span>
          </div>
          <div class="card" [class.card-bad]="report.failedOrReleasing > 0">
            <span class="card-label">Needs attention</span>
            <strong class="card-val" [class.bad]="report.failedOrReleasing > 0">{{ report.failedOrReleasing }}</strong>
            <span class="card-sub">failed / releasing · drift {{ report.driftCount }}</span>
          </div>
        </div>

        <div class="table-wrap">
          <table mat-table [dataSource]="report.mappings" class="tbl">
            <ng-container matColumnDef="disposition">
              <th mat-header-cell *matHeaderCellDef>State</th>
              <td mat-cell *matCellDef="let r">
                <span class="disp" [ngClass]="dispClass(r)" [matTooltip]="r.note">{{ label(r.disposition) }}</span>
              </td>
            </ng-container>
            <ng-container matColumnDef="publicIp">
              <th mat-header-cell *matHeaderCellDef>Public IP</th>
              <td mat-cell *matCellDef="let r"><span class="mono">{{ r.publicIp || '—' }}</span></td>
            </ng-container>
            <ng-container matColumnDef="privateIp">
              <th mat-header-cell *matHeaderCellDef>Private IP</th>
              <td mat-cell *matCellDef="let r"><span class="mono">{{ r.privateIp || '—' }}</span></td>
            </ng-container>
            <ng-container matColumnDef="user">
              <th mat-header-cell *matHeaderCellDef>User</th>
              <td mat-cell *matCellDef="let r">
                @if (r.email) {
                  {{ r.email }}
                  @if (r.userDeleted) { <span class="tag tag-bad">removed</span> }
                  @else if (!r.userEnabled) { <span class="tag">disabled</span> }
                } @else if (r.userDeleted) {
                  <span class="muted">deleted user</span> <span class="tag tag-bad">removed</span>
                } @else { <span class="muted">—</span> }
              </td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Alloc</th>
              <td mat-cell *matCellDef="let r">
                <span class="mono small">{{ r.status || (r.disposition === 'PROTECTED' ? 'SYSTEM' : '—') }}</span>
                @if (r.manuallyManaged) { <span class="tag">manual</span> }
              </td>
            </ng-container>
            <ng-container matColumnDef="eni">
              <th mat-header-cell *matHeaderCellDef>ENI / Instance</th>
              <td mat-cell *matCellDef="let r"><span class="mono small">{{ r.eniId || '—' }}<br>{{ r.instanceId || '' }}</span></td>
            </ng-container>
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let r">
                @if (r.disposition === 'REMOVABLE' && r.allocationId) {
                  <button mat-stroked-button color="warn" [disabled]="!report.automationEnabled || releasing === r.allocationId"
                          matTooltip="Release this Elastic IP (stops its hourly charge)" (click)="release(r)">
                    <mat-icon>link_off</mat-icon> Release
                  </button>
                }
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="cols"></tr>
            <tr mat-row *matRowDef="let row; columns: cols;"></tr>
          </table>
        </div>

        @if (report.mappings.length === 0) {
          <p class="muted empty">No Elastic IPs or allocations found.</p>
        }
        <p class="foot">Reconciled {{ report.generatedAt | date:'medium' }} · ENI {{ report.ourEni || '—' }} · {{ report.ourInstance || '—' }}</p>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; margin: 0 auto; }
    .page-title { font-size: 24px; font-weight: 800; margin: 0; color: var(--ink); }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 4px 0 0; }
    .hdr { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; margin-bottom:16px; }
    .banner { padding:10px 14px; border-radius:8px; font-size:13px; margin-bottom:14px; }
    .banner.bad { background:rgba(255,113,106,.14); color:var(--bad); }
    .banner.ok { background:rgba(69,209,140,.14); color:var(--ok); }
    .banner.warn { background:rgba(240,180,41,.14); color:var(--warn, #f0b429); }
    .loading { display:flex; align-items:center; gap:10px; color:var(--muted); padding:24px 0; }
    .cards { display:grid; grid-template-columns:repeat(4, minmax(0,1fr)); gap:12px; margin-bottom:18px; }
    .card { border:1px solid var(--line); border-radius:10px; padding:14px; background:rgba(255,255,255,.035); display:flex; flex-direction:column; gap:2px; }
    .card-bad { border-color:rgba(255,113,106,.4); }
    .card-label { font-size:11px; text-transform:uppercase; letter-spacing:.4px; color:var(--muted); font-weight:700; }
    .card-val { font-size:24px; font-weight:800; color:var(--ink); }
    .card-val.bad { color:var(--bad); } .card-val.warn { color:var(--warn, #f0b429); }
    .card-sub { font-size:11px; color:var(--muted); }
    .table-wrap { overflow-x:auto; border:1px solid var(--line); border-radius:10px; }
    .tbl { width:100%; background:transparent; }
    .mono { font-family:monospace; font-size:12px; }
    .small { font-size:11px; color:var(--muted); }
    .muted { color:var(--muted); }
    .empty { padding:18px 0; }
    .tag { font-size:10px; padding:1px 6px; border-radius:5px; background:rgba(160,160,160,.18); color:var(--muted); margin-left:6px; }
    .tag-bad { background:rgba(255,113,106,.16); color:var(--bad); }
    .disp { font-size:10px; font-weight:700; letter-spacing:.3px; padding:2px 8px; border-radius:6px; text-transform:uppercase; }
    .disp.nec { background:rgba(69,209,140,.14); color:var(--ok); }
    .disp.rem { background:rgba(255,113,106,.16); color:var(--bad); }
    .disp.ext { background:rgba(160,160,160,.14); color:var(--muted); }
    .disp.tbl { background:rgba(240,180,41,.14); color:var(--warn, #f0b429); }
    .disp.prot { background:rgba(99,150,255,.16); color:#6396ff; }
    .foot { color:var(--muted); font-size:11px; margin-top:12px; }
    @media (max-width: 760px) { .cards { grid-template-columns:repeat(2,1fr); } .page { padding:16px 12px; } }
  `]
})
export class AwsIpPageComponent implements OnInit {
  report?: ReconciliationReport;
  loading = false;
  releasing = '';
  error = '';
  info = '';
  readonly cols = ['disposition', 'publicIp', 'privateIp', 'user', 'status', 'eni', 'actions'];

  constructor(public readonly admin: AdminService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true; this.error = ''; this.info = '';
    this.admin.awsIpReconcile().subscribe({
      next: r => { this.report = r; this.loading = false; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Failed to reconcile'; this.loading = false; this.cd.detectChanges(); }
    });
  }

  release(r: EipRow): void {
    if (!r.allocationId) return;
    if (!confirm(`Release Elastic IP ${r.publicIp || r.allocationId}? This frees the quota slot and stops its charge. If the user returns they'll need a new IP re-whitelisted in Kite.`)) return;
    this.releasing = r.allocationId; this.error = ''; this.info = '';
    this.admin.awsIpReleaseEip(r.allocationId).subscribe({
      next: () => { this.info = `Released ${r.publicIp || r.allocationId}`; this.releasing = ''; this.load(); },
      error: e => { this.error = e?.error?.error || 'Release failed'; this.releasing = ''; this.cd.detectChanges(); }
    });
  }

  label(d: EipRow['disposition']): string {
    return { NECESSARY: 'In use', REMOVABLE: 'Removable', EXTERNAL: 'External', TABLE_ONLY: 'No live EIP', PROTECTED: 'Primary IP' }[d] || d;
  }

  dispClass(r: EipRow): string {
    return { NECESSARY: 'nec', REMOVABLE: 'rem', EXTERNAL: 'ext', TABLE_ONLY: 'tbl', PROTECTED: 'prot' }[r.disposition] || 'ext';
  }
}
