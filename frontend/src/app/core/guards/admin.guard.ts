import { inject } from '@angular/core';
import { CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';
import { AdminService } from '../services/admin.service';
import { AuthService } from '../services/auth.service';

export const adminGuard: CanActivateFn = (_route, routeState) => {
  const authService = inject(AuthService);
  const adminService = inject(AdminService);
  const router = inject(Router);

  return authService.ensureSession().pipe(
    switchMap(authState => {
      if (!authState.authenticated) {
        return of(adminLoginTree(router, routeState));
      }

      return adminService.getCurrentAdmin().pipe(
        map(() => true),
        catchError(() => of(router.createUrlTree(['/admin-access-denied'], {
          queryParams: {
            returnUrl: routeState.url || '/admin/dashboard',
          },
        })))
      );
    })
  );
};

function adminLoginTree(router: Router, state: RouterStateSnapshot): UrlTree {
  return router.createUrlTree(['/login'], {
    queryParams: {
      client: 'admin-portal',
      returnUrl: state.url || '/admin/dashboard',
    },
  });
}
