import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { Observable, firstValueFrom, of } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { authGuard } from './auth.guard';

@Component({
  standalone: true,
  template: 'dashboard',
})
class TestDashboardComponent {}

@Component({
  standalone: true,
  template: 'login',
})
class TestLoginComponent {}

describe('authGuard', () => {
  it('allows an authenticated BFF session', async () => {
    const authService = {
      refreshSession: () => of({ authenticated: true, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/dashboard' } as never)
    );

    expect(await firstValueFrom(result as Observable<boolean | UrlTree>)).toBeTrue();
  });

  it('redirects an unauthenticated BFF session to login', async () => {
    const authService = {
      refreshSession: () => of({ authenticated: false, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/dashboard' } as never)
    );
    const value = await firstValueFrom(result as Observable<boolean | UrlTree>);

    expect(value instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(value as UrlTree))
      .toBe('/login?client=marketplace&returnUrl=%2Fdashboard');
  });

  it('uses seller and admin login clients for matching protected route groups', async () => {
    const authService = {
      refreshSession: () => of({ authenticated: false, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const sellerResult = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/seller/business/apply' } as never)
    );
    const adminResult = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/admin/business-applications' } as never)
    );

    const router = TestBed.inject(Router);
    expect(router.serializeUrl(await firstValueFrom(sellerResult as Observable<UrlTree>)))
      .toBe('/login?client=seller-portal&returnUrl=%2Fseller%2Fbusiness%2Fapply');
    expect(router.serializeUrl(await firstValueFrom(adminResult as Observable<UrlTree>)))
      .toBe('/login?client=admin-portal&returnUrl=%2Fadmin%2Fbusiness-applications');
  });

  it('prevents loading a protected frontend route without an authenticated session', async () => {
    const authService = {
      refreshSession: () => of({ authenticated: false, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([
          { path: 'login', component: TestLoginComponent },
          { path: 'dashboard', component: TestDashboardComponent, canActivate: [authGuard] },
        ]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const router = TestBed.inject(Router);

    await router.navigateByUrl('/dashboard');

    expect(router.url).toBe('/login?client=marketplace&returnUrl=%2Fdashboard');
  });

  it('requires authentication for protected account routes after a full reload finds no session', async () => {
    const authService = {
      refreshSession: jasmine.createSpy('refreshSession').and.returnValue(of({ authenticated: false, user: null })),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([
          { path: 'login', component: TestLoginComponent },
          { path: 'account/profile', component: TestDashboardComponent, canActivate: [authGuard] },
        ]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const router = TestBed.inject(Router);

    await router.navigateByUrl('/account/profile');

    expect(authService.refreshSession).toHaveBeenCalled();
    expect(router.url).toBe('/login?client=marketplace&returnUrl=%2Faccount%2Fprofile');
  });

  it('loads a protected frontend route with an authenticated session', async () => {
    const authService = {
      refreshSession: () => of({ authenticated: true, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([
          { path: 'login', component: TestLoginComponent },
          { path: 'dashboard', component: TestDashboardComponent, canActivate: [authGuard] },
        ]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const router = TestBed.inject(Router);

    await router.navigateByUrl('/dashboard');

    expect(router.url).toBe('/dashboard');
  });
});
