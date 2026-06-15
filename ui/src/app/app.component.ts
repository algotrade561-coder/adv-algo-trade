import { Component, ChangeDetectorRef, HostListener, Inject, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, forkJoin, interval, of, Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { ApiService } from './core/api.service';
import { ServerStatusService } from './core/server-status.service';
import { AdminService } from './core/admin.service';
import { HealthResponse } from './core/models';

interface NavItem {
  label: string;
  path: string;
  icon: string;
  /** If set, only show this nav item to users with one of these roles */
  requiresRole?: ('ADMIN' | 'SUPERUSER')[];
}

interface HealthDialogData {
  api: HealthResponse;
  broker: HealthResponse;
  database: HealthResponse;
  checkedAt: string;
}

@Component({
  selector: 'app-health-dialog',
  standalone: true,
  imports: [MatButtonModule, MatDialogModule, MatIconModule],
  template: `
    <div class="health-title" mat-dialog-title>
      <div class="health-title-main">
        <mat-icon>health_and_safety</mat-icon>
        <div>
          <strong>API Health</strong>
          <span>Checked {{ data.checkedAt }}</span>
        </div>
      </div>
      <span class="badge" [class.ok]="overallUp" [class.bad]="!overallUp">
        {{ overallUp ? 'Healthy' : 'Needs attention' }}
      </span>
    </div>

    <mat-dialog-content>
      <div class="health-grid">
        @for (section of sections; track section.label) {
          <section class="health-card">
            <div class="row">
              <mat-icon [class.status-ok]="section.up" [class.status-bad]="!section.up">{{ section.icon }}</mat-icon>
              <div>
                <h3>{{ section.label }}</h3>
                <span class="badge" [class.ok]="section.up" [class.bad]="!section.up">
                  {{ section.status }}
                </span>
              </div>
            </div>
            <div class="health-rows">
              @for (row of section.rows; track row.label) {
                <div class="health-row">
                  <span>{{ row.label }}</span>
                  <strong>{{ row.value }}</strong>
                </div>
              }
            </div>
          </section>
        }
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-stroked-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .health-title {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding-bottom: 8px;
    }

    .health-title-main {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .health-title-main mat-icon {
      color: var(--accent);
    }

    .health-title strong,
    .health-title span {
      display: block;
    }

    .health-title strong {
      color: var(--ink);
      font-size: 20px;
      font-weight: 800;
    }

    .health-title span {
      color: var(--muted);
      font-size: 13px;
      margin-top: 2px;
    }

    .health-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 14px;
      min-width: min(820px, 82vw);
    }

    .health-card {
      border: 1px solid var(--line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.035);
      padding: 16px;
    }

    .health-card h3 {
      color: var(--ink);
      font-size: 16px;
      margin: 0 0 8px;
    }

    .health-rows {
      display: grid;
      gap: 8px;
      margin-top: 14px;
    }

    .health-row {
      display: grid;
      gap: 4px;
      padding: 10px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: rgba(0, 0, 0, 0.14);
    }

    .health-row span {
      color: var(--muted);
      font-size: 12px;
      font-weight: 800;
      text-transform: uppercase;
    }

    .health-row strong {
      color: var(--text);
      font-size: 14px;
      overflow-wrap: anywhere;
    }

    @media (max-width: 900px) {
      .health-grid {
        grid-template-columns: 1fr;
        min-width: 0;
      }

      .health-title {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  `]
})
export class HealthDialogComponent {
  constructor(@Inject(MAT_DIALOG_DATA) readonly data: HealthDialogData) {
  }

  get overallUp(): boolean {
    return this.sections.every((section) => section.up);
  }

  get sections(): Array<{
    label: string;
    icon: string;
    status: string;
    up: boolean;
    rows: Array<{ label: string; value: string }>;
  }> {
    return [
      this.section('API', 'dns', this.data.api),
      this.section('Broker', 'account_balance', this.data.broker),
      this.section('Database', 'storage', this.data.database)
    ];
  }

