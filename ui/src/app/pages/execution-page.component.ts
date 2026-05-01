import { ChangeDetectorRef, Component, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { ApiService, StrategyDto } from '../core/api.service';
import { ApiRecord, ExecutionMode, MarketDataMode, RuntimeStatus, TradingMode, UnderlyingSymbol } from '../core/models';

@Component({
  selector: 'app-execution-page',
  standalone: true,
  imports: [
    ReactiveFormsModule, FormsModule, RouterLink, DecimalPipe,
    MatButtonModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatSelectModule, MatSlideToggleModule
  ],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">Execution Control</h1>
          <p class="page-subtitle">Scanner, data feeds, routing, strategies, and manual orders.</p>
        </div>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
      </div>

      @if (toast()) { <div class="toast ok">{{ toast() }}</div> }
      @if (toastErr()) { <div class="toast bad">{{ toastErr() }}</div> }

      <!-- ── Emergency Stop ─────────────────────────────────────────── -->
      <div class="e-stop-panel" [class.e-stop-active]="runtime?.haltMode === 'HARD'">
        <div class="e-stop-left">
          <mat-icon class="e-stop-icon">emergency</mat-icon>
          <div>
            <div class="e-stop-title">
              {{ runtime?.haltMode === 'HARD' ? '🚨 HARD HALT ACTIVE — ALL ACTIVITY STOPPED' : 'Emergency Stop' }}
            </div>
            <div class="e-stop-desc">
              {{ runtime?.haltMode === 'HARD'
                ? 'Scanner, entries, and exits are all frozen. Use Resume to restore normal operation.'
                : 'Immediately freezes the scanner, blocks all new entries, and stops all exit monitors. Use only for serious issues.' }}
            </div>
          </div>
        </div>
        <div class="e-stop-btns">
          @if (runtime?.haltMode !== 'HARD') {
            <button class="e-stop-btn" (click)="hardStop()">
              <mat-icon>dangerous</mat-icon> EMERGENCY STOP
            </button>
          } @else {
            <button class="e-stop-resume" (click)="resumeHalt()">
              <mat-icon>play_circle</mat-icon> Resume
            </button>
          }
        </div>
      </div>

      <!-- ── Status Chips ──────────────────────────────────────────── -->
      <div class="chip-row">
        <div class="chip" [class.c-ok]="runtime?.running" [class.c-warn]="runtime && !runtime.running">
          <mat-icon>{{ runtime?.running ? 'play_circle' : 'pause_circle' }}</mat-icon> Scanner {{ runtime?.running ? 'Running' : 'Stopped' }}
        </div>
        <div class="chip" [class.c-ok]="runtime?.webSocketConnected" [class.c-bad]="runtime && !runtime.webSocketConnected">
          <mat-icon>{{ runtime?.webSocketConnected ? 'wifi' : 'wifi_off' }}</mat-icon> WS {{ runtime?.webSocketConnected ? 'Connected' : 'Off' }}
        </div>
        <div class="chip" [class.c-ok]="runtime?.schedulerEnabled" [class.c-muted]="runtime && !runtime.schedulerEnabled">
          <mat-icon>schedule</mat-icon> Poll {{ runtime?.schedulerEnabled ? 'On' : 'Off' }}
        </div>
        <div class="chip" [class.c-bad]="runtime?.killSwitch" [class.c-ok]="runtime && !runtime.killSwitch">
          <mat-icon>{{ runtime?.killSwitch ? 'block' : 'verified_user' }}</mat-icon> Kill {{ runtime?.killSwitch ? 'ON' : 'Clear' }}
        </div>
        <div class="chip" [class.c-bad]="runtime?.haltMode === 'HARD'" [class.c-warn]="runtime?.haltMode === 'SOFT'" [class.c-ok]="runtime?.haltMode === 'NONE'">
          <mat-icon>pause_circle</mat-icon> {{ runtime?.haltMode ?? 'NONE' }}
        </div>
        <div class="chip" [class.c-ok]="runtime?.dailyApproved" [class.c-warn]="!runtime?.dailyApproved">
          <mat-icon>{{ runtime?.dailyApproved ? 'thumb_up' : 'pending' }}</mat-icon> {{ runtime?.dailyApproved ? 'Approved' : 'Not Approved' }}
        </div>
        <div class="chip c-muted">
          <mat-icon>settings</mat-icon> {{ runtime?.configuredMode }} · {{ runtime?.marketDataMode }} · {{ runtime?.executionMode }}
        </div>
      </div>

      <!-- ── WebSocket Scanner (Primary) ─────────────────────────── -->
      <div class="panel section-primary">
        <div class="section-hdr">
          <div class="section-icon si-ws"><mat-icon>wifi</mat-icon></div>
          <div>
            <h2>WebSocket Scanner <span class="tag-primary">PRIMARY</span></h2>
            <p class="section-desc">Live tick stream → candle aggregation → instant signal on candle close.</p>
          </div>
          <span class="spacer"></span>
          <span class="status-pill" [class.sp-ok]="runtime?.webSocketConnected" [class.sp-off]="runtime && !runtime.webSocketConnected">
            {{ runtime?.webSocketConnected ? 'Connected' : 'Disconnected' }}
          </span>
        </div>

        <div class="ws-grid">
          <div class="ws-card">
            <span class="ws-label">Scanner</span>
            <div class="btn-row">
              <button class="sb sb-start" [class.sb-active]="runtime?.running" mat-stroked-button (click)="start()">
                {{ runtime?.running ? 'Running' : 'Start' }}
              </button>
              <button class="sb sb-stop" [class.sb-active]="runtime && !runtime.running" mat-stroked-button (click)="stop()">
                {{ runtime && !runtime.running ? 'Stopped' : 'Stop' }}
              </button>
            </div>
          </div>
          <div class="ws-card">
            <span class="ws-label">Connection</span>
            <div class="btn-row">
              <button mat-stroked-button class="fc-btn" (click)="reconnectWebSocket()"><mat-icon>refresh</mat-icon> Connect</button>
              <button mat-stroked-button class="fc-btn fc-btn-warn" [disabled]="!runtime?.webSocketConnected" (click)="disconnectWebSocket()"><mat-icon>power_off</mat-icon> Disconnect</button>
            </div>
          </div>
          <div class="ws-card">
            <span class="ws-label">Kill Switch</span>
            <div class="btn-row">
              <button class="sb sb-kill" [class.sb-active]="runtime?.killSwitch" mat-stroked-button (click)="setKillSwitch(true)">
                {{ runtime?.killSwitch ? 'Kill ON' : 'Activate' }}
              </button>
              <button class="sb sb-clear" [class.sb-active]="runtime && !runtime.killSwitch" mat-stroked-button (click)="setKillSwitch(false)">
                {{ runtime && !runtime.killSwitch ? 'Clear' : 'Clear Kill' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- ── REST Poll Scheduler (Fallback) ────────────────────────── -->
      <div class="panel section-fallback">
        <div class="section-hdr">
          <div class="section-icon si-rest"><mat-icon>schedule</mat-icon></div>
          <div>
            <h2>REST Poll Scheduler <span class="tag-fallback">FALLBACK</span></h2>
            <p class="section-desc">Polls historical candles via REST API every 60s. Use when WebSocket is unavailable.</p>
          </div>
          <span class="spacer"></span>
          <mat-slide-toggle [checked]="runtime?.schedulerEnabled ?? false" (change)="toggleScheduler($event.checked)" color="primary"></mat-slide-toggle>
        </div>
        <div class="rest-status">
          <div class="rest-indicator" [class.ri-on]="runtime?.schedulerEnabled" [class.ri-off]="runtime && !runtime.schedulerEnabled">
            <mat-icon>{{ runtime?.schedulerEnabled ? 'play_circle' : 'pause_circle' }}</mat-icon>
            <span>{{ runtime?.schedulerEnabled ? 'Active — polling every 60s' : 'Disabled — not polling' }}</span>
          </div>
          <p class="rest-note">Both triggers call the same scan logic. REST poll is only needed if WebSocket drops and can't reconnect.</p>
        </div>
      </div>

      <!-- ── Halt Mode & Daily Approval ──────────────────────────── -->
      <div class="grid two" style="margin-top:16px">
        <div class="panel">
          <h2><mat-icon class="hi">pause_circle</mat-icon> Halt Mode</h2>
          <p class="panel-desc">Soft halt blocks new entries but keeps managing open positions. Hard halt stops everything.</p>
          <div class="btn-row">
            <button class="sb sb-warn" [class.sb-active]="runtime?.haltMode === 'SOFT'" mat-stroked-button (click)="softHalt()">Soft Halt</button>
            <button class="sb sb-start" [class.sb-active]="runtime?.haltMode === 'NONE'" mat-stroked-button (click)="resumeHalt()">Resume</button>
          </div>
        </div>
        <div class="panel">
          <h2><mat-icon class="hi">today</mat-icon> Daily Approval</h2>
          <p class="panel-desc">Approve trading for today. Auto-approves at 10:30 AM. Extensions resume after daily loss limit breach.</p>
          <div class="btn-row">
            <button class="sb sb-start" [class.sb-active]="runtime?.dailyApproved" mat-stroked-button (click)="approveToday()">Approve Today</button>
            <button class="sb sb-stop" [class.sb-active]="!runtime?.dailyApproved" mat-stroked-button (click)="revokeApproval()">Revoke</button>
            <button class="sb sb-ext" mat-stroked-button (click)="extendLimit()" [disabled]="(runtime?.extensionsUsedToday ?? 0) >= 2">
              Extend Limit ({{ runtime?.extensionsUsedToday ?? 0 }}/2)
            </button>
          </div>
          @if ((runtime?.dailyLossExtension ?? 0) > 0) {
            <p class="ext-note">Extended by &#8377;{{ runtime?.dailyLossExtension | number:'1.0-0' }}</p>
          }
        </div>
      </div>

      <!-- ── Underlyings ───────────────────────────────────────────── -->
      <div class="panel" style="margin-top:16px">
        <h2><mat-icon class="hi">bar_chart</mat-icon> Scan Underlyings</h2>
        <div class="toggle-strip">
          @for (u of underlyings; track u) {
            <mat-slide-toggle [checked]="isUnderlyingEnabled(u)" (change)="setUnderlying(u, $event.checked)">{{ u }}</mat-slide-toggle>
          }
        </div>
      </div>

      <!-- ── Strategies ────────────────────────────────────────── -->
      <div class="panel" style="margin-top:16px">
        <div class="top-row" style="margin-bottom:0">
          <h2 style="margin:0"><mat-icon class="hi">psychology</mat-icon> Strategies</h2>
          <span class="spacer"></span>
          <a class="link" routerLink="/strategies">Manage Strategies →</a>
        </div>
      </div>

      <!-- ── Manual Order ──────────────────────────────────────────── -->
      <div class="panel" style="margin-top:16px">
        <div class="top-row" style="margin-bottom:0">
          <h2 style="margin:0"><mat-icon class="hi">receipt_long</mat-icon> Manual Order</h2>
          <span class="spacer"></span>
          <button mat-flat-button color="primary" (click)="openOrderModal()"><mat-icon>add_circle</mat-icon> Place Order</button>
        </div>
        <p class="page-subtitle" style="margin-top:6px">Route through Paper or Zerodha based on current routing config.</p>
      </div>

      <!-- ── Order Modal ───────────────────────────────────────────── -->
      @if (orderModalOpen()) {
        <div class="overlay" (click)="closeOrderModal()"></div>
        <div class="modal">
          <div class="modal-hdr">
            <h3>Place Manual Order</h3>
            <button mat-icon-button (click)="closeOrderModal()"><mat-icon>close</mat-icon></button>
          </div>
          <div class="modal-body">
            <mat-form-field appearance="outline" class="full">
              <mat-label>Instrument Key</mat-label>
              <input matInput [(ngModel)]="orderForm.instrumentKey" placeholder="NFO:NIFTY24APR24000CE">
            </mat-form-field>
            <div class="modal-2col">
              <mat-form-field appearance="outline"><mat-label>Side</mat-label>
                <mat-select [(ngModel)]="orderForm.side"><mat-option value="BUY">BUY</mat-option><mat-option value="SELL">SELL</mat-option></mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Type</mat-label>
                <mat-select [(ngModel)]="orderForm.orderType"><mat-option value="MARKET">MARKET</mat-option><mat-option value="LIMIT">LIMIT</mat-option></mat-select>
              </mat-form-field>
            </div>
            <div class="modal-2col">
              <mat-form-field appearance="outline"><mat-label>Product</mat-label>
                <mat-select [(ngModel)]="orderForm.productType"><mat-option value="MIS">MIS</mat-option><mat-option value="NRML">NRML</mat-option></mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Qty</mat-label>
                <input matInput type="number" min="1" [(ngModel)]="orderForm.quantity">
              </mat-form-field>
            </div>
            @if (orderForm.orderType === 'LIMIT') {
              <mat-form-field appearance="outline" class="full"><mat-label>Limit Price</mat-label>
                <input matInput type="number" step="0.05" [(ngModel)]="orderForm.limitPrice">
              </mat-form-field>
            }
            @if (orderResult()) {
              <div class="or" [class.or-ok]="orderResult()!['accepted']" [class.or-bad]="!orderResult()!['accepted']">
                <strong>{{ orderResult()!['accepted'] ? 'Accepted' : 'Rejected' }}</strong>
                @if (orderResult()!['status']) { <span>Status: {{ orderResult()!['status'] }}</span> }
                @if (orderResult()!['brokerOrderId']) { <span>Broker ID: {{ orderResult()!['brokerOrderId'] }}</span> }
                @if (orderResult()!['filledQuantity']) { <span>Filled: {{ orderResult()!['filledQuantity'] }}</span> }
                @if (orderResult()!['averageFillPrice']) { <span>Price: {{ orderResult()!['averageFillPrice'] }}</span> }
                @if (orderResult()!['reason']) { <span class="or-err">{{ orderResult()!['reason'] }}</span> }
                @if (orderResult()!['rejectionReason']) { <span class="or-err">{{ orderResult()!['rejectionReason'] }}</span> }
              </div>
            }
            <div class="modal-actions">
              <button mat-flat-button color="primary" [disabled]="!orderFormValid()" (click)="submitOrder()"><mat-icon>send</mat-icon> Submit</button>
              <button mat-stroked-button (click)="closeOrderModal()">Cancel</button>
            </div>
          </div>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1200px; }
    .top-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .grid.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 16px; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0 0 14px; display: flex; align-items: center; gap: 6px; }
    .hi { font-size: 18px; width: 18px; height: 18px; color: var(--accent); }
    .link { font-size: 12px; color: var(--accent); text-decoration: none; }

    .toast { padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
    .toast.ok { background: rgba(69,209,140,.08); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .toast.bad { background: rgba(242,189,75,.08); color: var(--warn); border: 1px solid rgba(242,189,75,.3); }

    /* Emergency Stop */
    .e-stop-panel { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
      padding: 16px 20px; border-radius: 12px; margin-bottom: 20px;
      border: 1.5px solid rgba(255,113,106,.3); background: rgba(255,113,106,.04); }
    .e-stop-active { border-color: rgba(255,113,106,.8) !important; background: rgba(255,113,106,.12) !important;
      animation: pulse-border 2s ease-in-out infinite; }
    @keyframes pulse-border { 0%,100% { box-shadow: 0 0 0 0 rgba(255,113,106,0); } 50% { box-shadow: 0 0 0 6px rgba(255,113,106,.15); } }
    .e-stop-left { display: flex; align-items: flex-start; gap: 12px; flex: 1; min-width: 0; }
    .e-stop-icon { font-size: 28px; width: 28px; height: 28px; color: var(--bad); flex-shrink: 0; margin-top: 2px; }
    .e-stop-title { font-size: 13px; font-weight: 700; color: var(--bad); margin-bottom: 3px; }
    .e-stop-desc { font-size: 12px; color: var(--muted); line-height: 1.4; }
    .e-stop-btns { display: flex; gap: 8px; flex-shrink: 0; }
    .e-stop-btn { display: flex; align-items: center; gap: 6px; padding: 10px 20px; border-radius: 8px; border: none; cursor: pointer;
      background: var(--bad); color: #fff; font-size: 13px; font-weight: 800; letter-spacing: .04em;
      transition: filter .15s; }
    .e-stop-btn:hover { filter: brightness(1.15); }
    .e-stop-btn mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .e-stop-resume { display: flex; align-items: center; gap: 6px; padding: 10px 20px; border-radius: 8px; border: none; cursor: pointer;
      background: var(--ok); color: #071018; font-size: 13px; font-weight: 800;
      transition: filter .15s; }
    .e-stop-resume:hover { filter: brightness(1.1); }
    .e-stop-resume mat-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Chips */
    .chip-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; }
    .chip { display: flex; align-items: center; gap: 5px; padding: 6px 12px; border-radius: 20px; font-size: 12px; font-weight: 600; border: 1px solid var(--line); background: var(--panel); }
    .chip mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .c-ok { border-color: rgba(69,209,140,.4); color: var(--ok); background: rgba(69,209,140,.05); }
    .c-warn { border-color: rgba(242,189,75,.4); color: var(--warn); background: rgba(242,189,75,.05); }
    .c-bad { border-color: rgba(255,113,106,.4); color: var(--bad); background: rgba(255,113,106,.05); }
    .c-muted { color: var(--muted); }

    /* Scanner buttons */
    .btn-row { display: flex; gap: 8px; flex-wrap: wrap; }
    .sb { border-color: var(--line) !important; color: var(--muted) !important; background: rgba(255,255,255,.03) !important; opacity: .7; }
    .sb.sb-active { opacity: 1; color: #071018 !important; border-color: transparent !important; box-shadow: 0 8px 20px rgba(0,0,0,.3); }
    .sb-start.sb-active, .sb-clear.sb-active { background: var(--ok) !important; }
    .sb-stop.sb-active { background: var(--warn) !important; color: #171005 !important; }
    .sb-kill.sb-active { background: var(--bad) !important; color: #180706 !important; }
    .sb-warn { border-color: rgba(242,189,75,.4) !important; color: var(--warn) !important; }
    .sb-warn.sb-active { background: var(--warn) !important; color: #171005 !important; }
    .sb-ext { border-color: rgba(97,168,255,.4) !important; color: var(--accent) !important; opacity: .9; }
    .panel-desc { font-size: 12px; color: var(--muted); margin: 0 0 12px; line-height: 1.5; }
    .ext-note { font-size: 12px; color: var(--warn); margin: 10px 0 0; }

    /* Feed cards */
    .feed-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .feed-card { border: 1px solid var(--line); border-radius: 10px; padding: 14px; background: rgba(255,255,255,.02); }
    .fc-on { border-color: rgba(69,209,140,.35); }
    .fc-off { border-color: rgba(255,113,106,.2); }
    .fc-top { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 13px; color: var(--ink); margin-bottom: 4px; }
    .fc-top mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .fc-desc { font-size: 11px; color: var(--muted); margin: 0 0 10px; }
    .fc-btn { font-size: 12px; }
    .fc-btns { display: flex; gap: 6px; }
    .fc-btn-warn { color: var(--bad) !important; border-color: rgba(255,113,106,.3) !important; }

    /* Section panels */
    .section-primary { border-color: rgba(69,209,140,.25); background: linear-gradient(180deg, rgba(69,209,140,.04), var(--panel)); margin-bottom: 16px; }
    .section-fallback { border-color: rgba(242,189,75,.2); background: linear-gradient(180deg, rgba(242,189,75,.03), var(--panel)); margin-bottom: 16px; }
    .section-hdr { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; flex-wrap: wrap; }
    .section-hdr h2 { margin: 0; font-size: 15px; font-weight: 700; color: var(--ink); display: flex; align-items: center; gap: 8px; }
    .section-hdr .section-desc { font-size: 12px; color: var(--muted); margin: 2px 0 0; }
    .section-icon { width: 36px; height: 36px; border-radius: 8px; display: grid; place-items: center; flex-shrink: 0; }
    .section-icon mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .si-ws { background: rgba(69,209,140,.12); color: var(--ok); }
    .si-rest { background: rgba(242,189,75,.12); color: var(--warn); }
    .tag-primary { font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 10px; background: rgba(69,209,140,.12); color: var(--ok); }
    .tag-fallback { font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 10px; background: rgba(242,189,75,.12); color: var(--warn); }
    .status-pill { font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 20px; }
    .sp-ok { background: rgba(69,209,140,.1); color: var(--ok); border: 1px solid rgba(69,209,140,.3); }
    .sp-off { background: rgba(255,113,106,.08); color: var(--bad); border: 1px solid rgba(255,113,106,.25); }

    /* WebSocket grid */
    .ws-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
    .ws-card { padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: rgba(255,255,255,.02); }
    .ws-label { display: block; font-size: 11px; font-weight: 700; text-transform: uppercase; color: var(--muted); margin-bottom: 8px; letter-spacing: .03em; }

    /* REST poll status */
    .rest-status { padding: 0 4px; }
    .rest-indicator { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; margin-bottom: 8px; }
    .rest-indicator mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .ri-on { color: var(--ok); }
    .ri-off { color: var(--muted); }
    .rest-note { font-size: 11px; color: var(--muted); margin: 0; line-height: 1.5; }

    /* Inline forms */
    .inline-form { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .toggle-strip { display: flex; gap: 16px; flex-wrap: wrap; }

    /* Strategy grid */
    .strat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 8px; }
    .sc { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 10px; border: 1px solid var(--line); background: rgba(255,255,255,.02); }
    .sc-on { border-color: rgba(97,168,255,.35); background: rgba(97,168,255,.04); }
    .sc-on.sc-sell { border-color: rgba(242,189,75,.35); background: rgba(242,189,75,.04); }
    .sc-info { display: flex; flex-direction: column; gap: 2px; }
    .sc-name { font-size: 12px; font-weight: 600; color: var(--ink); }
    .sc-tag { font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 10px; width: fit-content; }
    .t-buy { background: rgba(69,209,140,.12); color: var(--ok); }
    .t-sell { background: rgba(242,189,75,.12); color: var(--warn); }
    .sc-toggle { transform: scale(.85); }

    /* Modal */
    .overlay { position: fixed; inset: 0; background: rgba(0,0,0,.55); z-index: 200; }
    .modal { position: fixed; top: 50%; left: 50%; transform: translate(-50%,-50%); width: min(460px,94vw); max-height: 90vh; overflow-y: auto; background: var(--panel); border: 1px solid var(--line); border-radius: 16px; z-index: 201; box-shadow: 0 20px 60px rgba(0,0,0,.5); }
    .modal-hdr { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 12px; border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--panel); z-index: 1; }
    .modal-hdr h3 { margin: 0; font-size: 16px; color: var(--ink); }
    .modal-body { padding: 20px; display: flex; flex-direction: column; gap: 12px; }
    .modal-2col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .full { width: 100%; }
    .modal-actions { display: flex; gap: 10px; margin-top: 6px; }
    .modal-actions button:first-child { flex: 1; }
    .or { padding: 12px 16px; border-radius: 8px; font-size: 13px; display: flex; flex-direction: column; gap: 3px; }
    .or-ok { background: rgba(69,209,140,.08); border: 1px solid rgba(69,209,140,.3); color: var(--ok); }
    .or-bad { background: rgba(255,113,106,.08); border: 1px solid rgba(255,113,106,.3); color: var(--bad); }
    .or-err { color: var(--bad); }
  `]
})
export class ExecutionPageComponent implements OnInit {
  readonly tradingModes: TradingMode[] = ['PAPER', 'BACKTEST', 'LIVE'];
  readonly marketDataModes: MarketDataMode[] = ['MOCK', 'ZERODHA'];
  readonly executionModes: ExecutionMode[] = ['PAPER', 'ZERODHA'];
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY', 'SENSEX', 'FINNIFTY', 'MIDCPNIFTY'];

  runtime?: RuntimeStatus;
  strategies = signal<StrategyDto[]>([]);
  toast = signal('');
  toastErr = signal('');
  orderModalOpen = signal(false);
  orderResult = signal<ApiRecord | null>(null);
  orderForm = { instrumentKey: '', side: 'BUY', orderType: 'MARKET', productType: 'MIS', quantity: 75, limitPrice: undefined as number | undefined, tag: 'manual-ui' };

  readonly modeForm = this.fb.nonNullable.group({ mode: ['PAPER' as TradingMode, Validators.required] });
  readonly routingForm = this.fb.nonNullable.group({ marketDataMode: ['MOCK' as MarketDataMode, Validators.required], executionMode: ['PAPER' as ExecutionMode, Validators.required] });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder, private readonly cd: ChangeDetectorRef) {}
  ngOnInit(): void { this.load(); }

  load(): void {
    this.toast.set(''); this.toastErr.set('');
    this.api.config().subscribe({ next: c => this.setRuntime(c.runtime), error: () => this.toastErr.set('Failed to load') });
    this.api.getStrategies().subscribe({ next: s => this.strategies.set(s) });
  }

  start(): void { this.api.start().subscribe(r => this.setRuntime(r)); }
  stop(): void { this.api.stop().subscribe(r => this.setRuntime(r)); }
  setKillSwitch(v: boolean): void { this.api.setKillSwitch(v).subscribe(r => this.setRuntime(r)); }
  softHalt(): void { this.api.softHalt('Manual soft halt from UI').subscribe({ next: r => { this.setRuntime(r); this.toast.set('Soft halt — no new entries'); }, error: () => this.toastErr.set('Soft halt failed') }); }
  hardStop(): void {
    if (!confirm('EMERGENCY STOP: This will immediately freeze the scanner, block all entries, AND stop all exit monitors. Continue?')) return;
    this.api.hardHalt('Emergency stop triggered from UI').subscribe({ next: r => { this.setRuntime(r); this.toastErr.set('🚨 HARD HALT ACTIVE — everything stopped'); }, error: () => this.toastErr.set('Emergency stop failed') });
  }
  resumeHalt(): void { this.api.resumeFromHalt().subscribe({ next: r => { this.setRuntime(r); this.toast.set('Halt cleared — trading resumed'); }, error: () => this.toastErr.set('Resume failed') }); }
  approveToday(): void { this.api.approveToday().subscribe({ next: r => { this.setRuntime(r); this.toast.set('Daily trading approved'); }, error: () => this.toastErr.set('Approve failed') }); }
  revokeApproval(): void { this.api.revokeApproval().subscribe({ next: r => { this.setRuntime(r); this.toast.set('Daily approval revoked'); }, error: () => this.toastErr.set('Revoke failed') }); }
  extendLimit(): void { this.api.extendDailyLimit().subscribe({ next: (r: any) => { this.setRuntime(r); this.toast.set(r['message'] ?? 'Limit extended'); }, error: () => this.toastErr.set('Extend failed') }); }

  reconnectWebSocket(): void {
    this.api.reconnectWebSocket().subscribe({ next: r => { this.setRuntime(r); this.toast.set('WebSocket reconnect initiated'); }, error: () => this.toastErr.set('Reconnect failed') });
  }
  disconnectWebSocket(): void {
    this.api.disconnectWebSocket().subscribe({ next: r => { this.setRuntime(r); this.toast.set('WebSocket disconnected'); }, error: () => this.toastErr.set('Disconnect failed') });
  }
  toggleScheduler(v: boolean): void {
    this.api.setScheduler(v).subscribe({ next: r => { this.setRuntime(r); this.toast.set('Poll scheduler ' + (v ? 'enabled' : 'disabled')); }, error: () => this.toastErr.set('Toggle failed') });
  }

  applyMode(): void { this.api.setMode(this.modeForm.controls.mode.value).subscribe(r => this.setRuntime(r)); }
  applyRouting(): void { const v = this.routingForm.getRawValue(); this.api.setRouting(v.marketDataMode, v.executionMode).subscribe(r => this.setRuntime(r)); }
  setUnderlying(u: UnderlyingSymbol, v: boolean): void { this.api.setScanUnderlying(u, v).subscribe(r => this.setRuntime(r)); }
  isUnderlyingEnabled(u: UnderlyingSymbol): boolean { return this.runtime?.enabledUnderlyings?.includes(u) ?? false; }

  toggleStrategy(s: StrategyDto, v: boolean): void {
    this.toast.set(''); this.toastErr.set('');
    (v ? this.api.enableStrategy(s.type, s.underlying) : this.api.disableStrategy(s.type, s.underlying)).subscribe({
      next: () => { this.toast.set(s.displayName + (v ? ' enabled' : ' disabled')); this.api.getStrategies().subscribe({ next: s => this.strategies.set(s) }); },
      error: (e: any) => this.toastErr.set(e?.error?.error ?? 'Failed')
    });
  }

  openOrderModal(): void { this.orderResult.set(null); this.orderModalOpen.set(true); }
  closeOrderModal(): void { this.orderModalOpen.set(false); }
  orderFormValid(): boolean { return !!this.orderForm.instrumentKey && this.orderForm.quantity > 0; }

  submitOrder(): void {
    this.orderResult.set(null);
    const p: any = { instrumentKey: this.orderForm.instrumentKey, side: this.orderForm.side, orderType: this.orderForm.orderType, productType: this.orderForm.productType, quantity: this.orderForm.quantity, tag: this.orderForm.tag || 'manual-ui' };
    if (this.orderForm.orderType === 'LIMIT' && this.orderForm.limitPrice != null) p.limitPrice = this.orderForm.limitPrice;
    this.api.placeOrder(p).subscribe({ next: (r: ApiRecord) => this.orderResult.set(r), error: (e: any) => this.orderResult.set({ accepted: false, reason: e?.error?.reason ?? 'Order failed' }) });
  }

  private setRuntime(r: RuntimeStatus): void {
    this.runtime = r;
    this.modeForm.patchValue({ mode: r.requestedMode });
    this.routingForm.patchValue({ marketDataMode: r.marketDataMode, executionMode: r.executionMode });
    this.cd.detectChanges();
  }
}
