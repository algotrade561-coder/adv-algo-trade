import { Component, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../core/api.service';
import { ApiRecord, OptionType, Timeframe, UnderlyingSymbol } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-data-maintenance-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, JsonViewComponent],
  template: `
    <section class="page">
      <div class="top-row">
        <div>
          <h1 class="page-title">Data Maintenance</h1>
          <p class="page-subtitle">Append Zerodha option candles into the local backtest dataset.</p>
        </div>
      </div>

      @if (running()) { <div class="toast info"><mat-icon>hourglass_top</mat-icon> Appending data…</div> }

      <div class="panel">
        <h2><mat-icon class="hi">cloud_download</mat-icon> Append Zerodha Options</h2>
        <form [formGroup]="form" class="fg">
          <div class="fg-3col">
            <mat-form-field appearance="outline"><mat-label>Underlying</mat-label>
              <mat-select formControlName="underlying"><mat-option value="">Default</mat-option>
                @for (u of underlyings; track u) { <mat-option [value]="u">{{ u }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline"><mat-label>From</mat-label><input matInput type="date" formControlName="from"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>To</mat-label><input matInput type="date" formControlName="to"></mat-form-field>
          </div>
          <div class="fg-3col">
            <mat-form-field appearance="outline"><mat-label>Expiry</mat-label><input matInput type="date" formControlName="expiry"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Strike</mat-label><input matInput type="number" formControlName="strike"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Underlying price</mat-label><input matInput type="number" formControlName="underlyingPrice"></mat-form-field>
          </div>
          <div class="fg-2col">
            <mat-form-field appearance="outline"><mat-label>Timeframes</mat-label>
              <mat-select formControlName="timeframes" multiple>
                @for (t of timeframes; track t) { <mat-option [value]="t">{{ t }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Option types</mat-label>
              <mat-select formControlName="optionTypes" multiple>
                @for (o of optionTypes; track o) { <mat-option [value]="o">{{ o }}</mat-option> }
              </mat-select>
            </mat-form-field>
          </div>
          <button mat-flat-button color="primary" type="button" [disabled]="running()" (click)="append()">
            <mat-icon>cloud_upload</mat-icon> Append Data
          </button>
        </form>
      </div>

      @if (result) {
        <div class="panel" style="margin-top:16px">
          <div class="top-row" style="margin-bottom:10px">
            <h2 style="margin:0"><mat-icon class="hi">terminal</mat-icon> Result</h2>
            <span class="spacer"></span>
            <button mat-stroked-button (click)="result = undefined"><mat-icon>close</mat-icon> Clear</button>
          </div>
          <app-json-view [value]="result"></app-json-view>
        </div>
      }
    </section>
  `,
  styles: [`
    .page { padding: 24px; max-width: 900px; }
    .top-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
    .spacer { flex: 1; }
    .page-title { font-size: 22px; font-weight: 800; color: var(--ink); margin: 0 0 4px; }
    .page-subtitle { color: var(--muted); font-size: 13px; margin: 0; }
    .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
    .panel h2 { font-size: 14px; font-weight: 700; color: var(--ink); margin: 0 0 14px; display: flex; align-items: center; gap: 6px; }
    .hi { font-size: 18px; width: 18px; height: 18px; color: var(--accent); }

    .toast { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
    .toast.info { background: rgba(97,168,255,.06); border: 1px solid rgba(97,168,255,.2); color: var(--accent); }
    .toast mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .fg { display: flex; flex-direction: column; gap: 4px; }
    .fg-2col { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .fg-3col { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; }
  `]
})
export class DataMaintenancePageComponent {
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY'];
  readonly optionTypes: OptionType[] = ['CE', 'PE'];
  readonly timeframes: Timeframe[] = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'DAY'];
  result?: ApiRecord;
  running = signal(false);

  readonly form = this.fb.nonNullable.group({
    underlying: [''], from: [''], to: [''], expiry: [''],
    strike: [''], underlyingPrice: [''],
    timeframes: [[] as Timeframe[]], optionTypes: [[] as OptionType[]]
  });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder) {}

  append(): void {
    const r = this.form.getRawValue();
    this.running.set(true);
    this.api.appendZerodhaOptions({
      ...r,
      strike: r.strike === '' ? undefined : Number(r.strike),
      underlyingPrice: r.underlyingPrice === '' ? undefined : Number(r.underlyingPrice)
    }).subscribe({
      next: res => { this.result = res; this.running.set(false); },
      error: () => { this.result = { status: 'error', message: 'Request failed' }; this.running.set(false); }
    });
  }
}