  private section(label: string, icon: string, value: HealthResponse) {
    const status = String(value?.status ?? 'UNKNOWN');
    return {
      label,
      icon,
      status,
      up: status.toUpperCase() === 'UP',
      rows: Object.entries(value ?? {})
        .filter(([key]) => key !== 'status')
        .map(([key, fieldValue]) => ({ label: this.label(key), value: this.display(fieldValue) }))
    };
  }

  private label(value: string): string {
    return value
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (char) => char.toUpperCase());
  }

  private display(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatSidenavModule,
    MatToolbarModule,
    MatListModule
  ],
  template: `
    @if (!authChecked) {
      <div class="auth-loading">
        <div class="auth-spinner"></div>
        <p>Verifying authentication…</p>
      </div>
    } @else {
    <mat-sidenav-container class="shell">
      <mat-sidenav #sidenav [mode]="mobile ? 'over' : 'side'" [opened]="!mobile" class="nav"
                   (closedStart)="null">
        <div class="brand">
          <div class="brand-mark">
            <mat-icon>show_chart</mat-icon>
          </div>
          <div class="brand-text">
            <strong>AlgoTrader Pro</strong>
            <span>Kite control console</span>
          </div>
        </div>
        <mat-nav-list>
          @for (item of visibleNavItems(); track item.path) {
            <a mat-list-item [routerLink]="item.path" routerLinkActive="active-link"
               (click)="onNavClick()">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>{{ item.label }}</span>
            </a>
          }
        </mat-nav-list>
      </mat-sidenav>

      <mat-sidenav-content>
        <mat-toolbar class="topbar">
          <button class="menu-toggle" mat-icon-button (click)="sidenav.toggle()">
            <mat-icon>menu</mat-icon>
          </button>
          <div>
            <span class="topbar-title">Trading Operations</span>
            <span class="topbar-subtitle">Scanner, execution, auth, and reports</span>
          </div>
          <span class="spacer"></span>
          <button mat-stroked-button (click)="openHealthDialog()" class="health-btn">
            <mat-icon>health_and_safety</mat-icon>
            <span class="health-label">API Health</span>
          </button>
        </mat-toolbar>
        @if (!serverStatus.online()) {
          <div class="offline-bar">
            <mat-icon>cloud_off</mat-icon>
            <span>Cannot reach server — check that the backend is running. Retrying automatically every 15s.</span>
          </div>
        }
        <router-outlet></router-outlet>
      </mat-sidenav-content>
    </mat-sidenav-container>
    }
  `,
  styles: [`
    .shell {
      min-height: 100vh;
      background: transparent;
    }

    .menu-toggle {
      display: none;
    }

    .nav {
      width: 260px;
      border-right: 1px solid rgba(72, 81, 87, 0.86);
      background:
        linear-gradient(180deg, rgba(24, 34, 49, 0.98), rgba(20, 39, 43, 0.96) 48%, rgba(32, 40, 22, 0.92)),
        repeating-linear-gradient(0deg, rgba(97, 168, 255, 0.05) 0 1px, transparent 1px 56px);
      backdrop-filter: blur(12px);
      box-shadow: 10px 0 26px rgba(0, 0, 0, 0.24);
    }

    .brand {
      display: flex;
      gap: 12px;
      align-items: center;
      padding: 22px 18px;
      border-bottom: 1px solid rgba(72, 81, 87, 0.92);
      min-height: 86px;
      background: linear-gradient(135deg, rgba(18, 49, 54, 0.9), rgba(23, 38, 58, 0.9), rgba(53, 42, 20, 0.72));
    }

    .brand strong {
      display: block;
      letter-spacing: 0;
      color: var(--ink);
    }

    .brand span {
      display: block;
      color: var(--muted);
      font-size: 13px;
      margin-top: 2px;
    }

    .brand-mark {
      width: 42px;
      height: 42px;
      border-radius: 8px;
      display: grid;
      place-items: center;
      flex-shrink: 0;
      background: linear-gradient(135deg, var(--accent), var(--cyan));
      color: #071018;
      box-shadow: 0 12px 22px rgba(0, 0, 0, 0.32);
    }

    .brand-mark mat-icon {
      font-size: 24px;
      width: 24px;
      height: 24px;
    }

    .active-link {
      background: linear-gradient(90deg, var(--accent-soft), var(--cyan-soft));
      color: var(--accent-strong);
      border-radius: 8px;
      margin: 2px 8px;
      border: 1px solid #315a82;
      box-shadow: 0 8px 18px rgba(0, 0, 0, 0.24);
    }

    a[mat-list-item] {
      margin: 2px 8px;
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid transparent;
      transition: background-color 160ms ease, border-color 160ms ease, box-shadow 160ms ease, transform 120ms ease;
    }

    a[mat-list-item]:hover {
      background: rgba(255, 255, 255, 0.08);
      border-color: rgba(72, 81, 87, 0.8);
      box-shadow: 0 8px 18px rgba(0, 0, 0, 0.22);
      transform: translateX(2px);
    }

    .topbar {
      position: sticky;
      top: 0;
      z-index: 10;
      background:
        linear-gradient(90deg, rgba(53, 25, 34, 0.94), rgba(23, 38, 58, 0.94), rgba(18, 51, 35, 0.94), rgba(53, 42, 20, 0.9));
      border-bottom: 1px solid var(--line);
      min-height: 72px;
      padding: 0 24px;
      backdrop-filter: blur(12px);
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.24);
    }

    .topbar-title,
    .topbar-subtitle {
      display: block;
      line-height: 1.25;
    }

    .topbar-title {
      color: var(--ink);
      font-weight: 800;
      font-size: 18px;
      letter-spacing: 0;
    }

    .topbar-subtitle {
      color: var(--muted);
      font-size: 13px;
      margin-top: 2px;
    }

    /* ── Tablet: collapse nav to icons only ── */
    @media (max-width: 900px) and (min-width: 769px) {
      .nav {
        width: 82px;
      }

      .brand-text,
      a span[matListItemTitle],
      .topbar-subtitle {
        display: none;
      }

      .topbar {
        padding: 0 16px;
      }
    }

    /* ── Mobile: overlay nav + hamburger ── */
    @media (max-width: 768px) {
      .menu-toggle {
        display: inline-flex;
        margin-right: 8px;
        color: var(--ink);
      }

      .nav {
        width: 280px;
      }

      .topbar {
        padding: 0 12px;
        min-height: 56px;
      }

      .topbar-title {
        font-size: 15px;
      }

      .topbar-subtitle {
        display: none;
      }

      .health-label {
        display: none;
      }

      .health-btn {
        min-width: 40px;
        padding: 0 8px;
      }
    }

    /* ── Offline banner ── */
    .offline-bar {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 24px;
      background: rgba(255,113,106,.12);
      border-bottom: 1px solid rgba(255,113,106,.35);
      color: var(--bad);
      font-size: 13px;
      font-weight: 600;
    }
    .offline-bar mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }

    .auth-loading {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 100vh;
      background: #0d1117;
      color: #8b949e;
      gap: 16px;
    }
    .auth-loading p { font-size: 14px; margin: 0; }
    .auth-spinner {
      width: 36px; height: 36px;
      border: 3px solid #21262d;
      border-top-color: #58a6ff;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
  `]
})
export class AppComponent implements OnInit, OnDestroy {
  @ViewChild('sidenav') sidenav!: MatSidenav;
  mobile = false;
  authChecked = false;

