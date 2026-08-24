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
      { path: 'businesses/:businessId/disputes/:disputeId', loadComponent: () => import('./features/orders/order-dispute.component').then(m => m.OrderDisputeComponent) },
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
      { path: 'analytics', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.ANALYTICS_READ }, loadComponent: () => import('./features/admin/admin-analytics.component').then(m => m.AdminAnalyticsComponent) },
      { path: 'users', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.USER_READ }, loadComponent: () => import('./features/admin/admin-user-list.component').then(m => m.AdminUserListComponent) },
      { path: 'users/:userId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.USER_READ }, loadComponent: () => import('./features/admin/admin-user-detail.component').then(m => m.AdminUserDetailComponent) },
      { path: 'businesses', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_READ }, loadComponent: () => import('./features/admin/admin-business-list.component').then(m => m.AdminBusinessListComponent) },
      { path: 'businesses/:businessId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_READ }, loadComponent: () => import('./features/admin/admin-business-detail.component').then(m => m.AdminBusinessDetailComponent) },
      { path: 'orders', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.ORDER_READ }, loadComponent: () => import('./features/admin/admin-order-list.component').then(m => m.AdminOrderListComponent) },
      { path: 'orders/:orderId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.ORDER_READ }, loadComponent: () => import('./features/admin/admin-order-detail.component').then(m => m.AdminOrderDetailComponent) },
      { path: 'payments', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.FINANCE_READ }, loadComponent: () => import('./features/admin/admin-payment-list.component').then(m => m.AdminPaymentListComponent) },
      { path: 'payments/:paymentId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.FINANCE_READ }, loadComponent: () => import('./features/admin/admin-payment-detail.component').then(m => m.AdminPaymentDetailComponent) },
      { path: 'refunds', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REFUND_READ }, loadComponent: () => import('./features/admin/admin-refund-list.component').then(m => m.AdminRefundListComponent) },
      { path: 'refunds/:refundId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REFUND_READ }, loadComponent: () => import('./features/admin/admin-refund-detail.component').then(m => m.AdminRefundDetailComponent) },
      { path: 'disputes', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.DISPUTE_READ }, loadComponent: () => import('./features/admin/admin-dispute-list.component').then(m => m.AdminDisputeListComponent) },
      { path: 'disputes/:disputeId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.DISPUTE_READ }, loadComponent: () => import('./features/admin/admin-dispute-detail.component').then(m => m.AdminDisputeDetailComponent) },
      { path: 'support', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SUPPORT_READ }, loadComponent: () => import('./features/admin/admin-support-list.component').then(m => m.AdminSupportListComponent) },
      { path: 'support/:ticketId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SUPPORT_READ }, loadComponent: () => import('./features/admin/admin-support-detail.component').then(m => m.AdminSupportDetailComponent) },
      { path: 'catalog', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.CATALOG_READ }, loadComponent: () => import('./features/admin/admin-catalog.component').then(m => m.AdminCatalogComponent) },
      { path: 'catalog/categories', redirectTo: 'catalog', pathMatch: 'full' },
      { path: 'catalog/categories/:categoryId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.CATALOG_READ }, loadComponent: () => import('./features/admin/admin-catalog-detail.component').then(m => m.AdminCatalogDetailComponent) },
      { path: 'system', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'summary' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/health', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'health' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/jobs', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'jobs' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/jobs/:jobId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'jobs' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/outbox', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'outbox' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/outbox/:eventId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'outbox' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/reconciliation', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'reconciliation' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/inventory', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'inventory' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/search', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.SYSTEM_READ, systemSection: 'search' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'system/features', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.FEATURE_READ, systemSection: 'features' }, loadComponent: () => import('./features/admin/admin-system.component').then(m => m.AdminSystemComponent) },
      { path: 'governance', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_READ, governanceSection: 'dashboard' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'governance/admins', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_READ, governanceSection: 'admins' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'governance/admins/:adminId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_READ, governanceSection: 'admin' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'governance/roles', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_ROLES_READ, governanceSection: 'roles' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'governance/approvals', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_READ, governanceSection: 'approvals' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'governance/approvals/:approvalId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_READ, governanceSection: 'approval' }, loadComponent: () => import('./features/admin/admin-governance.component').then(m => m.AdminGovernanceComponent) },
      { path: 'reports', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REPORT_READ }, loadComponent: () => import('./features/admin/admin-report-inbox.component').then(m => m.AdminReportInboxComponent) },
      { path: 'reports/:reportId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REPORT_READ }, loadComponent: () => import('./features/admin/admin-report-detail.component').then(m => m.AdminReportDetailComponent) },
      { path: 'cases', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REPORT_READ }, loadComponent: () => import('./features/admin/admin-investigation-case-inbox.component').then(m => m.AdminInvestigationCaseInboxComponent) },
      { path: 'cases/:caseId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.REPORT_READ }, loadComponent: () => import('./features/admin/admin-investigation-case-detail.component').then(m => m.AdminInvestigationCaseDetailComponent) },
      { path: 'appeals', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.APPEAL_READ }, loadComponent: () => import('./features/admin/admin-appeal-inbox.component').then(m => m.AdminAppealInboxComponent) },
      { path: 'appeals/:appealId', canActivate: [adminPermissionGuard], data: { adminPermission: ADMIN_PERMISSIONS.APPEAL_READ }, loadComponent: () => import('./features/admin/admin-appeal-detail.component').then(m => m.AdminAppealDetailComponent) },
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
      { path: 'admin/reports', redirectTo: '/admin/reports', pathMatch: 'full' },
      { path: 'admin/cases', redirectTo: '/admin/cases', pathMatch: 'full' },
      { path: 'admin/appeals', redirectTo: '/admin/appeals', pathMatch: 'full' },
      { path: 'admin/orders', redirectTo: '/admin/orders', pathMatch: 'full' },
      { path: 'admin/disputes', redirectTo: '/admin/disputes', pathMatch: 'full' },
      { path: 'admin/system', redirectTo: '/admin/system', pathMatch: 'full' },
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
      { path: 'account/appeals', canActivate: [authGuard], loadComponent: () => import('./features/account/account-appeals.component').then(m => m.AccountAppealsComponent) },
      { path: 'support', canActivate: [authGuard], loadComponent: () => import('./features/support/support-ticket-list.component').then(m => m.SupportTicketListComponent) },
      { path: 'support/new', canActivate: [authGuard], loadComponent: () => import('./features/support/support-ticket-new.component').then(m => m.SupportTicketNewComponent) },
      { path: 'support/:ticketId', canActivate: [authGuard], loadComponent: () => import('./features/support/support-ticket-detail.component').then(m => m.SupportTicketDetailComponent) },
      buyerAddressesRoute(environment.features.buyerAddresses),
      ...buyerOrderRoutes(environment.features.buyerCheckout),
      { path: 'account/orders/:orderId/disputes/new', canActivate: [authGuard], loadComponent: () => import('./features/orders/order-dispute.component').then(m => m.OrderDisputeComponent) },
      { path: 'account/disputes/:disputeId', canActivate: [authGuard], loadComponent: () => import('./features/orders/order-dispute.component').then(m => m.OrderDisputeComponent) },
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
