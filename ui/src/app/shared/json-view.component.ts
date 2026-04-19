import { Component, Input } from '@angular/core';
import { ApiRecord } from '../core/models';
import { DataTableComponent } from './data-table.component';

@Component({
  selector: 'app-json-view',
  standalone: true,
  imports: [DataTableComponent],
  template: '<app-data-table [rows]="rows"></app-data-table>'
})
export class JsonViewComponent {
  @Input() value: unknown;

  get rows(): ApiRecord[] {
    return this.toRows(this.value);
  }

  private toRows(value: unknown): ApiRecord[] {
    if (value === null || value === undefined) {
      return [];
    }
    if (Array.isArray(value)) {
      return value.map((item, index) => this.isRecord(item)
        ? { index: index + 1, ...item }
        : { index: index + 1, value: item });
    }
    if (this.isRecord(value)) {
      return Object.entries(value).map(([key, fieldValue]) => ({
        parameter: this.label(key),
        value: this.display(fieldValue)
      }));
    }
    return [{ parameter: 'Value', value: this.display(value) }];
  }

  private isRecord(value: unknown): value is ApiRecord {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  private label(value: string): string {
    return value
      .replace(/([A-Z])/g, ' $1')
      .replace(/[_-]/g, ' ')
      .replace(/^./, (char) => char.toUpperCase());
  }

  private display(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }
    if (Array.isArray(value)) {
      return value.map((item) => this.display(item)).join(', ');
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }
}