  private static readonly MOBILE_BREAKPOINT = 768;
  private healthSub?: Subscription;

  constructor(
    private readonly api: ApiService,
    private readonly dialog: MatDialog,
    private readonly cd: ChangeDetectorRef,
    readonly serverStatus: ServerStatusService,
    readonly admin: AdminService
  ) {
    this.checkMobile();
  }

  ngOnInit(): void {
    // Check auth status on startup — redirect to Google login if not authenticated
    this.api.checkAuth().pipe(catchError(() => of({ authenticated: false }))).subscribe((auth: any) => {
      if (!auth || !auth.authenticated) {
        if (!sessionStorage.getItem('auth_redirecting')) {
          sessionStorage.setItem('auth_redirecting', 'true');
          window.location.href = '/advalgotrade/oauth2/authorization/google';
        } else {
          // Already tried redirecting — show app to avoid infinite loop
          this.authChecked = true;
          this.cd.detectChanges();
        }
        return;
      }
      sessionStorage.removeItem('auth_redirecting');
      this.authChecked = true;
      // Load role-aware current-user (for admin nav gating)
      this.admin.loadCurrentUser().subscribe({ next: () => this.cd.detectChanges(), error: () => {} });
      this.cd.detectChanges();
      this.serverStatus.markOnline();
    });

    this.healthSub = interval(15000).subscribe(() => {
      this.api.health().pipe(catchError(() => of(null))).subscribe(r => {
        if (r?.status) this.serverStatus.markOnline();
      });
    });
  }

