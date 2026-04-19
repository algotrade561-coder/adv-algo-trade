import { Component } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../core/api.service';
import { ApiRecord, OptionType, Timeframe, UnderlyingSymbol } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-data-maintenance-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, JsonViewComponent],
  template: `
    <section class="page">
      <h1 class="page-title">Data Maintenance</h1>
      <p class="page-subtitle">Append Zerodha option candles into the local backtest dataset.</p>

      <div class="panel">
        <form [formGroup]="form" class="form-grid">
          <mat-form-field appearance="outline">
            <mat-label>Underlying</mat-label>
            <mat-select formControlName="underlying">
              <mat-option value="">Default</mat-option>
              @for (underlying of underlyings; track underlying) {
                <mat-option [value]="underlying">{{ underlying }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>From</mat-label>
            <input matInput type="date" formControlName="from">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>To</mat-label>
            <input matInput type="date" formControlName="to">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Expiry</mat-label>
            <input matInput type="date" formControlName="expiry">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Strike</mat-label>
            <input matInput type="number" formControlName="strike">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Underlying price</mat-label>
            <input matInput type="number" formControlName="underlyingPrice">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Timeframes</mat-label>
            <mat-select formControlName="timeframes" multiple>
              @for (timeframe of timeframes; track timeframe) {
                <mat-option [value]="timeframe">{{ timeframe }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Option types</mat-label>
            <mat-select formControlName="optionTypes" multiple>
              @for (optionType of optionTypes; track optionType) {
                <mat-option [value]="optionType">{{ optionType }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <button mat-flat-button color="primary" type="button" (click)="append()">Append Data</button>
        </form>
      </div>

      <div class="panel" style="margin-top: 16px;">
        <h2>Result</h2>
        <app-json-view [value]="result"></app-json-view>
      </div>
    </section>
  `
})
export class DataMaintenancePageComponent {
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY'];
  readonly optionTypes: OptionType[] = ['CE', 'PE'];
  readonly timeframes: Timeframe[] = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'DAY'];
  result?: ApiRecord;

  readonly form = this.fb.nonNullable.group({
    underlying: [''],
    from: [''],
    to: [''],
    expiry: [''],
    strike: [''],
    underlyingPrice: [''],
    timeframes: [[] as Timeframe[]],
    optionTypes: [[] as OptionType[]]
  });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder) {
  }

  append(): void {
    const raw = this.form.getRawValue();
    this.api.appendZerodhaOptions({
      ...raw,
      strike: raw.strike === '' ? undefined : Number(raw.strike),
      underlyingPrice: raw.underlyingPrice === '' ? undefined : Number(raw.underlyingPrice)
    }).subscribe((result) => this.result = result);
  }
}
