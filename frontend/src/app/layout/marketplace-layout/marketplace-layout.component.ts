import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';

@Component({
  selector: 'app-marketplace-layout',
  standalone: true,
  imports: [RouterLink, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="marketplace-shell">
      <header class="marketplace-header">
        <a routerLink="/" class="brand" aria-label="MSBCommerce marketplace home">
          <span class="brand-mark">M</span>
          <span>MSB<span>Commerce</span></span>
        </a>
        <nav class="public-nav" aria-label="Marketplace navigation">
          <a routerLink="/">Browse</a>
          <a routerLink="/account/listings/new">Sell</a>
          @if (authService.isAuthenticated()) {
            <a routerLink="/account/listings">My Listings</a>
            <a routerLink="/account/profile">Account</a>
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
      --market-bg: #f7f4ef;
      --market-surface: #ffffff;
      --market-soft: #fff0f7;
      --market-ink: #382648;
      --market-muted: #827194;
      --market-line: #ead7f2;
      --market-accent: #f472b6;
      --market-accent-dark: #be3a83;
      --market-lavender: #8b6fe8;
      --market-mint: #38a895;
      --market-yellow: #f7b84b;
      --market-blue: #5d98e8;
      background:
        radial-gradient(circle at 12% 6%, rgba(255, 205, 226, 0.42) 0 18%, transparent 19%),
        radial-gradient(circle at 92% 0%, rgba(202, 191, 255, 0.34) 0 14%, transparent 15%),
        linear-gradient(180deg, #fff7fb 0%, #f9f2ff 52%, #fffaf0 100%);
      color: var(--market-ink);
    }

    .marketplace-shell::before {
      content: '';
      position: fixed;
      inset: 0;
      pointer-events: none;
      opacity: 0.42;
      background-image:
        linear-gradient(45deg, rgba(244, 114, 182, 0.08) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(139, 111, 232, 0.07) 25%, transparent 25%);
      background-size: 28px 28px;
      z-index: 0;
    }

    .marketplace-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
      margin: 0.75rem clamp(0.75rem, 2vw, 1.25rem) 0;
      padding: 0.65rem clamp(0.75rem, 2vw, 1rem);
      background: rgba(255, 255, 255, 0.9);
      border-bottom: 1px solid var(--market-line);
      border: 1px solid rgba(234, 215, 242, 0.92);
      border-radius: 8px;
      box-shadow: 0 14px 36px rgba(150, 96, 144, 0.13);
      backdrop-filter: blur(18px);
    }

    .brand {
      color: var(--market-ink);
      font-family: var(--font-display);
      font-weight: 800;
      font-size: 1.15rem;
      text-decoration: none;
      white-space: nowrap;
      display: inline-flex;
      align-items: center;
      gap: 0.55rem;
    }

    .brand > span:last-child span {
      color: var(--market-accent);
    }

    .brand-mark {
      width: 2.1rem;
      height: 2.1rem;
      display: grid;
      place-items: center;
      border-radius: 8px;
      background: linear-gradient(135deg, #ff9fcb, #9476ee);
      color: #fff;
      box-shadow: 0 8px 18px rgba(190, 58, 131, 0.22);
      font-size: 1rem;
    }

    .public-nav {
      display: flex;
      align-items: center;
      gap: 0.45rem;
    }

    .public-nav a,
    .public-nav button {
      color: var(--market-muted);
      background: transparent;
      border: 0;
      font: inherit;
      font-size: 0.875rem;
      font-weight: 700;
      cursor: pointer;
      text-decoration: none;
      min-height: 36px;
      display: inline-flex;
      align-items: center;
      padding: 0 0.75rem;
      border-radius: 8px;
    }

    .public-nav a:hover,
    .public-nav button:hover {
      color: var(--market-accent-dark);
      background: var(--market-soft);
    }

    .marketplace-main {
      position: relative;
      z-index: 1;
      max-width: 1500px;
      margin: 0 auto;
      padding: 1rem clamp(0.75rem, 3vw, 2rem) 4rem;
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
