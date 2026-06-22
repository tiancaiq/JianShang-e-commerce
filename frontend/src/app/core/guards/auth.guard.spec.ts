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
      ensureSession: () => of({ authenticated: true, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, {} as never)
    );

    expect(await firstValueFrom(result as Observable<boolean | UrlTree>)).toBeTrue();
  });

  it('redirects an unauthenticated BFF session to login', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: false, user: null }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, {} as never)
    );
    const value = await firstValueFrom(result as Observable<boolean | UrlTree>);

    expect(value instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(value as UrlTree)).toBe('/login');
  });

  it('prevents loading a protected frontend route without an authenticated session', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: false, user: null }),
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

    expect(router.url).toBe('/login');
  });

  it('loads a protected frontend route with an authenticated session', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: true, user: null }),
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
