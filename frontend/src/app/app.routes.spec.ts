import { routes } from './app.routes';
import { adminGuard } from './core/guards/admin.guard';
import { authGuard } from './core/guards/auth.guard';
import { MarketplaceHomeComponent } from './features/marketplace/marketplace-home.component';
import { PublicListingDetailComponent } from './features/marketplace/public-listing-detail.component';
import { BusinessStoresComponent } from './features/stores/business-stores.component';

describe('app routes', () => {
  const v2DemoSegments = new Set([
    'products',
    'orders',
    'payments',
    'inventory',
    'cart',
    'checkout',
    'wallet',
    'notifications',
  ]);

  it('keeps marketplace routes public', () => {
    const marketplaceRoute = routes.find(route => route.path === '');

    expect(marketplaceRoute).toBeTruthy();
    expect(marketplaceRoute?.canActivate).toBeUndefined();
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'marketplace',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'stores',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings/:listingId',
    }));
  });

  it('keeps public route components split by surface', async () => {
    const marketplaceRoute = routes.find(route => route.path === '');
    const child = (path: string) => marketplaceRoute?.children?.find(route => route.path === path);
    const load = async (path: string): Promise<unknown> => {
      const route = child(path);
      expect(route?.canActivate).withContext(`${path || '/'} must remain public`).toBeUndefined();
      expect(route?.loadComponent).withContext(`${path || '/'} must lazy-load a component`).toBeTruthy();
      return await route?.loadComponent?.();
    };

    await expectAsync(Promise.resolve(load(''))).toBeResolvedTo(MarketplaceHomeComponent);
    await expectAsync(Promise.resolve(load('marketplace'))).toBeResolvedTo(MarketplaceHomeComponent);
    await expectAsync(Promise.resolve(load('stores'))).toBeResolvedTo(BusinessStoresComponent);
    await expectAsync(Promise.resolve(load('listings/:listingId'))).toBeResolvedTo(PublicListingDetailComponent);
  });

  it('protects seller and admin route groups', () => {
    const sellerRoute = routes.find(route => route.path === 'seller');
    const adminRoute = routes.find(route => route.path === 'admin');

    expect(sellerRoute?.canActivate).toContain(authGuard);
    expect(adminRoute?.canActivate).toContain(adminGuard);
    expect(adminRoute?.children).toContain(jasmine.objectContaining({
      path: 'dashboard',
    }));
    expect(adminRoute?.children).toContain(jasmine.objectContaining({
      path: 'business-applications/:id',
    }));
    expect(adminRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings/moderation/:caseId',
    }));
    expect(adminRoute?.children).toContain(jasmine.objectContaining({
      path: '',
      redirectTo: 'dashboard',
    }));
  });

  it('protects marketplace account listing routes', () => {
    const marketplaceRoute = routes.find(route => route.path === '');

    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'sell',
      redirectTo: 'account/listings',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/profile',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/seller-profile',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/listings',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/listings/new',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/listings/:listingId/edit',
      canActivate: [authGuard],
    }));
  });

  it('keeps seller portal business-only and redirects old personal seller paths', () => {
    const sellerRoute = routes.find(route => route.path === 'seller');

    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings',
      redirectTo: '/account/listings',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings/new',
      redirectTo: '/account/listings/new',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings/:listingId/edit',
      redirectTo: '/account/listings/:listingId/edit',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'activate',
      redirectTo: '/account/seller-profile',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'profile',
      redirectTo: '/account/profile',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'business/apply',
    }));
  });

  it('keeps compatibility redirects for old console paths', () => {
    expect(routes).toContain(jasmine.objectContaining({
      path: 'dashboard',
      redirectTo: '',
    }));
    expect(routes).toContain(jasmine.objectContaining({
      path: 'listings/new',
      redirectTo: 'account/listings/new',
    }));
    expect(routes).toContain(jasmine.objectContaining({
      path: 'business/apply',
      redirectTo: 'seller/business/apply',
    }));
    expect(routes).toContain(jasmine.objectContaining({
      path: 'profile',
      redirectTo: 'account/profile',
    }));

    const consoleRoute = routes.find(route => route.path === 'console');
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'dashboard',
      redirectTo: '/seller/dashboard',
    }));
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings',
      redirectTo: '/account/listings',
    }));
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'listings/new',
      redirectTo: '/account/listings/new',
    }));
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'seller/activate',
      redirectTo: '/account/seller-profile',
    }));
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'profile',
      redirectTo: '/account/profile',
    }));
  });

  it('does not combine redirects with route guards', () => {
    const visit = (routeList: typeof routes): void => {
      for (const route of routeList) {
        if (route.redirectTo) {
          expect(route.canActivate)
            .withContext(`route "${route.path}" uses redirectTo and cannot also use canActivate`)
            .toBeUndefined();
        }

        if (route.children) {
          visit(route.children);
        }
      }
    };

    visit(routes);
  });

  it('does not expose V2 demo commerce routes in the active MVP route tree', () => {
    const firstSegment = (path: string | undefined): string => {
      return (path || '').replace(/^\//, '').split('/')[0];
    };

    const visit = (routeList: typeof routes): void => {
      for (const route of routeList) {
        expect(v2DemoSegments.has(firstSegment(route.path)))
          .withContext(`route "${route.path}" should not expose V2 demo UI in MVP navigation`)
          .toBeFalse();

        expect(v2DemoSegments.has(firstSegment(route.redirectTo as string | undefined)))
          .withContext(`redirect "${route.redirectTo}" should not point to V2 demo UI in MVP navigation`)
          .toBeFalse();

        if (route.children) {
          visit(route.children);
        }
      }
    };

    visit(routes);
  });
});
