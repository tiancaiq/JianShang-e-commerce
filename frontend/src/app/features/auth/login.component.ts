import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { take } from 'rxjs';
import { AuthService, LoginClient } from '../../core/services/auth.service';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ToastContainerComponent],
  template: `
    <div class="login-page bg-noise">
      <section class="login-panel">
        <div class="login-brand">
          <svg width="48" height="48" viewBox="0 0 28 28" fill="none" aria-hidden="true">
            <rect width="28" height="28" rx="6" fill="url(#lg)"/>
            <path d="M8 14l4-6 4 6-4 6-4-6z" fill="#0c0c0e" opacity="0.9"/>
            <path d="M14 10l4-2v12l-4-2V10z" fill="#0c0c0e" opacity="0.6"/>
            <defs><linearGradient id="lg" x1="0" y1="0" x2="28" y2="28"><stop stop-color="#f98e07"/><stop offset="1" stop-color="#dd6802"/></linearGradient></defs>
          </svg>
          <h1 class="brand-title">MSB<span class="accent">Commerce</span></h1>
          <p class="brand-subtitle">Sign in or create your MSB account</p>
        </div>

        <button type="button" class="submit-btn" (click)="handleLogin()">
          Continue to sign in
        </button>
        @if (canCreateAccount()) {
          <button type="button" class="secondary-btn" (click)="handleRegister()">
            Create account
          </button>
        }
      </section>
      <app-toast-container />
    </div>
  `,
  styles: [`
    .login-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--color-bg-primary);
      position: relative;
      padding: 1.5rem;
    }

    .login-panel {
      width: 100%;
      max-width: 400px;
      padding: 2.5rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-2xl);
      box-shadow: var(--shadow-lg);
      animation: fadeIn 0.5s ease-out;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(16px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .login-brand {
      text-align: center;
      margin-bottom: 2rem;
    }
    .login-brand svg {
      margin: 0 auto 1rem;
    }
    .brand-title {
      font-family: var(--font-display);
      font-size: 1.75rem;
      font-weight: 700;
      color: var(--color-text-primary);
      margin-bottom: 0.25rem;
    }
    .accent { color: var(--color-accent); }
    .brand-subtitle {
      font-size: 0.875rem;
      color: var(--color-text-muted);
    }

    .submit-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      padding: 0.75rem;
      background: linear-gradient(135deg, var(--color-accent) 0%, #dd6802 100%);
      color: #0c0c0e;
      font-family: var(--font-display);
      font-weight: 600;
      font-size: 0.9375rem;
      border: none;
      border-radius: var(--radius-md);
      cursor: pointer;
      transition: all var(--transition-fast);
    }
    .submit-btn:hover {
      box-shadow: 0 0 24px -4px rgba(249, 142, 7, 0.4);
      transform: translateY(-1px);
    }
    .secondary-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      margin-top: 0.75rem;
      padding: 0.75rem;
      background: transparent;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-weight: 600;
      font-size: 0.9375rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      cursor: pointer;
      transition: all var(--transition-fast);
    }
    .secondary-btn:hover {
      border-color: var(--color-accent);
      color: var(--color-accent);
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

  private isMarketplaceClient(): boolean {
    return this.client() === 'marketplace';
  }

  private client(): LoginClient {
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
