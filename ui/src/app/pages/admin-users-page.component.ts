import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AdminService, AdminUser, CreateUserRequest } from '../core/admin.service';

@Component({
  selector: 'app-admin-users-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatSlideToggleModule, MatTableModule, MatProgressSpinnerModule, MatTooltipModule
  ],
  template: `
    <section class="page">
      <h1 class="page-title">User Management</h1>
      <p class="page-subtitle">Add or disable users · assign roles · {{ admin.currentUser()?.role || '—' }} access</p>

      @if (!admin.isAdmin()) {
        <div class="warn-card">
          <mat-icon>block</mat-icon>
          You don't have permission to manage users. Contact an administrator.
        </div>
      } @else {
        <!-- Create form -->
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">person_add</mat-icon>
            <div><h2>Add User</h2><p>Invite a teammate by email. They sign in with Google.</p></div>
          </div>
          <div class="form-row">
            <mat-form-field appearance="outline">
              <mat-label>Email</mat-label>
              <input matInput type="email" [(ngModel)]="form.email" placeholder="user@example.com" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Name (optional)</mat-label>
              <input matInput [(ngModel)]="form.name" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Role</mat-label>
              <mat-select [(ngModel)]="form.role">
                <mat-option value="USER">USER</mat-option>
                @if (admin.isSuperUser()) {
                  <mat-option value="ADMIN">ADMIN</mat-option>
                  <mat-option value="SUPERUSER">SUPERUSER</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <button mat-flat-button color="primary" [disabled]="!form.email || saving" (click)="create()">
              <mat-icon>add</mat-icon> Add User
            </button>
          </div>
          @if (error) { <div class="error-strip"><mat-icon>error_outline</mat-icon>{{ error }}</div> }
          @if (info) { <div class="info-strip"><mat-icon>check_circle</mat-icon>{{ info }}</div> }
        </div>

        <!-- User list -->
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">group</mat-icon>
            <div><h2>Users ({{ users.length }})</h2><p>Click role/enabled to edit. Changes save immediately.</p></div>
            <button mat-stroked-button (click)="load()">
              <mat-icon>refresh</mat-icon> Refresh
            </button>
          </div>

          @if (loading) { <mat-spinner diameter="32"></mat-spinner> }

          @if (!loading && users.length > 0) {
            <div class="table-wrap">
            <table mat-table [dataSource]="users" class="users-table">
              <ng-container matColumnDef="email">
                <th mat-header-cell *matHeaderCellDef>Email</th>
                <td mat-cell *matCellDef="let u">{{ u.email }}</td>
              </ng-container>
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>Name</th>
                <td mat-cell *matCellDef="let u">{{ u.name || '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="role">
                <th mat-header-cell *matHeaderCellDef>Role</th>
                <td mat-cell *matCellDef="let u">
                  <mat-select [(value)]="u.role" (selectionChange)="updateRole(u, $event.value)" [disabled]="!canEditRole(u)">
                    <mat-option value="USER">USER</mat-option>
                    @if (admin.isSuperUser()) {
                      <mat-option value="ADMIN">ADMIN</mat-option>
                      <mat-option value="SUPERUSER">SUPERUSER</mat-option>
                    }
                  </mat-select>
                </td>
              </ng-container>
              <ng-container matColumnDef="enabled">
                <th mat-header-cell *matHeaderCellDef>Enabled</th>
                <td mat-cell *matCellDef="let u">
                  <mat-slide-toggle [checked]="u.enabled" (change)="toggleEnabled(u, $event.checked)"></mat-slide-toggle>
                </td>
              </ng-container>
              <ng-container matColumnDef="lastLogin">
                <th mat-header-cell *matHeaderCellDef>Last Login</th>
                <td mat-cell *matCellDef="let u">{{ formatTime(u.lastLoginAt) }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let u">
                  @if (admin.isSuperUser()) {
                    <button mat-icon-button [matTooltip]="'Set as Primary (market analysis)'"
                            (click)="togglePrimary(u)" [color]="u.primaryAccount ? 'primary' : ''">
                      <mat-icon>{{ u.primaryAccount ? 'satellite_alt' : 'satellite' }}</mat-icon>
                    </button>
                  }
                  <button mat-icon-button color="warn" [disabled]="!canDelete(u)" (click)="remove(u)">
                    <mat-icon>delete_outline</mat-icon>
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="cols"></tr>
              <tr mat-row *matRowDef="let row; columns: cols"></tr>
            </table>
            </div>
          } @else if (!loading) {
            <p class="muted">No users yet — add one above.</p>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; margin-bottom: 16px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
    .panel-hdr > div { flex: 1; }
    .hdr-icon { color: var(--accent); }
    .form-row { display: grid; grid-template-columns: 2fr 1.5fr 1fr auto; gap: 12px; align-items: start; }
    .users-table { width: 100%; }
    .muted { color: var(--muted); font-size: 13px; }
    .warn-card { display:flex; gap:10px; align-items:center; padding:14px; border-radius:8px; background: rgba(242,189,75,.08); border:1px solid rgba(242,189,75,.3); color: var(--warn); }
    .error-strip { display:flex; align-items:center; gap:8px; margin-top:12px; padding:10px 14px; border-radius:8px; font-size:12px; background:rgba(255,113,106,.06); border:1px solid rgba(255,113,106,.2); color:var(--bad); }
    .info-strip  { display:flex; align-items:center; gap:8px; margin-top:12px; padding:10px 14px; border-radius:8px; font-size:12px; background:rgba(69,209,140,.06); border:1px solid rgba(69,209,140,.2); color:var(--ok); }
    /* Horizontal scroll so all table columns are reachable on narrow screens. */
    .table-wrap { width: 100%; overflow-x: auto; -webkit-overflow-scrolling: touch; }
    @media (max-width: 640px) {
      .page { padding: 16px 12px; }
      .panel { padding: 16px; }
      .form-row { grid-template-columns: 1fr; }
      .panel-hdr { flex-wrap: wrap; }
      .users-table { min-width: 620px; }
    }
  `]
})
export class AdminUsersPageComponent implements OnInit {
  users: AdminUser[] = [];
  loading = false;
  saving = false;
  error = '';
  info = '';
  readonly cols = ['email', 'name', 'role', 'enabled', 'lastLogin', 'actions'];
  form: CreateUserRequest = { email: '', name: '', role: 'USER', enabled: true };

