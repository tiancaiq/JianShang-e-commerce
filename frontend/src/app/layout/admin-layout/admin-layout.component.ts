import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AdminService } from '../../core/services/admin.service';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { environment } from '../../../environments/environment';
import {
  ADMIN_SEARCH_MAINTENANCE_ENABLED,
} from '../../features/admin/admin-search-maintenance.capability';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="admin-shell">
      <aside class="admin-sidebar" [class.menu-open]="mobileMenuOpen()">
        <div class="sidebar-heading">
          <a routerLink="/admin" class="brand" (click)="closeMobileMenu()">MSB<span>Admin</span></a>
          <button
            type="button"
            class="menu-toggle"
            aria-controls="admin-navigation"
            [attr.aria-expanded]="mobileMenuOpen()"
            (click)="mobileMenuOpen.set(!mobileMenuOpen())"
          >Menu</button>
        </div>
        <nav id="admin-navigation" aria-label="Admin navigation">
          @if (adminService.hasPermission(permissions.DASHBOARD_READ)) {
            <a routerLink="/admin/dashboard" routerLinkActive="active" (click)="closeMobileMenu()">Dashboard</a>
          }
          @if (adminService.hasPermission(permissions.USER_READ)) {
            <a routerLink="/admin/users" routerLinkActive="active" (click)="closeMobileMenu()">Users</a>
          }
          @if (adminService.hasPermission(permissions.BUSINESS_READ)) {
            <a routerLink="/admin/businesses" routerLinkActive="active" (click)="closeMobileMenu()">Businesses</a>
          }
          @if (adminService.hasPermission(permissions.BUSINESS_APPLICATION_READ)) {
            <a routerLink="/admin/business-applications" routerLinkActive="active" (click)="closeMobileMenu()">Business Review</a>
          }
          @if (adminService.hasPermission(permissions.LISTING_MODERATION_READ)) {
            <a routerLink="/admin/listings/moderation" routerLinkActive="active" (click)="closeMobileMenu()">Listing Review</a>
          }
          @if (categoryGuidanceEnabled && adminService.hasRole('SUPER_ADMIN')) {
            <a routerLink="/admin/category-guidance" routerLinkActive="active" (click)="closeMobileMenu()">Category Guidance</a>
          }
          @if (adminSearchMaintenanceEnabled && adminService.hasRole('SUPER_ADMIN')) {
            <a routerLink="/admin/search-maintenance" routerLinkActive="active" (click)="closeMobileMenu()">Search Maintenance</a>
          }
        </nav>
        <a routerLink="/" class="back-link" (click)="closeMobileMenu()">Marketplace</a>
      </aside>

      <section class="admin-main">
        <header class="admin-header">
          <h1>Admin</h1>
          <button type="button" (click)="logout()">Logout</button>
        </header>
        <main class="admin-content">
          <div class="admin-content-inner">
            <router-outlet />
          </div>
        </main>
      </section>
      <app-toast-container />
    </div>
  `,
  styles: [`
    .admin-shell {
      min-height: 100vh;
      display: flex;
      background: var(--color-bg-primary);
    }

    .admin-sidebar {
      position: fixed;
      inset: 0 auto 0 0;
      width: 232px;
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1rem 0.75rem;
      background: var(--color-bg-secondary);
      border-right: 1px solid var(--color-border);
      z-index: 30;
    }

    .brand {
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-size: 1rem;
      font-weight: 800;
      padding: 0.25rem 0.625rem 0.75rem;
      text-decoration: none;
    }

    .brand span {
      color: var(--color-info);
    }

    .sidebar-heading {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
    }

    .menu-toggle {
      display: none;
    }

    nav {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    nav a,
    .back-link {
      min-height: 40px;
      display: flex;
      align-items: center;
      padding: 0.5rem 0.75rem;
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-size: 0.875rem;
      font-weight: 650;
      text-decoration: none;
    }

    nav a:hover,
    nav a.active,
    .back-link:hover {
      background: rgba(56, 189, 248, 0.1);
      color: var(--color-info);
    }

    .back-link {
      margin-top: auto;
    }

    .admin-main {
      flex: 1;
      margin-left: 232px;
      min-width: 0;
      display: flex;
      flex-direction: column;
    }

    .admin-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.875rem 1.5rem;
      background: rgba(15, 16, 20, 0.96);
      border-bottom: 1px solid var(--color-border);
    }

    .admin-header h1 {
      font-size: 1.25rem;
      letter-spacing: 0;
    }

    .admin-header button {
      border: 0;
      background: transparent;
      color: var(--color-text-muted);
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }

    .admin-header button:hover {
      color: var(--color-danger);
    }

    .admin-content {
      position: relative;
      flex: 1;
      background: var(--color-bg-primary);
    }

    .admin-content-inner {
      position: relative;
      z-index: 1;
      padding: 1.5rem;
      max-width: 1180px;
      margin: 0 auto;
    }

    @media (max-width: 760px) {
      .admin-shell {
        display: block;
      }

      .admin-sidebar {
        position: static;
        width: auto;
        gap: 0.35rem;
        padding: 0.65rem 0.75rem;
        border-right: 0;
        border-bottom: 1px solid var(--color-border);
      }

      .brand {
        padding: 0.4rem 0.25rem;
      }

      .menu-toggle {
        display: inline-flex;
        align-items: center;
        min-height: 40px;
        border: 1px solid var(--color-border);
        border-radius: var(--radius-md);
        background: var(--color-bg-primary);
        color: var(--color-text-primary);
        padding: 0.5rem 0.8rem;
        font: inherit;
        font-weight: 800;
        cursor: pointer;
      }

      .admin-sidebar nav,
      .admin-sidebar .back-link {
        display: none;
      }

      .admin-sidebar.menu-open nav,
      .admin-sidebar.menu-open .back-link {
        display: flex;
      }

      .admin-sidebar .back-link {
        margin-top: 0.25rem;
      }

      .admin-main {
        margin-left: 0;
      }

      .admin-content-inner {
        padding: 1rem;
      }
    }
  `],
})
export class AdminLayoutComponent {
  authService = inject(AuthService);
  readonly adminService = inject(AdminService);
  readonly permissions = ADMIN_PERMISSIONS;
  readonly mobileMenuOpen = signal(false);
  readonly categoryGuidanceEnabled = environment.features.categoryGuidance;
  readonly adminSearchMaintenanceEnabled = inject(ADMIN_SEARCH_MAINTENANCE_ENABLED);

  closeMobileMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  logout(): void {
    this.adminService.clearCurrentAdmin();
    this.authService.logout('admin-portal');
  }
}
