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
      'nativeLogin',
      'nativeRegister',
    ]);
    authService.ensureSession.and.returnValue(of({ authenticated: false, user: null }));
    authService.loginWithPopup.and.returnValue(of({ authenticated: false, user: null }));
    authService.registerWithPopup.and.returnValue(of({ authenticated: false, user: null }));
    authService.nativeLogin.and.returnValue(of({ authenticated: false, user: null }));
    authService.nativeRegister.and.returnValue(of({ authenticated: false, user: null }));

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

    expect(text).toContain('Use your marketplace account to continue.');
    expect(text).toContain('Create account');
    expect(text).not.toContain('Continue to sign in');
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

  it('uses native marketplace sign-in without opening Keycloak', () => {
    const { fixture, authService } = createFixture({
      client: 'marketplace',
      returnUrl: '/account/profile',
    });

    fixture.componentInstance.authEmail = 'buyer@example.test';
    fixture.componentInstance.authPassword = 'not-a-real-password';
    fixture.componentInstance.submitNativeAuth();

    expect(authService.nativeLogin).toHaveBeenCalledOnceWith({
      email: 'buyer@example.test',
      password: 'not-a-real-password',
    });
    expect(authService.loginWithPopup).not.toHaveBeenCalled();
    expect(authService.login).not.toHaveBeenCalled();
  });

  it('uses native registration and returns to the protected destination', () => {
    const { fixture, authService } = createFixture({
      client: 'marketplace',
      returnUrl: '/account/messages/agent',
    });
    const router = TestBed.inject(Router) as jasmine.SpyObj<Router>;
    authService.nativeRegister.and.returnValue(of({
      authenticated: true,
      user: null,
    }));

    fixture.componentInstance.setAuthMode('register');
    fixture.componentInstance.authDisplayName = 'New User';
    fixture.componentInstance.authEmail = 'new@example.test';
    fixture.componentInstance.authPassword = 'not-a-real-password';
    fixture.componentInstance.submitNativeAuth();

    expect(authService.nativeRegister).toHaveBeenCalledOnceWith({
      email: 'new@example.test',
      password: 'not-a-real-password',
      displayName: 'New User',
    });
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/account/messages/agent');
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

  it('returns admin portal sign-in to the dashboard when no return URL is supplied', () => {
    const { fixture, authService } = createFixture({
      client: 'admin-portal',
      signedOut: '1',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.login).toHaveBeenCalledOnceWith('admin-portal', '/admin/dashboard');
  });

  it('drops unsafe return URLs before redirect login', () => {
    const { fixture, authService } = createFixture({
      client: 'admin-portal',
      returnUrl: 'https://evil.example/admin',
    });

    fixture.componentInstance.handleLogin();

    expect(authService.login).toHaveBeenCalledOnceWith('admin-portal', '/admin/dashboard');
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
