import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';
import { AdminService } from '../services/admin.service';
import { AuthService } from '../services/auth.service';

export const adminGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const adminService = inject(AdminService);
  const router = inject(Router);

  return authService.ensureSession().pipe(
    switchMap(state => {
      if (!state.authenticated) {
        return of(router.createUrlTree(['/login']));
      }

      return adminService.getCurrentAdmin().pipe(
        map(() => true),
        catchError(() => of(router.createUrlTree(['/'])))
      );
    })
  );
};
