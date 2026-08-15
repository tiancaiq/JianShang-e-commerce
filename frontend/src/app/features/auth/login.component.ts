import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { take } from 'rxjs';
import { AuthService, LoginClient } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [BrandMascotComponent, FormsModule, ToastContainerComponent],
  template: `
    <div class="login-page bg-noise" [class.seller-login]="client() === 'seller-portal'" [class.admin-login]="client() === 'admin-portal'">
      @if (isMarketplaceClient()) {
        <section class="general-login-shell" aria-labelledby="general-login-title">
          <a class="marketplace-home" href="/marketplace">MSBCommerce</a>
          <div class="general-login-heading">
            <h1 id="general-login-title">{{ authMode() === 'login' ? 'Sign in' : 'Create account' }}</h1>
            <p>{{ authMode() === 'login' ? 'Use your marketplace account to continue.' : 'Create your marketplace account.' }}</p>
          </div>
          @if (signedOut()) {
            <p class="signed-out">Signed out successfully</p>
          }

          <div class="auth-tabs" role="tablist" aria-label="Account action">
            <button type="button" role="tab" [attr.aria-selected]="authMode() === 'login'" [class.active]="authMode() === 'login'" (click)="setAuthMode('login')">Sign in</button>
            <button type="button" role="tab" [attr.aria-selected]="authMode() === 'register'" [class.active]="authMode() === 'register'" (click)="setAuthMode('register')">Create account</button>
          </div>

          @if (authError()) {
            <p class="auth-error" role="alert">{{ authError() }}</p>
          }

          <form class="auth-form" (ngSubmit)="submitNativeAuth()">
            @if (authMode() === 'register') {
              <label>
                Display name
                <input name="displayName" autocomplete="name" [(ngModel)]="authDisplayName" [disabled]="authBusy()" required />
              </label>
            }
            <label>
              Email
              <input name="email" type="email" autocomplete="email" [(ngModel)]="authEmail" [disabled]="authBusy()" required />
            </label>
            <label>
              Password
              <input
                name="password"
                type="password"
                [autocomplete]="authMode() === 'login' ? 'current-password' : 'new-password'"
                [(ngModel)]="authPassword"
                [disabled]="authBusy()"
                required
              />
            </label>
            <button type="submit" class="submit-btn" [disabled]="authBusy()">
              {{ authMode() === 'login' ? 'Sign in' : 'Create account' }}
            </button>
          </form>
          <p class="auth-footnote">Credentials are securely verified by the identity service.</p>
        </section>
      } @else {
        <section class="login-shell">
          <app-brand-mascot variant="login" alt="MSB portal sign in" />
          <div class="login-panel">
            <div class="login-brand">
              <span class="brand-mark" aria-hidden="true">M</span>
              <p class="portal-label">{{ portalLabel() }}</p>
              <h1 class="brand-title">{{ portalTitle() }}</h1>
              <p class="brand-subtitle">{{ portalSubtitle() }}</p>
            </div>

            @if (signedOut()) {
              <p class="signed-out">Signed out successfully</p>
            }

            <button type="button" class="submit-btn" (click)="handleLogin()">
              Continue to sign in
            </button>
          </div>
        </section>
      }
      <app-toast-container />
    </div>
  `,
  styles: [`
    .login-page {
      min-height: 100vh;
      display: grid;
      place-items: center;
      background:
        radial-gradient(circle at 12% 8%, rgba(255, 207, 228, 0.38) 0 18%, transparent 19%),
        radial-gradient(circle at 90% 0%, rgba(206, 193, 255, 0.34) 0 16%, transparent 17%),
        linear-gradient(180deg, #fff7fb 0%, #f8f0ff 100%);
      position: relative;
      padding: 1.5rem;
    }

    .general-login-shell {
      width: min(100%, 430px);
      padding: 2rem;
      border: 1px solid #dce1e8;
      border-top: 4px solid #d84d7f;
      border-radius: 8px;
      background: #fff;
      box-shadow: 0 18px 48px rgba(31, 41, 55, 0.14);
    }

    .marketplace-home {
      display: inline-flex;
      margin-bottom: 2rem;
      color: #46364f;
      font-family: var(--font-display);
      font-weight: 900;
      text-decoration: none;
    }

    .general-login-heading {
      margin-bottom: 1.5rem;
    }

    .general-login-heading h1 {
      margin: 0 0 0.4rem;
      color: #241b2a;
      font-size: 1.75rem;
      letter-spacing: 0;
    }

    .general-login-heading p,
    .auth-footnote {
      margin: 0;
      color: #667085;
      font-size: 0.9rem;
    }

    .auth-tabs {
      display: grid;
      grid-template-columns: 1fr 1fr;
      margin-bottom: 1.25rem;
      border: 1px solid #dce1e8;
      border-radius: 6px;
      background: #f7f8fa;
      overflow: hidden;
    }

    .auth-tabs button {
      min-height: 2.75rem;
      border: 0;
      background: transparent;
      color: #667085;
      font-weight: 800;
      cursor: pointer;
    }

    .auth-tabs button.active {
      background: #fff;
      color: #a72f67;
      box-shadow: inset 0 -2px #d84d7f;
    }

    .auth-form {
      display: grid;
      gap: 1rem;
    }

    .auth-form label {
      display: grid;
      gap: 0.4rem;
      color: #344054;
      font-size: 0.82rem;
      font-weight: 800;
    }

    .auth-form input {
      box-sizing: border-box;
      width: 100%;
      min-height: 2.8rem;
      padding: 0.65rem 0.75rem;
      border: 1px solid #cfd5de;
      border-radius: 6px;
      background: #fff;
      color: #1f2937;
      font: inherit;
      font-weight: 500;
    }

    .auth-form input:focus {
      border-color: #d84d7f;
      outline: 3px solid rgba(216, 77, 127, 0.14);
    }

    .auth-error {
      margin: 0 0 1rem;
      padding: 0.75rem;
      border: 1px solid #f3b7c8;
      border-radius: 6px;
      background: #fff2f5;
      color: #9f234f;
      font-size: 0.85rem;
      font-weight: 700;
    }

    .auth-footnote {
      margin-top: 1rem;
      text-align: center;
    }

    .login-shell {
      width: 100%;
      max-width: 900px;
      display: grid;
      grid-template-columns: minmax(280px, 420px) minmax(0, 400px);
      gap: 1rem;
      align-items: stretch;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.94);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.72);
      box-shadow: 0 24px 70px rgba(132, 83, 143, 0.18);
      animation: fadeIn 0.5s ease-out;
    }

    .login-panel {
      display: flex;
      flex-direction: column;
      justify-content: center;
      padding: clamp(1.2rem, 4vw, 2.5rem);
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
    }

    .seller-login .login-page,
    .seller-login {
      background:
        radial-gradient(circle at 10% 8%, rgba(56, 189, 248, 0.22) 0 18%, transparent 19%),
        linear-gradient(180deg, #101722 0%, #111827 100%);
    }

    .admin-login .login-page,
    .admin-login {
      background:
        radial-gradient(circle at 10% 8%, rgba(248, 113, 113, 0.16) 0 18%, transparent 19%),
        linear-gradient(180deg, #0f1014 0%, #171923 100%);
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(16px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .login-brand {
      text-align: center;
      margin-bottom: 2rem;
    }

    .portal-label {
      margin: 0 0 0.35rem;
      color: #7e6d96;
      font-size: 0.75rem;
      font-weight: 900;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .brand-mark {
      width: 3rem;
      height: 3rem;
      display: grid;
      place-items: center;
      margin: 0 auto 1rem;
      border-radius: 8px;
      background: linear-gradient(135deg, #ff9fcb, #9476ee);
      color: #fff;
      font-weight: 900;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.22);
    }

    .brand-title {
      font-family: var(--font-display);
      font-size: 1.75rem;
      font-weight: 900;
      color: #37214b;
      margin-bottom: 0.25rem;
    }

    .accent { color: #be3a83; }

    .brand-subtitle {
      font-size: 0.875rem;
      color: #7e6d96;
    }

    .signed-out {
      margin: -0.75rem 0 1rem;
      padding: 0.75rem;
      border: 1px solid rgba(56, 168, 149, 0.26);
      border-radius: 8px;
      background: #effbf8;
      color: #246558;
      font-weight: 800;
      text-align: center;
    }

    .seller-login .brand-mark {
      background: linear-gradient(135deg, #38bdf8, #22c55e);
    }

    .admin-login .brand-mark {
      background: linear-gradient(135deg, #f97316, #ef4444);
    }

    .seller-login .accent,
    .seller-login .portal-label {
      color: #0284c7;
    }

    .admin-login .accent,
    .admin-login .portal-label {
      color: #c2410c;
    }

    .submit-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      padding: 0.75rem;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      font-family: var(--font-display);
      font-weight: 900;
      font-size: 0.9375rem;
      border: none;
      border-radius: 8px;
      cursor: pointer;
      transition: all var(--transition-fast);
    }

    .submit-btn:hover {
      box-shadow: 0 14px 26px rgba(190, 58, 131, 0.2);
      transform: translateY(-1px);
    }

    .submit-btn:disabled {
      cursor: wait;
      opacity: 0.65;
      transform: none;
    }

    @media (max-width: 760px) {
      .login-shell {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class LoginComponent {
  private authService = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  authMode = signal<'login' | 'register'>('login');
  authBusy = signal(false);
  authError = signal<string | null>(null);
  authEmail = '';
  authPassword = '';
  authDisplayName = '';

  constructor() {
    this.authService.ensureSession()
      .pipe(take(1))
      .subscribe(state => {
        if (state.authenticated) {
          this.router.navigateByUrl(this.returnUrl());
        }
      });
  }

  handleLogin(): void {
    this.authService.login(this.client(), this.returnUrl());
  }

  setAuthMode(mode: 'login' | 'register'): void {
    if (this.authBusy()) {
      return;
    }
    this.authMode.set(mode);
    this.authError.set(null);
  }

  submitNativeAuth(): void {
    const email = this.authEmail.trim();
    const password = this.authPassword;
    const displayName = this.authDisplayName.trim();
    if (!email || !password || (this.authMode() === 'register' && !displayName)) {
      this.authError.set('Enter the required account details to continue.');
      return;
    }

    this.authBusy.set(true);
    this.authError.set(null);
    const authFlow = this.authMode() === 'login'
      ? this.authService.nativeLogin({ email, password })
      : this.authService.nativeRegister({ email, password, displayName });
    const unauthenticatedMessage = this.authMode() === 'login'
      ? 'Email or password is incorrect.'
      : 'Account could not be created. Try again.';

    authFlow.pipe(take(1)).subscribe({
      next: state => {
        this.authBusy.set(false);
        if (state.authenticated) {
          this.authPassword = '';
          void this.router.navigateByUrl(this.returnUrl());
          return;
        }
        this.authError.set(unauthenticatedMessage);
      },
      error: error => {
        this.authBusy.set(false);
        this.authError.set(this.nativeAuthErrorMessage(error));
      },
    });
  }

  signedOut(): boolean {
    return this.route.snapshot.queryParamMap.get('signedOut') === '1';
  }

  portalLabel(): string {
    return this.client() === 'admin-portal'
      ? 'Admin Portal'
      : this.client() === 'seller-portal'
        ? 'Business Seller Portal'
        : 'Marketplace Account';
  }

  portalTitle(): string {
    return this.client() === 'admin-portal'
      ? 'MSB Admin'
      : this.client() === 'seller-portal'
        ? 'MSB Seller'
        : 'MSBCommerce';
  }

  portalSubtitle(): string {
    return this.client() === 'admin-portal'
      ? 'Secure staff sign-in for moderation and platform operations'
      : this.client() === 'seller-portal'
        ? 'Secure business sign-in for store and listing management'
        : 'Sign in or create your MSB account';
  }

  isMarketplaceClient(): boolean {
    return this.client() === 'marketplace';
  }

  client(): LoginClient {
    const candidate = this.route.snapshot.queryParamMap.get('client');
    if (candidate === 'seller-portal' || candidate === 'admin-portal') {
      return candidate;
    }
    return 'marketplace';
  }

  private returnUrl(): string {
    const candidate = this.route.snapshot.queryParamMap.get('returnUrl');
    if (!candidate || !candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) {
      return this.defaultReturnUrl();
    }
    return candidate;
  }

  private defaultReturnUrl(): string {
    return this.client() === 'admin-portal'
      ? '/admin/dashboard'
      : this.client() === 'seller-portal'
        ? '/seller/dashboard'
        : '/';
  }

  private nativeAuthErrorMessage(error: unknown): string {
    const payload = (error as { error?: unknown })?.error;
    if (typeof payload === 'object' && payload !== null && 'message' in payload) {
      const message = (payload as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        return message;
      }
    }
    return 'Account sign-in could not finish. Try again.';
  }
}
