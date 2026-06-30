import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { Inject, Injectable, PLATFORM_ID, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, finalize, map, of, shareReplay, switchMap } from 'rxjs';
import {
  ApiDataResponse,
  AuthState,
  CsrfSummary,
  CurrentUser,
  SessionUser,
  SessionResponse,
} from '../models/auth.model';
import { environment } from '../../../environments/environment';
import { unwrapData } from './api-response';

export type LoginClient = 'marketplace' | 'seller-portal' | 'admin-portal';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly gatewayUrl = environment.apiGatewayUrl;
  private readonly currentUser = signal<CurrentUser | null>(null);
  private readonly csrfToken = signal<CsrfSummary | null>(null);
  private readonly isLoading = signal(false);
  private readonly initialized = signal(false);
  private sessionRequest: Observable<AuthState> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly csrf = this.csrfToken.asReadonly();
  readonly loading = this.isLoading.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  constructor(
    private http: HttpClient,
    @Inject(PLATFORM_ID) private platformId: Object,
    @Inject(DOCUMENT) private document: Document
  ) {}

  ensureSession(): Observable<AuthState> {
    if (this.initialized()) {
      return of(this.snapshot());
    }

    if (!this.sessionRequest) {
      this.sessionRequest = this.loadSession();
    }

    return this.sessionRequest;
  }

  refreshSession(): Observable<AuthState> {
    this.sessionRequest = this.loadSession();
    return this.sessionRequest;
  }

  setCurrentUser(user: CurrentUser): void {
    this.currentUser.set(user);
    this.initialized.set(true);
  }

  login(client: LoginClient = 'marketplace', returnUrl?: string | null): void {
    this.redirectToAuthEndpoint('/api/v1/auth/login', client, returnUrl);
  }

  register(client: LoginClient = 'marketplace', returnUrl?: string | null): void {
    this.redirectToAuthEndpoint('/api/v1/auth/register', client, returnUrl);
  }

  private redirectToAuthEndpoint(endpoint: string, client: LoginClient, returnUrl?: string | null): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const params = new URLSearchParams({ client });
    const safeReturnUrl = this.safeReturnUrl(returnUrl);
    if (safeReturnUrl) {
      params.set('returnUrl', safeReturnUrl);
    }

    this.document.defaultView?.location.assign(this.url(`${endpoint}?${params.toString()}`));
  }

  logout(): void {
    this.clearUser();

    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const form = this.document.createElement('form');
    form.method = 'post';
    form.action = this.url('/api/v1/auth/logout');
    form.style.display = 'none';

    const csrf = this.csrfToken();
    if (csrf) {
      const csrfInput = this.document.createElement('input');
      csrfInput.type = 'hidden';
      csrfInput.name = csrf.parameterName;
      csrfInput.value = csrf.token;
      form.appendChild(csrfInput);
    }

    this.document.body.appendChild(form);
    form.submit();
  }

  clearUser(): void {
    this.currentUser.set(null);
    this.initialized.set(true);
  }

  private loadSession(): Observable<AuthState> {
    this.isLoading.set(true);

    return this.http.get<SessionResponse>(this.url('/api/v1/auth/session'), { withCredentials: true }).pipe(
      switchMap(session => {
        this.csrfToken.set(session.csrf);

        if (!session.authenticated) {
          this.currentUser.set(null);
          return of(this.snapshot());
        }

        const sessionUser = this.currentUserFromSession(session.user);
        this.currentUser.set(sessionUser);

        return this.http.get<ApiDataResponse<CurrentUser>>(this.url('/api/v1/users/me'), {
          withCredentials: true,
        }).pipe(
          map(unwrapData),
          map(user => {
            this.currentUser.set(user);
            return this.snapshot();
          }),
          catchError(() => {
            this.currentUser.set(sessionUser);
            return of(this.snapshot());
          })
        );
      }),
      catchError(() => {
        this.csrfToken.set(null);
        this.currentUser.set(null);
        return of(this.snapshot());
      }),
      finalize(() => {
        this.initialized.set(true);
        this.isLoading.set(false);
        this.sessionRequest = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );
  }

  private snapshot(): AuthState {
    const user = this.currentUser();
    return {
      authenticated: user !== null,
      user,
    };
  }

  private currentUserFromSession(user: SessionUser | null): CurrentUser | null {
    if (!user) {
      return null;
    }

    const now = new Date().toISOString();
    return {
      id: user.subject,
      keycloakSub: user.subject,
      email: user.email,
      emailVerified: false,
      displayName: user.displayName,
      phone: null,
      phoneVerified: false,
      avatarUrl: null,
      status: 'ACTIVE',
      version: 0,
      createdAt: now,
      updatedAt: now,
    };
  }

  private url(path: string): string {
    if (!this.gatewayUrl) {
      return path;
    }
    return `${this.gatewayUrl}${path}`;
  }

  private safeReturnUrl(returnUrl?: string | null): string | null {
    if (!returnUrl || !returnUrl.startsWith('/') || returnUrl.startsWith('//') || returnUrl.includes('\\')) {
      return null;
    }
    return returnUrl;
  }
}
