import { Component, HostListener, Injector, OnDestroy, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterOutlet } from '@angular/router';
import { take } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { CartService } from '../../core/services/cart.service';
import { FloatingChatComponent } from '../../features/chat/floating-chat.component';
import { MarketplaceNavbarComponent } from '../../features/marketplace/components/marketplace-navbar.component';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { ToastService } from '../../core/services/toast.service';
import { CART_ENABLED } from '../../features/cart/cart.capability';
import { NOTIFICATION_CENTER_ENABLED } from '../../features/account/notification-center.capability';
import { NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-marketplace-layout',
  standalone: true,
  imports: [FloatingChatComponent, FormsModule, MarketplaceNavbarComponent, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="marketplace-shell" [class.account-dashboard-shell]="isAccountDashboardView()">
      <app-marketplace-navbar
        [authenticated]="authService.isAuthenticated()"
        [cartEnabled]="cartEnabled"
        [cartCount]="cartEnabled ? cartService.count() : 0"
        [notificationsEnabled]="notificationsEnabled"
        [notificationCount]="notificationCount()"
        [currentUser]="authService.user()"
        (loginRequested)="openAuthDialog()"
        (searchRequested)="searchMarketplace($event)"
      />

      @if (authDialogOpen()) {
        <section class="auth-overlay" aria-label="Marketplace sign in">
          <div class="auth-dialog" role="dialog" aria-modal="true" aria-labelledby="marketplace-auth-title">
            <button type="button" class="auth-close" aria-label="Close sign in" (click)="closeAuthDialog()">×</button>
            <aside class="auth-visual" aria-hidden="true">
              <img src="/assets/brand/anime/auth-shopping-scene.webp" alt="" />
              <div><span>Welcome back</span><strong>Your next favorite find is waiting.</strong></div>
            </aside>
            <div class="auth-content">
              <div class="auth-mark" aria-hidden="true">★</div>
              <p class="auth-kicker">MSB marketplace account</p>
              <h2 id="marketplace-auth-title">Sign in to keep trading local.</h2>
              <p class="auth-copy">
                Return to saved listings, messages, purchases, and selling tools.
              </p>
              <div class="auth-tabs" role="tablist" aria-label="Account action">
                <button type="button" [class.active]="authMode() === 'login'" (click)="setAuthMode('login')">Sign in</button>
                <button type="button" [class.active]="authMode() === 'register'" (click)="setAuthMode('register')">Create account</button>
              </div>
              @if (authDialogError()) {
                <p class="auth-error">{{ authDialogError() }}</p>
              }
              <form class="auth-form" (ngSubmit)="submitNativeAuth()">
                @if (authMode() === 'register') {
                  <label>
                    Display name
                    <input name="displayName" autocomplete="name" [(ngModel)]="authDisplayName" [disabled]="authDialogBusy()" />
                  </label>
                }
                <label>
                  Email
                  <input name="email" type="email" autocomplete="email" [(ngModel)]="authEmail" [disabled]="authDialogBusy()" required />
                </label>
                <label>
                  Password
                  <input
                    name="password"
                    type="password"
                    [autocomplete]="authMode() === 'login' ? 'current-password' : 'new-password'"
                    [(ngModel)]="authPassword"
                    [disabled]="authDialogBusy()"
                    required
                  />
                </label>
                <button type="submit" class="auth-primary" [disabled]="authDialogBusy()">
                  {{ authMode() === 'login' ? 'Sign in' : 'Create account' }}
                </button>
              </form>
              <p class="auth-footnote">Keycloak verifies credentials behind the gateway; MSB never stores passwords.</p>
            </div>
          </div>
        </section>
      }

      <main class="marketplace-main" [class.account-dashboard-main]="isAccountDashboardView()">
        <router-outlet />
      </main>
      <app-floating-chat />
      <app-toast-container />
    </div>
  `,
  styles: [`
    .marketplace-shell {
      min-height: 100vh;
      position: relative;
      background:
        radial-gradient(circle at 92% 4%, rgba(255, 188, 216, .42) 0 18rem, transparent 34rem),
        radial-gradient(circle at 2% 44%, rgba(255, 220, 235, .56) 0 12rem, transparent 28rem),
        linear-gradient(180deg, #ffffff 0%, #fff8fb 48%, #fff2f8 100%),
        var(--market-canvas);
      color: var(--market-ink);
    }

    .marketplace-shell::before {
      position: fixed;
      z-index: 0;
      inset: 0;
      background:
        radial-gradient(ellipse at 74% 30%, rgba(255,255,255,.8), transparent 32%),
        linear-gradient(120deg, transparent 0 68%, rgba(233,79,138,.035) 68% 68.2%, transparent 68.2%);
      content: '';
      pointer-events: none;
    }

    .marketplace-main {
      position: relative;
      z-index: 1;
      max-width: var(--market-content);
      margin: 0 auto;
      padding: 1.25rem var(--market-page-pad) 4.5rem;
    }

    .marketplace-main.account-dashboard-main {
      max-width: none;
      padding: 0;
    }

    .auth-overlay {
      position: fixed;
      inset: 0;
      z-index: 100;
      display: grid;
      place-items: center;
      padding: 1rem;
      background: rgba(51, 38, 62, 0.48);
      backdrop-filter: blur(12px);
    }

    .auth-dialog {
      position: relative;
      width: min(100%, 880px);
      display: grid;
      grid-template-columns: minmax(300px, .9fr) minmax(0, 1.1fr);
      gap: 1rem;
      padding: 1.25rem;
      border: 1px solid var(--market-line);
      border-radius: 34px 14px 34px 14px;
      background: rgba(255, 255, 255, 0.96);
      color: var(--market-ink);
      box-shadow: var(--market-shadow-lg);
    }

    .auth-content {
      padding: 1rem .75rem;
    }

    .auth-visual {
      position: relative;
      min-height: 520px;
      overflow: hidden;
      border-radius: 25px 9px 25px 9px;
      background: #eee9ff;
    }

    .auth-visual img { width: 100%; height: 100%; object-fit: cover; object-position: 54% center; }

    .auth-visual div {
      position: absolute;
      right: 1rem;
      bottom: 1rem;
      left: 1rem;
      display: grid;
      gap: .2rem;
      padding: 1rem;
      border: 1px solid rgba(255,255,255,.75);
      border-radius: 18px 7px 18px 7px;
      background: rgba(51,38,62,.78);
      color: #fff;
      backdrop-filter: blur(12px);
    }

    .auth-visual span { color: #ffb4cd; font-size: .72rem; font-weight: 900; letter-spacing: .08em; text-transform: uppercase; }
    .auth-visual strong { font-family: var(--font-market-display); font-size: 1.25rem; line-height: 1.15; }

    .auth-close {
      position: absolute;
      z-index: 6;
      top: 0.75rem;
      right: 0.75rem;
      width: 2rem;
      height: 2rem;
      display: grid;
      place-items: center;
      border: 0;
      border-radius: 999px;
      background: var(--market-surface-subtle);
      color: var(--market-muted);
      cursor: pointer;
      font-weight: 800;
    }

    .auth-close:hover {
      color: var(--market-accent-dark);
    }

    .auth-mark {
      width: 2.6rem;
      height: 2.6rem;
      display: grid;
      place-items: center;
      border-radius: 13px 5px 13px 5px;
      background: linear-gradient(135deg, var(--market-accent), var(--market-lavender));
      color: #fff;
      font-weight: 850;
      box-shadow: var(--market-shadow-sm);
    }

    .auth-kicker {
      margin: 1rem 0 0.35rem;
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 800;
      text-transform: uppercase;
    }

    .auth-dialog h2 {
      margin: 0;
      font-family: var(--font-display);
      font-size: 1.55rem;
      letter-spacing: 0;
    }

    .auth-copy,
    .auth-footnote,
    .auth-error {
      margin: 0.65rem 0 0;
      color: var(--market-muted);
      font-size: 0.9rem;
      line-height: 1.5;
    }

    .auth-error {
      color: #ad2f65;
      font-weight: 700;
    }

    .auth-tabs {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.35rem;
      margin-top: 1rem;
      padding: 0.3rem;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: var(--market-surface-subtle);
    }

    .auth-tabs button {
      min-height: 36px;
      border: 0;
      border-radius: 6px;
      background: transparent;
      color: var(--market-muted);
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    .auth-tabs button.active {
      background: #fff;
      color: var(--market-accent-dark);
      box-shadow: var(--market-shadow-sm);
    }

    .auth-form {
      display: grid;
      gap: 0.75rem;
      margin-top: 1rem;
    }

    .auth-form label {
      display: grid;
      gap: 0.35rem;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 800;
    }

    .auth-form input {
      width: 100%;
      min-height: 42px;
      padding: 0 0.75rem;
      border: 1px solid var(--market-line);
      border-radius: var(--market-radius-sm);
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      font-size: 0.95rem;
      outline: none;
    }

    .auth-form input:focus {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(13, 124, 117, 0.18);
    }

    .auth-form button {
      min-height: 44px;
      border-radius: 8px;
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    .auth-primary {
      border: 0;
      background: var(--market-accent);
      color: #fff;
      box-shadow: var(--market-shadow-sm);
    }

    @media (max-width: 760px) {
      .auth-dialog {
        grid-template-columns: 1fr;
      }
      .auth-visual { min-height: 170px; }
      .auth-visual img { object-position: 50% 38%; }
      .auth-visual div { padding: .75rem; }
      .auth-visual strong { font-size: 1rem; }
    }
  `],
})
export class MarketplaceLayoutComponent implements OnInit, OnDestroy {
  authService = inject(AuthService);
  cartService = inject(CartService);
  readonly cartEnabled = inject(CART_ENABLED);
  readonly notificationsEnabled = inject(NOTIFICATION_CENTER_ENABLED);
  readonly notificationCount = signal(0);
  private readonly injector = inject(Injector);
  private notificationPoll: ReturnType<typeof setInterval> | null = null;
  private toastService = inject(ToastService);
  private router = inject(Router);
  authDialogOpen = signal(false);
  authDialogBusy = signal(false);
  authDialogError = signal<string | null>(null);
  authMode = signal<'login' | 'register'>('login');
  authEmail = '';
  authPassword = '';
  authDisplayName = '';
  private readonly authenticatedDialogEffect = effect(() => {
    if (this.authDialogOpen() && this.authService.isAuthenticated()) {
      this.completeAuthenticatedDialog();
    }
  });

  ngOnInit(): void {
    this.showSignedOutConfirmation();
    this.authService.ensureSession().subscribe(state => {
      if (!this.cartEnabled) {
        return;
      }
      if (state.authenticated) {
        this.cartService.load().subscribe({ error: () => undefined });
        if (this.notificationsEnabled) {
          this.refreshNotificationCount();
          if (!this.notificationPoll) {
            this.notificationPoll = setInterval(() => this.refreshNotificationCount(), 15000);
          }
        }
      } else {
        this.cartService.reset();
      }
    });
  }

  ngOnDestroy(): void {
    if (this.notificationPoll) clearInterval(this.notificationPoll);
  }

  private refreshNotificationCount(): void {
    this.injector.get(NotificationService).count().subscribe({
      next: value => this.notificationCount.set(value.unreadCount), error: () => undefined,
    });
  }

  openAuthDialog(): void {
    this.authDialogError.set(null);
    this.authMode.set('login');
    this.authDialogOpen.set(true);
  }

  closeAuthDialog(): void {
    if (this.authDialogBusy()) {
      return;
    }
    this.authDialogOpen.set(false);
    this.authDialogError.set(null);
  }

  @HostListener('document:keydown.escape')
  dismissAuthDialogFromKeyboard(): void {
    if (this.authDialogOpen()) this.closeAuthDialog();
  }

  startPopupLogin(): void {
    this.startPopupAuth('login');
  }

  startPopupRegistration(): void {
    this.startPopupAuth('register');
  }

  setAuthMode(mode: 'login' | 'register'): void {
    if (this.authDialogBusy()) {
      return;
    }
    this.authMode.set(mode);
    this.authDialogError.set(null);
  }

  searchMarketplace(query: string): void {
    this.router.navigate(['/marketplace'], {
      queryParams: query ? { q: query } : {},
    });
  }

  isAccountDashboardView(): boolean {
    return (this.router.url || '').split(/[?#]/)[0] === '/account';
  }

  submitNativeAuth(): void {
    const email = this.authEmail.trim();
    const password = this.authPassword;
    const displayName = this.authDisplayName.trim();
    if (!email || !password || (this.authMode() === 'register' && !displayName)) {
      this.authDialogError.set('Enter the required account details to continue.');
      return;
    }

    this.authDialogBusy.set(true);
    this.authDialogError.set(null);
    const authFlow = this.authMode() === 'login'
      ? this.authService.nativeLogin({ email, password })
      : this.authService.nativeRegister({ email, password, displayName });
    const unauthenticatedMessage = this.authMode() === 'login'
      ? 'Email or password is incorrect.'
      : 'Account could not be created. Try again.';

    authFlow.pipe(take(1)).subscribe({
      next: state => this.finishAuthAttempt(state.authenticated, unauthenticatedMessage),
      error: error => {
        this.authDialogBusy.set(false);
        this.authDialogError.set(this.nativeAuthErrorMessage(error));
      },
    });
  }

  startGoogleLogin(): void {
    this.authDialogBusy.set(true);
    this.authDialogError.set(null);
    this.authService.loginWithGooglePopup('marketplace', this.currentReturnUrl()).pipe(take(1)).subscribe({
      next: state => {
        this.finishAuthAttempt(state.authenticated, 'Finish Google sign-in in the popup window to continue.');
      },
      error: () => {
        this.authDialogBusy.set(false);
        this.authDialogError.set('Google sign-in could not finish. Try again.');
      },
    });
  }

  private startPopupAuth(flow: 'login' | 'register'): void {
    this.authDialogBusy.set(true);
    this.authDialogError.set(null);
    const returnUrl = this.currentReturnUrl();
    const authFlow = flow === 'login'
      ? this.authService.loginWithPopup('marketplace', returnUrl)
      : this.authService.registerWithPopup('marketplace', returnUrl);
    authFlow.pipe(take(1)).subscribe({
      next: state => {
        this.finishAuthAttempt(state.authenticated, 'Finish sign-in in the popup window to continue.');
      },
      error: () => {
        this.authDialogBusy.set(false);
        this.authDialogError.set('Sign-in could not finish. Try again.');
      },
    });
  }

  private finishAuthAttempt(authenticated: boolean, unauthenticatedMessage: string): void {
    if (authenticated) {
      this.completeAuthenticatedDialog();
      return;
    }
    this.authDialogBusy.set(false);
    this.authDialogError.set(unauthenticatedMessage);
  }

  // Dismisses the modal at the session transition instead of waiting for optional profile enrichment.
  private completeAuthenticatedDialog(): void {
    const wasAttemptActive = this.authDialogOpen() || this.authDialogBusy();
    this.authDialogOpen.set(false);
    this.authDialogBusy.set(false);
    this.authDialogError.set(null);
    this.authPassword = '';
    if (wasAttemptActive && this.cartEnabled) {
      this.cartService.load().subscribe({ error: () => undefined });
    }
  }

  private nativeAuthErrorMessage(error: unknown): string {
    const payload = (error as { error?: unknown })?.error;
    if (typeof payload === 'object' && payload !== null && 'message' in payload) {
      const message = (payload as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        if (message.includes('Unexpected token')) {
          return 'The auth gateway returned an unexpected page. Restart the gateway and try again.';
        }
        return message;
      }
    }
    if (typeof payload === 'string' && payload.includes('<!DOCTYPE')) {
      return 'The auth gateway returned a page instead of JSON. Check that the frontend is calling port 9000.';
    }
    return 'Account sign-in could not finish. Try again.';
  }

  private currentReturnUrl(): string {
    const url = this.router.url || '/';
    if (!url.startsWith('/') || url.startsWith('//') || url.includes('\\') || url.startsWith('/login')) {
      return '/';
    }
    return url;
  }

  private showSignedOutConfirmation(): void {
    const url = this.router.url || '';
    if (!url.includes('signedOut=1')) {
      return;
    }
    this.toastService.success('Signed out successfully');
    const path = url.split(/[?#]/, 1)[0] || '/';
    this.router.navigateByUrl(path, { replaceUrl: true });
  }
}
