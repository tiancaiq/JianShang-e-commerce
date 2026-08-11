import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CartValidation } from '../models/cart.model';
import { AuthService } from './auth.service';
import { CartService } from './cart.service';

describe('CartService', () => {
  let service: CartService;
  let httpMock: HttpTestingController;

  const cart = {
    version: 1,
    expiresAt: '2026-08-16T00:00:00Z',
    itemCount: 1,
    totalQuantity: 2,
    totals: [{ currency: 'USD', amount: 10 }],
    items: [{
      listingId: '01L00000000000000000000001',
      title: 'Business item',
      thumbnailUrl: null,
      quantity: 2,
      observedPrice: 5,
      currency: 'USD',
      addedAt: '2026-07-17T00:00:00Z',
    }],
  };
  const validation = {
    cartVersion: 1,
    validatedAt: '2026-07-18T12:00:00Z',
    checkoutReady: true,
    itemCount: 1,
    totalQuantity: 2,
    validatedTotals: [{ currency: 'USD', amount: 10 }],
    cartIssues: [],
    items: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { csrf: () => null } },
      ],
    });
    service = TestBed.inject(CartService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('stores the loaded cart and exposes total quantity', () => {
    service.load().subscribe();

    httpMock.expectOne('/api/v1/cart').flush(cart);

    expect(service.cart()).toEqual(cart);
    expect(service.count()).toBe(2);
  });

  it('refreshes until confirmed-checkout reconciliation changes the authoritative cart', () => {
    jasmine.clock().install();
    try {
      service.refreshAfterConfirmedCheckout(cart.version).subscribe();
      jasmine.clock().tick(0);

      httpMock.expectOne('/api/v1/cart').flush(cart);
      expect(service.count()).toBe(2);

      jasmine.clock().tick(500);
      httpMock.expectOne('/api/v1/cart').flush({
        ...cart,
        version: 2,
        itemCount: 0,
        totalQuantity: 0,
        totals: [],
        items: [],
      });
      expect(service.count()).toBe(0);

      jasmine.clock().tick(1000);
      httpMock.expectNone('/api/v1/cart');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('stops on a buyer-modified newer cart without clearing its remaining quantity', () => {
    jasmine.clock().install();
    try {
      service.refreshAfterConfirmedCheckout(cart.version).subscribe();
      jasmine.clock().tick(0);

      httpMock.expectOne('/api/v1/cart').flush({
        ...cart,
        version: 2,
        itemCount: 1,
        totalQuantity: 1,
        items: [{ ...cart.items[0], quantity: 1 }],
      });
      expect(service.count()).toBe(1);

      jasmine.clock().tick(1000);
      httpMock.expectNone('/api/v1/cart');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('keeps loading true until overlapping cart requests all finish', () => {
    service.load().subscribe();
    service.add({ listingId: cart.items[0].listingId, quantity: 1 }).subscribe();

    const load = httpMock.expectOne('/api/v1/cart');
    const add = httpMock.expectOne('/api/v1/cart/items');
    expect(service.loading()).toBeTrue();
    expect(add.request.headers.get('If-Match')).toBe('"0"');
    expect(add.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);

    load.flush(cart);
    expect(service.loading()).toBeTrue();

    add.flush({ ...cart, version: 2 });
    expect(service.loading()).toBeFalse();
  });

  it('posts listing and quantity when adding a business item', () => {
    service.add({ listingId: cart.items[0].listingId, quantity: 2 }).subscribe();

    const request = httpMock.expectOne('/api/v1/cart/items');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"0"');
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    expect(request.request.body).toEqual({
      listingId: cart.items[0].listingId,
      quantity: 2,
    });
    request.flush(cart);
  });

  it('replaces quantity and removes by listing ID', () => {
    service.load().subscribe();
    httpMock.expectOne('/api/v1/cart').flush(cart);

    service.update(cart.items[0].listingId, { quantity: 3 }).subscribe();
    const update = httpMock.expectOne(`/api/v1/cart/items/${cart.items[0].listingId}`);
    expect(update.request.method).toBe('PATCH');
    expect(update.request.headers.get('If-Match')).toBe('"1"');
    expect(update.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    expect(update.request.body).toEqual({ quantity: 3 });
    update.flush({ ...cart, version: 2, totalQuantity: 3 });

    service.remove(cart.items[0].listingId).subscribe();
    const remove = httpMock.expectOne(`/api/v1/cart/items/${cart.items[0].listingId}`);
    expect(remove.request.method).toBe('DELETE');
    expect(remove.request.headers.get('If-Match')).toBe('"2"');
    expect(remove.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    remove.flush({ ...cart, itemCount: 0, totalQuantity: 0, items: [], totals: [] });
  });

  it('posts a bodyless validation request and stores a matching result', () => {
    service.load().subscribe();
    httpMock.expectOne('/api/v1/cart').flush(cart);

    service.validate().subscribe();

    const request = httpMock.expectOne('/api/v1/cart/validate');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(validation);

    expect(service.validation()).toEqual(validation);
  });

  it('ignores a validation result for an older cart version', () => {
    service.load().subscribe();
    httpMock.expectOne('/api/v1/cart').flush({ ...cart, version: 2 });

    service.validate().subscribe();
    httpMock.expectOne('/api/v1/cart/validate').flush(validation);

    expect(service.validation()).toBeNull();
  });

  it('keeps the add-update-reload-validate-repair-remove-clear journey explicit', () => {
    const listingId = cart.items[0].listingId;
    const addedCart = {
      ...cart,
      version: 1,
      totalQuantity: 1,
      totals: [{ currency: 'USD', amount: 5 }],
      items: [{ ...cart.items[0], quantity: 1 }],
    };
    const reloadedCart = {
      ...cart,
      version: 2,
      totalQuantity: 3,
      totals: [{ currency: 'USD', amount: 15 }],
      items: [{ ...cart.items[0], quantity: 3 }],
    };
    const updatedCart = {
      ...cart,
      version: 3,
      totalQuantity: 4,
      totals: [{ currency: 'USD', amount: 20 }],
      items: [{ ...cart.items[0], quantity: 4 }],
    };
    const priceChanged: CartValidation = {
      ...validation,
      cartVersion: 3,
      checkoutReady: false,
      validatedTotals: [],
      items: [{
        listingId,
        title: cart.items[0].title,
        thumbnailUrl: cart.items[0].thumbnailUrl,
        requestedQuantity: 4,
        availableQuantity: 6,
        observedPrice: 5,
        currentPrice: 7,
        observedCurrency: 'USD',
        currentCurrency: 'USD',
        status: 'PRICE_CHANGED',
        issues: [{
          code: 'CART_PRICE_CHANGED',
          message: 'The price changed.',
          action: 'ACCEPT_CURRENT_PRICE',
        }],
      }],
    };
    const repairedCart = {
      ...cart,
      version: 4,
      totalQuantity: 4,
      totals: [{ currency: 'USD', amount: 28 }],
      items: [{ ...cart.items[0], quantity: 4, observedPrice: 7 }],
    };
    const emptyCart = {
      ...cart,
      version: 5,
      itemCount: 0,
      totalQuantity: 0,
      totals: [],
      items: [],
    };

    service.add({ listingId, quantity: 1 }).subscribe();
    const add = httpMock.expectOne('/api/v1/cart/items');
    expect(add.request.method).toBe('POST');
    expect(add.request.headers.get('If-Match')).toBe('"0"');
    expect(add.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    expect(add.request.body).toEqual({ listingId, quantity: 1 });
    add.flush(addedCart);
    expect(service.count()).toBe(1);

    service.load().subscribe();
    const reload = httpMock.expectOne('/api/v1/cart');
    expect(reload.request.method).toBe('GET');
    reload.flush(reloadedCart);
    expect(service.count()).toBe(3);

    service.update(listingId, { quantity: 4 }).subscribe();
    const update = httpMock.expectOne(`/api/v1/cart/items/${listingId}`);
    expect(update.request.method).toBe('PATCH');
    expect(update.request.headers.get('If-Match')).toBe('"2"');
    expect(update.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    expect(update.request.body).toEqual({ quantity: 4 });
    update.flush(updatedCart);
    expect(service.validation()).toBeNull();

    service.validate().subscribe();
    const validate = httpMock.expectOne('/api/v1/cart/validate');
    expect(validate.request.method).toBe('POST');
    expect(validate.request.body).toBeNull();
    validate.flush(priceChanged);

    expect(service.validation()).toEqual(priceChanged);
    expect(service.count()).toBe(4);
    httpMock.expectNone('/api/v1/cart/items');
    httpMock.expectNone(`/api/v1/cart/items/${listingId}`);

    service.add({ listingId, quantity: 4 }).subscribe();
    const repair = httpMock.expectOne('/api/v1/cart/items');
    expect(repair.request.headers.get('If-Match')).toBe('"3"');
    expect(repair.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    expect(repair.request.body).toEqual({ listingId, quantity: 4 });
    repair.flush(repairedCart);
    expect(service.validation()).toBeNull();
    expect(service.cart()?.totals[0].amount).toBe(28);

    service.remove(listingId).subscribe();
    const remove = httpMock.expectOne(`/api/v1/cart/items/${listingId}`);
    expect(remove.request.method).toBe('DELETE');
    expect(remove.request.headers.get('If-Match')).toBe('"4"');
    expect(remove.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    remove.flush(emptyCart);
    expect(service.count()).toBe(0);

    service.clear().subscribe();
    const clear = httpMock.expectOne('/api/v1/cart');
    expect(clear.request.method).toBe('DELETE');
    expect(clear.request.headers.get('If-Match')).toBe('"5"');
    expect(clear.request.headers.get('Idempotency-Key')).toMatch(/^cart-/);
    clear.flush({ ...emptyCart, version: 6 });
    expect(service.cart()?.items).toEqual([]);
  });
});
