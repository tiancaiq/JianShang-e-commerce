import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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

  it('posts listing and quantity when adding a business item', () => {
    service.add({ listingId: cart.items[0].listingId, quantity: 2 }).subscribe();

    const request = httpMock.expectOne('/api/v1/cart/items');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      listingId: cart.items[0].listingId,
      quantity: 2,
    });
    request.flush(cart);
  });

  it('replaces quantity and removes by listing ID', () => {
    service.update(cart.items[0].listingId, { quantity: 3 }).subscribe();
    const update = httpMock.expectOne(`/api/v1/cart/items/${cart.items[0].listingId}`);
    expect(update.request.method).toBe('PATCH');
    expect(update.request.body).toEqual({ quantity: 3 });
    update.flush({ ...cart, totalQuantity: 3 });

    service.remove(cart.items[0].listingId).subscribe();
    const remove = httpMock.expectOne(`/api/v1/cart/items/${cart.items[0].listingId}`);
    expect(remove.request.method).toBe('DELETE');
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
});
