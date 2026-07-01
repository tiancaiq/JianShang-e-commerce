import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { take } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';

@Component({
  selector: 'app-marketplace-layout',
  standalone: true,
  imports: [FormsModule, RouterLink, RouterOutlet, ToastContainerComponent],
  template: `
    <div class="marketplace-shell">
      <header class="marketplace-header">
        <a routerLink="/" class="brand" aria-label="MSBCommerce marketplace home">
          <span class="brand-mark">M</span>
          <span>MSB<span>Commerce</span></span>
        </a>
        <nav class="public-nav" aria-label="Marketplace navigation">
          <a routerLink="/marketplace">Marketplace</a>
          <a routerLink="/stores">Stores</a>
          @if (authService.isAuthenticated()) {
            <a routerLink="/account/listings">My Listings</a>
            <a routerLink="/account/profile">Account</a>
            <button type="button" (click)="authService.logout()">Logout</button>
          } @else {
            <button type="button" (click)="openAuthDialog()">Login</button>
          }
        </nav>
      </header>

      @if (authDialogOpen()) {
        <section class="auth-overlay" aria-label="Marketplace sign in">
          <div class="auth-dialog" role="dialog" aria-modal="true" aria-labelledby="marketplace-auth-title">
            <button type="button" class="auth-close" aria-label="Close sign in" (click)="closeAuthDialog()">x</button>
            <div class="auth-mark">M</div>
            <p class="auth-kicker">MSB marketplace account</p>
            <h2 id="marketplace-auth-title">Sign in to keep shopping local.</h2>
            <p class="auth-copy">
              Create listings, manage your profile, and come back to your saved marketplace flow.
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
        </section>
      }

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
      width: min(100%, 430px);
      padding: 1.4rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      color: var(--market-ink);
      box-shadow: 0 28px 70px rgba(98, 62, 108, 0.24);
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
      border-radius: 8px;
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
      border-radius: 8px;
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
  private router = inject(Router);
  authDialogOpen = signal(false);
  authDialogBusy = signal(false);
  authDialogError = signal<string | null>(null);
  authMode = signal<'login' | 'register'>('login');
  authEmail = '';
  authPassword = '';
  authDisplayName = '';

  ngOnInit(): void {
    this.authService.ensureSession().subscribe();
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

    authFlow.pipe(take(1)).subscribe({
      next: state => this.finishAuthAttempt(state.authenticated, 'Account is ready.'),
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
}
