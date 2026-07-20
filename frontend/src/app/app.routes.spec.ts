import {
  agentMessageRoutes,
  buyerAddressesRoute,
  businessOrderRoutes,
  cartRoute,
  categoryGuidanceRoute,
  checkoutDetailRoute,
  checkoutReviewRoute,
  routes,
  sellerInventoryRoute,
} from './app.routes';
import { adminGuard } from './core/guards/admin.guard';
import { authGuard } from './core/guards/auth.guard';
import { MarketplaceHomeComponent } from './features/marketplace/marketplace-home.component';
import { PublicListingDetailComponent } from './features/marketplace/public-listing-detail.component';
import { NotFoundComponent } from './features/not-found/not-found.component';
import { BusinessAccountComponent } from './features/business/business-account.component';
import { BusinessStoresComponent } from './features/stores/business-stores.component';
import { PublicStoreProfileComponent } from './features/stores/public-store-profile.component';

describe('app routes', () => {
  const v2DemoSegments = new Set([
    'products',
    'payments',
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
      path: 'stores/:storeSlug',
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
    await expectAsync(Promise.resolve(load('stores/:storeSlug'))).toBeResolvedTo(PublicStoreProfileComponent);
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
      path: 'category-guidance',
      redirectTo: 'dashboard',
    }));
    expect(adminRoute?.children).toContain(jasmine.objectContaining({
      path: '',
      redirectTo: 'dashboard',
    }));
  });

  it('protects marketplace account listing routes', () => {
    const marketplaceRoute = routes.find(route => route.path === '');

    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'cart',
      redirectTo: '/marketplace',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'sell',
      redirectTo: 'account/listings',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/profile',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/addresses',
      redirectTo: '/account',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/liked',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/seller-profile',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/messages',
      canActivate: [authGuard],
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/messages/agent',
      redirectTo: '/account/messages',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/messages/agent/:sessionId',
      redirectTo: '/account/messages',
    }));
    expect(marketplaceRoute?.children).toContain(jasmine.objectContaining({
      path: 'account/messages/:conversationId',
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
      redirectTo: '/seller/account',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'business/apply',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'businesses/:businessId/store',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'store/items',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'store/items/new',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'store/items/:listingId/edit',
    }));
    expect(sellerRoute?.children).toContain(jasmine.objectContaining({
      path: 'account',
    }));
  });

  it('keeps seller account inside the business seller surface', async () => {
    const sellerRoute = routes.find(route => route.path === 'seller');
    const accountRoute = sellerRoute?.children?.find(route => route.path === 'account');

    expect(accountRoute?.redirectTo).toBeUndefined();
    expect(accountRoute?.loadComponent).toBeTruthy();
    await expectAsync(Promise.resolve(accountRoute?.loadComponent?.())).toBeResolvedTo(BusinessAccountComponent);
  });

  it('redirects the deferred seller inventory route while the V2 flag is disabled', () => {
    const sellerRoute = routes.find(route => route.path === 'seller');
    const inventoryRoute = sellerRoute?.children?.find(route => route.path === 'inventory');

    expect(inventoryRoute?.redirectTo).toBe('store/items');
    expect(inventoryRoute?.pathMatch).toBe('full');
    expect(inventoryRoute?.loadComponent).toBeUndefined();
  });

  it('redirects both business order routes while the V2 flag is disabled', () => {
    const sellerRoute = routes.find(route => route.path === 'seller');
    const orderRoutes = sellerRoute?.children?.filter(route => route.path?.startsWith('orders')) || [];

    expect(orderRoutes).toEqual([
      jasmine.objectContaining({ path: 'orders/:businessOrderId', redirectTo: 'dashboard', pathMatch: 'full' }),
      jasmine.objectContaining({ path: 'orders', redirectTo: 'dashboard', pathMatch: 'full' }),
    ]);
    expect(orderRoutes.every(route => route.loadComponent === undefined)).toBeTrue();
  });

  it('redirects checkout routes to the marketplace while the production feature flag is disabled', () => {
    const marketplaceRoute = routes.find(route => route.path === '');
    const review = marketplaceRoute?.children?.find(route => route.path === 'checkout');
    const detail = marketplaceRoute?.children?.find(route => route.path === 'checkout/:checkoutId');

    expect(review?.redirectTo).toBe('/marketplace');
    expect(detail?.redirectTo).toBe('/marketplace');
    expect(review?.loadComponent).toBeUndefined();
    expect(detail?.loadComponent).toBeUndefined();
  });

  it('keeps deferred components available only through explicit opt-in route construction', () => {
    const enabledRoutes = [
      sellerInventoryRoute(true),
      ...businessOrderRoutes(true),
      checkoutReviewRoute(true),
      checkoutDetailRoute(true),
      cartRoute(true),
      buyerAddressesRoute(true),
      categoryGuidanceRoute(true),
      ...agentMessageRoutes(true),
    ];

    for (const route of enabledRoutes) {
      expect(route.redirectTo).toBeUndefined();
      expect(route.loadComponent).toBeTruthy();
    }
    expect(cartRoute(true).canActivate).toContain(authGuard);
    expect(buyerAddressesRoute(true).canActivate).toContain(authGuard);
    expect(checkoutReviewRoute(true).canActivate).toContain(authGuard);
    expect(checkoutDetailRoute(true).canActivate).toContain(authGuard);
    expect(agentMessageRoutes(true).every(route => route.canActivate?.includes(authGuard))).toBeTrue();
    expect(businessOrderRoutes(true).every(route => route.loadComponent)).toBeTrue();
  });

  it('reserves explicit agent routes before buyer-seller conversation parameters', () => {
    const marketplaceRoute = routes.find(route => route.path === '');
    const children = marketplaceRoute?.children || [];
    const agentIndex = children.findIndex(route => route.path === 'account/messages/agent');
    const agentSessionIndex = children.findIndex(route => route.path === 'account/messages/agent/:sessionId');
    const conversationIndex = children.findIndex(route => route.path === 'account/messages/:conversationId');

    expect(agentIndex).toBeGreaterThanOrEqual(0);
    expect(agentSessionIndex).toBeGreaterThanOrEqual(0);
    expect(agentIndex).toBeLessThan(conversationIndex);
    expect(agentSessionIndex).toBeLessThan(conversationIndex);
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
      redirectTo: '/seller/account',
    }));
    expect(consoleRoute?.children).toContain(jasmine.objectContaining({
      path: 'admin/category-guidance',
      redirectTo: '/admin/dashboard',
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

  it('shows a branded not found page for unknown routes', async () => {
    const wildcardRoute = routes.find(route => route.path === '**');

    expect(wildcardRoute?.redirectTo).toBeUndefined();
    expect(wildcardRoute?.loadComponent).toBeTruthy();
    await expectAsync(Promise.resolve(wildcardRoute?.loadComponent?.())).toBeResolvedTo(NotFoundComponent);
  });

  it('does not expose unimplemented V2 commerce routes in the active route tree', () => {
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
