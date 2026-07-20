import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { take } from 'rxjs';
import { AuthService, LoginClient } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [BrandMascotComponent, ToastContainerComponent],
  template: `
    <div class="login-page bg-noise" [class.seller-login]="client() === 'seller-portal'" [class.admin-login]="client() === 'admin-portal'">
      <section class="login-shell">
        <app-brand-mascot variant="login" alt="MSB marketplace mascot on the login page" />
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
          @if (canCreateAccount()) {
            <button type="button" class="secondary-btn" (click)="handleRegister()">
              Create account
            </button>
          }
        </div>
      </section>
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

    .secondary-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      margin-top: 0.75rem;
      padding: 0.75rem;
      background: #fff8fc;
      color: #be3a83;
      font-family: var(--font-display);
      font-weight: 900;
      font-size: 0.9375rem;
      border: 1px solid #ead3f0;
      border-radius: 8px;
      cursor: pointer;
      transition: all var(--transition-fast);
    }

    .secondary-btn:hover {
      border-color: #f472b6;
      color: #8b6fe8;
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
    const returnUrl = this.returnUrl();
    if (!this.isMarketplaceClient()) {
      this.authService.login(this.client(), returnUrl);
      return;
    }

    this.authService.loginWithPopup(this.client(), returnUrl)
      .pipe(take(1))
      .subscribe(state => {
        if (state.authenticated) {
          this.router.navigateByUrl(returnUrl);
        }
      });
  }

  handleRegister(): void {
    const returnUrl = this.returnUrl();
    if (!this.isMarketplaceClient()) {
      this.authService.register(this.client(), returnUrl);
      return;
    }

    this.authService.registerWithPopup(this.client(), returnUrl)
      .pipe(take(1))
      .subscribe(state => {
        if (state.authenticated) {
          this.router.navigateByUrl(returnUrl);
        }
      });
  }

  canCreateAccount(): boolean {
    return this.isMarketplaceClient();
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

  private isMarketplaceClient(): boolean {
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
      return '/';
    }
    return candidate;
  }
}
