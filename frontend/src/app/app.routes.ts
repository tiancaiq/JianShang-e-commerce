import { Route, Routes } from '@angular/router';
import { adminGuard } from './core/guards/admin.guard';
import { adminPermissionGuard } from './core/guards/admin-permission.guard';
import { ADMIN_PERMISSIONS } from './core/security/admin-permissions';
import { authGuard } from './core/guards/auth.guard';
import { environment } from '../environments/environment';
import { BUSINESS_ORDERS_DEFAULT_ENABLED } from './features/business/business-orders.capability';
import { NOTIFICATION_CENTER_DEFAULT_ENABLED } from './features/account/notification-center.capability';
import { CART_DEFAULT_ENABLED } from './features/cart/cart.capability';
import { ADMIN_SEARCH_MAINTENANCE_DEFAULT_ENABLED } from './features/admin/admin-search-maintenance.capability';

// Keeps deferred components available for explicit tests without exposing them in the MVP route tree.
export function sellerInventoryRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'inventory',
      loadComponent: () => import('./features/business/business-inventory.component')
        .then(m => m.BusinessInventoryComponent),
    }
  : { path: 'inventory', redirectTo: 'store/items', pathMatch: 'full' };
}

export function businessOrderRoutes(enabled: boolean): Routes {
  if (!enabled) {
    return [
      { path: 'orders/:businessOrderId', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'orders', redirectTo: 'dashboard', pathMatch: 'full' },
    ];
  }
  return [
    {
      path: 'orders/:businessOrderId',
      loadComponent: () => import('./features/business/business-orders.component')
        .then(m => m.BusinessOrdersComponent),
    },
    {
      path: 'orders',
      loadComponent: () => import('./features/business/business-orders.component')
        .then(m => m.BusinessOrdersComponent),
    },
  ];
}

export function checkoutReviewRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'checkout',
      canActivate: [authGuard],
      loadComponent: () => import('./features/checkout/checkout-review.component')
        .then(m => m.CheckoutReviewComponent),
    }
  : { path: 'checkout', redirectTo: '/marketplace', pathMatch: 'full' };
}

export function checkoutDetailRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'checkout/:checkoutId',
      canActivate: [authGuard],
      loadComponent: () => import('./features/checkout/checkout-detail.component')
        .then(m => m.CheckoutDetailComponent),
    }
  : { path: 'checkout/:checkoutId', redirectTo: '/marketplace', pathMatch: 'full' };
}

export function buyerOrderRoutes(enabled: boolean): Routes {
  return enabled ? [
    { path: 'account/orders', canActivate: [authGuard], loadComponent: () => import('./features/orders/order-list.component').then(m => m.OrderListComponent) },
    { path: 'account/orders/:orderId', canActivate: [authGuard], loadComponent: () => import('./features/orders/order-detail.component').then(m => m.OrderDetailComponent) },
  ] : [
    { path: 'account/orders', redirectTo: '/account', pathMatch: 'full' },
    { path: 'account/orders/:orderId', redirectTo: '/account', pathMatch: 'full' },
  ];
}

export function cartRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'cart',
      canActivate: [authGuard],
      loadComponent: () => import('./features/cart/cart.component').then(m => m.CartComponent),
    }
  : { path: 'cart', redirectTo: '/marketplace', pathMatch: 'full' };
}

export function buyerAddressesRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'account/addresses',
      canActivate: [authGuard],
      loadComponent: () => import('./features/account/address-book.component').then(m => m.AddressBookComponent),
    }
  : { path: 'account/addresses', redirectTo: '/account', pathMatch: 'full' };
}

export function notificationCenterRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'account/notifications',
      canActivate: [authGuard],
      loadComponent: () => import('./features/account/notification-center.component')
        .then(m => m.NotificationCenterComponent),
    }
  : { path: 'account/notifications', redirectTo: '/account', pathMatch: 'full' };
}

export function marketplaceAgentV2Route(enabled: boolean): Route {
  return enabled ? {
    path: 'account/marketplace-agent-v2',
    canActivate: [authGuard],
    loadComponent: () => import('./features/agent/agent-marketplace-v2-page.component')
      .then(m => m.AgentMarketplaceV2PageComponent),
  } : {
    path: 'account/marketplace-agent-v2', redirectTo: '/account/messages', pathMatch: 'full',
  };
}

export function categoryGuidanceRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'category-guidance',
      loadComponent: () => import('./features/admin/category-guidance-editor.component')
        .then(m => m.CategoryGuidanceEditorComponent),
    }
  : { path: 'category-guidance', redirectTo: 'dashboard', pathMatch: 'full' };
}

export function adminSearchMaintenanceRoute(enabled: boolean): Route {
  return enabled ? {
      path: 'search-maintenance',
      loadComponent: () => import('./features/admin/admin-search-maintenance.component')
        .then(m => m.AdminSearchMaintenanceComponent),
    }
  : { path: 'search-maintenance', redirectTo: 'dashboard', pathMatch: 'full' };
}

