import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import {
  EMPTY,
  Observable,
  catchError,
  exhaustMap,
  finalize,
  take,
  takeWhile,
  tap,
  timer,
} from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AddCartItemRequest,
  Cart,
  CartValidation,
  UpdateCartItemRequest,
} from '../models/cart.model';

@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/cart`;
  private readonly currentCart = signal<Cart | null>(null);
  private readonly currentValidation = signal<CartValidation | null>(null);
  private readonly activeRequests = signal(0);
  private readonly validationInFlight = signal(false);

  readonly cart = this.currentCart.asReadonly();
  readonly validation = this.currentValidation.asReadonly();
  readonly loading = computed(() => this.activeRequests() > 0);
  readonly validating = this.validationInFlight.asReadonly();
  readonly count = computed(() => this.currentCart()?.totalQuantity ?? 0);

  constructor(private readonly http: HttpClient) {}

  load(): Observable<Cart> {
    return this.track(this.http.get<Cart>(this.baseUrl, { withCredentials: true }), false);
  }

  // Refreshes the shared badge after confirmation without making order success depend on Redis cleanup.
  refreshAfterConfirmedCheckout(purchasedCartVersion: number): Observable<Cart> {
    return timer(0, 500).pipe(
      exhaustMap(() => this.load()),
      takeWhile(
        cart => cart.version === purchasedCartVersion && cart.totalQuantity > 0,
        true,
      ),
      take(8),
      catchError(() => EMPTY),
    );
  }

  add(request: AddCartItemRequest): Observable<Cart> {
    return this.track(this.http.post<Cart>(
      `${this.baseUrl}/items`,
      request,
      this.mutationOptions(),
    ));
  }

  update(listingId: string, request: UpdateCartItemRequest): Observable<Cart> {
    return this.track(this.http.patch<Cart>(
      `${this.baseUrl}/items/${encodeURIComponent(listingId)}`,
      request,
      this.mutationOptions(),
    ));
  }

  remove(listingId: string): Observable<Cart> {
    return this.track(this.http.delete<Cart>(
      `${this.baseUrl}/items/${encodeURIComponent(listingId)}`,
      this.mutationOptions(),
    ));
  }

  clear(): Observable<Cart> {
    return this.track(this.http.delete<Cart>(this.baseUrl, this.mutationOptions()));
  }

  validate(): Observable<CartValidation> {
    this.validationInFlight.set(true);
    return this.http.post<CartValidation>(
      `${this.baseUrl}/validate`,
      null,
      { withCredentials: true },
    ).pipe(
      tap(validation => {
        if (this.currentCart()?.version === validation.cartVersion) {
          this.currentValidation.set(validation);
        }
      }),
      finalize(() => this.validationInFlight.set(false)),
    );
  }

  reset(): void {
    this.currentCart.set(null);
    this.currentValidation.set(null);
  }

  private track(request: Observable<Cart>, invalidateValidation = true): Observable<Cart> {
    this.activeRequests.update(count => count + 1);
    return request.pipe(
      tap(cart => {
        this.currentCart.set(cart);
        if (invalidateValidation) {
          this.currentValidation.set(null);
        } else if (this.currentValidation()?.cartVersion !== cart.version) {
          this.currentValidation.set(null);
        }
      }),
      finalize(() => this.activeRequests.update(count => Math.max(0, count - 1))),
    );
  }

  private mutationOptions(): { withCredentials: true; headers: HttpHeaders } {
    return {
      withCredentials: true,
      headers: new HttpHeaders({
        'If-Match': `"${this.currentCart()?.version ?? 0}"`,
        'Idempotency-Key': this.idempotencyKey(),
      }),
    };
  }

  private idempotencyKey(): string {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
      return `cart-${crypto.randomUUID()}`;
    }
    return `cart-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  }
}
