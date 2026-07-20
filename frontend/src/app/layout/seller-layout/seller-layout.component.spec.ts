import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SellerLayoutComponent } from './seller-layout.component';
import { AuthService } from '../../core/services/auth.service';
import { BUSINESS_ORDERS_ENABLED } from '../../features/business/business-orders.capability';

describe('SellerLayoutComponent', () => {
  let fixture: ComponentFixture<SellerLayoutComponent>;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['user', 'logout']);
    authService.user.and.returnValue({ email: 'seller@example.com', displayName: 'Seller' } as ReturnType<AuthService['user']>);
    await TestBed.configureTestingModule({
      imports: [SellerLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: authService,
        },
        { provide: BUSINESS_ORDERS_ENABLED, useValue: false },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SellerLayoutComponent);
  });

  it('shows MVP business navigation without deferred commerce links', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Business Apply');
    expect(text).toContain('Business Account');
    expect(text).toContain('Store Items');
    expect(text).not.toContain('Inventory');
    expect((fixture.nativeElement as HTMLElement).querySelector('a[href="/seller/inventory"]')).toBeNull();
    expect(text).not.toContain('Profile');
    expect(text).not.toContain('New Listing');
    expect(text).not.toContain('Individual Seller');
    expect(text).not.toContain('My Listings');
    expect(text).not.toContain('Business Review');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });

  it('links account management to the business seller account route', () => {
    fixture.detectChanges();

    const accountLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Business Account');

    expect(accountLink?.getAttribute('href')).toBe('/seller/account');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('/account/profile');
  });

  it('shows the business order entry only when explicitly enabled', async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [SellerLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: BUSINESS_ORDERS_ENABLED, useValue: true },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(SellerLayoutComponent);
    fixture.detectChanges();

    const orderLink = (fixture.nativeElement as HTMLElement).querySelector('a[href="/seller/orders"]');
    expect(orderLink?.textContent?.trim()).toBe('Orders');
  });

  it('does not include catalog query parameters in the page title', () => {
    const router = TestBed.inject(Router);
    Object.defineProperty(router, 'url', {
      configurable: true,
      value: '/seller/store/items?q=BUS06-123',
    });

    fixture.detectChanges();

    expect(fixture.componentInstance.pageTitle()).toBe('Items');
  });

  it('uses a stable title instead of exposing the business order identifier', () => {
    const router = TestBed.inject(Router);
    Object.defineProperty(router, 'url', {
      configurable: true,
      value: '/seller/orders/01O00000000000000000000001',
    });

    fixture.detectChanges();

    expect(fixture.componentInstance.pageTitle()).toBe('Order Detail');
  });

  it('logs out through the seller portal surface', () => {
    fixture.detectChanges();

    const logoutButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Logout') as HTMLButtonElement | undefined;

    logoutButton?.click();

    expect(authService.logout).toHaveBeenCalledOnceWith('seller-portal');
  });
});
