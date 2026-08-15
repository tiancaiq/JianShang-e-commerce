import { PLATFORM_ID, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { CurrentUser } from '../models/auth.model';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PLATFORM_ID, useValue: 'browser' },
      ],
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.stopSessionActivityMonitor();
    httpMock.verify();
  });

  it('loads an unauthenticated gateway session without reading browser token storage', async () => {
    const getItemSpy = spyOn(localStorage, 'getItem').and.callThrough();
    const setItemSpy = spyOn(localStorage, 'setItem').and.callThrough();

    const statePromise = firstValueFrom(service.ensureSession());
    const sessionRequest = httpMock.expectOne('/api/v1/auth/session');

    expect(sessionRequest.request.method).toBe('GET');
    expect(sessionRequest.request.withCredentials).toBeTrue();

    sessionRequest.flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
    httpMock.expectNone('/api/v1/users/me');
    expect(getItemSpy).not.toHaveBeenCalled();
    expect(setItemSpy).not.toHaveBeenCalled();
  });

  it('loads the identity user when the gateway session is authenticated', async () => {
    const statePromise = firstValueFrom(service.ensureSession());

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: true,
      user: {
        subject: 'keycloak-sub-1',
        email: 'buyer@example.com',
        displayName: 'Buyer One',
        roles: ['buyer'],
        expiresAt: '2026-06-16T12:00:00Z',
      },
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    const userRequest = httpMock.expectOne('/api/v1/users/me');
    expect(userRequest.request.method).toBe('GET');
    expect(userRequest.request.withCredentials).toBeTrue();
    userRequest.flush({
      data: {
        id: '01JY0000000000000000000000',
        keycloakSub: 'keycloak-sub-1',
        email: 'buyer@example.com',
        emailVerified: true,
        displayName: 'Buyer One',
        phone: null,
        phoneVerified: false,
        avatarUrl: null,
        status: 'ACTIVE',
        version: 0,
        createdAt: '2026-06-16T12:00:00Z',
        updatedAt: '2026-06-16T12:00:00Z',
      },
    });

    const state = await statePromise;
    expect(state.authenticated).toBeTrue();
    expect(state.user?.email).toBe('buyer@example.com');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('refreshes the gateway session on throttled browser activity for signed-in users', () => {
    spyOn(Date, 'now').and.returnValues(1000, 2000);
    spyOnProperty(document, 'visibilityState', 'get').and.returnValue('visible');
    service.setCurrentUser(currentUserFixture());

    service.startSessionActivityMonitor();
    window.dispatchEvent(new Event('pointerdown'));

    const sessionRequest = httpMock.expectOne('/api/v1/auth/session');
    expect(sessionRequest.request.method).toBe('GET');
    expect(sessionRequest.request.withCredentials).toBeTrue();
    sessionRequest.flush({
      authenticated: true,
      user: {
        subject: 'keycloak-sub-1',
        email: 'buyer@example.com',
        displayName: 'Buyer One',
        roles: ['buyer'],
        expiresAt: '2026-06-16T12:00:00Z',
      },
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });
    httpMock.expectOne('/api/v1/users/me').flush({ data: currentUserFixture() });

    window.dispatchEvent(new Event('keydown'));

    expect(service.isAuthenticated()).toBeTrue();
    httpMock.expectNone('/api/v1/auth/session');
  });

  it('redirects login to the marketplace gateway login endpoint by default', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.login();

    expect(assign).toHaveBeenCalledOnceWith('/api/v1/auth/login?client=marketplace');
  });

  it('keeps production-style local frontend requests same-origin when no gateway URL is configured', async () => {
    const fakeDocument = {
      defaultView: {
        location: { origin: 'http://localhost:4200' },
      },
    } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    const statePromise = firstValueFrom(serviceWithFakeDocument.ensureSession());
    const sessionRequest = httpMock.expectOne('/api/v1/auth/session');
    expect(sessionRequest.request.method).toBe('GET');
    sessionRequest.flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
  });

  it('redirects login with selected client and safe return URL', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.login('seller-portal', '/seller/business/apply?draft=1');

    expect(assign).toHaveBeenCalledOnceWith(
      '/api/v1/auth/login?client=seller-portal&returnUrl=%2Fseller%2Fbusiness%2Fapply%3Fdraft%3D1'
    );
  });

  it('drops unsafe login return URLs', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.login('admin-portal', 'https://evil.example/admin');

    expect(assign).toHaveBeenCalledOnceWith('/api/v1/auth/login?client=admin-portal');
  });

  it('redirects registration through the gateway registration endpoint', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.register('marketplace', '/account/profile');

    expect(assign).toHaveBeenCalledOnceWith(
      '/api/v1/auth/register?client=marketplace&returnUrl=%2Faccount%2Fprofile'
    );
  });

  it('drops unsafe registration return URLs', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.register('marketplace', '//evil.example/account');

    expect(assign).toHaveBeenCalledOnceWith('/api/v1/auth/register?client=marketplace');
  });

  it('opens marketplace login in a popup and refreshes the session on callback', async () => {
    const popup = { closed: false } as Window;
    const openSpy = spyOn(window, 'open').and.returnValue(popup);

    const statePromise = firstValueFrom(service.loginWithPopup('marketplace', '/account/profile'));

    expect(openSpy).toHaveBeenCalledOnceWith(
      '/api/v1/auth/login?client=marketplace&returnUrl=%2Faccount%2Fprofile&mode=popup',
      'msb-auth',
      jasmine.stringContaining('width=520')
    );

    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      data: { type: 'MSB_AUTH_COMPLETE', returnUrl: '/account/profile' },
    }));

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
  });

  it('opens marketplace Google login in a popup with the provider hint', async () => {
    const popup = { closed: false } as Window;
    const openSpy = spyOn(window, 'open').and.returnValue(popup);

    const statePromise = firstValueFrom(service.loginWithGooglePopup('marketplace', '/account/profile'));

    expect(openSpy).toHaveBeenCalledOnceWith(
      '/api/v1/auth/login?client=marketplace&returnUrl=%2Faccount%2Fprofile&provider=google&mode=popup',
      'msb-auth',
      jasmine.stringContaining('width=520')
    );

    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      data: { type: 'MSB_AUTH_COMPLETE', returnUrl: '/account/profile' },
    }));

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
  });

  it('drops unsafe popup return URLs before opening login', async () => {
    const popup = { closed: false } as Window;
    const openSpy = spyOn(window, 'open').and.returnValue(popup);

    const statePromise = firstValueFrom(service.loginWithPopup('marketplace', 'https://evil.example/account'));

    expect(openSpy).toHaveBeenCalledOnceWith(
      '/api/v1/auth/login?client=marketplace&mode=popup',
      'msb-auth',
      jasmine.stringContaining('width=520')
    );

    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      data: { type: 'MSB_AUTH_COMPLETE', returnUrl: 'https://evil.example/account' },
    }));

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
  });

  it('opens marketplace registration in a popup', async () => {
    const popup = { closed: false } as Window;
    const openSpy = spyOn(window, 'open').and.returnValue(popup);

    const statePromise = firstValueFrom(service.registerWithPopup('marketplace', '/account/profile'));

    expect(openSpy).toHaveBeenCalledOnceWith(
      '/api/v1/auth/register?client=marketplace&returnUrl=%2Faccount%2Fprofile&mode=popup',
      'msb-auth',
      jasmine.stringContaining('width=520')
    );

    window.dispatchEvent(new MessageEvent('message', {
      origin: window.location.origin,
      data: { type: 'MSB_AUTH_COMPLETE', returnUrl: '/account/profile' },
    }));

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    expect(await statePromise).toEqual({ authenticated: false, user: null });
  });

  it('posts native login through the gateway and applies the authenticated response before logout', async () => {
    const statePromise = firstValueFrom(service.nativeLogin({
      email: 'buyer@example.com',
      password: 'password-123',
    }));

    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });

    const loginRequest = httpMock.expectOne('/api/v1/auth/native/login');
    expect(loginRequest.request.method).toBe('POST');
    expect(loginRequest.request.withCredentials).toBeTrue();
    expect(loginRequest.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
    expect(loginRequest.request.body).toEqual({
      email: 'buyer@example.com',
      password: 'password-123',
    });
    loginRequest.flush({
      authenticated: true,
      user: {
        subject: 'keycloak-sub-1',
        email: 'buyer@example.com',
        displayName: 'Buyer One',
        roles: ['BUYER'],
        expiresAt: '2026-06-16T12:00:00Z',
      },
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token-2',
      },
    });

    httpMock.expectOne('/api/v1/users/me').flush({
      data: {
        id: '01JY0000000000000000000000',
        keycloakSub: 'keycloak-sub-1',
        email: 'buyer@example.com',
        emailVerified: true,
        displayName: 'Buyer One',
        phone: null,
        phoneVerified: false,
        avatarUrl: null,
        status: 'ACTIVE',
        version: 0,
        createdAt: '2026-06-16T12:00:00Z',
        updatedAt: '2026-06-16T12:00:00Z',
      },
    });

    expect((await statePromise).authenticated).toBeTrue();

    const submitSpy = spyOn(HTMLFormElement.prototype, 'submit').and.stub();
    expect(service.logout('marketplace')).toBeTrue();
    const form = Array.from(document.forms).find(candidate =>
      candidate.getAttribute('action') === '/api/v1/auth/logout'
    );
    expect(form?.parentElement).toBe(document.body);
    expect(form?.querySelector('input[name="_csrf"]')?.getAttribute('value')).toBe('csrf-token-2');
    expect(form?.querySelector('input[name="client"]')?.getAttribute('value')).toBe('marketplace');
    expect(submitSpy).toHaveBeenCalled();
    form?.remove();
  });

  it('refreshes CSRF before native registration and applies the authenticated response', async () => {
    const statePromise = firstValueFrom(service.nativeRegister({
      email: 'new@example.com',
      password: 'password-123',
      displayName: 'New Buyer',
    }));

    const sessionRequest = httpMock.expectOne('/api/v1/auth/session');
    expect(sessionRequest.request.method).toBe('GET');
    sessionRequest.flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'fresh-csrf-token',
      },
    });

    const registerRequest = httpMock.expectOne('/api/v1/auth/native/register');
    expect(registerRequest.request.method).toBe('POST');
    expect(registerRequest.request.withCredentials).toBeTrue();
    expect(registerRequest.request.headers.get('X-CSRF-TOKEN')).toBe('fresh-csrf-token');
    expect(registerRequest.request.body).toEqual({
      email: 'new@example.com',
      password: 'password-123',
      displayName: 'New Buyer',
    });
    registerRequest.flush({
      authenticated: true,
      user: {
        subject: 'keycloak-sub-2',
        email: 'new@example.com',
        displayName: 'New Buyer',
        roles: ['BUYER'],
        expiresAt: '2026-06-16T12:00:00Z',
      },
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token-2',
      },
    });

    httpMock.expectOne('/api/v1/users/me').flush({
      data: {
        id: '01JY0000000000000000000001',
        keycloakSub: 'keycloak-sub-2',
        email: 'new@example.com',
        emailVerified: true,
        displayName: 'New Buyer',
        phone: null,
        phoneVerified: false,
        avatarUrl: null,
        status: 'ACTIVE',
        version: 0,
        createdAt: '2026-06-16T12:00:00Z',
        updatedAt: '2026-06-16T12:00:00Z',
      },
    });

    expect((await statePromise).authenticated).toBeTrue();
  });

  it('posts logout through the gateway with the current CSRF parameter and client hint', async () => {
    const statePromise = firstValueFrom(service.ensureSession());
    httpMock.expectOne('/api/v1/auth/session').flush({
      authenticated: false,
      user: null,
      csrf: {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token',
      },
    });
    await statePromise;

    const submitSpy = spyOn(HTMLFormElement.prototype, 'submit').and.stub();
    localStorage.setItem('accessToken', 'legacy-access-token');
    sessionStorage.setItem('refreshToken', 'legacy-refresh-token');
    localStorage.setItem('saved-filter', 'keep-user-preference');

    expect(service.logout('admin-portal')).toBeTrue();

    const form = Array.from(document.forms).find(candidate =>
      candidate.getAttribute('action') === '/api/v1/auth/logout'
    );
    expect(form).toBeTruthy();
    expect(form?.parentElement).toBe(document.body);
    expect(form?.method).toBe('post');
    expect(form?.querySelector('input[name="_csrf"]')?.getAttribute('value')).toBe('csrf-token');
    expect(form?.querySelector('input[name="client"]')?.getAttribute('value')).toBe('admin-portal');
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(sessionStorage.getItem('refreshToken')).toBeNull();
    expect(localStorage.getItem('saved-filter')).toBe('keep-user-preference');
    expect(submitSpy).toHaveBeenCalled();

    localStorage.removeItem('saved-filter');
    form?.remove();
  });

  it('keeps in-memory user state when logout form submission cannot start', async () => {
    service.setCurrentUser(currentUserFixture());
    const submitSpy = spyOn(HTMLFormElement.prototype, 'submit').and.throwError('submit blocked');

    expect(service.logout('marketplace')).toBeFalse();

    expect(service.isAuthenticated()).toBeTrue();
    expect(submitSpy).toHaveBeenCalled();
    expect(Array.from(document.forms).some(candidate =>
      candidate.getAttribute('action') === '/api/v1/auth/logout'
    )).toBeFalse();
  });
});

function currentUserFixture(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: '01JY0000000000000000000000',
    keycloakSub: 'keycloak-sub-1',
    email: 'buyer@example.com',
    emailVerified: true,
    displayName: 'Buyer One',
    phone: null,
    phoneVerified: false,
    avatarUrl: null,
    status: 'ACTIVE',
    version: 0,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
    ...overrides,
  };
}