  constructor(public readonly admin: AdminService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    if (!this.admin.isAdmin()) return;
    this.loading = true;
    this.admin.listUsers().subscribe({
      next: u => { this.users = u; this.loading = false; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Failed to load users'; this.loading = false; this.cd.detectChanges(); }
    });
  }

  create(): void {
    this.error = ''; this.info = ''; this.saving = true;
    this.admin.createUser({ ...this.form, email: this.form.email.trim().toLowerCase() }).subscribe({
      next: u => {
        this.users = [...this.users, u];
        this.info = `Added ${u.email}`;
        this.form = { email: '', name: '', role: 'USER', enabled: true };
        this.saving = false; this.cd.detectChanges();
      },
      error: e => { this.error = e?.error?.error || 'Failed to add user'; this.saving = false; this.cd.detectChanges(); }
    });
  }

  updateRole(u: AdminUser, role: AdminUser['role']): void {
    this.admin.updateUser(u.id, { role }).subscribe({
      next: updated => { Object.assign(u, updated); this.info = `Role updated for ${u.email}`; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Update failed'; this.cd.detectChanges(); }
    });
  }

  toggleEnabled(u: AdminUser, enabled: boolean): void {
    this.admin.updateUser(u.id, { enabled }).subscribe({
      next: updated => { Object.assign(u, updated); this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Update failed'; u.enabled = !enabled; this.cd.detectChanges(); }
    });
  }

  togglePrimary(u: AdminUser): void {
    const next = !u.primaryAccount;
    this.admin.setPrimaryAccount(u.id, next).subscribe({
      next: r => {
        // Clear flag on all other rows, set on this one
        this.users.forEach(x => x.primaryAccount = (x.id === u.id ? r.primaryAccount : false));
        this.info = next ? `Primary account → ${u.email}` : `Primary cleared for ${u.email}`;
        this.cd.detectChanges();
      },
      error: e => {
        const reason = e?.error?.error || 'Failed to set primary';
        this.error = reason.includes('no broker config')
          ? `${u.email} has not saved their Kite API key yet — they must configure it on the My Broker page before they can be set as primary.`
          : reason;
        this.cd.detectChanges();
      }
    });
  }

  remove(u: AdminUser): void {
    if (!confirm(`Delete ${u.email}? This cannot be undone.`)) return;
    this.admin.deleteUser(u.id).subscribe({
      next: () => { this.users = this.users.filter(x => x.id !== u.id); this.info = `Deleted ${u.email}`; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Delete failed'; this.cd.detectChanges(); }
    });
  }

  canEditRole(u: AdminUser): boolean {
    // ADMIN can only manage USER rows. SUPERUSER can manage everyone but themselves.
    if (this.admin.isSuperUser()) return u.email !== this.admin.currentUser()?.email;
    return u.role === 'USER';
  }
  canDelete(u: AdminUser): boolean {
    if (u.email === this.admin.currentUser()?.email) return false;
    if (this.admin.isSuperUser()) return true;
    return u.role === 'USER';
  }
  formatTime(t?: string): string {
    if (!t) return '—';
    try { return new Date(t).toLocaleString(); } catch { return t; }
  }
}
