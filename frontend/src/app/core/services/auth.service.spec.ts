import { PLATFORM_ID, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
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

  it('redirects login to the gateway login endpoint', () => {
    const assign = jasmine.createSpy('assign');
    const fakeDocument = { defaultView: { location: { assign } } } as unknown as Document;
    const serviceWithFakeDocument = new AuthService(
      TestBed.inject(HttpClient),
      'browser',
      fakeDocument
    );

    serviceWithFakeDocument.login();

    expect(assign).toHaveBeenCalledOnceWith('/api/v1/auth/login');
  });

  it('posts logout through the gateway with the current CSRF parameter', async () => {
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

    service.logout();

    const form = Array.from(document.forms).find(candidate =>
      candidate.getAttribute('action') === '/api/v1/auth/logout'
    );
    expect(form).toBeTruthy();
    expect(form?.method).toBe('post');
    expect(form?.querySelector('input[name="_csrf"]')?.getAttribute('value')).toBe('csrf-token');
    expect(submitSpy).toHaveBeenCalled();

    form?.remove();
  });
});
