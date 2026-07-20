import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AdminLayoutComponent } from './admin-layout.component';

describe('AdminLayoutComponent', () => {
  let fixture: ComponentFixture<AdminLayoutComponent>;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);
    await TestBed.configureTestingModule({
      imports: [AdminLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminLayoutComponent);
    fixture.detectChanges();
  });

  it('shows only MVP admin navigation', () => {
    const text = fixture.nativeElement.textContent;

    expect(text).toContain('Dashboard');
    expect(text).toContain('Business Review');
    expect(text).toContain('Listing Review');
    expect(text).not.toContain('Category Guidance');
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

  it('logs out through the admin portal surface', () => {
    const logoutButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Logout') as HTMLButtonElement | undefined;

    logoutButton?.click();

    expect(authService.logout).toHaveBeenCalledOnceWith('admin-portal');
  });
});
