import { Component, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../core/api.service';
import { ExecutionMode, MarketDataMode, RuntimeStatus, TradingMode, UnderlyingSymbol } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-execution-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatSelectModule,
    MatSlideToggleModule,
    JsonViewComponent
  ],
  template: `
    <section class="page">
      <h1 class="page-title">Execution</h1>
      <p class="page-subtitle">Start or stop scanning, set routing, manage underlyings, and control the kill switch.</p>

      <div class="grid two">
        <div class="panel">
          <h2>Scanner Control</h2>
          <div class="action-strip">
            <div class="row">
              <span class="badge" [class.ok]="runtime?.running" [class.warn]="!runtime?.running">
                {{ runtime?.running ? 'Running' : 'Stopped' }}
              </span>
              <span class="badge" [class.bad]="runtime?.killSwitch" [class.ok]="!runtime?.killSwitch">
                {{ runtime?.killSwitch ? 'Kill switch on' : 'Kill switch clear' }}
              </span>
            </div>
            <div class="row">
              <button
                class="action-button state-button scanner-start"
                [class.state-active]="runtime?.running"
                [class.state-inactive]="runtime && !runtime.running"
                mat-stroked-button
                (click)="start()">
                {{ runtime?.running ? 'Scanner Running' : 'Start Scanner' }}
              </button>
              <button
                class="action-button state-button scanner-stop"
                [class.state-active]="runtime && !runtime.running"
                [class.state-inactive]="runtime?.running"
                mat-stroked-button
                (click)="stop()">
                {{ runtime && !runtime.running ? 'Scanner Stopped' : 'Stop Scanner' }}
              </button>
              <button
                class="action-button state-button kill-enable"
                [class.state-active]="runtime?.killSwitch"
                [class.state-inactive]="runtime && !runtime.killSwitch"
                mat-stroked-button
                (click)="setKillSwitch(true)">
                {{ runtime?.killSwitch ? 'Kill Switch Enabled' : 'Enable Kill Switch' }}
              </button>
              <button
                class="action-button state-button kill-clear"
                [class.state-active]="runtime && !runtime.killSwitch"
                [class.state-inactive]="runtime?.killSwitch"
                mat-stroked-button
                (click)="setKillSwitch(false)">
                {{ runtime && !runtime.killSwitch ? 'Kill Switch Clear' : 'Clear Kill Switch' }}
              </button>
            </div>
          </div>
        </div>

        <div class="panel">
          <h2>Mode</h2>
          <form [formGroup]="modeForm" class="row" (ngSubmit)="applyMode()">
            <mat-form-field appearance="outline">
              <mat-label>Requested mode</mat-label>
              <mat-select formControlName="mode">
                @for (mode of tradingModes; track mode) {
                  <mat-option [value]="mode">{{ mode }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <button class="action-button" [class.active]="modeForm.controls.mode.value === runtime?.requestedMode" mat-flat-button color="primary" type="submit" [disabled]="modeForm.invalid">Apply</button>
          </form>
        </div>
      </div>

      <div class="grid two" style="margin-top: 16px;">
        <div class="panel">
          <h2>Routing</h2>
          <form [formGroup]="routingForm" class="form-grid" (ngSubmit)="applyRouting()">
            <mat-form-field appearance="outline">
              <mat-label>Market data</mat-label>
              <mat-select formControlName="marketDataMode">
                @for (mode of marketDataModes; track mode) {
                  <mat-option [value]="mode">{{ mode }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Execution</mat-label>
              <mat-select formControlName="executionMode">
                @for (mode of executionModes; track mode) {
                  <mat-option [value]="mode">{{ mode }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <button class="action-button" [class.active]="routingMatchesRuntime" mat-flat-button color="primary" type="submit" [disabled]="routingForm.invalid">Apply Routing</button>
          </form>
        </div>

        <div class="panel">
          <h2>Scan Underlyings</h2>
          <div class="row">
            @for (underlying of underlyings; track underlying) {
              <mat-slide-toggle
                [checked]="isUnderlyingEnabled(underlying)"
                (change)="setUnderlying(underlying, $event.checked)">
                {{ underlying }}
              </mat-slide-toggle>
            }
          </div>
        </div>
      </div>

      <div class="panel" style="margin-top: 16px;">
        <h2>Current Runtime</h2>
        <app-json-view [value]="runtime"></app-json-view>
      </div>
    </section>
  `
  ,
  styles: [`
    .state-button {
      border-color: var(--line-strong) !important;
      color: var(--muted) !important;
      background: rgba(255, 255, 255, 0.03) !important;
    }

    .state-button.state-inactive {
      opacity: 0.72;
    }

    .state-button.state-active {
      opacity: 1;
      color: #071018 !important;
      border-color: transparent !important;
      box-shadow: 0 10px 24px rgba(0, 0, 0, 0.34);
    }

    .scanner-start.state-active,
    .kill-clear.state-active {
      background: var(--ok) !important;
    }

    .scanner-stop.state-active {
      background: var(--warn) !important;
      color: #171005 !important;
    }

    .kill-enable.state-active {
      background: var(--bad) !important;
      color: #180706 !important;
    }
  `]
})
export class ExecutionPageComponent implements OnInit {
  readonly tradingModes: TradingMode[] = ['PAPER', 'BACKTEST', 'LIVE'];
  readonly marketDataModes: MarketDataMode[] = ['MOCK', 'ZERODHA'];
  readonly executionModes: ExecutionMode[] = ['PAPER', 'ZERODHA'];
  readonly underlyings: UnderlyingSymbol[] = ['NIFTY', 'BANKNIFTY'];

