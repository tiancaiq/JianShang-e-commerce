import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { MarketplaceLayoutComponent } from './marketplace-layout.component';

describe('MarketplaceLayoutComponent', () => {
  let fixture: ComponentFixture<MarketplaceLayoutComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarketplaceLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => false,
            ensureSession: () => of({ authenticated: false, user: null }),
            login: jasmine.createSpy('login'),
            logout: jasmine.createSpy('logout'),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MarketplaceLayoutComponent);
  });

  it('does not expose admin navigation on the public marketplace', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Browse');
    expect(text).toContain('Sell');
    expect(text).not.toContain('Admin');
    expect(text).not.toContain('Business Review');
  });

  it('does not expose V2 commerce navigation on the public marketplace', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });

  it('keeps marketplace selling inside account listing creation', () => {
    fixture.detectChanges();

    const sellLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Sell');

    expect(sellLink?.getAttribute('href')).toBe('/account/listings/new');
  });
});
