import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ServerStatusService } from './server-status.service';

export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const snackBar = inject(MatSnackBar);
  const serverStatus = inject(ServerStatusService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      // Auth required — redirect browser to Google login
      if (error.status === 401) {
        // Prevent redirect loop — only redirect once
        if (!sessionStorage.getItem('auth_redirecting')) {
          sessionStorage.setItem('auth_redirecting', 'true');
          const loginUrl = error.error?.loginUrl || '/advalgotrade/oauth2/authorization/google';
          window.location.href = loginUrl;
        }
        return throwError(() => error);
      }

      // 403 Forbidden — permission denied (non-admin accessing admin endpoint)
      if (error.status === 403) {
        snackBar.open('Access denied — insufficient permissions', 'Dismiss', { duration: 5000 });
        return throwError(() => error);
      }

      // 429 Rate Limited — user is sending too many requests
      if (error.status === 429) {
        snackBar.open('Too many requests — please slow down and retry in a few seconds', 'Dismiss', { duration: 5000 });
        return throwError(() => error);
      }

      const isNetworkOrServer = error.status === 0 || error.status >= 500;
      if (isNetworkOrServer) {
        serverStatus.markOffline();
        // Show user-visible error for 5xx/network failures
        const msg = error.status === 0
          ? 'Network connection lost — check your internet'
          : `Server error (${error.status}) — some data may be stale. Retrying automatically.`;
        snackBar.open(msg, 'Dismiss', { duration: 8000 });
      } else {
        // 4xx errors: action-specific, show snackbar
        const message = readableError(error);
        snackBar.open(message, 'Dismiss', { duration: 6000 });
      }
      return throwError(() => error);
    })
  );
};

function readableError(error: HttpErrorResponse): string {
  if (typeof error.error === 'string' && error.error.trim()) return error.error;
  if (error.error && typeof error.error === 'object') {
    if ('error' in error.error) return String(error.error.error);
    if ('message' in error.error) return String(error.error.message);
  }
  if (error.statusText && error.statusText !== 'Unknown Error') return error.statusText;
  return `Request failed (${error.status})`;
}
