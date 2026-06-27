import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';

@Component({
  selector: 'app-marketplace-layout',
  standalone: true,
  imports: [RouterLink, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="marketplace-shell bg-noise">
      <header class="marketplace-header">
        <a routerLink="/" class="brand">MSB<span>Commerce</span></a>
        <nav class="public-nav" aria-label="Marketplace navigation">
          <a routerLink="/">Browse</a>
          <a routerLink="/seller">Sell</a>
          @if (authService.isAuthenticated()) {
            <a routerLink="/seller/profile">Account</a>
            <button type="button" (click)="authService.logout()">Logout</button>
          } @else {
            <button type="button" (click)="authService.login()">Login</button>
          }
        </nav>
      </header>

      <main class="marketplace-main">
        <router-outlet />
      </main>
      <app-toast-container />
    </div>
  `,
  styles: [`
    .marketplace-shell {
      min-height: 100vh;
      position: relative;
    }

    .marketplace-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
      padding: 1rem 1.5rem;
      background: rgba(12, 12, 14, 0.9);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--color-border);
    }

    .brand {
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-weight: 800;
      font-size: 1.125rem;
      text-decoration: none;
    }

    .brand span {
      color: var(--color-accent);
    }

    .public-nav {
      display: flex;
      align-items: center;
      gap: 1rem;
    }

    .public-nav a,
    .public-nav button {
      color: var(--color-text-secondary);
      background: transparent;
      border: 0;
      font: inherit;
      font-size: 0.875rem;
      font-weight: 700;
      cursor: pointer;
      text-decoration: none;
    }

    .public-nav a:hover,
    .public-nav button:hover {
      color: var(--color-accent);
    }

    .marketplace-main {
      position: relative;
      z-index: 1;
      max-width: 1180px;
      margin: 0 auto;
      padding: 2rem 1.5rem 4rem;
    }

    @media (max-width: 640px) {
      .marketplace-header {
        align-items: flex-start;
        flex-direction: column;
      }

      .public-nav {
        flex-wrap: wrap;
      }
    }
  `],
})
export class MarketplaceLayoutComponent implements OnInit {
  authService = inject(AuthService);

  ngOnInit(): void {
    this.authService.ensureSession().subscribe();
  }
}