  ngOnDestroy(): void { this.healthSub?.unsubscribe(); }

  @HostListener('window:resize')
  onResize(): void {
    this.checkMobile();
  }

  private checkMobile(): void {
    this.mobile = window.innerWidth <= AppComponent.MOBILE_BREAKPOINT;
  }

  onNavClick(): void {
    if (this.mobile) {
      this.sidenav.close();
    }
  }

  readonly navItems: NavItem[] = [
    { label: 'Dashboard', path: 'dashboard', icon: 'dashboard' },
    { label: 'Execution', path: 'execution', icon: 'play_circle' },
    { label: 'Strategies', path: 'strategies', icon: 'auto_awesome' },
    { label: 'Index Config', path: 'index-config', icon: 'category' },
    { label: 'Monitoring', path: 'monitoring', icon: 'monitoring' },
    { label: 'Reports', path: 'reports', icon: 'description' },
    { label: 'Tuning Capture', path: 'tuning-capture', icon: 'science' },
    { label: 'Diagnostics', path: 'diagnostics', icon: 'monitor_heart' },
    { label: 'Kite Auth', path: 'auth', icon: 'lock_open' },
    { label: 'My Trading', path: 'my-trading', icon: 'person_pin' },
    { label: 'My Broker', path: 'my-broker', icon: 'account_balance_wallet' },
    { label: 'Users', path: 'admin/users', icon: 'group', requiresRole: ['ADMIN', 'SUPERUSER'] },
    { label: 'Settings', path: 'settings', icon: 'tune' }
  ];

  /** Filters out admin-only nav items for non-admin users. */
  visibleNavItems(): NavItem[] {
    return this.navItems.filter(i => {
      if (!i.requiresRole) return true;
      const role = this.admin.currentUser()?.role;
      return role !== undefined && i.requiresRole.includes(role as 'ADMIN' | 'SUPERUSER');
    });
  }

  openHealthDialog(): void {
    forkJoin({
      api: this.api.health().pipe(catchError((error) => of(this.errorHealth(error)))),
      broker: this.api.brokerHealth().pipe(catchError((error) => of(this.errorHealth(error)))),
      database: this.api.databaseHealth().pipe(catchError((error) => of(this.errorHealth(error))))
    }).subscribe((health) => {
      this.dialog.open(HealthDialogComponent, {
        data: {
          ...health,
          checkedAt: new Date().toLocaleTimeString()
        },
        panelClass: 'health-dialog-panel',
        maxWidth: '920px',
        width: 'min(920px, 94vw)'
      });
    });
  }

  private errorHealth(error: unknown): HealthResponse {
    return {
      status: 'DOWN',
      error: error instanceof Error ? error.message : 'Health check failed'
    };
  }
}
