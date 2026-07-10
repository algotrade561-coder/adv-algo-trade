import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { interval, Subscription } from 'rxjs';
import {
  AdminService, MyBrokerConfig, SaveBrokerRequest,
  TelegramLinkChallenge, TelegramLinkStatus
} from '../core/admin.service';

@Component({
  selector: 'app-my-broker-page',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule,
    MatInputModule, MatSlideToggleModule, MatProgressSpinnerModule, MatChipsModule
  ],
  template: `
    <section class="page">
      <h1 class="page-title">My Broker &amp; Telegram</h1>
      <p class="page-subtitle">Your Kite API key/secret is encrypted before being stored. Telegram alerts use a single shared bot — link your account with a one-time code.</p>

      @if (loading) { <mat-spinner diameter="32"></mat-spinner> }

      @if (!loading) {
        <!-- Status card -->
        <div class="status-card" [class.sc-ok]="config?.configured" [class.sc-warn]="!config?.configured">
          <mat-icon class="sc-icon">{{ config?.configured ? 'verified_user' : 'warning' }}</mat-icon>
          <div class="sc-body">
            <span class="sc-title">{{ config?.configured ? 'Broker configured' : 'Not configured yet' }}</span>
            <span class="sc-detail">
              @if (config?.apiKey) { API key: <strong>{{ config!.apiKey }}</strong> · }
              Access token: <strong>{{ config?.accessTokenPresent ? 'present' : 'missing' }}</strong>
              @if (config?.primaryAccount) { · <mat-chip color="primary" highlighted>PRIMARY (market data)</mat-chip> }
            </span>
          </div>
        </div>

        <div class="grid two">
          <!-- Kite credentials -->
          <div class="panel">
            <div class="panel-hdr">
              <mat-icon class="hdr-icon">vpn_key</mat-icon>
              <div><h2>Zerodha Kite Credentials</h2><p>Stored encrypted (AES-GCM). Leave blank to keep current.</p></div>
            </div>
            <mat-form-field appearance="outline" class="full">
              <mat-label>API Key</mat-label>
              <input matInput [(ngModel)]="form.apiKey" [placeholder]="config?.apiKey || 'enter Kite API key'" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="full">
              <mat-label>API Secret</mat-label>
              <input matInput type="password" [(ngModel)]="form.apiSecret"
                     [placeholder]="config?.apiSecretMasked ? '•••• (saved, leave blank to keep)' : 'enter Kite API secret'" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="full">
              <mat-label>Source IP (SEBI static-IP rule)</mat-label>
              <input matInput [(ngModel)]="form.sourceIp"
                     [placeholder]="config?.sourceIp || 'e.g. 172.31.5.20 — leave empty to use the server default IP'"
                     pattern="^$|^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$" />
              @if (form.sourceIp && !isValidIp(form.sourceIp)) {
                <mat-error>Enter a valid IPv4 address (e.g. 172.31.5.20)</mat-error>
              }
            </mat-form-field>
            <p class="hint-text">
              The <strong>private</strong> IP on this server your API calls must leave from.
              Its associated Elastic IP must match the static IP you whitelisted at
              developers.kite.trade. Leave empty if you registered the server's primary IP.
            </p>
            @if (config?.primaryAccount) {
              <div class="info-strip">
                <mat-icon>satellite_alt</mat-icon>
                <span>This account drives <strong>market analysis</strong>. When you save new credentials the WebSocket feed switches over immediately.</span>
              </div>
            }
          </div>

          <!-- Telegram OTP link -->
          <div class="panel">
            <div class="panel-hdr">
              <mat-icon class="hdr-icon">send</mat-icon>
              <div><h2>Telegram Alerts</h2><p>One shared bot &mdash; one-time code links you to entry/exit/risk alerts.</p></div>
            </div>

            @if (linkStatus?.linked) {
              <div class="linked-card">
                <mat-icon class="ok">check_circle</mat-icon>
                <div class="linked-body">
                  <strong>Linked</strong>
                  <span>chat id: {{ linkStatus!.chatId }} · bot: <code>&#64;{{ linkStatus!.botUsername }}</code></span>
                </div>
                <div class="linked-actions">
                  <button mat-stroked-button (click)="test()" [disabled]="testing">
                    <mat-icon>{{ testing ? 'hourglass_empty' : 'campaign' }}</mat-icon>
                    {{ testing ? 'Sending...' : 'Send Test' }}
                  </button>
                  <button mat-stroked-button color="warn" (click)="unlink()" [disabled]="unlinking">
                    <mat-icon>link_off</mat-icon> Unlink
                  </button>
                </div>
              </div>
            } @else if (challenge) {
              <div class="otp-card">
                <div class="otp-label">Your one-time code</div>
                <div class="otp-code">{{ challenge.otp }}</div>
                @if (challenge.deepLink) {
                  <a mat-flat-button color="primary" [href]="challenge.deepLink" target="_blank" rel="noreferrer" class="link-btn">
                    <mat-icon>open_in_new</mat-icon>
                    Open &#64;{{ challenge.botUsername }} &amp; auto-send code
                  </a>
                  <p class="otp-hint">Or manually: open Telegram → &#64;{{ challenge.botUsername }} → send <code>/start {{ challenge.otp }}</code></p>
                } @else {
                  <p class="otp-hint">Bot username not configured. Ask an admin to set <code>TELEGRAM_BOT_USERNAME</code>.</p>
                }
                <p class="otp-hint muted">Code expires {{ formatTime(challenge.expiresAt) }} · waiting for you to start the bot…</p>
                <button mat-stroked-button (click)="cancelLink()">
                  <mat-icon>close</mat-icon> Cancel
                </button>
              </div>
            } @else {
              <p class="muted">Click below to receive a 6-digit code, then open the bot link to finish linking.</p>
              <button mat-flat-button color="primary" (click)="startLink()" [disabled]="linking">
                <mat-icon>{{ linking ? 'hourglass_empty' : 'link' }}</mat-icon>
                {{ linking ? 'Generating...' : 'Link Telegram' }}
              </button>
            }
          </div>
        </div>

        <!-- Trading toggle (risk caps controlled from Risk Profile section) -->
        <div class="panel">
          <div class="panel-hdr">
            <mat-icon class="hdr-icon">tune</mat-icon>
            <div><h2>Trading Control</h2><p>Enable/disable signal reception for this broker account. Risk caps are managed in the Risk Profile section.</p></div>
          </div>
          <div class="toggle-row">
            <mat-slide-toggle [(ngModel)]="tradingEnabled">Trading Enabled</mat-slide-toggle>
          </div>
        </div>

        <!-- Save -->
        <div class="actions">
          <button mat-flat-button color="primary" [disabled]="saving || (form.sourceIp && !isValidIp(form.sourceIp))" (click)="save()">
            <mat-icon>{{ saving ? 'hourglass_empty' : 'save' }}</mat-icon>
            {{ saving ? 'Saving...' : 'Save Changes' }}
          </button>
          @if (error) { <span class="err"><mat-icon>error_outline</mat-icon>{{ error }}</span> }
          @if (info) { <span class="ok"><mat-icon>check_circle</mat-icon>{{ info }}</span> }
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; margin-bottom: 16px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; }
    .hdr-icon { color: var(--accent); }
    .full { width: 100%; }
    .link-btn { margin: 8px 0; }
    .form-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
    .toggle-row { margin-top: 8px; }
    .actions { display: flex; align-items: center; gap: 12px; }
    .err { color: var(--bad); display:flex; gap:6px; align-items:center; font-size: 12px; }
    .ok  { color: var(--ok);  display:flex; gap:6px; align-items:center; font-size: 12px; }
    .muted { color: var(--muted); font-size: 13px; }
    .status-card { display:flex; align-items:center; gap:14px; padding:16px 20px; border-radius:12px; margin-bottom:20px; border:1px solid var(--line); background:var(--panel); }
    .sc-ok { border-color: rgba(69,209,140,.4); background: rgba(69,209,140,.04); }
    .sc-warn { border-color: rgba(242,189,75,.4); background: rgba(242,189,75,.04); }
    .sc-icon { font-size: 28px; width: 28px; height: 28px; }
    .sc-ok .sc-icon { color: var(--ok); }
    .sc-warn .sc-icon { color: var(--warn); }
    .sc-body { flex: 1; }
    .sc-title { display:block; font-size:15px; font-weight:700; color:var(--ink); }
    .sc-detail { display:block; font-size:12px; color:var(--muted); margin-top:2px; }
    .info-strip { display:flex; align-items:flex-start; gap:8px; margin: 8px 0 0; padding:10px 14px; border-radius:8px; font-size:12px; background:rgba(97,168,255,.06); border:1px solid rgba(97,168,255,.2); color:var(--accent); }
    .hint-text { font-size: 11px; color: var(--muted); margin: -8px 0 8px; }

    /* Telegram link card */
    .otp-card { display:flex; flex-direction:column; gap:10px; padding:16px; border:1px dashed rgba(97,168,255,.4); border-radius:10px; background:rgba(97,168,255,.04); }
    .otp-label { font-size: 11px; text-transform: uppercase; letter-spacing: .1em; color: var(--muted); font-weight: 700; }
    .otp-code { font-family: ui-monospace, Menlo, monospace; font-size: 36px; font-weight: 800; letter-spacing: 6px; color: var(--ink); }
    .otp-hint { font-size: 12px; color: var(--muted); margin: 0; }
    .otp-hint code { background: rgba(0,0,0,.2); padding: 2px 6px; border-radius: 4px; }

    .linked-card { display:flex; align-items:center; gap:12px; padding:16px; border-radius:10px; background:rgba(69,209,140,.06); border:1px solid rgba(69,209,140,.3); }
    .linked-card .ok { color: var(--ok); }
    .linked-body { flex: 1; display:flex; flex-direction:column; }
    .linked-body strong { color: var(--ink); }
    .linked-body span { font-size: 12px; color: var(--muted); }
    .linked-actions { display:flex; gap:8px; flex-wrap:wrap; }
  `]
})
export class MyBrokerPageComponent implements OnInit, OnDestroy {
  config: MyBrokerConfig | null = null;
  challenge: TelegramLinkChallenge | null = null;
  linkStatus: TelegramLinkStatus | null = null;
  form: SaveBrokerRequest = {};
  tradingEnabled = true;
  loading = false;
  saving = false;
  testing = false;
  linking = false;
  unlinking = false;
  error = '';
  info = '';

