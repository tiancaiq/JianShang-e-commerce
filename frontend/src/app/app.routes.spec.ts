import { routes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';

describe('app routes', () => {
  it('keeps marketplace routes public', () => {
    const marketplaceRoute = routes.find(route => route.path === '');

    expect(marketplaceRoute).toBeTruthy();
    expect(marketplaceRoute?.canActivate).toBeUndefined();
  });

  it('protects seller and admin route groups', () => {
    const sellerRoute = routes.find(route => route.path === 'seller');
    const adminRoute = routes.find(route => route.path === 'admin');

    expect(sellerRoute?.canActivate).toContain(authGuard);
    expect(adminRoute?.canActivate).toContain(authGuard);
  });

  it('keeps compatibility redirects for old seller paths', () => {
    expect(routes).toContain(jasmine.objectContaining({
      path: 'listings/new',
      redirectTo: 'seller/listings/new',
    }));
    expect(routes).toContain(jasmine.objectContaining({
      path: 'business/apply',
      redirectTo: 'seller/business/apply',
    }));

    const consoleRoute = routes.find(route => route.path === 'console');
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'dashboard',
      redirectTo: '/seller/dashboard',
    }));
  });
});
