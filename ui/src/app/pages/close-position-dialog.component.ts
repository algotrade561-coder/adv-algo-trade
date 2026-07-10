import { ChangeDetectorRef, Component, Inject, OnInit } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ApiService } from '../core/api.service';
import { ApiRecord } from '../core/models';

export interface ClosePositionDialogData {
  instrumentKey: string;
  /** The user whose position is being closed (the monitoring dropdown selection). */
  userId: number | null;
  userEmail?: string;
}

export interface ClosePositionDialogResult {
  ok: boolean;
  message: string;
}

/**
 * Close-position modal. On open it fetches an authoritative close-preview from the SERVER for the
 * TARGET user (never trusting the possibly-stale table row that launched it), prepopulates the
 * maximum closable lots, and lets the operator reduce the lots for a partial close. The confirm
 * button always restates the owner + exact quantity that will be sold.
 */
@Component({
  selector: 'app-close-position-dialog',
  standalone: true,
  imports: [
    CommonModule, DecimalPipe, FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatDialogModule
  ],
  template: `
    <div class="cp-title" mat-dialog-title>
      <mat-icon color="warn">close</mat-icon>
      <div>
        <strong>Close position</strong>
        <span class="cp-inst">{{ data.instrumentKey }}</span>
      </div>
    </div>

    <mat-dialog-content>
      @if (loading) {
        <div class="cp-loading">Fetching live position from the broker…</div>
      } @else if (error) {
        <div class="cp-error"><mat-icon>error_outline</mat-icon> {{ error }}</div>
      } @else {
        <div class="cp-owner">
          Closing for <strong>{{ ownerLabel }}</strong>
          @if (preview?.['ownerUserId'] != null) { <span class="cp-uid">user #{{ preview?.['ownerUserId'] }}</span> }
        </div>

        <div class="cp-grid">
          <div class="cp-cell"><label>Open qty (broker)</label><span class="mono">{{ maxQty }}</span></div>
          <div class="cp-cell"><label>Lot size</label><span class="mono">{{ lotSize > 0 ? lotSize : '—' }}</span></div>
          <div class="cp-cell"><label>Avg price</label><span class="mono">{{ num(preview?.['avgPrice']) != null ? (num(preview?.['avgPrice']) | number:'1.2-2') : '—' }}</span></div>
          <div class="cp-cell"><label>Last price</label><span class="mono">{{ num(preview?.['lastPrice']) != null ? (num(preview?.['lastPrice']) | number:'1.2-2') : '—' }}</span></div>
          <div class="cp-cell"><label>Unrealized P&L</label>
            <span class="mono" [class.pos]="num(preview?.['unrealizedPnl'])! >= 0" [class.neg]="num(preview?.['unrealizedPnl'])! < 0">
              {{ num(preview?.['unrealizedPnl']) != null ? (num(preview?.['unrealizedPnl']) | number:'1.0-0') : '—' }}
            </span>
          </div>
          <div class="cp-cell"><label>Bot trades</label><span class="mono">{{ preview?.['botTradeCount'] ?? 0 }} ({{ preview?.['botOpenQty'] ?? 0 }} qty)</span></div>
        </div>

        @if (maxQty > 0) {
          @if (lotSize > 0) {
            <mat-form-field appearance="outline" class="cp-qty">
              <mat-label>Lots to close (max {{ maxLots }})</mat-label>
              <input matInput type="number" [(ngModel)]="lots" (ngModelChange)="onLotsChange()"
                     [min]="1" [max]="maxLots" step="1" autocomplete="off" />
              <span matTextSuffix class="cp-qty-suffix">= {{ qty }} qty</span>
            </mat-form-field>
          } @else {
            <mat-form-field appearance="outline" class="cp-qty">
              <mat-label>Quantity to close (max {{ maxQty }})</mat-label>
              <input matInput type="number" [(ngModel)]="qty" [min]="1" [max]="maxQty" autocomplete="off" />
            </mat-form-field>
          }
          @if (validationError) { <div class="cp-error-inline">{{ validationError }}</div> }
          @if (isPartial && !validationError) {
            <div class="cp-hint">Partial close: {{ qty }} of {{ maxQty }} — the remaining {{ maxQty - qty }} stays open and bot-managed.</div>
          }
          <div class="cp-warn">A real <strong>{{ side }}</strong> order will be placed on the owner's broker account. This cannot be undone.</div>
        } @else {
          <div class="cp-hint">No open quantity found for this instrument — nothing to close.</div>
        }
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-stroked-button (click)="cancel()" [disabled]="submitting">Cancel</button>
      <button mat-flat-button color="warn" (click)="confirm()"
              [disabled]="loading || submitting || !!error || !!validationError || maxQty <= 0 || qty <= 0">
        {{ submitting ? 'Closing…' : confirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .cp-title { display: flex; align-items: center; gap: 10px; }
    .cp-title strong { display: block; font-size: 15px; }
    .cp-inst { font-family: var(--mono, monospace); font-size: 12px; color: var(--muted, #9aa); }
    .cp-loading { padding: 18px 4px; color: var(--muted, #9aa); font-size: 13px; }
    .cp-error { display: flex; align-items: center; gap: 8px; color: #ef5350; font-size: 13px; padding: 12px 4px; }
    .cp-error-inline { color: #ef5350; font-size: 12px; margin: -6px 0 8px; }
    .cp-owner { font-size: 13px; margin-bottom: 12px; }
    .cp-uid { margin-left: 6px; font-size: 11px; color: var(--muted, #9aa); }
    .cp-grid { display: grid; grid-template-columns: repeat(3, minmax(110px, 1fr)); gap: 10px 16px; margin-bottom: 14px; }
    .cp-cell label { display: block; font-size: 10px; text-transform: uppercase; letter-spacing: .4px; color: var(--muted, #9aa); margin-bottom: 2px; }
    .cp-cell .mono { font-family: var(--mono, monospace); font-size: 13px; }
    .cp-qty { width: 100%; }
    .cp-qty-suffix { font-size: 12px; color: var(--muted, #9aa); }
    .cp-hint { font-size: 12px; color: var(--muted, #9aa); margin-bottom: 8px; }
    .cp-warn { font-size: 12px; color: #ffb74d; margin-top: 4px; }
    .pos { color: #66bb6a; } .neg { color: #ef5350; }
  `]
})
export class ClosePositionDialogComponent implements OnInit {
  loading = true;
  submitting = false;
  error = '';
  preview: ApiRecord | null = null;