  private pollSub?: Subscription;

  constructor(private readonly admin: AdminService, private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void { this.load(); }
  ngOnDestroy(): void { this.pollSub?.unsubscribe(); }

  load(): void {
    this.loading = true;
    this.admin.getMyBroker().subscribe({
      next: c => {
        this.config = c;
        this.tradingEnabled = c?.tradingEnabled ?? true;
        this.loading = false; this.cd.detectChanges();
      },
      error: e => { this.error = e?.error?.error || 'Failed to load'; this.loading = false; this.cd.detectChanges(); }
    });
    this.refreshLinkStatus();
  }

  refreshLinkStatus(): void {
    this.admin.telegramLinkStatus().subscribe({
      next: s => { this.linkStatus = s; this.cd.detectChanges(); },
      error: () => {}
    });
  }

  startLink(): void {
    this.error = ''; this.info = ''; this.linking = true;
    this.admin.startTelegramLink().subscribe({
      next: c => {
        this.challenge = c;
        this.linking = false;
        this.cd.detectChanges();
        // Poll status every 3s while challenge is active
        this.pollSub?.unsubscribe();
        this.pollSub = interval(3000).subscribe(() => {
          this.admin.telegramLinkStatus().subscribe({
            next: s => {
              this.linkStatus = s;
              if (s.linked) {
                this.challenge = null;
                this.pollSub?.unsubscribe();
                this.info = 'Telegram linked ✅';
                this.load();
              }
              this.cd.detectChanges();
            }
          });
        });
      },
      error: e => { this.error = e?.error?.error || 'Failed to start link'; this.linking = false; this.cd.detectChanges(); }
    });
  }

  cancelLink(): void {
    this.challenge = null;
    this.pollSub?.unsubscribe();
    this.cd.detectChanges();
  }

  unlink(): void {
    if (!confirm('Stop receiving Telegram alerts?')) return;
    this.unlinking = true;
    this.admin.unlinkTelegram().subscribe({
      next: () => { this.unlinking = false; this.linkStatus = { linked: false }; this.info = 'Unlinked'; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Unlink failed'; this.unlinking = false; this.cd.detectChanges(); }
    });
  }

  save(): void {
    this.error = ''; this.info = ''; this.saving = true;
    const payload: SaveBrokerRequest = { ...this.form, tradingEnabled: this.tradingEnabled };
    this.admin.saveMyBroker(payload).subscribe({
      next: c => {
        this.config = c;
        this.info = 'Saved';
        this.form = {};
        this.saving = false; this.cd.detectChanges();
      },
      error: e => { this.error = e?.error?.error || 'Save failed'; this.saving = false; this.cd.detectChanges(); }
    });
  }

  test(): void {
    this.error = ''; this.info = ''; this.testing = true;
    this.admin.testTelegram().subscribe({
      next: r => {
        this.testing = false;
        if (r.sent) this.info = 'Test message sent — check Telegram.';
        else this.error = 'Send failed: ' + (r.error || 'status ' + r.status);
        this.cd.detectChanges();
      },
      error: e => { this.error = e?.error?.error || 'Test failed'; this.testing = false; this.cd.detectChanges(); }
    });
  }

  formatTime(t: string): string {
    try { return new Date(t).toLocaleTimeString(); } catch { return t; }
  }

  isValidIp(ip: string): boolean {
    if (!ip || ip.trim() === '') return true; // empty is valid (means "use default")
    return /^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$/.test(ip.trim());
  }
}
