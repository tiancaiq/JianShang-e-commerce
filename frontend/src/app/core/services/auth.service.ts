import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { Inject, Injectable, PLATFORM_ID, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, finalize, map, of, shareReplay, switchMap, take } from 'rxjs';
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
type ExternalLoginProvider = 'google';

interface AuthPopupMessage {
  type: 'MSB_AUTH_COMPLETE';
  returnUrl?: string;
}

export interface NativeLoginRequest {
  email: string;
  password: string;
}

export interface NativeRegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

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

  loginWithPopup(client: LoginClient = 'marketplace', returnUrl?: string | null): Observable<AuthState> {
    return this.openAuthPopup('/api/v1/auth/login', client, returnUrl);
  }

  loginWithGooglePopup(client: LoginClient = 'marketplace', returnUrl?: string | null): Observable<AuthState> {
    return this.openAuthPopup('/api/v1/auth/login', client, returnUrl, 'google');
  }

  registerWithPopup(client: LoginClient = 'marketplace', returnUrl?: string | null): Observable<AuthState> {
    return this.openAuthPopup('/api/v1/auth/register', client, returnUrl);
  }

  nativeLogin(request: NativeLoginRequest): Observable<AuthState> {
    return this.submitNativeAuth('/api/v1/auth/native/login', request);
  }

  nativeRegister(request: NativeRegisterRequest): Observable<AuthState> {
    return this.submitNativeAuth('/api/v1/auth/native/register', request);
  }

  private redirectToAuthEndpoint(endpoint: string, client: LoginClient, returnUrl?: string | null): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const params = this.authEndpointParams(client, returnUrl);
    this.document.defaultView?.location.assign(this.url(`${endpoint}?${params.toString()}`));
  }

  private openAuthPopup(
    endpoint: string,
    client: LoginClient,
    returnUrl?: string | null,
    provider?: ExternalLoginProvider
  ): Observable<AuthState> {
    if (!isPlatformBrowser(this.platformId)) {
      return of(this.snapshot());
    }

    return new Observable<AuthState>(subscriber => {
      const win = this.document.defaultView;
      if (!win) {
        subscriber.next(this.snapshot());
        subscriber.complete();
        return;
      }

      const params = this.authEndpointParams(client, returnUrl, provider);
      params.set('mode', 'popup');
      const authUrl = this.url(`${endpoint}?${params.toString()}`);
      const popup = win.open(authUrl, 'msb-auth', 'popup,width=520,height=680,noopener=false');
      if (!popup) {
        win.location.assign(authUrl);
        subscriber.complete();
        return;
      }

      let completed = false;
      let closeTimer: number | undefined;
      const expectedOrigin = this.authMessageOrigin(win);
      const finish = () => {
        if (completed) {
          return;
        }
        completed = true;
        cleanup();
        this.refreshSession().pipe(take(1)).subscribe({
          next: state => {
            subscriber.next(state);
            subscriber.complete();
          },
          error: error => subscriber.error(error),
        });
      };
      const onMessage = (event: MessageEvent) => {
        if (event.origin !== expectedOrigin || !this.isAuthPopupMessage(event.data)) {
          return;
        }
        finish();
      };
      const cleanup = () => {
        win.removeEventListener('message', onMessage);
        if (closeTimer !== undefined) {
          win.clearInterval(closeTimer);
        }
      };

      win.addEventListener('message', onMessage);
      closeTimer = win.setInterval(() => {
        if (popup.closed) {
          finish();
        }
      }, 750);

      return cleanup;
    });
  }

  private authEndpointParams(
    client: LoginClient,
    returnUrl?: string | null,
    provider?: ExternalLoginProvider
  ): URLSearchParams {
    const params = new URLSearchParams({ client });
    const safeReturnUrl = this.safeReturnUrl(returnUrl);
    if (safeReturnUrl) {
      params.set('returnUrl', safeReturnUrl);
    }
    if (provider) {
      params.set('provider', provider);
    }
    return params;
  }

  logout(): void {
    this.clearUser();

    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    this.clearLegacyBrowserAuthStorage();

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
      switchMap(session => this.applySession(session)),
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

  private submitNativeAuth(endpoint: string, body: NativeLoginRequest | NativeRegisterRequest): Observable<AuthState> {
    return this.refreshSession().pipe(
      take(1),
      switchMap(() => this.http.post<SessionResponse>(
        this.url(endpoint),
        body,
        {
          withCredentials: true,
          headers: this.csrfHeader(),
        }
      )),
      switchMap(session => this.applySession(session))
    );
  }

  private applySession(session: SessionResponse): Observable<AuthState> {
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
  }

  private csrfHeader(): Record<string, string> {
    const csrf = this.csrfToken();
    return csrf ? { [csrf.headerName]: csrf.token } : {};
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
    if (!this.gatewayUrl && isPlatformBrowser(this.platformId)) {
      const origin = this.document.defaultView?.location.origin;
      if (origin === 'http://localhost:4200') {
        return `http://localhost:9000${path}`;
      }
    }
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

  private authMessageOrigin(win: Window): string {
    return new URL(this.url('/'), win.location.href).origin;
  }

  private isAuthPopupMessage(data: unknown): data is AuthPopupMessage {
    return typeof data === 'object'
      && data !== null
      && 'type' in data
      && (data as { type?: unknown }).type === 'MSB_AUTH_COMPLETE';
  }

  private clearLegacyBrowserAuthStorage(): void {
    const keys = [
      'accessToken',
      'refreshToken',
      'idToken',
      'token',
      'authToken',
      'jwt',
      'msb.accessToken',
      'msb.refreshToken',
      'msb.idToken',
      'msb.auth',
    ];
    for (const storage of [this.document.defaultView?.localStorage, this.document.defaultView?.sessionStorage]) {
      if (!storage) {
        continue;
      }
      for (const key of keys) {
        storage.removeItem(key);
      }
    }
  }
}
