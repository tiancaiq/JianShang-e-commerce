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
  templateUrl: './login.component.html',
  styleUrls: ['./login-portal.component.css', './login.component.css'],
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly authMode = signal<'login' | 'register'>('login');
  readonly authBusy = signal(false);
  readonly authError = signal<string | null>(null);
  readonly passwordVisible = signal(false);
  authEmail = '';
  authPassword = '';
  authDisplayName = '';

  constructor() {
    this.authService.ensureSession()
      .pipe(take(1))
      .subscribe(state => {
        if (state.authenticated) {
          void this.router.navigateByUrl(this.returnUrl());
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
