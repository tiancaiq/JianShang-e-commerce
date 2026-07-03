import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.refreshSession().pipe(
    map(authState => authState.authenticated ? true : router.createUrlTree(['/login'], {
      queryParams: {
        client: loginClientForUrl(state.url),
        returnUrl: state.url,
      },
    }))
  );
};

function loginClientForUrl(url: string): 'marketplace' | 'seller-portal' | 'admin-portal' {
  if (url === '/admin' || url.startsWith('/admin/')) {
    return 'admin-portal';
  }
  if (url === '/seller' || url.startsWith('/seller/')) {
    return 'seller-portal';
  }
  return 'marketplace';
}
