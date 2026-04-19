import { ChangeDetectorRef, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ApiService } from '../core/api.service';
import { KiteLoginResponse, KiteSessionResponse } from '../core/models';
import { JsonViewComponent } from '../shared/json-view.component';

@Component({
  selector: 'app-auth-page',
  standalone: true,
  imports: [MatButtonModule, JsonViewComponent],
  template: `
    <section class="page">
      <h1 class="page-title">Kite Auth</h1>
      <p class="page-subtitle">Start Zerodha login and let the callback refresh the daily access token.</p>

      <div class="grid two">
        <div class="panel">
          <h2>Initial Login Flow</h2>
          <p class="page-subtitle">
            Launch Zerodha login, complete authentication, and let the callback capture the daily access token.
          </p>
          <div class="action-strip">
            <button class="action-button" [class.active]="login?.launched || login?.loginUrl" mat-flat-button color="primary" (click)="getLoginUrl()">Initiate Login Flow</button>
            @if (login?.loginUrl) {
              <a class="action-button active" mat-stroked-button [href]="login?.loginUrl" target="_blank" rel="noreferrer">Open Kite Login</a>
            }
          </div>
          @if (login?.message) {
            <p class="page-subtitle" style="margin-top: 14px; margin-bottom: 0;">{{ login?.message }}</p>
          }
        </div>

        <div class="panel">
          <h2>Session</h2>
          <p class="page-subtitle">
            Use the configured or persisted access token when available, otherwise start the login flow.
          </p>
          <div class="action-strip">
            <button
              class="action-button"
              [class.active]="session?.authenticated"
              mat-flat-button
              color="primary"
              [disabled]="sessionLoading"
              (click)="startSession()">
              {{ sessionLoading ? 'Validating...' : 'Start / Validate Session' }}
            </button>
            @if (session) {
              <span class="badge" [class.ok]="session.authenticated" [class.warn]="!session.authenticated">
                {{ session.authenticated ? 'Authenticated' : 'Needs login' }}
              </span>
            }
          </div>
        </div>
      </div>

      <div class="grid two" style="margin-top: 16px;">
        <div class="panel">
          <h2>Login Response</h2>
          <app-json-view [value]="login"></app-json-view>
        </div>
        <div class="panel">
          <h2>Session Response</h2>
          <app-json-view [value]="session"></app-json-view>
        </div>
      </div>
    </section>
  `
})
export class AuthPageComponent {
  login?: KiteLoginResponse;
  session?: KiteSessionResponse;
  sessionLoading = false;

  constructor(
    private readonly api: ApiService,
    private readonly changeDetector: ChangeDetectorRef
  ) {
  }

  getLoginUrl(): void {
    const loginWindow = window.open('about:blank', '_blank');
    if (loginWindow) {
      loginWindow.opener = null;
    }
    this.api.kiteLogin().subscribe({
      next: (login) => {
        this.login = login;
        if (login.loginUrl) {
          if (loginWindow) {
            loginWindow.location.href = login.loginUrl;
          } else {
            window.open(login.loginUrl, '_blank', 'noopener,noreferrer');
          }
        } else {
          loginWindow?.close();
        }
      },
      error: () => {
        loginWindow?.close();
      }
    });
  }

  startSession(): void {
    this.sessionLoading = true;
    this.api.kiteSession().subscribe({
      next: (session) => {
        this.session = session;
        this.sessionLoading = false;
        this.changeDetector.detectChanges();
      },
      error: () => {
        this.sessionLoading = false;
        this.changeDetector.detectChanges();
      }
    });
  }
}
