import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CurrentUser } from '../../../core/models/user.model';
import { environment } from '../../../../environments/environment';
import { AuthService } from '../../../core/services/auth.service';
import { CartService } from '../../../core/services/cart.service';
import { ToastService } from '../../../core/services/toast.service';

@Component({
  selector: 'app-marketplace-navbar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <header class="marketplace-header">
      <a routerLink="/marketplace" class="brand" aria-label="MyCnMiPSS marketplace home">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="m12 3 2.3 4.7 5.2.8-3.8 3.7.9 5.2L12 15l-4.6 2.4.9-5.2-3.8-3.7 5.2-.8L12 3z"/></svg>
        </span>
        <span class="brand-text">MyCnMiPSS</span>
      </a>

      <nav class="public-nav" aria-label="Marketplace navigation">
        <a routerLink="/marketplace" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: false }">
          <svg viewBox="0 0 24 24"><path d="M4 10.5 12 4l8 6.5V20h-5v-5H9v5H4v-9.5z"/></svg>
          Marketplace
        </a>
        <a routerLink="/stores" routerLinkActive="active">
          <svg viewBox="0 0 24 24"><path d="M4 9h16v11H4V9zm2-5h12l2 4H4l2-4z"/></svg>
          Stores
        </a>
        @if (authenticated) {
          @if (cartEnabled) {
            <a routerLink="/cart" routerLinkActive="active" class="cart-link">
              <svg viewBox="0 0 24 24"><path d="M3 4h2l2.2 10.2a2 2 0 0 0 2 1.6h7.9a2 2 0 0 0 2-1.6L20.5 8H7.1l-.4-2H3V4zm6.5 13.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z"/></svg>
              Cart
              @if (cartCount > 0) {
                <span class="cart-count">{{ cartCount > 99 ? '99+' : cartCount }}</span>
              }
            </a>
          }
          <a routerLink="/account/listings" routerLinkActive="active">
            <svg viewBox="0 0 24 24"><path d="M8 7h8l-2.2-2.2L15 3.6 19.4 8 15 12.4l-1.2-1.2L16 9H8V7zm8 10H8l2.2 2.2L9 20.4 4.6 16 9 11.6l1.2 1.2L8 15h8v2z"/></svg>
            Trades
          </a>
          <a routerLink="/account/liked" routerLinkActive="active">
            <svg viewBox="0 0 24 24"><path d="M12 20.4 5.4 14C2 10.8 3.8 5 8.4 5c1.5 0 2.8.7 3.6 1.8C12.8 5.7 14.1 5 15.6 5 20.2 5 22 10.8 18.6 14L12 20.4z"/></svg>
            Favorites
          </a>
          <a routerLink="/account/messages" routerLinkActive="active">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v10H8.8L5 18.5V5zm2 2v7.2l1.1-1H17V7H7z"/></svg>
            Inbox
          </a>
        } @else {
          <button type="button" (click)="loginRequested.emit()">
            <svg viewBox="0 0 24 24"><path d="M8 7h8l-2.2-2.2L15 3.6 19.4 8 15 12.4l-1.2-1.2L16 9H8V7zm8 10H8l2.2 2.2L9 20.4 4.6 16 9 11.6l1.2 1.2L8 15h8v2z"/></svg>
            Trades
          </button>
          <button type="button" (click)="loginRequested.emit()">
            <svg viewBox="0 0 24 24"><path d="M12 20.4 5.4 14C2 10.8 3.8 5 8.4 5c1.5 0 2.8.7 3.6 1.8C12.8 5.7 14.1 5 15.6 5 20.2 5 22 10.8 18.6 14L12 20.4z"/></svg>
            Favorites
          </button>
        }
      </nav>

      <div class="nav-account">
        @if (authenticated) {
          <a routerLink="/account" class="account-chip" aria-label="Open account dashboard">
            <span class="avatar-wrap">
              <span class="avatar-frame">
                @if (avatarUrl()) {
                  <img [src]="avatarUrl() || ''" [alt]="displayName() + ' avatar'" (error)="avatarLoadFailed.set(true)">
                } @else {
                  <span>{{ initials() }}</span>
                }
              </span>
            </span>
            <strong>{{ displayName() }}</strong>
          </a>
          <button
            type="button"
            class="logout-link"
            aria-label="Logout"
            title="Logout"
            (pointerdown)="logout()"
            (click)="logout()">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h8v2H7v12h6v2H5V4zm10.6 4.4L20.2 13l-4.6 4.6-1.4-1.4 2.2-2.2H10v-2h6.4l-2.2-2.2 1.4-1.4z"/></svg>
          </button>
        } @else {
          <button type="button" class="login-link" (click)="loginRequested.emit()">Login</button>
        }
      </div>
    </header>
  `,
  styles: [`
    .marketplace-header {
      position: sticky;
      top: 0;
      z-index: 20;
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr) max-content;
      align-items: center;
      gap: clamp(1rem, 3vw, 2.2rem);
      min-height: 62px;
      padding: 0 28px;
      border-bottom: 1px solid rgba(234, 215, 242, 0.88);
      background: rgba(255, 255, 255, 0.9);
      box-shadow: 0 12px 34px rgba(159, 91, 144, 0.1);
      backdrop-filter: blur(18px);
    }

    .brand {
      display: inline-flex;
      align-items: center;
      gap: 0.65rem;
      color: var(--market-ink);
      font-family: var(--font-display);
      font-size: 1.22rem;
      font-weight: 950;
      letter-spacing: 0;
      text-decoration: none;
      white-space: nowrap;
    }

    .brand-text {
      color: var(--market-accent);
    }

    .brand-mark {
      width: 1.75rem;
      height: 1.75rem;
      display: grid;
      place-items: center;
      color: var(--market-accent);
    }

    .public-nav {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.7rem;
      min-width: 0;
    }

    .public-nav a,
    .public-nav button,
    .nav-account {
      display: flex;
      justify-content: flex-end;
      min-width: 0;
      position: relative;
    }

    .public-nav a,
    .public-nav button,
    .login-link,
    .account-chip {
      min-height: 42px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 0;
      border-radius: 999px;
      color: var(--market-muted);
      background: transparent;
      cursor: pointer;
      font: inherit;
      font-size: 0.875rem;
      font-weight: 900;
      text-decoration: none;
      padding: 0 0.95rem;
      white-space: nowrap;
      transition: transform 220ms ease, box-shadow 220ms ease, color 220ms ease, background 220ms ease;
    }

    .public-nav svg {
      width: 1.05rem;
      height: 1.05rem;
      margin-right: 0.45rem;
      fill: currentColor;
    }

    .public-nav .active {
      background: #fff0f7;
      color: var(--market-accent);
      box-shadow: 0 10px 22px rgba(244, 114, 182, 0.12);
    }

    .cart-link {
      gap: 0.2rem;
    }

    .cart-count {
      min-width: 1.25rem;
      height: 1.25rem;
      display: inline-grid;
      place-items: center;
      margin-left: 0.2rem;
      padding: 0 0.25rem;
      border-radius: 999px;
      background: var(--market-accent);
      color: #fff;
      font-size: 0.7rem;
      line-height: 1;
    }

    .login-link {
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      box-shadow: 0 10px 22px rgba(190, 58, 131, 0.18);
    }

    .public-nav a:hover,
    .public-nav button:hover,
    .login-link:hover,
    .account-chip:hover {
      transform: translateY(-1px);
      box-shadow: 0 12px 26px rgba(159, 91, 144, 0.16);
    }

    .public-nav a:not(.active):hover,
    .public-nav button:hover {
      color: var(--market-accent-dark);
      background: linear-gradient(135deg, #ffe6f1, #f2ecff);
      box-shadow: none;
    }

    .account-chip {
      gap: 0.5rem;
      color: var(--market-ink);
      min-height: 48px;
      padding: 4px 0.75rem 4px 5px;
      border: 1px solid rgba(234, 215, 242, 0.82);
      background: rgba(255, 255, 255, 0.78);
      box-shadow: 0 10px 22px rgba(159, 91, 144, 0.08);
    }

    .avatar-wrap {
      width: 40px;
      height: 40px;
      display: grid;
      place-items: center;
      border-radius: 50%;
      padding: 2px;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      font-size: 0.8rem;
      font-weight: 950;
      box-sizing: border-box;
      flex: 0 0 40px;
    }

    .avatar-frame {
      width: 100%;
      height: 100%;
      display: block;
      box-sizing: border-box;
      border: 2px solid #fff;
      border-radius: 50%;
      overflow: hidden;
      background: #fff7fb;
    }

    .avatar-frame img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
      object-position: center;
    }

    .avatar-frame span {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
    }

    .account-chip strong {
      max-width: 130px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      line-height: 1;
    }

    .logout-link {
      width: 42px;
      height: 42px;
      display: inline-grid;
      place-items: center;
      margin-left: 0.5rem;
      border: 1px solid rgba(234, 215, 242, 0.88);
      background: #fff7fb;
      color: var(--market-accent-dark);
      border-radius: 50%;
      cursor: pointer;
      transition: transform 160ms ease, background 160ms ease;
    }

    .logout-link:hover,
    .logout-link:focus-visible {
      background: linear-gradient(135deg, #ffe6f1, #f2ecff);
      transform: translateY(-1px);
      outline: none;
    }

    .logout-link svg {
      width: 1.1rem;
      height: 1.1rem;
      fill: currentColor;
    }

    @media (max-width: 980px) {
      .marketplace-header {
        grid-template-columns: 1fr;
        gap: 0.75rem;
        padding-block: 0.75rem;
      }

      .public-nav {
        justify-content: flex-start;
        flex-wrap: wrap;
      }

      .nav-account {
        justify-content: flex-start;
      }

    }

    @media (max-width: 640px) {
      .marketplace-header {
        padding-inline: 16px;
      }

      .public-nav a,
      .public-nav button {
        flex: 1 1 auto;
      }
    }
  `],
})
export class MarketplaceNavbarComponent {
  private authService = inject(AuthService);
  private cartService = inject(CartService);
  private toastService = inject(ToastService);
  readonly cartEnabled = environment.features.cart;
  @Input() authenticated = false;
  @Input() cartCount = 0;
  @Input() currentUser: CurrentUser | null = null;
  @Output() loginRequested = new EventEmitter<void>();
  @Output() searchRequested = new EventEmitter<string>();
  avatarLoadFailed = signal(false);
  private logoutStarting = false;

  displayName(): string {
    return this.currentUser?.displayName?.trim() || this.currentUser?.email?.split('@')[0] || 'Account';
  }

  initials(): string {
    return this.displayName()
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map(part => part.charAt(0).toUpperCase())
      .join('') || 'A';
  }

  avatarUrl(): string | null {
    if (this.avatarLoadFailed()) {
      return null;
    }
    const value = this.currentUser?.avatarUrl?.trim();
    if (!value) {
      return null;
    }
    return value.startsWith('/api/') ? `${environment.apiGatewayUrl}${value}` : value;
  }

  logout(): void {
    if (this.logoutStarting) {
      return;
    }
    this.logoutStarting = true;
    if (this.authService.logout('marketplace')) {
      if (this.cartEnabled) {
        this.cartService.reset();
      }
      return;
    }
    this.logoutStarting = false;
    this.toastService.error('Logout could not start. Try again.');
  }

  submitSearch(): void {
    this.searchRequested.emit('');
  }
}
