import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AdminService, MyBrokerConfig } from '../core/admin.service';

@Component({
  selector: 'app-auth-page',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule],
  template: `
    <section class="page">
      <h1 class="page-title">Kite Auth</h1>
      <p class="page-subtitle">
        Each user logs into Zerodha with their <strong>own API key</strong>.
        The access token lands in your account row in the database and is used to place your entry &amp; exit orders.
      </p>

      <!-- Per-user Kite login (this user's account) -->
      <div class="panel highlight">
        <div class="panel-hdr">
          <mat-icon class="hdr-icon">person</mat-icon>
          <div>
            <h2>Your Kite Account</h2>
            <p>Logged in as <strong>{{ admin.currentUser()?.email || '—' }}</strong>
               · API key: <strong>{{ broker?.apiKey || 'not configured' }}</strong>
               · Token: <strong [class.pos]="broker?.tokenValid" [class.neg]="broker && !broker.tokenValid"
                         [title]="broker?.tokenVerifyMessage || ''">
                 {{ broker?.tokenValid ? 'valid ✓' : (broker?.accessTokenPresent ? 'present, rejected by broker ✗' : 'missing') }}
               </strong>
               @if (broker?.primaryAccount) { · <span class="badge primary">PRIMARY</span> }
            </p>
          </div>
        </div>
        @if (!broker?.apiKey) {
          <div class="info-strip">
            <mat-icon>info</mat-icon> Save your Kite API key on the <a href="/advalgotrade/my-broker">My Broker</a> page first.
          </div>
        } @else {
          <div class="btn-row">
            <button mat-flat-button color="primary" (click)="loginAsCurrentUser()" [disabled]="myLoading">
              <mat-icon>{{ myLoading ? 'hourglass_empty' : 'login' }}</mat-icon>
              {{ myLoading ? 'Preparing...' : 'Login to Kite (' + (admin.currentUser()?.email || 'me') + ')' }}
            </button>
            @if (myLoginUrl) {
              <a mat-stroked-button [href]="myLoginUrl" target="_blank" rel="noreferrer">
                <mat-icon>open_in_new</mat-icon> Open Kite Login
              </a>
            }
          </div>
          @if (myInfo)  { <div class="info-strip"><mat-icon>info</mat-icon>{{ myInfo }}</div> }
          @if (myError) { <div class="error-strip"><mat-icon>error_outline</mat-icon>{{ myError }}</div> }
          <p class="hint">
            After Kite redirects back, your access token will be saved to your own row in the database
            and used for your future entry/exit orders.
          </p>
        }
      </div>

      <p class="hint">
        The shared market-analysis feed uses the account flagged <strong>PRIMARY</strong> —
        a superuser sets this on the <a href="/advalgotrade/admin/users">Users</a> page.
      </p>
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
    .panel.highlight { border-color: rgba(97,168,255,.5); background: rgba(97,168,255,.04); margin-bottom: 16px; }
    .section-h3 { font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); font-weight: 700; margin: 22px 0 12px; }
    .badge.primary { display:inline-block; padding:2px 8px; border-radius:10px; font-size:10px; font-weight:700; background: rgba(97,168,255,.15); color: var(--accent); }
    .hint { font-size: 12px; color: var(--muted); margin: 10px 0 0; }
  `]
})
export class AuthPageComponent implements OnInit, OnDestroy {
  // Per-user fields
  broker: MyBrokerConfig | null = null;
  myLoading = false;
  myLoginUrl = '';
  myInfo = '';
  myError = '';

  // Refresh token status when the user returns to this tab after Kite login
  private readonly onWindowFocus = () => this.loadMyBroker();

  constructor(public readonly admin: AdminService,
              private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.loadMyBroker();
    window.addEventListener('focus', this.onWindowFocus);
    // If we just came back from Kite (?kite=linked), refresh and clean URL
    const params = new URLSearchParams(window.location.search);
    if (params.get('kite') === 'linked') {
      this.myInfo = 'Kite linked ✅ — your access token is now stored in the database.';
      history.replaceState(null, '', window.location.pathname);
    }
  }

  ngOnDestroy(): void {
    window.removeEventListener('focus', this.onWindowFocus);
  }

  loadMyBroker(): void {
    this.admin.getMyBroker().subscribe({
      next: b => { this.broker = b; this.cd.detectChanges(); },
      error: () => {}
    });
  }

  loginAsCurrentUser(): void {
    this.myError = ''; this.myInfo = ''; this.myLoading = true;
    // Open the tab synchronously (inside the click handler) so popup blockers allow it,
    // then point it at the Kite login URL once the API responds. The Google session
    // cookie is shared across tabs, so /me/broker/kite/callback still authenticates.
    const win = window.open('about:blank', '_blank');
    this.admin.kiteLoginUrl().subscribe({
      next: r => {
        this.myLoginUrl = r.loginUrl;
        this.myLoading = false;
        if (win) { win.location.href = r.loginUrl; }
        else { window.open(r.loginUrl, '_blank'); }
        this.myInfo = 'Complete the Kite login in the new tab — this page refreshes automatically when you return.';
        this.cd.detectChanges();
      },
      error: e => {
        win?.close();
        this.myError = e?.error?.error || 'Failed to build Kite login URL';
        this.myLoading = false; this.cd.detectChanges();
      }
    });
  }

}
