import { Component, Input } from '@angular/core';
import { AgGridAngular } from 'ag-grid-angular';
import { AllCommunityModule, ColDef, Module } from 'ag-grid-community';
import { ApiRecord } from '../core/models';

@Component({
  selector: 'app-data-table',
  standalone: true,
  imports: [AgGridAngular],
  template: `
    <div class="ag-grid-wrap ag-theme-quartz">
      <ag-grid-angular
        [modules]="modules"
        [rowData]="rows"
        [columnDefs]="columnDefs"
        [defaultColDef]="defaultColDef"
        [animateRows]="true"
        [pagination]="true"
        [paginationPageSize]="25"
        [paginationPageSizeSelector]="[10, 25, 50, 100]"
        [rowHeight]="44"
        [headerHeight]="44">
      </ag-grid-angular>
      @if (rows.length === 0) {
        <p class="empty-state">No records returned.</p>
      }
    </div>
  `
})
export class DataTableComponent {
  readonly modules: Module[] = [AllCommunityModule];
  readonly defaultColDef: ColDef = {
    sortable: true,
    filter: true,
    floatingFilter: true,
    resizable: true,
    wrapText: true,
    autoHeight: true,
    minWidth: 140
  };

  @Input() rows: ApiRecord[] = [];

  get columnDefs(): ColDef[] {
    const keys = new Set<string>();
    for (const row of this.rows.slice(0, 20)) {
      Object.keys(row).forEach((key) => keys.add(key));
    }
    return Array.from(keys).map((key) => ({
      field: key,
      headerName: this.label(key),
      valueFormatter: (params) => this.display(params.value),
      tooltipValueGetter: (params) => this.display(params.value)
    }));
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

  private label(value: string): string {
    return value
      .replace(/([A-Z])/g, ' $1')
      .replace(/[_-]/g, ' ')
      .replace(/^./, (char) => char.toUpperCase());
  }
}
