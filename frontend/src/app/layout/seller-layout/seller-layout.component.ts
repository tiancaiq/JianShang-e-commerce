import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { environment } from '../../../environments/environment';
import { BUSINESS_ORDERS_ENABLED } from '../../features/business/business-orders.capability';

@Component({
  selector: 'app-seller-layout',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="portal-shell">
      <aside class="portal-sidebar">
        <a routerLink="/seller" class="brand">MSB<span>Seller</span></a>
        <nav class="portal-nav" aria-label="Seller navigation">
          <a routerLink="/seller/dashboard" routerLinkActive="active">Dashboard</a>
          <a routerLink="/seller/business/apply" routerLinkActive="active">Business Apply</a>
          <a routerLink="/seller/store/items" routerLinkActive="active">Store Items</a>
          @if (sellerInventoryEnabled) {
            <a routerLink="/seller/inventory" routerLinkActive="active">Inventory</a>
          }
          @if (businessOrdersEnabled) {
            <a routerLink="/seller/orders" routerLinkActive="active">Orders</a>
          }
          <a routerLink="/seller/account" routerLinkActive="active">Business Account</a>
        </nav>
        <a routerLink="/" class="back-link">Marketplace</a>
      </aside>

      <section class="portal-main">
        <header class="portal-header">
          <h1>{{ pageTitle() }}</h1>
          <div class="user-menu">
            <span>{{ authService.user()?.displayName || authService.user()?.email }}</span>
            <button type="button" (click)="authService.logout('seller-portal')">Logout</button>
          </div>
        </header>
        <main class="portal-content">
          <div class="portal-content-inner">
            <router-outlet />
          </div>
        </main>
      </section>
      <app-toast-container />
    </div>
  `,
  styles: [`
    .portal-shell {
      min-height: 100vh;
      display: flex;
      background: var(--color-bg-primary);
    }

    .portal-sidebar {
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
      color: var(--color-accent);
    }

    .portal-nav {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .portal-nav a,
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

    .portal-nav a:hover,
    .portal-nav a.active,
    .back-link:hover {
      background: var(--color-accent-muted);
      color: var(--color-accent);
    }

    .back-link {
      margin-top: auto;
    }

    .portal-main {
      flex: 1;
      margin-left: 232px;
      min-width: 0;
      display: flex;
      flex-direction: column;
    }

    .portal-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.875rem 1.5rem;
      background: rgba(15, 16, 20, 0.96);
      border-bottom: 1px solid var(--color-border);
    }

    .portal-header h1 {
      font-size: 1.25rem;
      letter-spacing: 0;
    }

    .user-menu {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
    }

    .user-menu button {
      border: 0;
      background: transparent;
      color: var(--color-text-muted);
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }

    .user-menu button:hover {
      color: var(--color-danger);
    }

    .portal-content {
      position: relative;
      flex: 1;
      background: var(--color-bg-primary);
    }

    .portal-content-inner {
      position: relative;
      z-index: 1;
      padding: 1.5rem;
      max-width: 1180px;
      margin: 0 auto;
    }

    @media (max-width: 760px) {
      .portal-shell {
        display: block;
      }

      .portal-sidebar {
        position: static;
        width: auto;
      }

      .portal-main {
        margin-left: 0;
      }

      .portal-header {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  `],
})
export class SellerLayoutComponent {
  authService = inject(AuthService);
  private router = inject(Router);
  readonly sellerInventoryEnabled = environment.features.sellerInventory;
  readonly businessOrdersEnabled = inject(BUSINESS_ORDERS_ENABLED);

  pageTitle(): string {
    const path = this.router.url.split(/[?#]/, 1)[0];
    if (/^\/seller\/orders\/[^/]+$/.test(path)) {
      return 'Order Detail';
    }
    const segment = path.split('/').filter(Boolean).at(-1) || 'dashboard';
    return segment.replace(/-/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }
}
