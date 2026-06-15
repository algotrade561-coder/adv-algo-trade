import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DecimalPipe } from '@angular/common';
import { interval, Subscription, catchError, forkJoin, of } from 'rxjs';
import { AdminService, MyTradingState } from '../core/admin.service';
import { ApiService } from '../core/api.service';
import { PnlSnapshot, ApiRecord } from '../core/models';

@Component({
  selector: 'app-my-trading-page',
  standalone: true,
  imports: [CommonModule, DecimalPipe, MatButtonModule, MatIconModule, MatSlideToggleModule, MatProgressSpinnerModule],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">My Trading</h1>
          <p class="page-subtitle">Your personal trading state, positions, P&amp;L, and controls — independent of other users.</p>
        </div>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
      </div>

      @if (loading) { <mat-spinner diameter="32"></mat-spinner> }

      @if (!loading && state) {
        <!-- Status Card -->
        <div class="status-card" [class.sc-ok]="state.entryAllowed" [class.sc-warn]="!state.entryAllowed">
          <mat-icon class="sc-icon">{{ state.entryAllowed ? 'check_circle' : 'block' }}</mat-icon>
          <div class="sc-body">
            <span class="sc-title">{{ state.entryAllowed ? 'Entries Allowed' : 'Entries Blocked' }}</span>
            <span class="sc-detail">
              {{ state.email }}
              @if (state.primaryAccount) { · <strong>PRIMARY</strong> }
              · Broker: <strong [class.pos]="state.brokerAuthenticated" [class.neg]="!state.brokerAuthenticated">
                {{ state.brokerAuthenticated ? 'Connected' : 'Not connected' }}
              </strong>
              @if (state.tokenExpiresAt) { · Token expires: {{ formatTime(state.tokenExpiresAt) }} }
            </span>
          </div>
        </div>

        <!-- Controls -->
        <div class="grid two">
          <!-- State chips + controls -->
          <div class="panel">
            <div class="panel-hdr">
              <mat-icon class="hdr-icon">tune</mat-icon>
              <div><h2>My Trading Controls</h2><p>Start/stop your personal trading gate. Does not affect other users.</p></div>
            </div>

            <div class="chip-row">
              <div class="chip" [class.c-ok]="state.running" [class.c-warn]="!state.running">
                <mat-icon>{{ state.running ? 'play_circle' : 'pause_circle' }}</mat-icon>
                {{ state.running ? 'Running' : 'Stopped' }}
              </div>
              <div class="chip" [class.c-bad]="state.killSwitch" [class.c-ok]="!state.killSwitch">
                <mat-icon>{{ state.killSwitch ? 'block' : 'verified_user' }}</mat-icon>
                Kill {{ state.killSwitch ? 'ON' : 'Clear' }}
              </div>
              <div class="chip" [class.c-bad]="state.haltMode === 'HARD'" [class.c-warn]="state.haltMode === 'SOFT'" [class.c-ok]="state.haltMode === 'NONE'">
                <mat-icon>pause_circle</mat-icon> {{ state.haltMode }}
              </div>
              <div class="chip" [class.c-ok]="state.dailyApproved" [class.c-warn]="!state.dailyApproved">
                <mat-icon>{{ state.dailyApproved ? 'thumb_up' : 'pending' }}</mat-icon>
                {{ state.dailyApproved ? 'Approved' : 'Pending' }}
              </div>
              <div class="chip" [class.c-ok]="state.tradingEnabled" [class.c-warn]="!state.tradingEnabled">
                <mat-icon>{{ state.tradingEnabled ? 'toggle_on' : 'toggle_off' }}</mat-icon>
                {{ state.tradingEnabled ? 'Enabled' : 'Disabled' }}
              </div>
            </div>

            <div class="btn-row">
              @if (!state.running) {
                <button mat-flat-button color="primary" (click)="start()"><mat-icon>play_arrow</mat-icon> Start</button>
              } @else {
                <button mat-stroked-button (click)="stop()"><mat-icon>stop</mat-icon> Stop</button>
              }
              @if (!state.killSwitch) {
                <button mat-stroked-button color="warn" (click)="killSwitch(true)"><mat-icon>block</mat-icon> Kill Switch</button>
              } @else {
                <button mat-flat-button (click)="killSwitch(false)"><mat-icon>check</mat-icon> Clear Kill</button>
              }
              @if (state.haltMode === 'NONE') {
                <button mat-stroked-button (click)="halt('SOFT')"><mat-icon>pause</mat-icon> Soft Halt</button>
              } @else {
                <button mat-flat-button color="primary" (click)="resume()"><mat-icon>play_circle</mat-icon> Resume</button>
              }
            </div>

            <div class="btn-row" style="margin-top:12px">
              @if (!state.dailyApproved) {
                <button mat-flat-button color="primary" (click)="approve()"><mat-icon>thumb_up</mat-icon> Approve Today</button>
              } @else {
                <button mat-stroked-button (click)="revokeApproval()"><mat-icon>thumb_down</mat-icon> Revoke</button>
              }
              <button mat-stroked-button (click)="extendLimit()" [disabled]="state.extensionsUsedToday >= 2">
                <mat-icon>add_circle</mat-icon> Extend Limit ({{ state.extensionsUsedToday }}/2)
              </button>
            </div>
            @if (state.dailyLossExtension > 0) {
              <p class="ext-note">Extended by ₹{{ state.dailyLossExtension | number:'1.0-0' }}</p>
            }
          </div>

          <!-- P&L card -->
          <div class="panel">
            <div class="panel-hdr">
              <mat-icon class="hdr-icon">account_balance_wallet</mat-icon>
              <div><h2>My P&amp;L Today</h2><p>Your personal realized + unrealized P&amp;L.</p></div>
            </div>

            <div class="pnl-grid">
              <div class="pnl-card">
                <span class="pnl-label">Total P&amp;L</span>
                <span class="pnl-val" [class.pos]="(pnl?.totalPnl ?? 0) >= 0" [class.neg]="(pnl?.totalPnl ?? 0) < 0">
                  ₹{{ (pnl?.totalPnl ?? 0) | number:'1.2-2' }}
                </span>
              </div>
              <div class="pnl-card">
                <span class="pnl-label">Realized</span>
                <span class="pnl-val" [class.pos]="(pnl?.realizedPnl ?? 0) >= 0" [class.neg]="(pnl?.realizedPnl ?? 0) < 0">
                  ₹{{ (pnl?.realizedPnl ?? 0) | number:'1.2-2' }}
                </span>
              </div>
              <div class="pnl-card">
                <span class="pnl-label">Unrealized</span>
                <span class="pnl-val" [class.pos]="(pnl?.unrealizedPnl ?? 0) >= 0" [class.neg]="(pnl?.unrealizedPnl ?? 0) < 0">
                  ₹{{ (pnl?.unrealizedPnl ?? 0) | number:'1.2-2' }}
                </span>
              </div>
              <div class="pnl-card">
                <span class="pnl-label">Open Positions</span>
                <span class="pnl-val">{{ positions.length }}</span>
              </div>
            </div>

            @if (positions.length > 0) {
              <h3 class="sub-hdr">Open Positions</h3>
              <div class="pos-list">
                @for (p of positions; track p['instrumentKey']) {
                  <div class="pos-row">
                    <span class="pos-inst">{{ p['instrumentKey'] }}</span>
                    <span class="pos-qty">{{ p['quantity'] }} qty</span>
                    <span class="pos-pnl" [class.pos]="asNum(p['unrealizedPnl']) >= 0" [class.neg]="asNum(p['unrealizedPnl']) < 0">
                      ₹{{ asNum(p['unrealizedPnl']) | number:'1.2-2' }}
                    </span>
                  </div>
                }
              </div>
            } @else {
              <p class="muted" style="margin-top:12px">No open positions.</p>
            }
          </div>
        </div>

        @if (error) { <div class="err"><mat-icon>error_outline</mat-icon>{{ error }}</div> }
        @if (info) { <div class="ok"><mat-icon>check_circle</mat-icon>{{ info }}</div> }
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .top-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0; }
    .panel p { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .panel-hdr { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; }
    .hdr-icon { color: var(--accent); }

    .status-card { display:flex; align-items:center; gap:14px; padding:16px 20px; border-radius:12px; margin-bottom:20px; border:1px solid var(--line); background:var(--panel); }
    .sc-ok { border-color: rgba(69,209,140,.4); background: rgba(69,209,140,.04); }
    .sc-warn { border-color: rgba(255,113,106,.4); background: rgba(255,113,106,.04); }
    .sc-icon { font-size: 28px; width: 28px; height: 28px; }
    .sc-ok .sc-icon { color: var(--ok); }
    .sc-warn .sc-icon { color: var(--bad); }
    .sc-body { flex: 1; }
    .sc-title { display:block; font-size:15px; font-weight:700; color:var(--ink); }
    .sc-detail { display:block; font-size:12px; color:var(--muted); margin-top:2px; }

    .chip-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
    .chip { display: flex; align-items: center; gap: 5px; padding: 6px 12px; border-radius: 20px; font-size: 12px; font-weight: 600; border: 1px solid var(--line); background: var(--panel); }
    .chip mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .c-ok { border-color: rgba(69,209,140,.4); color: var(--ok); background: rgba(69,209,140,.05); }
    .c-warn { border-color: rgba(242,189,75,.4); color: var(--warn); background: rgba(242,189,75,.05); }
    .c-bad { border-color: rgba(255,113,106,.4); color: var(--bad); background: rgba(255,113,106,.05); }

    .btn-row { display: flex; gap: 8px; flex-wrap: wrap; }
    .ext-note { font-size: 12px; color: var(--warn); margin: 10px 0 0; }

    .pnl-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
    .pnl-card { padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: rgba(255,255,255,.02); }
    .pnl-label { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--muted); }
    .pnl-val { display: block; font-size: 18px; font-weight: 800; color: var(--ink); margin-top: 4px; }

    .sub-hdr { font-size: 12px; font-weight: 700; color: var(--muted); margin: 16px 0 8px; text-transform: uppercase; }
    .pos-list { display: flex; flex-direction: column; gap: 6px; }
    .pos-row { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--line); background: rgba(255,255,255,.02); font-size: 12px; }
    .pos-inst { flex: 1; font-weight: 600; color: var(--ink); }
    .pos-qty { color: var(--muted); }
    .pos-pnl { font-weight: 700; }

    .pos, .pos { color: var(--ok); }
    .neg { color: var(--bad); }
    .muted { color: var(--muted); font-size: 13px; }
    .err { display:flex; gap:6px; align-items:center; font-size:12px; color:var(--bad); margin-top:12px; }
    .ok  { display:flex; gap:6px; align-items:center; font-size:12px; color:var(--ok); margin-top:12px; }
  `]
})
export class MyTradingPageComponent implements OnInit, OnDestroy {
  state: MyTradingState | null = null;
  pnl: PnlSnapshot | null = null;
  positions: ApiRecord[] = [];
  loading = false;
  error = '';
  info = '';
  private pollSub?: Subscription;

  constructor(
    private readonly admin: AdminService,
    private readonly api: ApiService,
    private readonly cd: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.load();
    this.pollSub = interval(5000).subscribe(() => this.refreshData());
  }

  ngOnDestroy(): void { this.pollSub?.unsubscribe(); }

  load(): void {
    this.loading = true;
    this.error = '';
    forkJoin({
      state: this.admin.getMyTradingState(),
      pnl: this.api.pnl().pipe(catchError(() => of(null as PnlSnapshot | null))),
      positions: this.api.positions().pipe(catchError(() => of([] as ApiRecord[])))
    }).subscribe({
      next: ({ state, pnl, positions }) => {
        this.state = state;
        this.pnl = pnl;
        this.positions = positions;
        this.loading = false;
        this.cd.detectChanges();
      },
      error: e => {
        this.error = e?.error?.error || 'Failed to load';
        this.loading = false;
        this.cd.detectChanges();
      }
    });
  }

  private refreshData(): void {
    forkJoin({
      state: this.admin.getMyTradingState().pipe(catchError(() => of(null as MyTradingState | null))),
      pnl: this.api.pnl().pipe(catchError(() => of(null as PnlSnapshot | null))),
      positions: this.api.positions().pipe(catchError(() => of([] as ApiRecord[])))
    }).subscribe(({ state, pnl, positions }) => {
      if (state) this.state = state;
      if (pnl) this.pnl = pnl;
      this.positions = positions;
      this.cd.detectChanges();
    });
  }

  start(): void { this.admin.myTradingStart().subscribe(s => { this.state = s; this.info = 'Started'; this.cd.detectChanges(); }); }
  stop(): void { this.admin.myTradingStop().subscribe(s => { this.state = s; this.info = 'Stopped'; this.cd.detectChanges(); }); }
  killSwitch(v: boolean): void { this.admin.myTradingKillSwitch(v).subscribe(s => { this.state = s; this.info = v ? 'Kill switch ON' : 'Kill switch cleared'; this.cd.detectChanges(); }); }
  halt(mode: 'SOFT' | 'HARD'): void { this.admin.myTradingHalt(mode).subscribe(s => { this.state = s; this.info = mode + ' halt active'; this.cd.detectChanges(); }); }
  resume(): void { this.admin.myTradingResume().subscribe(s => { this.state = s; this.info = 'Resumed'; this.cd.detectChanges(); }); }
  approve(): void { this.admin.myTradingApprove().subscribe(s => { this.state = s; this.info = 'Approved for today'; this.cd.detectChanges(); }); }
  revokeApproval(): void { this.admin.myTradingRevokeApproval().subscribe(s => { this.state = s; this.info = 'Approval revoked'; this.cd.detectChanges(); }); }
  extendLimit(): void {
    this.admin.myTradingExtendLimit().subscribe({
      next: s => { this.state = s; this.info = 'Limit extended'; this.cd.detectChanges(); },
      error: e => { this.error = e?.error?.error || 'Extension failed'; this.cd.detectChanges(); }
    });
  }

  formatTime(t: string): string {
    try { return new Date(t).toLocaleTimeString(); } catch { return t; }
  }

  asNum(v: unknown): number {
    return typeof v === 'number' ? v : 0;
  }
}
