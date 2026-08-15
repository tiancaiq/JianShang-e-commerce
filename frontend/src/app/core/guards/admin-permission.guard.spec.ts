import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { AdminService } from '../services/admin.service';
import { ADMIN_PERMISSIONS } from '../security/admin-permissions';
import { adminPermissionGuard } from './admin-permission.guard';

describe('adminPermissionGuard', () => {
  it('allows a route when the effective permission is present', () => {
    const adminService = {
      currentAdmin: () => ({ userId: '01ADMIN' }),
      hasPermission: (permission: string) => permission === ADMIN_PERMISSIONS.BUSINESS_APPLICATION_READ,
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminService, useValue: adminService },
      ],
    });

    const result = TestBed.runInInjectionContext(() => adminPermissionGuard(
      { data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_APPLICATION_READ } } as never,
      { url: '/admin/business-applications' } as never,
    ));

    expect(result).toBeTrue();
  });

  it('returns the explicit denied route when permission is absent', () => {
    const adminService = {
      currentAdmin: () => ({ userId: '01AUDITOR' }),
      hasPermission: () => false,
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminService, useValue: adminService },
      ],
    });

    const result = TestBed.runInInjectionContext(() => adminPermissionGuard(
      { data: { adminPermission: ADMIN_PERMISSIONS.BUSINESS_APPLICATION_DECIDE } } as never,
      { url: '/admin/business-applications/01APP' } as never,
    ));

    expect(result instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree))
      .toBe('/admin-access-denied?returnUrl=%2Fadmin%2Fbusiness-applications%2F01APP');
  });
});
