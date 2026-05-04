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

      const isNetworkOrServer = error.status === 0 || error.status >= 500;
      if (isNetworkOrServer) {
        serverStatus.markOffline(); // banner in AppComponent handles user communication
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
  if (error.error && typeof error.error === 'object' && 'message' in error.error) return String(error.error.message);
  return error.message || 'Request failed';
}
