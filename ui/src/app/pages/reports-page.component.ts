import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ApiService } from '../core/api.service';
import { ReportArchiveResult } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-reports-page',
  standalone: true,
  imports: [MatButtonModule, JsonViewComponent],
  template: `
    <section class="page">
      <h1 class="page-title">Reports</h1>
      <p class="page-subtitle">Download trade journal CSV and archive entry-signal analysis files.</p>

      <div class="grid two">
        <div class="panel">
          <h2>Trade Journal</h2>
          <p class="page-subtitle">Download the current trade journal as CSV.</p>
          <button mat-flat-button color="primary" (click)="downloadJournal()">Download CSV</button>
        </div>
        <div class="panel">
          <h2>Entry Signal Reports</h2>
          <p class="page-subtitle">Archive generated entry-signal report files.</p>
          <button mat-flat-button color="primary" (click)="archive()">Archive Reports</button>
        </div>
      </div>

      <div class="panel" style="margin-top: 16px;">
        <h2>Last Archive Result</h2>
        <app-json-view [value]="archiveResult"></app-json-view>
      </div>
    </section>
  `
})
export class ReportsPageComponent {
  archiveResult?: ReportArchiveResult;

  constructor(private readonly api: ApiService) {
  }

  downloadJournal(): void {
    this.api.tradeJournalCsv().subscribe((csv) => {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'trade-journal.csv';
      link.click();
      URL.revokeObjectURL(url);
    });
  }

  archive(): void {
    this.api.archiveEntrySignals().subscribe((result) => {
      this.archiveResult = result;
    });
  }
}
