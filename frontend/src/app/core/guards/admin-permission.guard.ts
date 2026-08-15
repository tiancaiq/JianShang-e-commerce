import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AdminPermission } from '../security/admin-permissions';
import { AdminService } from '../services/admin.service';

export const adminPermissionGuard: CanActivateFn = (route, state) => {
  const adminService = inject(AdminService);
  const router = inject(Router);
  const permission = requiredPermission(route);
  const denied = () => router.createUrlTree(['/admin-access-denied'], {
    queryParams: { returnUrl: state.url || '/admin/dashboard' },
  });

  if (adminService.currentAdmin()) {
    return adminService.hasPermission(permission) ? true : denied();
  }
  return adminService.getCurrentAdmin().pipe(
    map(() => adminService.hasPermission(permission) ? true : denied()),
    catchError(() => of(denied())),
  );
};

function requiredPermission(route: ActivatedRouteSnapshot): AdminPermission {
  return route.data['adminPermission'] as AdminPermission;
}
