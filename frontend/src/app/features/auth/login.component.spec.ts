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
    ]);
    authService.ensureSession.and.returnValue(of({ authenticated: false, user: null }));

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
    expect(text).toContain('Create account');
  });

  it('starts registration with the selected client and safe return URL', () => {
    const { fixture, authService } = createFixture({
      client: 'seller-portal',
      returnUrl: '/seller/business/apply',
    });

    fixture.componentInstance.handleRegister();

    expect(authService.register).toHaveBeenCalledOnceWith('seller-portal', '/seller/business/apply');
  });

  it('does not advertise self-registration for admin sign-in', () => {
    const { fixture } = createFixture({
      client: 'admin-portal',
      returnUrl: '/admin/business-applications',
    });

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).not.toContain('Create account');
  });
});
