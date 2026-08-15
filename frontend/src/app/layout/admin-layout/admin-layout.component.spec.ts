import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AdminLayoutComponent } from './admin-layout.component';
import { ADMIN_SEARCH_MAINTENANCE_ENABLED } from '../../features/admin/admin-search-maintenance.capability';
import { AdminService } from '../../core/services/admin.service';

describe('AdminLayoutComponent', () => {
  let fixture: ComponentFixture<AdminLayoutComponent>;
  let authService: jasmine.SpyObj<AuthService>;
  let adminService: jasmine.SpyObj<AdminService>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);
    adminService = jasmine.createSpyObj<AdminService>(
      'AdminService', ['hasPermission', 'hasRole', 'clearCurrentAdmin']);
    adminService.hasPermission.and.returnValue(true);
    adminService.hasRole.and.returnValue(true);
    await TestBed.configureTestingModule({
      imports: [AdminLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: false },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminLayoutComponent);
    fixture.detectChanges();
  });

  it('shows only MVP admin navigation', () => {
    const text = fixture.nativeElement.textContent;

    expect(text).toContain('Dashboard');
    expect(text).toContain('Users');
    expect(text).not.toContain('User Control');
    expect(text).toContain('Business Review');
    expect(text).toContain('Listing Review');
    expect(text).not.toContain('Category Guidance');
    expect(text).not.toContain('Search Maintenance');
    expect(text).not.toContain('Reports');
    expect(text).not.toContain('Support');
    expect(text).not.toContain('Suspensions');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });

  it('shows search maintenance navigation only after explicit capability opt-in', async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [AdminLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: true },
      ],
    }).compileComponents();
    const enabledFixture = TestBed.createComponent(AdminLayoutComponent);
    enabledFixture.detectChanges();

    const link = Array.from(
      (enabledFixture.nativeElement as HTMLElement).querySelectorAll('a'),
    ).find(anchor => anchor.textContent?.trim() === 'Search Maintenance');
    expect(link?.getAttribute('href')).toBe('/admin/search-maintenance');
  });

  it('shows only sections allowed by effective permissions', () => {
    fixture.destroy();
    adminService.hasPermission.and.callFake(permission =>
      permission === 'admin.dashboard.read' || permission === 'admin.business.application.read');
    adminService.hasRole.and.returnValue(false);
    fixture = TestBed.createComponent(AdminLayoutComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Dashboard');
    expect(text).toContain('Business Review');
    expect(text).not.toContain('Listing Review');
    expect(text).not.toContain('Search Maintenance');
  });

  it('logs out through the admin portal surface', () => {
    const logoutButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Logout') as HTMLButtonElement | undefined;

    logoutButton?.click();

    expect(authService.logout).toHaveBeenCalledOnceWith('admin-portal');
    expect(adminService.clearCurrentAdmin).toHaveBeenCalled();
  });

  it('exposes a collapsed mobile navigation disclosure', () => {
    const toggle = (fixture.nativeElement as HTMLElement).querySelector('.menu-toggle') as HTMLButtonElement;

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect((fixture.nativeElement as HTMLElement).querySelector('.admin-sidebar')?.classList).toContain('menu-open');
  });
});