  lotSize = 0;
  maxQty = 0;
  maxLots = 0;
  lots = 0;
  qty = 0;
  side = 'SELL';

  constructor(@Inject(MAT_DIALOG_DATA) public data: ClosePositionDialogData,
              private readonly ref: MatDialogRef<ClosePositionDialogComponent, ClosePositionDialogResult>,
              private readonly api: ApiService,
              private readonly cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.api.closePreview(this.data.instrumentKey, this.data.userId).subscribe({
      next: (p) => {
        this.preview = p;
        this.lotSize = Number(p['lotSize'] ?? 0) || 0;
        this.maxQty = Number(p['maxClosableQty'] ?? 0) || 0;
        this.maxLots = this.lotSize > 0 ? Math.floor(this.maxQty / this.lotSize) : 0;
        this.side = String(p['side'] ?? 'SELL');
        // Prepopulate at the maximum closable — the operator reduces for a partial close.
        this.lots = this.maxLots;
        this.qty = this.lotSize > 0 ? this.maxLots * this.lotSize : this.maxQty;
        this.loading = false;
        this.cd.detectChanges();
      },
      error: (err) => {
        this.loading = false;
        this.error = `Could not fetch the live position: ${err?.error?.error ?? err?.error?.message ?? err?.message ?? 'request error'}`;
        this.cd.detectChanges();
      }
    });
  }

  onLotsChange(): void {
    this.qty = this.lotSize > 0 ? Math.max(0, Math.floor(this.lots || 0)) * this.lotSize : this.qty;
  }

  get ownerLabel(): string {
    const email = this.preview?.['ownerEmail'] ?? this.data.userEmail;
    return email ? String(email) : ('user #' + (this.preview?.['ownerUserId'] ?? this.data.userId ?? '?'));
  }

  get isPartial(): boolean { return this.qty > 0 && this.qty < this.maxQty; }

  get validationError(): string {
    if (this.loading || this.maxQty <= 0) return '';
    if (this.lotSize > 0) {
      if (!Number.isInteger(this.lots) || this.lots < 1) return 'Lots must be a whole number ≥ 1.';
      if (this.lots > this.maxLots) return `Only ${this.maxLots} lot(s) are open.`;
    } else {
      if (!Number.isInteger(this.qty) || this.qty < 1) return 'Quantity must be a whole number ≥ 1.';
      if (this.qty > this.maxQty) return `Only ${this.maxQty} qty is open.`;
    }
    return '';
  }

  get confirmLabel(): string {
    if (this.maxQty <= 0 || this.qty <= 0) return 'Close';
    const what = this.lotSize > 0 ? `${this.lots} lot${this.lots === 1 ? '' : 's'} (${this.qty})` : `${this.qty} qty`;
    return this.isPartial ? `Close ${what} of ${this.maxQty}` : `Close ALL — ${what}`;
  }

  num(v: unknown): number | null {
    if (v == null || v === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  cancel(): void { this.ref.close(); }

  confirm(): void {
    if (this.validationError || this.qty <= 0 || this.submitting) return;
    this.submitting = true;
    this.cd.detectChanges();
    // ALWAYS send the explicit quantity. The quantity path closes bot trades through the engine
    // AND flattens any manual (non-bot) remainder on the same instrument — so "Close ALL" means
    // the broker position, not just the bot's slice. (No-quantity legacy behavior remains for
    // any old callers of the API.)
    const quantity = this.qty;
    this.api.closePosition(this.data.instrumentKey, this.data.userId, quantity).subscribe({
      next: (res) => {
        const closedQty = Number(res?.['closedQty'] ?? 0);
        const accepted = res?.['ok'] === true && Number(res?.['closed'] ?? 0) > 0;
        const msg = accepted
          ? `Close order placed for ${this.data.instrumentKey}` +
            (quantity != null ? ` (${closedQty > 0 ? closedQty : quantity}/${this.maxQty} qty)` : '') +
            ` — ${this.ownerLabel}.`
          : `Could not close ${this.data.instrumentKey}: ${res?.['message'] ?? 'no managed open trade found'}`;
        this.ref.close({ ok: accepted, message: msg });
      },
      error: (err) => {
        this.ref.close({
          ok: false,
          message: `Close failed for ${this.data.instrumentKey}: ${err?.error?.error ?? err?.error?.message ?? err?.message ?? 'request error'}`
        });
      }
    });
  }
}
