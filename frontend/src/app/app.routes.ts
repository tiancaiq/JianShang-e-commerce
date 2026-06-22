import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent),
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell.component').then(m => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent) },
      { path: 'products', loadComponent: () => import('./features/products/product-list.component').then(m => m.ProductListComponent) },
      { path: 'products/new', loadComponent: () => import('./features/products/product-create.component').then(m => m.ProductCreateComponent) },
      { path: 'listings/new', loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'orders', loadComponent: () => import('./features/orders/order-list.component').then(m => m.OrderListComponent) },
      { path: 'orders/new', loadComponent: () => import('./features/orders/order-place.component').then(m => m.OrderPlaceComponent) },
      { path: 'payments', loadComponent: () => import('./features/payments/payment-list.component').then(m => m.PaymentListComponent) },
      { path: 'payments/new', loadComponent: () => import('./features/payments/payment-process.component').then(m => m.PaymentProcessComponent) },
      { path: 'inventory', loadComponent: () => import('./features/inventory/inventory-check.component').then(m => m.InventoryCheckComponent) },
      { path: 'profile', loadComponent: () => import('./features/account/profile.component').then(m => m.ProfileComponent) },
      { path: 'seller/activate', loadComponent: () => import('./features/seller/individual-seller-activation.component').then(m => m.IndividualSellerActivationComponent) },
      { path: 'business/apply', loadComponent: () => import('./features/business/business-application.component').then(m => m.BusinessApplicationComponent) },
      { path: 'admin/business-applications', loadComponent: () => import('./features/business/admin-business-application-decision.component').then(m => m.AdminBusinessApplicationDecisionComponent) },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
