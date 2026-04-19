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
  selector: 'app-backtests-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, JsonViewComponent],
  template: `
    <section class="page">
      <h1 class="page-title">Backtests</h1>
      <p class="page-subtitle">Run single backtests, download candle data, and launch suite presets.</p>

      <div class="grid two">
        <div class="panel">
          <h2>Run Backtest</h2>
          <form [formGroup]="runForm" class="form-grid">
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
              <mat-label>Option type</mat-label>
              <mat-select formControlName="optionType">
                <mat-option value="">Default</mat-option>
                @for (optionType of optionTypes; track optionType) {
                  <mat-option [value]="optionType">{{ optionType }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Timeframe</mat-label>
              <mat-select formControlName="timeframe">
                <mat-option value="">Default</mat-option>
                @for (timeframe of timeframes; track timeframe) {
                  <mat-option [value]="timeframe">{{ timeframe }}</mat-option>
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
            <button mat-flat-button color="primary" type="button" (click)="runBacktest()">Run</button>
          </form>
        </div>

        <div class="panel">
          <h2>Suite Presets</h2>
          <form [formGroup]="suiteForm" class="form-grid">
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
            <div class="row full">
              <button mat-flat-button color="primary" type="button" (click)="runSuite()">Run Suite</button>
              <button mat-stroked-button type="button" (click)="analyzeVariants()">Analyze Variants</button>
              <button mat-stroked-button type="button" (click)="analyzeQuick()">Quick</button>
              <button mat-stroked-button type="button" (click)="focusedValidation()">Focused Validation</button>
            </div>
          </form>
        </div>
      </div>

      <div class="grid two" style="margin-top: 16px;">
        <div class="panel">
          <h2>Download Instrument Data</h2>
          <form [formGroup]="downloadForm" class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Instrument token</mat-label>
              <input matInput formControlName="instrumentToken">
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
              <mat-label>Timeframe</mat-label>
              <mat-select formControlName="timeframe">
                <mat-option value="">Default</mat-option>
                @for (timeframe of timeframes; track timeframe) {
                  <mat-option [value]="timeframe">{{ timeframe }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <button mat-flat-button color="primary" type="button" (click)="downloadData()">Download</button>
          </form>
        </div>

        <div class="panel">
          <h2>Download Option Data</h2>
          <form [formGroup]="optionDownloadForm" class="form-grid">
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
              <mat-label>Option type</mat-label>
              <mat-select formControlName="optionType">
                <mat-option value="">Default</mat-option>
                @for (optionType of optionTypes; track optionType) {
                  <mat-option [value]="optionType">{{ optionType }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Timeframe</mat-label>
              <mat-select formControlName="timeframe">
                <mat-option value="">Default</mat-option>
                @for (timeframe of timeframes; track timeframe) {
                  <mat-option [value]="timeframe">{{ timeframe }}</mat-option>
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
            <button mat-flat-button color="primary" type="button" (click)="downloadOptionData()">Download Option</button>
          </form>
        </div>
      </div>

      <div class="panel" style="margin-top: 16px;">
        <h2>Result</h2>
        <app-json-view [value]="result"></app-json-view>
      </div>
    </section>
  `
})
export class BacktestsPageComponent {
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY'];
  readonly optionTypes: OptionType[] = ['CE', 'PE'];
  readonly timeframes: Timeframe[] = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'DAY'];
  result?: ApiRecord;

  readonly runForm = this.fb.nonNullable.group({
    underlying: [''],
    timeframe: [''],
    optionType: [''],
    from: [''],
    to: ['']
  });

  readonly suiteForm = this.fb.nonNullable.group({
    underlying: [''],
    to: [''],
    expiry: [''],
    strike: [''],
    underlyingPrice: ['']
  });

  readonly downloadForm = this.fb.nonNullable.group({
    instrumentToken: [''],
    from: [''],
    to: [''],
    timeframe: ['']
  });

  readonly optionDownloadForm = this.fb.nonNullable.group({
    underlying: [''],
    optionType: [''],
    from: [''],
    to: [''],
    timeframe: [''],
    expiry: [''],
    strike: [''],
    underlyingPrice: ['']
  });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder) {
  }

  runBacktest(): void {
    this.api.runBacktest(this.runForm.getRawValue()).subscribe((result) => this.result = result);
  }

  runSuite(): void {
    this.api.runSuite(this.suiteRequest()).subscribe((result) => this.result = result);
  }

  analyzeVariants(): void {
    this.api.analyzeVariants(this.suiteRequest()).subscribe((result) => this.result = result);
  }

  analyzeQuick(): void {
    this.api.analyzeQuick(this.suiteRequest()).subscribe((result) => this.result = result);
  }

  focusedValidation(): void {
    this.api.analyzeFocusedValidation(this.suiteRequest()).subscribe((result) => this.result = result);
  }

  downloadData(): void {
    this.api.downloadData(this.downloadForm.getRawValue()).subscribe((result) => this.result = result);
  }

  downloadOptionData(): void {
    this.api.downloadOptionData(this.optionDownloadForm.getRawValue()).subscribe((result) => this.result = result);
  }

  private suiteRequest(): Record<string, unknown> {
    const raw = this.suiteForm.getRawValue();
    return {
      ...raw,
      strike: raw.strike === '' ? '' : Number(raw.strike),
      underlyingPrice: raw.underlyingPrice === '' ? '' : Number(raw.underlyingPrice)
    };
  }
}