// Keeps the reserved agent paths ahead of :conversationId while defaulting to a network-silent redirect.
export function agentMessageRoutes(
  customerServiceEnabled: boolean,
  discoveryEnabled = false,
  marketplaceAgentV2Enabled = false,
): Routes {
  if (marketplaceAgentV2Enabled || (!customerServiceEnabled && !discoveryEnabled)) {
    return [
      { path: 'account/messages/agent/:sessionId', redirectTo: '/account/messages', pathMatch: 'full' },
      { path: 'account/messages/agent', redirectTo: '/account/messages', pathMatch: 'full' },
    ];
  }
  return [
    {
      path: 'account/messages/agent/:sessionId',
      canActivate: [authGuard],
      loadComponent: () => import('./features/agent/agent-customer-service-page.component')
        .then(m => m.AgentCustomerServicePageComponent),
    },
    {
      path: 'account/messages/agent',
      canActivate: [authGuard],
      loadComponent: () => import('./features/agent/agent-customer-service-page.component')
        .then(m => m.AgentCustomerServicePageComponent),
    },
  ];
}

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent),
  },
  {
    path: 'admin-access-denied',
    loadComponent: () => import('./features/admin/admin-access-denied.component').then(m => m.AdminAccessDeniedComponent),
  },
  {
    path: 'seller',
    loadComponent: () => import('./layout/seller-layout/seller-layout.component').then(m => m.SellerLayoutComponent),
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', loadComponent: () => import('./features/seller/seller-dashboard.component').then(m => m.SellerDashboardComponent) },
      { path: 'listings', redirectTo: '/account/listings', pathMatch: 'full' },
      { path: 'listings/new', redirectTo: '/account/listings/new', pathMatch: 'full' },
      { path: 'listings/:listingId/edit', redirectTo: '/account/listings/:listingId/edit', pathMatch: 'full' },
      { path: 'activate', redirectTo: '/account/seller-profile', pathMatch: 'full' },
      { path: 'business/apply', loadComponent: () => import('./features/business/business-application.component').then(m => m.BusinessApplicationComponent) },
      { path: 'businesses/:businessId/store', loadComponent: () => import('./features/business/business-store.component').then(m => m.BusinessStoreComponent) },
      { path: 'store/items', loadComponent: () => import('./features/business/business-store-items.component').then(m => m.BusinessStoreItemsComponent) },
      { path: 'store/items/new', loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'store/items/:listingId/edit', loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      sellerInventoryRoute(environment.features.sellerInventory),
      ...businessOrderRoutes(BUSINESS_ORDERS_DEFAULT_ENABLED),
      ...(NOTIFICATION_CENTER_DEFAULT_ENABLED ? [{ path: 'notifications',
        loadComponent: () => import('./features/business/business-notification-center.component')
          .then(m => m.BusinessNotificationCenterComponent) }] : []),
      { path: 'account', loadComponent: () => import('./features/business/business-account.component').then(m => m.BusinessAccountComponent) },
      { path: 'profile', redirectTo: '/seller/account', pathMatch: 'full' },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
    ],
  },
  {
    path: 'admin',
    loadComponent: () => import('./layout/admin-layout/admin-layout.component').then(m => m.AdminLayoutComponent),
    canActivate: [adminGuard],
    children: [
      { path: 'dashboard', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.DASHBOARD_READ }, loadComponent: () => import('./features/admin/admin-dashboard.component').then(m => m.AdminDashboardComponent) },
      { path: 'users', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.USER_READ }, loadComponent: () => import('./features/admin/admin-user-list.component').then(m => m.AdminUserListComponent) },
      { path: 'users/:userId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.USER_READ }, loadComponent: () => import('./features/admin/admin-user-detail.component').then(m => m.AdminUserDetailComponent) },
      { path: 'businesses', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_READ }, loadComponent: () => import('./features/admin/admin-business-list.component').then(m => m.AdminBusinessListComponent) },
      { path: 'businesses/:businessId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_READ }, loadComponent: () => import('./features/admin/admin-business-detail.component').then(m => m.AdminBusinessDetailComponent) },
      { path: 'business-applications', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_APPLICATION_READ }, loadComponent: () => import('./features/business/admin-business-application-queue.component').then(m => m.AdminBusinessApplicationQueueComponent) },
      { path: 'business-applications/:id', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_APPLICATION_READ }, loadComponent: () => import('./features/business/admin-business-application-detail.component').then(m => m.AdminBusinessApplicationDetailComponent) },
      { path: 'listings/moderation', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.LISTING_MODERATION_READ }, loadComponent: () => import('./features/listings/admin-listing-moderation.component').then(m => m.AdminListingModerationComponent) },
      { path: 'listings/moderation/:caseId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.LISTING_MODERATION_READ }, loadComponent: () => import('./features/listings/admin-listing-moderation-detail.component').then(m => m.AdminListingModerationDetailComponent) },
      categoryGuidanceRoute(environment.features.categoryGuidance),
      adminSearchMaintenanceRoute(ADMIN_SEARCH_MAINTENANCE_DEFAULT_ENABLED),
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
    ],
  },
  { path: 'dashboard', redirectTo: '', pathMatch: 'full' },
  { path: 'listings/new', redirectTo: 'account/listings/new', pathMatch: 'full' },
  { path: 'business/apply', redirectTo: 'seller/business/apply', pathMatch: 'full' },
  { path: 'profile', redirectTo: 'account/profile', pathMatch: 'full' },
  {
    path: 'console',
    children: [
      { path: 'dashboard', redirectTo: '/seller/dashboard', pathMatch: 'full' },
      { path: 'listings', redirectTo: '/account/listings', pathMatch: 'full' },
      { path: 'listings/new', redirectTo: '/account/listings/new', pathMatch: 'full' },
      { path: 'seller/activate', redirectTo: '/account/seller-profile', pathMatch: 'full' },
      { path: 'business/apply', redirectTo: '/seller/business/apply', pathMatch: 'full' },
      { path: 'profile', redirectTo: '/seller/account', pathMatch: 'full' },
      { path: 'admin/dashboard', redirectTo: '/admin/dashboard', pathMatch: 'full' },
      { path: 'admin/users', redirectTo: '/admin/users', pathMatch: 'full' },
      { path: 'admin/business-applications', redirectTo: '/admin/business-applications', pathMatch: 'full' },
      { path: 'admin/listings/moderation', redirectTo: '/admin/listings/moderation', pathMatch: 'full' },
      {
        path: 'admin/category-guidance',
        redirectTo: environment.features.categoryGuidance ? '/admin/category-guidance' : '/admin/dashboard',
        pathMatch: 'full',
      },
      { path: '', redirectTo: '/seller/dashboard', pathMatch: 'full' },
    ],
  },
  {
    path: '',
    loadComponent: () => import('./layout/marketplace-layout/marketplace-layout.component').then(m => m.MarketplaceLayoutComponent),
    children: [
      { path: '', loadComponent: () => import('./features/marketplace/marketplace-home.component').then(m => m.MarketplaceHomeComponent) },
      { path: 'marketplace', loadComponent: () => import('./features/marketplace/marketplace-home.component').then(m => m.MarketplaceHomeComponent) },
      { path: 'stores', loadComponent: () => import('./features/stores/business-stores.component').then(m => m.BusinessStoresComponent) },
      { path: 'stores/:storeSlug', loadComponent: () => import('./features/stores/public-store-profile.component').then(m => m.PublicStoreProfileComponent) },
      cartRoute(CART_DEFAULT_ENABLED),
      checkoutReviewRoute(environment.features.buyerCheckout),
      checkoutDetailRoute(environment.features.buyerCheckout),
      { path: 'sell', redirectTo: 'account/listings', pathMatch: 'full' },
      { path: 'account', canActivate: [authGuard], loadComponent: () => import('./features/account/account.component').then(m => m.AccountComponent) },
      { path: 'account/profile', canActivate: [authGuard], loadComponent: () => import('./features/account/profile.component').then(m => m.ProfileComponent) },
      buyerAddressesRoute(environment.features.buyerAddresses),
      ...buyerOrderRoutes(environment.features.buyerCheckout),
      notificationCenterRoute(NOTIFICATION_CENTER_DEFAULT_ENABLED),
      marketplaceAgentV2Route(
        (environment.features as Record<string, boolean>)['marketplaceAgentV2'] === true,
      ),
      { path: 'account/liked', canActivate: [authGuard], loadComponent: () => import('./features/account/liked-listings.component').then(m => m.LikedListingsComponent) },
      { path: 'account/seller-profile', canActivate: [authGuard], loadComponent: () => import('./features/seller/individual-seller-activation.component').then(m => m.IndividualSellerActivationComponent) },
      ...agentMessageRoutes(
        environment.features.aiAssistant,
        environment.features.aiDiscovery,
        (environment.features as Record<string, boolean>)['marketplaceAgentV2'] === true,
      ),
      { path: 'account/messages', canActivate: [authGuard], loadComponent: () => import('./features/account/conversation-shell.component').then(m => m.ConversationShellComponent) },
      { path: 'account/messages/:conversationId', canActivate: [authGuard], loadComponent: () => import('./features/account/conversation-shell.component').then(m => m.ConversationShellComponent) },
      { path: 'account/listings', canActivate: [authGuard], loadComponent: () => import('./features/listings/account-listings-entry.component').then(m => m.AccountListingsEntryComponent) },
      { path: 'account/listings/new', canActivate: [authGuard], loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'account/listings/:listingId/edit', canActivate: [authGuard], loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'listings/:listingId', loadComponent: () => import('./features/marketplace/public-listing-detail.component').then(m => m.PublicListingDetailComponent) },
    ],
  },
  {
    path: '**',
    loadComponent: () => import('./features/not-found/not-found.component').then(m => m.NotFoundComponent),
  },
];
