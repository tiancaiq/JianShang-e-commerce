import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UserProfileService } from './user-profile.service';
import { AuthService } from './auth.service';
import { CurrentUser } from '../models/user.model';

describe('UserProfileService', () => {
  let service: UserProfileService;
  let httpMock: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;

  const user: CurrentUser = {
    id: '01JY0000000000000000000000',
    keycloakSub: 'keycloak-sub',
    email: 'alex@example.com',
    emailVerified: true,
    displayName: 'Alex',
    phone: null,
    phoneVerified: false,
    avatarUrl: null,
    status: 'ACTIVE',
    version: 3,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['setCurrentUser']);

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
      ],
    });

    service = TestBed.inject(UserProfileService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads the current profile through the gateway', () => {
    service.getMe().subscribe(response => {
      expect(response.data).toEqual(user);
    });

    const request = httpMock.expectOne('/api/v1/users/me');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: user });

    expect(authService.setCurrentUser).toHaveBeenCalledOnceWith(user);
  });

  it('updates only allowed profile fields with the current version', () => {
    service.updateMe({
      displayName: 'Alex Profile',
      phone: '+19495551234',
      avatarUrl: 'https://example.com/avatar.png',
    }, 3).subscribe(response => {
      expect(response.data).toEqual(user);
    });

    const request = httpMock.expectOne('/api/v1/users/me');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('If-Match')).toBe('3');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      displayName: 'Alex Profile',
      phone: '+19495551234',
      avatarUrl: 'https://example.com/avatar.png',
    });
    request.flush({ data: user });

    expect(authService.setCurrentUser).toHaveBeenCalledOnceWith(user);
  });
});
