import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Observable, finalize, tap } from 'rxjs';
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
  private readonly requestInFlight = signal(false);
  private readonly validationInFlight = signal(false);

  readonly cart = this.currentCart.asReadonly();
  readonly validation = this.currentValidation.asReadonly();
  readonly loading = this.requestInFlight.asReadonly();
  readonly validating = this.validationInFlight.asReadonly();
  readonly count = computed(() => this.currentCart()?.totalQuantity ?? 0);

  constructor(private readonly http: HttpClient) {}

  load(): Observable<Cart> {
    return this.track(this.http.get<Cart>(this.baseUrl, { withCredentials: true }), false);
  }

  add(request: AddCartItemRequest): Observable<Cart> {
    return this.track(this.http.post<Cart>(`${this.baseUrl}/items`, request, { withCredentials: true }));
  }

  update(listingId: string, request: UpdateCartItemRequest): Observable<Cart> {
    return this.track(this.http.patch<Cart>(
      `${this.baseUrl}/items/${encodeURIComponent(listingId)}`,
      request,
      { withCredentials: true },
    ));
  }

  remove(listingId: string): Observable<Cart> {
    return this.track(this.http.delete<Cart>(
      `${this.baseUrl}/items/${encodeURIComponent(listingId)}`,
      { withCredentials: true },
    ));
  }

  clear(): Observable<Cart> {
    return this.track(this.http.delete<Cart>(this.baseUrl, { withCredentials: true }));
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
    this.requestInFlight.set(true);
    return request.pipe(
      tap(cart => {
        this.currentCart.set(cart);
        if (invalidateValidation) {
          this.currentValidation.set(null);
        } else if (this.currentValidation()?.cartVersion !== cart.version) {
          this.currentValidation.set(null);
        }
      }),
      finalize(() => this.requestInFlight.set(false)),
    );
  }
}
