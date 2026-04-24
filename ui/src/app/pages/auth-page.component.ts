import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../core/api.service';
import { KiteLoginResponse, KiteSessionResponse } from '../core/models';

@Component({
  selector: 'app-auth-page',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section class="page">
      <h1 class="page-title">Kite Auth</h1>
      <p class="page-subtitle">Zerodha login and daily access token management.</p>

      <!-- Session Status -->
      <div class="status-card" [class.sc-ok]="session?.authenticated" [class.sc-warn]="session && !session.authenticated">
        <mat-icon class="sc-icon">{{ session?.authenticated ? 'verified_user' : 'shield' }}</mat-icon>
        <div class="sc-body">
          <span class="sc-title">{{ session?.authenticated ? 'Authenticated' : 'Not Authenticated' }}</span>
          @if (session?.authenticated) {
            <span class="sc-detail">User: {{ session!.userId }} · Since: {{ session!.authenticatedAt }}</span>
          } @else {
            <span class="sc-detail">Start a session or complete the login flow below.</span>
          }
        </div>
        <button mat-stroked-button [disabled]="sessionLoading" (click)="checkSession()">
          <mat-icon>{{ sessionLoading ? 'hourglass_empty' : 'refresh' }}</mat-icon>
          {{ sessionLoading ? 'Checking...' : 'Check Session' }}
        </button>
      </div>

      <div class="grid two">
        <!-- Login Flow -->
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">login</mat-icon>
            <div>
              <h2>Login Flow</h2>
              <p>Launch Zerodha login and capture the daily access token via callback.</p>
            </div>
          </div>
          <div class="btn-row">
            <button mat-flat-button color="primary" (click)="initiateLogin()">
              <mat-icon>launch</mat-icon> Initiate Login
            </button>
            @if (login?.loginUrl) {
              <a mat-stroked-button [href]="login!.loginUrl" target="_blank" rel="noreferrer">
                <mat-icon>open_in_new</mat-icon> Open Kite Login
              </a>
            }
          </div>
          @if (login?.message) {
            <div class="info-strip">
              <mat-icon>info</mat-icon> {{ login!.message }}
            </div>
          }
          @if (loginError) {
            <div class="error-strip">
              <mat-icon>error_outline</mat-icon> {{ loginError }}
            </div>
          }
        </div>

        <!-- Session Validate -->
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">key</mat-icon>
            <div>
              <h2>Session</h2>
              <p>Validate the persisted or configured access token.</p>
            </div>
          </div>
          <button mat-flat-button color="primary" [disabled]="sessionLoading" (click)="checkSession()">
            <mat-icon>{{ sessionLoading ? 'hourglass_empty' : 'verified_user' }}</mat-icon>
            {{ sessionLoading ? 'Validating...' : 'Validate Session' }}
          </button>
          @if (session) {
            <div class="detail-grid">
              <div class="dg-item"><span>Status</span><strong [class.pos]="session.authenticated" [class.neg]="!session.authenticated">{{ session.authenticated ? 'Authenticated' : 'Needs Login' }}</strong></div>
              <div class="dg-item"><span>User ID</span><strong>{{ session.userId || '-' }}</strong></div>
              <div class="dg-item"><span>Authenticated At</span><strong>{{ session.authenticatedAt || '-' }}</strong></div>
            </div>
          }
        </div>
      </div>
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1000px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; }
    .hdr-icon { font-size: 20px; width: 20px; height: 20px; color: var(--accent); margin-top: 1px; }
    .btn-row { display: flex; gap: 10px; flex-wrap: wrap; }

    .status-card {
      display: flex; align-items: center; gap: 14px;
      padding: 16px 20px; border-radius: 12px; margin-bottom: 20px;
      border: 1px solid var(--line); background: var(--panel);
    }
    .sc-ok { border-color: rgba(69,209,140,.4); background: rgba(69,209,140,.04); }
    .sc-warn { border-color: rgba(242,189,75,.4); background: rgba(242,189,75,.04); }
    .sc-icon { font-size: 28px; width: 28px; height: 28px; }
    .sc-ok .sc-icon { color: var(--ok); }
    .sc-warn .sc-icon { color: var(--warn); }
    .sc-body { flex: 1; }
    .sc-title { display: block; font-size: 15px; font-weight: 700; color: var(--ink); }
    .sc-detail { display: block; font-size: 12px; color: var(--muted); margin-top: 2px; }

    .info-strip {
      display: flex; align-items: center; gap: 8px; margin-top: 12px;
      padding: 10px 14px; border-radius: 8px; font-size: 12px;
      background: rgba(97,168,255,.06); border: 1px solid rgba(97,168,255,.2); color: var(--accent);
    }
    .info-strip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .error-strip {
      display: flex; align-items: center; gap: 8px; margin-top: 12px;
      padding: 10px 14px; border-radius: 8px; font-size: 12px;
      background: rgba(255,113,106,.06); border: 1px solid rgba(255,113,106,.2); color: var(--bad);
    }
    .error-strip mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; margin-top: 14px; }
    .dg-item { padding: 10px 12px; border-radius: 8px; background: rgba(255,255,255,.02); border: 1px solid var(--line); }
    .dg-item span { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
    .dg-item strong { display: block; font-size: 13px; color: var(--ink); margin-top: 3px; }
    .pos { color: var(--ok) !important; }
    .neg { color: var(--bad) !important; }
  `]
})
export class AuthPageComponent implements OnInit {
  login?: KiteLoginResponse;
  loginError = '';
  session?: KiteSessionResponse;
  sessionLoading = false;

  constructor(private readonly api: ApiService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void { this.checkSession(); }

  initiateLogin(): void {
    this.loginError = '';
    const win = window.open('about:blank', '_blank');
    if (win) win.opener = null;
    this.api.kiteLogin().subscribe({
      next: r => {
        this.login = r;
        if (r.loginUrl) { win ? (win.location.href = r.loginUrl) : window.open(r.loginUrl, '_blank', 'noopener,noreferrer'); }
        else { win?.close(); }
      },
      error: () => { win?.close(); this.loginError = 'Failed to initiate login flow.'; }
    });
  }

  checkSession(): void {
    this.sessionLoading = true;
    this.api.kiteSession().subscribe({
      next: s => { this.session = s; this.sessionLoading = false; this.cd.detectChanges(); },
      error: () => { this.sessionLoading = false; this.cd.detectChanges(); }
    });
  }
}
