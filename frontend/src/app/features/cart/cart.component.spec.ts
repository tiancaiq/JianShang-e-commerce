import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CartService } from '../../core/services/cart.service';
import { ListingService } from '../../core/services/listing.service';
import { CartComponent } from './cart.component';

describe('CartComponent', () => {
  let fixture: ComponentFixture<CartComponent>;
  let cartService: any;
  let cart: any;
  let validation: any;

  beforeEach(async () => {
    cart = {
      version: 1,
      expiresAt: '2026-08-16T00:00:00Z',
      itemCount: 1,
      totalQuantity: 2,
      totals: [{ currency: 'USD', amount: 20 }],
      items: [{
        listingId: '01L00000000000000000000001',
        title: 'Store plush',
        thumbnailUrl: null,
        quantity: 2,
        observedPrice: 10,
        currency: 'USD',
        addedAt: '2026-07-17T00:00:00Z',
      }],
    };
    validation = {
      cartVersion: 1,
      validatedAt: '2026-07-18T12:00:00Z',
      checkoutReady: true,
      itemCount: 1,
      totalQuantity: 2,
      validatedTotals: [{ currency: 'USD', amount: 20 }],
      cartIssues: [],
      items: [{
        listingId: cart.items[0].listingId,
        title: cart.items[0].title,
        thumbnailUrl: null,
        requestedQuantity: 2,
        availableQuantity: 5,
        observedPrice: 10,
        currentPrice: 10,
        observedCurrency: 'USD',
        currentCurrency: 'USD',
        status: 'READY',
        issues: [],
      }],
    };
    cartService = jasmine.createSpyObj<CartService>(
      'CartService',
      ['load', 'validate', 'add', 'update', 'remove', 'clear'],
    );
    cartService.cart = signal(cart).asReadonly();
    cartService.validation = signal(validation).asReadonly();
    cartService.count = signal(2).asReadonly();
    cartService.loading = signal(false).asReadonly();
    cartService.validating = signal(false).asReadonly();
    cartService.load.and.returnValue(of(cart));
    cartService.validate.and.returnValue(of(validation));
    cartService.add.and.returnValue(of(cart));
    cartService.update.and.returnValue(of(cart));
    cartService.remove.and.returnValue(of({ ...cart, itemCount: 0, totalQuantity: 0, items: [], totals: [] }));
    cartService.clear.and.returnValue(of({ ...cart, version: 0, itemCount: 0, totalQuantity: 0, items: [], totals: [] }));

    await TestBed.configureTestingModule({
      imports: [CartComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: CartService, useValue: cartService },
        { provide: ListingService, useValue: { mediaUrl: (url: string | null) => url || '' } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CartComponent);
  });

  it('renders observed totals and updates quantity', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Your cart');
    expect(text).toContain('Store plush');
    expect(text).toContain('20.00 USD');
    expect(text).toContain('Ready for checkout');
    expect(cartService.validate).toHaveBeenCalled();

    const increase = fixture.nativeElement.querySelector('[aria-label="Increase quantity"]') as HTMLButtonElement;
    increase.click();

    expect(cartService.update).toHaveBeenCalledOnceWith(
      '01L00000000000000000000001',
      { quantity: 3 },
    );
  });

  it('shows actionable quantity and price warnings', () => {
    const warningValidation = {
      ...validation,
      checkoutReady: false,
      validatedTotals: [],
      items: [{
        ...validation.items[0],
        availableQuantity: 1,
        currentPrice: 12,
        status: 'QUANTITY_REDUCED',
        issues: [
          {
            code: 'CART_QUANTITY_REDUCED',
            message: 'Only 1 is currently available.',
            action: 'SET_AVAILABLE_QUANTITY',
          },
          {
            code: 'CART_PRICE_CHANGED',
            message: 'The price changed.',
            action: 'ACCEPT_CURRENT_PRICE',
          },
        ],
      }],
    };
    cartService.validation = signal(warningValidation).asReadonly();
    cartService.validate.and.returnValue(of(warningValidation));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Needs attention');
    expect(text).toContain('Only 1 is currently available.');
    expect(text).toContain('Accept current price');

    const useAvailable = fixture.nativeElement.querySelector(
      '[aria-label="Use available quantity for Store plush"]',
    ) as HTMLButtonElement;
    useAvailable.click();

    expect(cartService.update).toHaveBeenCalledWith(
      cart.items[0].listingId,
      { quantity: 1 },
    );
  });
});
