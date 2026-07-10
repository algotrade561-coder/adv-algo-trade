import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  MAT_DIALOG_DATA, MatDialogModule, MatDialogRef
} from '@angular/material/dialog';

export interface ProvisionIpDialogData {
  email: string;
  privateIp?: string;
  publicIp?: string;
  eniId?: string;
}

export interface ProvisionIpDialogResult {
  privateIp?: string;
  publicIp?: string;
  eniId?: string;
}

/**
 * Source-IP record/provision dialog — replaces the browser prompt() popups so the
 * flow matches the rest of the app's Material dialogs.
 */
@Component({
  selector: 'app-provision-ip-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatDialogModule
  ],
  template: `
    <div class="prov-title" mat-dialog-title>
      <div class="prov-title-main">
        <mat-icon>add_link</mat-icon>
        <div>
          <strong>Source IP</strong>
          <span>{{ data.email }}</span>
        </div>
      </div>
    </div>

    <mat-dialog-content>
      <p class="prov-hint">
        Record the IPs assigned on AWS for this user. The <strong>public</strong> Elastic IP
        is what gets whitelisted in Kite; the <strong>private</strong> IP is what the app binds to.
      </p>

      <mat-form-field appearance="outline" class="prov-field">
        <mat-label>Private (secondary) IP on the ENI</mat-label>
        <input matInput [(ngModel)]="privateIp" placeholder="e.g. 172.31.20.45" autocomplete="off" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="prov-field">
        <mat-label>Public Elastic IP (whitelist in Kite)</mat-label>
        <input matInput [(ngModel)]="publicIp" placeholder="optional" autocomplete="off" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="prov-field">
        <mat-label>ENI id</mat-label>
        <input matInput [(ngModel)]="eniId" placeholder="optional" autocomplete="off" />
      </mat-form-field>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-stroked-button (click)="cancel()">Cancel</button>
      <button mat-flat-button color="primary" [disabled]="!privateIp.trim()" (click)="save()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .prov-title { display:flex; align-items:center; justify-content:space-between; gap:16px; padding-bottom:4px; }
    .prov-title-main { display:flex; align-items:center; gap:12px; }
    .prov-title-main mat-icon { color: var(--accent); }
    .prov-title strong { display:block; color: var(--ink); font-size:20px; font-weight:800; }
    .prov-title span { display:block; color: var(--muted); font-size:13px; margin-top:2px; }
    .prov-hint { color: var(--muted); font-size:13px; margin:4px 0 16px; max-width:420px; }
    .prov-field { width:100%; min-width: min(420px, 80vw); display:block; }
  `]
})
export class ProvisionIpDialogComponent {
  privateIp: string;
  publicIp: string;
  eniId: string;

  constructor(
    private readonly ref: MatDialogRef<ProvisionIpDialogComponent, ProvisionIpDialogResult>,
    @Inject(MAT_DIALOG_DATA) readonly data: ProvisionIpDialogData
  ) {
    this.privateIp = data.privateIp ?? '';
    this.publicIp = data.publicIp ?? '';
    this.eniId = data.eniId ?? '';
  }

  cancel(): void {
    this.ref.close();
  }

  save(): void {
    if (!this.privateIp.trim()) return;
    this.ref.close({
      privateIp: this.privateIp.trim() || undefined,
      publicIp: this.publicIp.trim() || undefined,
      eniId: this.eniId.trim() || undefined
    });
  }
}
