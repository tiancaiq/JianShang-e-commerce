import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
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
        thumbnailUrl: '/api/v1/public/listing-media/01M00000000000000000000001',
        storeName: 'Mochi Store',
        storeSlug: 'mochi-store',
        businessVerified: true,
        publicCity: 'Irvine',
        publicRegion: 'CA',
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
        thumbnailUrl: cart.items[0].thumbnailUrl,
        storeName: cart.items[0].storeName,
        storeSlug: cart.items[0].storeSlug,
        businessVerified: cart.items[0].businessVerified,
        publicCity: cart.items[0].publicCity,
        publicRegion: cart.items[0].publicRegion,
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
        { provide: ListingService, useValue: { mediaUrl: (url: string | null) => url ? `media:${url}` : '' } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CartComponent);
  });

  it('renders the cart as store packing cards and updates quantity', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Your cart');
    expect(text).toContain('Store plush');
    expect(text).toContain('Mochi Store');
    expect(text).toContain('Verified');
    expect(text).toContain('Irvine, CA');
    expect(text).toContain('20.00 USD');
    expect(text).toContain('Cart checks passed');
    expect(text).toContain('Current subtotal');
    expect(text).toContain('Checkout is not enabled yet.');
    expect(fixture.nativeElement.querySelector('.packing-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.receipt-rail')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/stores/mochi-store"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('img')?.getAttribute('alt')).toBe('Store plush');
    expect(fixture.nativeElement.querySelector('img')?.getAttribute('src')).toBe(
      'media:/api/v1/public/listing-media/01M00000000000000000000001',
    );
    expect(fixture.nativeElement.querySelector('a[href="/checkout"]')).toBeNull();
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
    expect(text).toContain('Quantity needs review');
    expect(text).toContain('Only 1 is currently available.');
    expect(text).toContain('Accept current price');
    expect(text).toContain('Use the repair actions on the affected items.');
    expect(cartService.add).not.toHaveBeenCalled();
    expect(cartService.update).not.toHaveBeenCalled();
    expect(cartService.remove).not.toHaveBeenCalled();

    const useAvailable = fixture.nativeElement.querySelector(
      '[aria-label="Use available quantity for Store plush"]',
    ) as HTMLButtonElement;
    useAvailable.click();

    expect(cartService.update).toHaveBeenCalledWith(
      cart.items[0].listingId,
      { quantity: 1 },
    );
  });

  it('accepts a current price only after an explicit repair click', () => {
    const priceValidation = {
      ...validation,
      checkoutReady: false,
      validatedTotals: [],
      items: [{
        ...validation.items[0],
        currentPrice: 12,
        status: 'PRICE_CHANGED',
        issues: [{
          code: 'CART_PRICE_CHANGED',
          message: 'The price changed from 10.00 USD to 12.00 USD.',
          action: 'ACCEPT_CURRENT_PRICE',
        }],
      }],
    };
    cartService.validation = signal(priceValidation).asReadonly();
    cartService.validate.and.returnValue(of(priceValidation));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('12.00 USD current price');
    expect(cartService.add).not.toHaveBeenCalled();

    const accept = fixture.nativeElement.querySelector(
      '[aria-label="Accept current price for Store plush"]',
    ) as HTMLButtonElement;
    accept.click();

    expect(cartService.add).toHaveBeenCalledOnceWith({
      listingId: cart.items[0].listingId,
      quantity: 2,
    });
  });

  it('runs remove and clear only from explicit buyer actions', () => {
    fixture.detectChanges();

    const remove = fixture.nativeElement.querySelector(
      '[aria-label="Remove Store plush"]',
    ) as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();

    expect(cartService.remove).toHaveBeenCalledOnceWith(cart.items[0].listingId);
    expect(cartService.clear).not.toHaveBeenCalled();

    cartService.remove.calls.reset();
    fixture = TestBed.createComponent(CartComponent);
    fixture.detectChanges();

    const clear = fixture.nativeElement.querySelector('.clear-button') as HTMLButtonElement;
    clear.click();

    expect(cartService.clear).toHaveBeenCalledTimes(1);
    expect(cartService.remove).not.toHaveBeenCalled();
  });

  it('does not auto-replay uncertain cart mutations', () => {
    cartService.update.and.returnValue(throwError(() => ({ status: 503 })));
    fixture.detectChanges();

    const increase = fixture.nativeElement.querySelector('[aria-label="Increase quantity"]') as HTMLButtonElement;
    increase.click();
    fixture.detectChanges();

    expect(cartService.update).toHaveBeenCalledTimes(1);
    expect(cartService.validate).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('The cart could not be updated.');
  });

  it('shows no checkout route in cart-only mode even when validation is ready', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cart checks passed');
    expect(fixture.nativeElement.textContent).not.toContain('Ready for checkout');
    expect(fixture.nativeElement.textContent).toContain('Checkout is not enabled yet.');
    expect(fixture.nativeElement.querySelector('a[href="/checkout"]')).toBeNull();
  });

  it('disables quantity increase at the latest known availability', () => {
    cart = {
      ...cart,
      totalQuantity: 5,
      items: [{ ...cart.items[0], quantity: 5 }],
    };
    validation = {
      ...validation,
      totalQuantity: 5,
      items: [{
        ...validation.items[0],
        requestedQuantity: 5,
        availableQuantity: 5,
      }],
    };
    cartService.cart = signal(cart).asReadonly();
    cartService.validation = signal(validation).asReadonly();
    cartService.load.and.returnValue(of(cart));
    cartService.validate.and.returnValue(of(validation));

    fixture.detectChanges();

    const increase = fixture.nativeElement.querySelector('[aria-label="Increase quantity"]') as HTMLButtonElement;
    expect(increase.disabled).toBeTrue();
    increase.click();

    expect(cartService.update).not.toHaveBeenCalled();
  });

  it('retains cart contents and exposes retry when validation is unavailable', () => {
    cartService.validation = signal(null).asReadonly();
    cartService.validate.and.returnValue(throwError(() => ({
      error: { error: { message: 'Current price and stock are unavailable.' } },
    })));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Store plush');
    expect(text).toContain('Validation unavailable');
    expect(text).toContain('Current price and stock are unavailable.');
    expect(cartService.update).not.toHaveBeenCalled();
    expect(cartService.remove).not.toHaveBeenCalled();

    const retry = fixture.nativeElement.querySelector('.validation-summary button') as HTMLButtonElement;
    retry.click();

    expect(cartService.validate).toHaveBeenCalledTimes(2);
  });

  it('groups same-store lines together and keeps same-name stores separate', () => {
    const secondSameStore = {
      ...cart.items[0],
      listingId: '01L00000000000000000000002',
      title: 'Store blanket',
      quantity: 1,
    };
    const differentStore = {
      ...cart.items[0],
      listingId: '01L00000000000000000000003',
      title: 'Store tote',
      storeName: 'Mochi Store',
      storeSlug: 'mochi-store-two',
      publicCity: 'Costa Mesa',
      quantity: 2,
    };
    cart = {
      ...cart,
      itemCount: 3,
      totalQuantity: 5,
      items: [cart.items[0], secondSameStore, differentStore],
    };
    cartService.cart = signal(cart).asReadonly();
    cartService.load.and.returnValue(of(cart));

    fixture.detectChanges();

    const groups = fixture.nativeElement.querySelectorAll('[data-testid="cart-store-group"]');
    expect(groups.length).toBe(2);
    expect(groups[0].querySelectorAll('.packing-card').length).toBe(2);
    expect(groups[1].querySelectorAll('.packing-card').length).toBe(1);
    expect(groups[0].querySelector('a[href="/stores/mochi-store"]')).not.toBeNull();
    expect(groups[1].querySelector('a[href="/stores/mochi-store-two"]')).not.toBeNull();
    expect(groups[1].textContent).toContain('Store tote');
    expect(fixture.nativeElement.textContent).toContain('5 total');
  });

  it('removing the final line from one store preserves the other store group', () => {
    const otherStore = {
      ...cart.items[0],
      listingId: '01L00000000000000000000004',
      title: 'Harbor tote',
      storeName: 'Harbor Cart Supply',
      storeSlug: 'harbor-cart-supply',
      publicCity: 'Costa Mesa',
      quantity: 1,
    };
    const initial = { ...cart, itemCount: 2, totalQuantity: 3, items: [cart.items[0], otherStore] };
    const remaining = {
      ...initial,
      version: 2,
      itemCount: 1,
      totalQuantity: 1,
      items: [otherStore],
    };
    const cartSignal = signal(initial);
    cartService.cart = cartSignal.asReadonly();
    cartService.load.and.returnValue(of(initial));
    cartService.remove.and.callFake(() => {
      cartSignal.set(remaining);
      return of(remaining);
    });

    fixture.detectChanges();
    const removeFirst = fixture.nativeElement.querySelector(
      '[aria-label="Remove Store plush"]',
    ) as HTMLButtonElement;
    removeFirst.click();
    fixture.detectChanges();

    const groups = fixture.nativeElement.querySelectorAll('[data-testid="cart-store-group"]');
    expect(groups.length).toBe(1);
    expect(groups[0].textContent).toContain('Harbor Cart Supply');
    expect(groups[0].textContent).not.toContain('Store plush');
  });

  it('keeps validation conflicts scoped to their store line', () => {
    const otherStore = {
      ...cart.items[0],
      listingId: '01L00000000000000000000005',
      title: 'Harbor tote',
      storeName: 'Harbor Cart Supply',
      storeSlug: 'harbor-cart-supply',
      quantity: 1,
    };
    cart = { ...cart, itemCount: 2, totalQuantity: 3, items: [cart.items[0], otherStore] };
    validation = {
      ...validation,
      checkoutReady: false,
      itemCount: 2,
      totalQuantity: 3,
      items: [
        {
          ...validation.items[0],
          status: 'PRICE_CHANGED',
          currentPrice: 12,
          issues: [{
            code: 'CART_PRICE_CHANGED',
            message: 'The price changed.',
            action: 'ACCEPT_CURRENT_PRICE',
          }],
        },
        {
          ...validation.items[0],
          listingId: otherStore.listingId,
          title: otherStore.title,
          storeName: otherStore.storeName,
          storeSlug: otherStore.storeSlug,
          requestedQuantity: 1,
          status: 'READY',
          issues: [],
        },
      ],
    };
    cartService.cart = signal(cart).asReadonly();
    cartService.validation = signal(validation).asReadonly();
    cartService.load.and.returnValue(of(cart));
    cartService.validate.and.returnValue(of(validation));

    fixture.detectChanges();

    const groups = fixture.nativeElement.querySelectorAll('[data-testid="cart-store-group"]');
    expect(groups.length).toBe(2);
    expect(groups[0].querySelectorAll('.needs-repair').length).toBe(1);
    expect(groups[1].querySelectorAll('.needs-repair').length).toBe(0);
    expect(groups[1].textContent).toContain('Price is current.');
  });

  it('shows the empty cart path with clear shopping actions', () => {
    const emptyCart = { ...cart, version: 0, itemCount: 0, totalQuantity: 0, items: [], totals: [] };
    cartService.cart = signal(emptyCart).asReadonly();
    cartService.load.and.returnValue(of(emptyCart));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Your cart is empty');
    expect(text).toContain('Browse business items');
    expect(fixture.nativeElement.querySelector('a[href="/stores"]')).not.toBeNull();
    expect(cartService.validate).not.toHaveBeenCalled();
  });

  it('keeps quantity controls bounded from 1 through 999', () => {
    const maxCart = {
      ...cart,
      items: [{ ...cart.items[0], quantity: 999 }],
    };
    cartService.cart = signal(maxCart).asReadonly();
    cartService.load.and.returnValue(of(maxCart));

    fixture.detectChanges();

    const increase = fixture.nativeElement.querySelector('[aria-label="Increase quantity"]') as HTMLButtonElement;
    expect(increase.disabled).toBeTrue();
    increase.click();

    expect(cartService.update).not.toHaveBeenCalled();
  });
});
