import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';

export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const snackBar = inject(MatSnackBar);
  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      const message = readableError(error);
      snackBar.open(message, 'Dismiss', { duration: 6000 });
      return throwError(() => error);
    })
  );
};

function readableError(error: HttpErrorResponse): string {
  if (typeof error.error === 'string' && error.error.trim()) {
    return error.error;
  }
  if (error.error && typeof error.error === 'object' && 'message' in error.error) {
    return String(error.error.message);
  }
  return error.message || 'Request failed';
}
