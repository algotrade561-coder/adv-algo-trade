import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ServerStatusService {
  private readonly _online = signal(true);
  readonly online = this._online.asReadonly();

  markOnline(): void  { this._online.set(true); }
  markOffline(): void { this._online.set(false); }
}
