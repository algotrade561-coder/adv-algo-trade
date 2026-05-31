import { Component, OnInit, inject, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { ApiService } from '../core/api.service';

@Component({
  selector: 'app-tuning-report-page',
  standalone: true,
  imports: [MatButtonModule],
  template: `
    <section class="page">
      <h1 class="page-title">Tuning report</h1>
      <p class="page-subtitle">Job: {{ jobId() }}</p>
      @if (status()) { <p>Status: <strong>{{ status() }}</strong></p> }
      @if (error()) { <div class="toast-warn">{{ error() }}</div> }
      @if (html()) {
        <div class="report-frame" [innerHTML]="html()"></div>
      } @else {
        <button mat-flat-button color="primary" (click)="poll()">Reload</button>
      }
    </section>
  `,
  styles: [`.report-frame { background:#fff;color:#111;padding:8px;border-radius:8px;overflow:auto;max-height:85vh }`]
})
export class TuningReportPageComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);
  private sanitizer = inject(DomSanitizer);

  jobId = signal('');
  status = signal('');
  error = signal<string | null>(null);
  html = signal<SafeHtml | null>(null);

  ngOnInit(): void {
    this.route.paramMap.subscribe(m => {
      const id = m.get('jobId') ?? '';
      this.jobId.set(id);
      this.poll();
    });
  }

  poll(): void {
    const id = this.jobId();
    if (!id) return;
    this.api.getTuningReportJob(id).subscribe({
      next: j => {
        this.status.set(String(j['status'] ?? ''));
        if (j['status'] === 'COMPLETE') {
          this.api.getTuningReportHtml(id).subscribe({
            next: body => this.html.set(this.sanitizer.bypassSecurityTrustHtml(body)),
            error: e => this.error.set(e?.message ?? 'HTML load failed')
          });
        }
      },
      error: e => this.error.set(e?.message ?? 'Job load failed')
    });
  }
}
