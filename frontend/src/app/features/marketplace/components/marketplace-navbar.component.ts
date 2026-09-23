import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CurrentUser } from '../../../core/models/user.model';
import { environment } from '../../../../environments/environment';
import { AuthService } from '../../../core/services/auth.service';
import { CartService } from '../../../core/services/cart.service';
import { ToastService } from '../../../core/services/toast.service';

@Component({
  selector: 'app-marketplace-navbar',
  standalone: true,
  imports: [FormsModule, RouterLink, RouterLinkActive],
  template: `
    <header class="marketplace-header">
      <a routerLink="/marketplace" class="brand" aria-label="MSB Market marketplace home" (click)="closeMenu()">
        <span class="brand-mark" aria-hidden="true">
          <span class="brand-moon"></span><span class="bag-star">✦</span>
        </span>
        <span class="brand-text">MSB <strong>Market</strong></span>
      </a>

      <form class="header-search" role="search" (submit)="submitSearch(); $event.preventDefault()">
        <label for="marketplace-header-search" class="visually-hidden">Search marketplace</label>
        <input
          id="marketplace-header-search"
          name="marketplaceHeaderSearch"
          type="search"
          [(ngModel)]="searchTerm"
          placeholder="Search listings and stores"
        />
        <button type="submit" aria-label="Search marketplace">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m20.7 19.3-4.1-4.1a7 7 0 1 0-1.4 1.4l4.1 4.1 1.4-1.4zM5 11a6 6 0 1 1 12 0 6 6 0 0 1-12 0z"/></svg>
        </button>
      </form>

      <nav id="marketplace-mobile-navigation" class="public-nav" [class.open]="mobileMenuOpen()" aria-label="Marketplace navigation">
        <a routerLink="/marketplace" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: false }" (click)="closeMenu()">
          <svg viewBox="0 0 24 24"><path d="M4 10.5 12 4l8 6.5V20h-5v-5H9v5H4v-9.5z"/></svg>
          Marketplace
        </a>
        <a routerLink="/stores" routerLinkActive="active" (click)="closeMenu()">
          <svg viewBox="0 0 24 24"><path d="M4 9h16v11H4V9zm2-5h12l2 4H4l2-4z"/></svg>
          Stores
        </a>
        @if (authenticated) {
          @if (cartEnabled) {
            <a
              routerLink="/cart"
              routerLinkActive="active"
              class="cart-link"
              (click)="closeMenu()"
              [attr.aria-label]="cartAriaLabel()">
              <svg viewBox="0 0 24 24"><path d="M3 4h2l2.2 10.2a2 2 0 0 0 2 1.6h7.9a2 2 0 0 0 2-1.6L20.5 8H7.1l-.4-2H3V4zm6.5 13.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z"/></svg>
              Cart
              @if (cartCount > 0) {
                <span class="cart-count" aria-live="polite">{{ cartCountLabel() }}</span>
              }
            </a>
          }
          @if (notificationsEnabled) {
            <a routerLink="/account/notifications" routerLinkActive="active" (click)="closeMenu()"
               class="notification-link" [attr.aria-label]="notificationAriaLabel()">
              <span aria-hidden="true">&#128276;</span>
              Notifications
              @if (notificationCount > 0) {
                <span class="cart-count" aria-live="polite">{{ notificationCountLabel() }}</span>
              }
            </a>
          }
          <a routerLink="/account/listings" routerLinkActive="active" (click)="closeMenu()">
            <svg viewBox="0 0 24 24"><path d="M8 7h8l-2.2-2.2L15 3.6 19.4 8 15 12.4l-1.2-1.2L16 9H8V7zm8 10H8l2.2 2.2L9 20.4 4.6 16 9 11.6l1.2 1.2L8 15h8v2z"/></svg>
            Trades
          </a>
          <a routerLink="/account/liked" routerLinkActive="active" (click)="closeMenu()">
            <svg viewBox="0 0 24 24"><path d="M12 20.4 5.4 14C2 10.8 3.8 5 8.4 5c1.5 0 2.8.7 3.6 1.8C12.8 5.7 14.1 5 15.6 5 20.2 5 22 10.8 18.6 14L12 20.4z"/></svg>
            Favorites
          </a>
          <a routerLink="/account/messages" routerLinkActive="active" (click)="closeMenu()">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v10H8.8L5 18.5V5zm2 2v7.2l1.1-1H17V7H7z"/></svg>
            Inbox
          </a>
        } @else {
          <button type="button" (click)="requestLogin()">
            <svg viewBox="0 0 24 24"><path d="M8 7h8l-2.2-2.2L15 3.6 19.4 8 15 12.4l-1.2-1.2L16 9H8V7zm8 10H8l2.2 2.2L9 20.4 4.6 16 9 11.6l1.2 1.2L8 15h8v2z"/></svg>
            Trades
          </button>
          <button type="button" (click)="requestLogin()">
            <svg viewBox="0 0 24 24"><path d="M12 20.4 5.4 14C2 10.8 3.8 5 8.4 5c1.5 0 2.8.7 3.6 1.8C12.8 5.7 14.1 5 15.6 5 20.2 5 22 10.8 18.6 14L12 20.4z"/></svg>
            Favorites
          </button>
        }
      </nav>

      <div class="nav-account">
        @if (authenticated) {
          <a routerLink="/account" class="account-chip" aria-label="Open account dashboard" (click)="closeMenu()">
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
          <button type="button" class="login-link" (click)="requestLogin()">Login</button>
        }
      </div>

      <button
        type="button"
        class="mobile-menu-toggle"
        [attr.aria-expanded]="mobileMenuOpen()"
        aria-controls="marketplace-mobile-navigation"
        [attr.aria-label]="mobileMenuOpen() ? 'Close marketplace menu' : 'Open marketplace menu'"
        (click)="toggleMenu()"
      >
        <span aria-hidden="true"></span><span aria-hidden="true"></span><span aria-hidden="true"></span>
      </button>
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

    /* Modern shell override: inventory and search lead; mobile navigation stays compact. */
    .marketplace-header {
      grid-template-columns: max-content minmax(240px, 1fr) max-content max-content;
      gap: clamp(.65rem, 1.4vw, 1.25rem);
      min-height: 76px;
      padding: 0 var(--market-page-pad);
      border-color: rgba(233, 79, 138, .18);
      background: rgba(255, 255, 255, .92);
      box-shadow: 0 10px 34px rgba(208, 75, 126, .08);
      backdrop-filter: blur(22px) saturate(1.08);
    }

    .brand {
      gap: 0.55rem;
      color: var(--market-ink);
      font-family: var(--font-market-display);
      font-size: 1.32rem;
      font-weight: 600;
      letter-spacing: -.025em;
    }

    .brand-text {
      color: var(--market-ink);
    }

    .brand-text strong {
      color: var(--market-accent-dark);
      font-weight: 600;
    }

    .brand-mark {
      position: relative;
      width: 2.15rem;
      height: 2.15rem;
      overflow: visible;
      border: 1px solid rgba(185,137,130,.42);
      border-radius: 50% 50% 50% 18%;
      background: linear-gradient(145deg, #ffffff, #ffe2ee);
      color: var(--market-accent-dark);
      box-shadow: 0 8px 18px rgba(208,75,126,.14);
      transform: rotate(-7deg);
    }

    .brand-moon {
      position: absolute;
      inset: .38rem;
      border-radius: 50%;
      background: var(--market-accent-dark);
      box-shadow: inset -.34rem .08rem 0 #ffd7e7;
    }

    .bag-star {
      position: relative;
      z-index: 1;
      color: #fff8eb;
      font-size: .68rem;
      line-height: 1;
      transform: rotate(7deg) translate(.22rem, -.15rem);
    }

    .header-search {
      min-width: 0;
      display: grid;
      grid-template-columns: minmax(0, 1fr) 42px;
      align-items: center;
      height: 44px;
      border: 1px solid var(--market-line-strong);
      border-radius: var(--market-radius-control);
      background: rgba(255,255,255,.88);
      overflow: hidden;
    }

    .header-search:focus-within {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(186,91,120,.12);
    }

    .header-search input,
    .header-search button {
      height: 100%;
      border: 0;
      background: transparent;
      color: var(--market-ink);
      font: inherit;
      outline: 0;
    }

    .header-search input {
      min-width: 0;
      padding: 0 1rem;
    }

    .header-search button {
      display: grid;
      place-items: center;
      color: var(--market-accent-dark);
      cursor: pointer;
    }

    .header-search svg {
      width: 1.15rem;
      height: 1.15rem;
      fill: currentColor;
    }

    .public-nav {
      gap: 0.1rem;
    }

    .public-nav a,
    .public-nav button,
    .login-link,
    .account-chip {
      min-height: 40px;
      border-radius: var(--market-radius-sm);
      color: var(--market-muted);
      font-family: var(--font-market-utility);
      font-size: 0.82rem;
      font-weight: 680;
      padding-inline: 0.62rem;
    }

    .public-nav .active,
    .public-nav a:not(.active):hover,
    .public-nav button:hover {
      background: var(--market-accent-soft);
      color: var(--market-accent-dark);
      box-shadow: none;
      transform: none;
    }

    .login-link {
      border-radius: 999px;
      background: var(--market-surface-strong);
      color: #fff;
      box-shadow: none;
    }

    .login-link:hover {
      background: var(--market-accent-dark);
      box-shadow: none;
      transform: none;
    }

    .cart-count {
      background: var(--market-accent-dark);
    }

    .mobile-menu-toggle {
      display: none;
    }

    @media (max-width: 1180px) {
      .marketplace-header {
        grid-template-columns: max-content minmax(220px, 1fr) max-content;
      }

      .public-nav {
        grid-column: 1 / -1;
        justify-content: flex-start;
        order: 5;
        padding-bottom: 0.65rem;
      }
    }

    @media (max-width: 820px) {
      .marketplace-header {
        grid-template-columns: minmax(0, 1fr) max-content max-content;
        gap: 0.65rem;
        padding: 0.75rem 1rem;
      }

      .brand {
        min-width: 0;
      }

      .header-search {
        grid-column: 1 / -1;
        grid-row: 2;
      }

      .nav-account {
        grid-column: 2;
        grid-row: 1;
      }

      .mobile-menu-toggle {
        width: 42px;
        height: 42px;
        display: grid;
        place-content: center;
        gap: 4px;
        grid-column: 3;
        grid-row: 1;
        border: 1px solid var(--market-line);
        border-radius: var(--market-radius-sm);
        background: #fff;
        cursor: pointer;
      }

      .mobile-menu-toggle span {
        width: 18px;
        height: 2px;
        border-radius: 2px;
        background: var(--market-ink);
      }

      .public-nav {
        display: none;
        grid-column: 1 / -1;
        grid-row: 3;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0.4rem;
        padding: 0.3rem 0 0;
      }

      .public-nav.open {
        display: grid;
      }

      .public-nav a,
      .public-nav button {
        width: 100%;
        justify-content: flex-start;
        border: 1px solid var(--market-line);
        background: var(--market-surface);
      }

      .login-link {
        min-height: 40px;
        padding-inline: 0.9rem;
      }
    }

    @media (max-width: 520px) {
      .brand { font-size: 1.18rem; }
      .brand-mark { width: 1.95rem; height: 1.95rem; }
      .account-chip { width: 44px; min-height: 44px; padding: 3px; }
      .account-chip strong { display: none; }
      .avatar-wrap { width: 36px; height: 36px; flex-basis: 36px; }
      .logout-link { width: 40px; height: 40px; margin-left: .25rem; }
    }
  `],
})
export class MarketplaceNavbarComponent {
  private authService = inject(AuthService);
  private cartService = inject(CartService);
  private toastService = inject(ToastService);
  @Input() authenticated = false;
  @Input() cartEnabled = false;
  @Input() cartCount = 0;
  @Input() notificationsEnabled = false;
  @Input() notificationCount = 0;
  @Input() currentUser: CurrentUser | null = null;
  @Output() loginRequested = new EventEmitter<void>();
  @Output() searchRequested = new EventEmitter<string>();
  avatarLoadFailed = signal(false);
  mobileMenuOpen = signal(false);
  searchTerm = '';
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

  cartCountLabel(): string {
    return this.cartCount > 99 ? '99+' : String(Math.max(0, this.cartCount));
  }

  cartAriaLabel(): string {
    const count = Math.max(0, this.cartCount);
    return count === 1 ? 'Cart, 1 item' : `Cart, ${count} items`;
  }

  notificationCountLabel(): string {
    return this.notificationCount > 99 ? '99+' : String(Math.max(0, this.notificationCount));
  }

  notificationAriaLabel(): string {
    const count = Math.max(0, this.notificationCount);
    return count === 1 ? 'Notifications, 1 unread' : `Notifications, ${count} unread`;
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
    this.searchRequested.emit(this.searchTerm.trim());
    this.closeMenu();
  }

  toggleMenu(): void {
    this.mobileMenuOpen.update(open => !open);
  }

  closeMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  requestLogin(): void {
    this.closeMenu();
    this.loginRequested.emit();
  }
}
