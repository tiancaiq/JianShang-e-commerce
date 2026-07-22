import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterOutlet } from '@angular/router';
import { take } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { CartService } from '../../core/services/cart.service';
import { FloatingChatComponent } from '../../features/chat/floating-chat.component';
import { MarketplaceNavbarComponent } from '../../features/marketplace/components/marketplace-navbar.component';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { ToastService } from '../../core/services/toast.service';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';
import { CART_ENABLED } from '../../features/cart/cart.capability';

@Component({
  selector: 'app-marketplace-layout',
  standalone: true,
  imports: [BrandMascotComponent, FloatingChatComponent, FormsModule, MarketplaceNavbarComponent, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="marketplace-shell" [class.account-dashboard-shell]="isAccountDashboardView()">
      <app-marketplace-navbar
        [authenticated]="authService.isAuthenticated()"
        [cartEnabled]="cartEnabled"
        [cartCount]="cartEnabled ? cartService.count() : 0"
        [currentUser]="authService.user()"
        (loginRequested)="openAuthDialog()"
        (searchRequested)="searchMarketplace($event)"
      />

      @if (authDialogOpen()) {
        <section class="auth-overlay" aria-label="Marketplace sign in">
          <div class="auth-dialog" role="dialog" aria-modal="true" aria-labelledby="marketplace-auth-title">
            <button type="button" class="auth-close" aria-label="Close sign in" (click)="closeAuthDialog()">x</button>
            <div class="auth-illustration">
              <app-brand-mascot variant="login" alt="MSB marketplace mascot sign in illustration" />
            </div>
            <div class="auth-content">
              <div class="auth-mark">M</div>
              <p class="auth-kicker">MSB marketplace account</p>
              <h2 id="marketplace-auth-title">Sign in to keep trading local.</h2>
              <p class="auth-copy">
                Create listings, manage your profile, and return to saved marketplace flows.
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
      --kawaii-bg: #fff7fb;
      --kawaii-bg-2: #f7f0ff;
      --kawaii-surface: #ffffff;
      --kawaii-pink: #f472b6;
      --kawaii-pink-light: #fbcfe8;
      --kawaii-pink-soft: #fff0f7;
      --kawaii-purple: #8b6fe8;
      --kawaii-purple-light: #c7b7ff;
      --kawaii-lavender: #ede7ff;
      --kawaii-ink: #382648;
      --kawaii-muted: #827194;
      --kawaii-border: #ead7f2;
      --kawaii-success: #38a895;
      --kawaii-warning: #f7b84b;
      --market-bg: var(--kawaii-bg);
      --market-surface: var(--kawaii-surface);
      --market-soft: var(--kawaii-pink-soft);
      --market-ink: var(--kawaii-ink);
      --market-muted: var(--kawaii-muted);
      --market-line: var(--kawaii-border);
      --market-accent: var(--kawaii-pink);
      --market-accent-dark: #be3a83;
      --market-lavender: var(--kawaii-purple);
      --market-purple: var(--kawaii-purple);
      --market-mint: var(--kawaii-success);
      --market-success: var(--kawaii-success);
      --market-yellow: var(--kawaii-warning);
      --market-blue: #5d98e8;
      background:
        radial-gradient(circle at 12% 6%, rgba(255, 207, 228, 0.36) 0 18%, transparent 19%),
        radial-gradient(circle at 92% 0%, rgba(206, 193, 255, 0.3) 0 14%, transparent 15%),
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

    .marketplace-main {
      position: relative;
      z-index: 1;
      max-width: 1560px;
      margin: 0 auto;
      padding: 1.5rem clamp(1rem, 3vw, 2rem) 4rem;
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
      background: rgba(56, 38, 72, 0.34);
      backdrop-filter: blur(12px);
    }

    .auth-dialog {
      position: relative;
      width: min(100%, 860px);
      display: grid;
      grid-template-columns: minmax(280px, 0.9fr) minmax(0, 1fr);
      gap: 1rem;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 24px;
      background: rgba(255, 255, 255, 0.96);
      color: var(--market-ink);
      box-shadow: 0 28px 70px rgba(98, 62, 108, 0.24);
    }

    .auth-content {
      padding: 1rem;
    }

    .auth-illustration {
      min-width: 0;
    }

    .auth-close {
      position: absolute;
      top: 0.75rem;
      right: 0.75rem;
      width: 2rem;
      height: 2rem;
      display: grid;
      place-items: center;
      border: 0;
      border-radius: 999px;
      background: var(--market-soft);
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
      border-radius: 12px;
      background: linear-gradient(135deg, #ff9fcb, #9476ee);
      color: #fff;
      font-weight: 850;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.22);
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
      background: #fff7fb;
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
      box-shadow: 0 8px 18px rgba(150, 96, 144, 0.12);
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
      border-radius: 14px;
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      font-size: 0.95rem;
      outline: none;
    }

    .auth-form input:focus {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(244, 114, 182, 0.18);
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
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      box-shadow: 0 14px 26px rgba(190, 58, 131, 0.2);
    }

    @media (max-width: 760px) {
      .auth-dialog {
        grid-template-columns: 1fr;
      }

      .auth-illustration {
        display: none;
      }
    }
  `],
})
export class MarketplaceLayoutComponent implements OnInit {
  authService = inject(AuthService);
  cartService = inject(CartService);
  readonly cartEnabled = inject(CART_ENABLED);
  private toastService = inject(ToastService);
  private router = inject(Router);
  authDialogOpen = signal(false);
  authDialogBusy = signal(false);
  authDialogError = signal<string | null>(null);
  authMode = signal<'login' | 'register'>('login');
  authEmail = '';
  authPassword = '';
  authDisplayName = '';

  ngOnInit(): void {
    this.showSignedOutConfirmation();
    this.authService.ensureSession().subscribe(state => {
      if (!this.cartEnabled) {
        return;
      }
      if (state.authenticated) {
        this.cartService.load().subscribe({ error: () => undefined });
      } else {
        this.cartService.reset();
      }
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
    this.authDialogBusy.set(false);
    if (authenticated) {
      this.authDialogOpen.set(false);
      this.authPassword = '';
      return;
    }
    this.authDialogError.set(unauthenticatedMessage);
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
