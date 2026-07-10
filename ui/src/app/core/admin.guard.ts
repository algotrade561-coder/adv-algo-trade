import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AdminService } from './admin.service';

/**
 * Route guard for admin-only pages (e.g. /admin/users).
 * Redirects non-admin users to the dashboard.
 */
export const adminGuard: CanActivateFn = () => {
  const admin = inject(AdminService);
  const router = inject(Router);

  if (admin.isAdmin()) {
    return true;
  }

  // Non-admin: redirect to dashboard
  router.navigate(['/dashboard']);
  return false;
};

/**
 * Route guard for SUPERUSER-only pages (e.g. /admin/aws-ip).
 * Redirects everyone else to the dashboard.
 */
export const superUserGuard: CanActivateFn = () => {
  const admin = inject(AdminService);
  const router = inject(Router);

  if (admin.isSuperUser()) {
    return true;
  }

  router.navigate(['/dashboard']);
  return false;
};