  runtime?: RuntimeStatus;

  readonly modeForm = this.fb.nonNullable.group({
    mode: ['PAPER' as TradingMode, Validators.required]
  });

  readonly routingForm = this.fb.nonNullable.group({
    marketDataMode: ['MOCK' as MarketDataMode, Validators.required],
    executionMode: ['PAPER' as ExecutionMode, Validators.required]
  });

  constructor(private readonly api: ApiService, private readonly fb: FormBuilder) {
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.config().subscribe((config) => {
      this.setRuntime(config.runtime);
    });
  }

  start(): void {
    this.api.start().subscribe((runtime) => this.setRuntime(runtime));
  }

  stop(): void {
    this.api.stop().subscribe((runtime) => this.setRuntime(runtime));
  }

  setKillSwitch(enabled: boolean): void {
    this.api.setKillSwitch(enabled).subscribe((runtime) => this.setRuntime(runtime));
  }

  applyMode(): void {
    this.api.setMode(this.modeForm.controls.mode.value).subscribe((runtime) => this.setRuntime(runtime));
  }

  applyRouting(): void {
    const value = this.routingForm.getRawValue();
    this.api.setRouting(value.marketDataMode, value.executionMode).subscribe((runtime) => this.setRuntime(runtime));
  }

  setUnderlying(underlying: UnderlyingSymbol, enabled: boolean): void {
    this.api.setScanUnderlying(underlying, enabled).subscribe((runtime) => this.setRuntime(runtime));
  }

  isUnderlyingEnabled(underlying: UnderlyingSymbol): boolean {
    return this.runtime?.enabledUnderlyings?.includes(underlying) ?? false;
  }

  get routingMatchesRuntime(): boolean {
    return this.routingForm.controls.marketDataMode.value === this.runtime?.marketDataMode
      && this.routingForm.controls.executionMode.value === this.runtime?.executionMode;
  }

  private setRuntime(runtime: RuntimeStatus): void {
    this.runtime = runtime;
    this.modeForm.patchValue({ mode: runtime.requestedMode });
    this.routingForm.patchValue({
      marketDataMode: runtime.marketDataMode,
      executionMode: runtime.executionMode
    });
  }
}
