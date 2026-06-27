import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent),
  },
  {
    path: 'seller',
    loadComponent: () => import('./layout/seller-layout/seller-layout.component').then(m => m.SellerLayoutComponent),
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', loadComponent: () => import('./features/seller/seller-dashboard.component').then(m => m.SellerDashboardComponent) },
      { path: 'listings', loadComponent: () => import('./features/listings/listing-management.component').then(m => m.ListingManagementComponent) },
      { path: 'listings/new', loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'listings/:listingId/edit', loadComponent: () => import('./features/listings/listing-draft-form.component').then(m => m.ListingDraftFormComponent) },
      { path: 'activate', loadComponent: () => import('./features/seller/individual-seller-activation.component').then(m => m.IndividualSellerActivationComponent) },
      { path: 'business/apply', loadComponent: () => import('./features/business/business-application.component').then(m => m.BusinessApplicationComponent) },
      { path: 'profile', loadComponent: () => import('./features/account/profile.component').then(m => m.ProfileComponent) },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
    ],
  },
  {
    path: 'admin',
    loadComponent: () => import('./layout/admin-layout/admin-layout.component').then(m => m.AdminLayoutComponent),
    canActivate: [authGuard],
    children: [
      { path: 'business-applications', loadComponent: () => import('./features/business/admin-business-application-decision.component').then(m => m.AdminBusinessApplicationDecisionComponent) },
      { path: 'listings/moderation', loadComponent: () => import('./features/listings/admin-listing-moderation.component').then(m => m.AdminListingModerationComponent) },
      { path: '', redirectTo: 'business-applications', pathMatch: 'full' },
    ],
  },
  { path: 'dashboard', redirectTo: 'seller/dashboard', pathMatch: 'full' },
  { path: 'listings/new', redirectTo: 'seller/listings/new', pathMatch: 'full' },
  { path: 'business/apply', redirectTo: 'seller/business/apply', pathMatch: 'full' },
  { path: 'profile', redirectTo: 'seller/profile', pathMatch: 'full' },
  {
    path: 'console',
    children: [
      { path: 'dashboard', redirectTo: '/seller/dashboard', pathMatch: 'full' },
      { path: 'listings', redirectTo: '/seller/listings', pathMatch: 'full' },
      { path: 'listings/new', redirectTo: '/seller/listings/new', pathMatch: 'full' },
      { path: 'seller/activate', redirectTo: '/seller/activate', pathMatch: 'full' },
      { path: 'business/apply', redirectTo: '/seller/business/apply', pathMatch: 'full' },
      { path: 'profile', redirectTo: '/seller/profile', pathMatch: 'full' },
      { path: 'admin/business-applications', redirectTo: '/admin/business-applications', pathMatch: 'full' },
      { path: 'admin/listings/moderation', redirectTo: '/admin/listings/moderation', pathMatch: 'full' },
      { path: '', redirectTo: '/seller/dashboard', pathMatch: 'full' },
    ],
  },
  {
    path: '',
    loadComponent: () => import('./layout/marketplace-layout/marketplace-layout.component').then(m => m.MarketplaceLayoutComponent),
    children: [
      { path: '', loadComponent: () => import('./features/marketplace/marketplace-home.component').then(m => m.MarketplaceHomeComponent) },
    ],
  },
  { path: '**', redirectTo: '' },
];
