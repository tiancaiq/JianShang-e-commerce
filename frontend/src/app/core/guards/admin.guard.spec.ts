import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { Observable, firstValueFrom, of, throwError } from 'rxjs';
import { AdminService } from '../services/admin.service';
import { AuthService } from '../services/auth.service';
import { adminGuard } from './admin.guard';

@Component({
  standalone: true,
  template: 'admin',
})
class TestAdminComponent {}

@Component({
  standalone: true,
  template: 'login',
})
class TestLoginComponent {}

@Component({
  standalone: true,
  template: 'marketplace',
})
class TestMarketplaceComponent {}

describe('adminGuard', () => {
  it('redirects unauthenticated users to login', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: false, user: null }),
    };
    const adminService = jasmine.createSpyObj<AdminService>('AdminService', ['getCurrentAdmin']);

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as never, { url: '/admin/dashboard' } as never)
    );
    const value = await firstValueFrom(result as Observable<boolean | UrlTree>);

    expect(value instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(value as UrlTree))
      .toBe('/login?client=admin-portal&returnUrl=%2Fadmin%2Fdashboard');
    expect(adminService.getCurrentAdmin).not.toHaveBeenCalled();
  });

  it('allows authenticated platform admins', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: true, user: null }),
    };
    const adminService = {
      getCurrentAdmin: () => of({ data: { userId: '01ADMIN', role: 'PLATFORM_ADMIN' as const } }),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as never, {} as never)
    );

    expect(await firstValueFrom(result as Observable<boolean | UrlTree>)).toBeTrue();
  });

  it('redirects authenticated non-admin users to admin access denied', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: true, user: null }),
    };
    const adminService = {
      getCurrentAdmin: () => throwError(() => ({ status: 403 })),
      clearCurrentAdmin: () => undefined,
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
      ],
    });

    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as never, { url: '/admin/dashboard' } as never)
    );
    const value = await firstValueFrom(result as Observable<boolean | UrlTree>);

    expect(value instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(value as UrlTree))
      .toBe('/admin-access-denied?returnUrl=%2Fadmin%2Fdashboard');
  });

  it('prevents login loops for authenticated non-admin users', async () => {
    const authService = {
      ensureSession: () => of({ authenticated: true, user: null }),
    };
    const adminService = {
      getCurrentAdmin: () => throwError(() => ({ status: 403 })),
      clearCurrentAdmin: () => undefined,
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([
          { path: '', component: TestMarketplaceComponent },
          { path: 'login', component: TestLoginComponent },
          { path: 'admin-access-denied', component: TestLoginComponent },
          { path: 'admin', component: TestAdminComponent, canActivate: [adminGuard] },
        ]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
      ],
    });

    const router = TestBed.inject(Router);

    await router.navigateByUrl('/admin');

    expect(router.url).toBe('/admin-access-denied?returnUrl=%2Fadmin');
  });
});
