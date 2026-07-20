import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  function createFixture(queryParams: Record<string, string> = {}): {
    fixture: ComponentFixture<LoginComponent>;
    authService: jasmine.SpyObj<AuthService>;
  } {
    const authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'ensureSession',
      'login',
      'register',
      'loginWithPopup',
      'registerWithPopup',
    ]);
    authService.ensureSession.and.returnValue(of({ authenticated: false, user: null }));
    authService.loginWithPopup.and.returnValue(of({ authenticated: false, user: null }));
    authService.registerWithPopup.and.returnValue(of({ authenticated: false, user: null }));

    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AuthService, useValue: authService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(queryParams),
            },
          },
        },
        {
          provide: Router,
          useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']),
        },
      ],
    });

    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    return { fixture, authService };
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('offers account creation for marketplace sign-in', () => {
    const { fixture } = createFixture({
      client: 'marketplace',
      returnUrl: '/account/profile',
    });

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Sign in or create your MSB account');
    expect(text).toContain('Marketplace Account');
    expect(text).toContain('Create account');
  });

  it('shows signed-out confirmation on login surfaces', () => {
    const { fixture } = createFixture({
      client: 'admin-portal',
      signedOut: '1',
    });

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Admin Portal');
    expect(text).toContain('Signed out successfully');
  });

  it('uses the marketplace popup flow for marketplace sign-in', () => {
    const { fixture, authService } = createFixture({
      client: 'marketplace',
      returnUrl: '/account/profile',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.loginWithPopup).toHaveBeenCalledOnceWith('marketplace', '/account/profile');
    expect(authService.login).not.toHaveBeenCalled();
  });

  it('uses regular Keycloak redirect for seller portal sign-in', () => {
    const { fixture, authService } = createFixture({
      client: 'seller-portal',
      returnUrl: '/seller/business/apply',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.login).toHaveBeenCalledOnceWith('seller-portal', '/seller/business/apply');
    expect(authService.loginWithPopup).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent || '').toContain('Business Seller Portal');
  });

  it('uses regular Keycloak redirect for admin portal sign-in', () => {
    const { fixture, authService } = createFixture({
      client: 'admin-portal',
      returnUrl: '/admin/business-applications',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.login).toHaveBeenCalledOnceWith('admin-portal', '/admin/business-applications');
    expect(authService.loginWithPopup).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent || '').toContain('Admin Portal');
    expect((fixture.nativeElement as HTMLElement).textContent || '').toContain('Secure staff sign-in');
  });

  it('drops unsafe return URLs before redirect login', () => {
    const { fixture, authService } = createFixture({
      client: 'admin-portal',
      returnUrl: 'https://evil.example/admin',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.login).toHaveBeenCalledOnceWith('admin-portal', '/');
  });

  it('does not advertise self-registration for seller or admin sign-in', () => {
    const seller = createFixture({
      client: 'seller-portal',
      returnUrl: '/seller/business/apply',
    });
    expect((seller.fixture.nativeElement as HTMLElement).textContent || '').not.toContain('Create account');

    TestBed.resetTestingModule();

    const { fixture } = createFixture({
      client: 'admin-portal',
      returnUrl: '/admin/business-applications',
    });

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).not.toContain('Create account');
  });
});
