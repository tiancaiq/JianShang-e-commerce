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

  it('shows seller navigation without admin or V2 commerce links', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('New Listing');
    expect(text).toContain('Individual Seller');
    expect(text).toContain('Business Apply');
    expect(text).not.toContain('Business Review');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
  });
});
