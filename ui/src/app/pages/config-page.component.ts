import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiService } from '../core/api.service';
import { ConfigParameter, ConfigResponse } from '../core/models';
import { DataTableComponent } from '../shared/data-table.component';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-config-page',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, DataTableComponent, JsonViewComponent],
  template: `
    <section class="page">
      <div class="row">
        <div>
          <h1 class="page-title">Configuration</h1>
          <p class="page-subtitle">Current values, descriptions, and usage notes for each trading parameter.</p>
        </div>
        <span class="spacer"></span>
        <span class="badge" [class.ok]="config && !loadError" [class.warn]="loadError">
          {{ loading ? 'Loading' : lastUpdatedAt ? 'Updated ' + lastUpdatedAt : 'Waiting' }}
        </span>
        <button mat-flat-button color="primary" [disabled]="loading" (click)="load()">Refresh</button>
      </div>
      @if (loadError) {
        <p class="page-subtitle status-warn" style="margin-top: -12px;">{{ loadError }}</p>
      }

      @if (!config) {
        <div class="panel metric">
          <span class="metric-label">Configuration</span>
          <span class="metric-value status-warn">Loading</span>
          <span class="metric-note">Waiting for configuration from the server.</span>
        </div>
      } @else {
      <div class="panel">
        <h2>Parameter Catalog</h2>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Search parameter</mat-label>
          <input matInput [formControl]="search" placeholder="risk, broker, breakout, telegram">
        </mat-form-field>
        <app-data-table [rows]="filteredRows"></app-data-table>
      </div>

      <div class="panel" style="margin-top: 16px;">
        <h2>Full Snapshot</h2>
        <app-json-view [value]="config"></app-json-view>
      </div>
      }
    </section>
  `
})
export class ConfigPageComponent implements OnInit {
  readonly search = new FormControl('', { nonNullable: true });
  config?: ConfigResponse;
  loading = false;
  loadError = '';
  lastUpdatedAt = '';
  private requestInFlight = false;

  constructor(
    private readonly api: ApiService,
    private readonly changeDetector: ChangeDetectorRef
  ) {
  }

  get filtered(): ConfigParameter[] {
    const term = this.search.value.trim().toLowerCase();
    const rows = this.config?.parameters ?? [];
    if (!term) {
      return rows;
    }
    return rows.filter((row) =>
      row.path.toLowerCase().includes(term)
      || row.description.toLowerCase().includes(term)
      || row.usedBy.toLowerCase().includes(term)
    );
  }

  get filteredRows() {
    return this.filtered.map((row) => ({
      path: row.path,
      value: this.display(row.value),
      description: row.description,
      usedBy: row.usedBy,
      sensitive: row.sensitive ? 'Yes' : 'No'
    }));
  }

  ngOnInit(): void {
    setTimeout(() => this.load(), 0);
  }

  load(): void {
    if (this.requestInFlight) {
      return;
    }
    this.requestInFlight = true;
    this.loading = true;
    this.loadError = '';
    this.api.config().subscribe({
      next: (config) => {
        this.config = config;
        this.loading = false;
        this.requestInFlight = false;
        this.lastUpdatedAt = new Date().toLocaleTimeString();
        this.changeDetector.detectChanges();
      },
      error: () => {
        this.loading = false;
        this.requestInFlight = false;
        this.loadError = 'Unable to load configuration. Click Refresh to try again.';
        this.changeDetector.detectChanges();
      }
    });
  }

  display(value: unknown): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }
}
