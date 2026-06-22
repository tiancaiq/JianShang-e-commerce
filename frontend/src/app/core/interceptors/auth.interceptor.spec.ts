import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../services/auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            csrf: () => ({
              headerName: 'X-CSRF-TOKEN',
              parameterName: '_csrf',
              token: 'csrf-token',
            }),
          },
        },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('sends gateway requests with cookies and without bearer tokens', () => {
    http.get('http://localhost:9000/api/v1/users/me').subscribe();

    const request = httpMock.expectOne('http://localhost:9000/api/v1/users/me');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBeFalse();
    request.flush({ data: null });
  });

  it('adds the BFF CSRF header to state-changing gateway requests', () => {
    http.post('http://localhost:9000/api/v1/example', {}).subscribe();

    const request = httpMock.expectOne('http://localhost:9000/api/v1/example');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });

  it('leaves non-gateway requests untouched', () => {
    http.post('http://example.test/api/v1/example', {}).subscribe();

    const request = httpMock.expectOne('http://example.test/api/v1/example');
    expect(request.request.withCredentials).toBeFalse();
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBeFalse();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });
});
