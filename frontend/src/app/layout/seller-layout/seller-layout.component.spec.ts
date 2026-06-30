import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SellerLayoutComponent } from './seller-layout.component';
import { AuthService } from '../../core/services/auth.service';

describe('SellerLayoutComponent', () => {
  let fixture: ComponentFixture<SellerLayoutComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SellerLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            user: () => ({ email: 'seller@example.com', displayName: 'Seller' }),
            logout: jasmine.createSpy('logout'),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SellerLayoutComponent);
  });

  it('shows business seller navigation without individual, admin, or V2 commerce links', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Business Apply');
    expect(text).toContain('Profile');
    expect(text).not.toContain('New Listing');
    expect(text).not.toContain('Individual Seller');
    expect(text).not.toContain('My Listings');
    expect(text).not.toContain('Business Review');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });
});
