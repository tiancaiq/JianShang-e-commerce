import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Checkout,
  CheckoutOrderResolution,
  CheckoutPaymentIntent,
  CreateCheckoutRequest,
  DemoPaymentCompletion,
} from '../models/checkout.model';

@Injectable({ providedIn: 'root' })
export class CheckoutService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/checkouts`;
  private readonly createKeys = new Map<string, string>();
  private readonly cancelKeys = new Map<string, string>();

  constructor(private readonly http: HttpClient) {}

  create(request: CreateCheckoutRequest): Observable<Checkout> {
    const logicalCommand = `${request.cartVersion}:${request.addressId}`;
    return this.http.post<Checkout>(this.baseUrl, request, {
      headers: this.idempotencyHeader(this.createKeys, logicalCommand),
      withCredentials: true,
    });
  }

  get(checkoutId: string): Observable<Checkout> {
    return this.http.get<Checkout>(
      `${this.baseUrl}/${encodeURIComponent(checkoutId)}`,
      { withCredentials: true },
    );
  }

  cancel(checkoutId: string): Observable<Checkout> {
    return this.http.post<Checkout>(
      `${this.baseUrl}/${encodeURIComponent(checkoutId)}/cancel`,
      null,
      {
        headers: this.idempotencyHeader(this.cancelKeys, checkoutId),
        withCredentials: true,
      },
    );
  }

  createPaymentIntent(checkoutId: string): Observable<CheckoutPaymentIntent> {
    return this.http.post<CheckoutPaymentIntent>(
      `${this.baseUrl}/${encodeURIComponent(checkoutId)}/payment-intent`,
      null,
      {
        // The checkout is the logical payment command, including across a full browser refresh.
        headers: new HttpHeaders({ 'Idempotency-Key': `checkout-payment:${checkoutId}` }),
        withCredentials: true,
      },
    );
  }

  completeDemoPayment(checkoutId: string): Observable<DemoPaymentCompletion> {
    return this.http.post<DemoPaymentCompletion>(
      `${this.baseUrl}/${encodeURIComponent(checkoutId)}/complete-demo-payment`,
      null,
      { withCredentials: true },
    );
  }

  confirmedOrder(checkoutId: string): Observable<CheckoutOrderResolution> {
    return this.http.get<CheckoutOrderResolution>(
      `${this.baseUrl}/${encodeURIComponent(checkoutId)}/confirmed-order`,
      { withCredentials: true },
    );
  }

  private idempotencyHeader(keys: Map<string, string>, logicalCommand: string): HttpHeaders {
    let key = keys.get(logicalCommand);
    if (!key) {
      key = globalThis.crypto?.randomUUID?.()
        || `checkout-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      keys.set(logicalCommand, key);
    }
    return new HttpHeaders({ 'Idempotency-Key': key });
  }
}
