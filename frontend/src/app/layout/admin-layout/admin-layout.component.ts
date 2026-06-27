import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="admin-shell">
      <aside class="admin-sidebar">
        <a routerLink="/admin" class="brand">MSB<span>Admin</span></a>
        <nav aria-label="Admin navigation">
          <a routerLink="/admin/business-applications" routerLinkActive="active">Business Review</a>
          <a routerLink="/admin/listings/moderation" routerLinkActive="active">Listing Review</a>
        </nav>
        <a routerLink="/" class="back-link">Marketplace</a>
      </aside>

      <section class="admin-main">
        <header class="admin-header">
          <h1>Admin</h1>
          <button type="button" (click)="authService.logout()">Logout</button>
        </header>
        <main class="admin-content bg-noise">
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
    }

    .admin-sidebar {
      position: fixed;
      inset: 0 auto 0 0;
      width: 236px;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      padding: 1.25rem 0.875rem;
      background: #101114;
      border-right: 1px solid var(--color-border);
      z-index: 30;
    }

    .brand {
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-size: 1.125rem;
      font-weight: 800;
      padding: 0 0.625rem;
      text-decoration: none;
    }

    .brand span {
      color: var(--color-info);
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
      padding: 0.625rem 0.75rem;
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-size: 0.875rem;
      font-weight: 700;
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
      margin-left: 236px;
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
      padding: 1rem 1.5rem;
      background: rgba(12, 12, 14, 0.9);
      backdrop-filter: blur(12px);
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
    }

    .admin-content-inner {
      position: relative;
      z-index: 1;
      padding: 1.5rem;
      max-width: 1260px;
    }

    @media (max-width: 760px) {
      .admin-shell {
        display: block;
      }

      .admin-sidebar {
        position: static;
        width: auto;
      }

      .admin-main {
        margin-left: 0;
      }
    }
  `],
})
export class AdminLayoutComponent {
  authService = inject(AuthService);
}
