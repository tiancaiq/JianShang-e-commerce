import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  InventoryAdjustmentRequest,
  InventoryBalance,
  InventoryCatalogPage,
  InventoryInitializeRequest,
  InventoryMovementPage,
} from '../models/inventory.model';
import { unwrapData } from './api-response';

@Injectable({ providedIn: 'root' })
export class InventoryService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/businesses`;
  private readonly legacyAvailabilityUrl = `${environment.apiGatewayUrl}/api/inventory`;

  constructor(private readonly http: HttpClient) {}

  list(
    businessId: string,
    filters: { q?: string | null; listingStatus?: string | null; cursor?: string | null; limit?: number } = {},
  ): Observable<InventoryCatalogPage> {
    let params = new HttpParams().set('limit', String(filters.limit ?? 24));
    if (filters.q) {
      params = params.set('q', filters.q);
    }
    if (filters.listingStatus) {
      params = params.set('listingStatus', filters.listingStatus);
    }
    if (filters.cursor) {
      params = params.set('cursor', filters.cursor);
    }
    return this.http.get<InventoryCatalogPage>(`${this.baseUrl}/${businessId}/inventory`, {
      params,
      withCredentials: true,
    });
  }

  initialize(
    businessId: string,
    listingId: string,
    request: InventoryInitializeRequest,
  ): Observable<InventoryBalance> {
    return this.http.post<ApiDataResponse<InventoryBalance>>(
      `${this.baseUrl}/${businessId}/inventory/${listingId}/initialize`,
      request,
      {
        headers: new HttpHeaders({ 'Idempotency-Key': this.idempotencyKey() }),
        withCredentials: true,
      },
    ).pipe(map(unwrapData));
  }

  adjust(
    businessId: string,
    listingId: string,
    version: number,
    request: InventoryAdjustmentRequest,
  ): Observable<InventoryBalance> {
    return this.http.post<ApiDataResponse<InventoryBalance>>(
      `${this.baseUrl}/${businessId}/inventory/${listingId}/adjustments`,
      request,
      {
        headers: new HttpHeaders({
          'Idempotency-Key': this.idempotencyKey(),
          'If-Match': String(version),
        }),
        withCredentials: true,
      },
    ).pipe(map(unwrapData));
  }

  movements(
    businessId: string,
    listingId: string,
    cursor: string | null = null,
    limit = 20,
  ): Observable<InventoryMovementPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<InventoryMovementPage>(
      `${this.baseUrl}/${businessId}/inventory/${listingId}/movements`,
      { params, withCredentials: true },
    );
  }

  // Keeps the legacy tutorial screens compiling until their deferred order flow is removed.
  isInStock(skuCode: string, quantity: number): Observable<boolean> {
    return this.http.get<boolean>(this.legacyAvailabilityUrl, {
      params: { skuCode, quantity: String(quantity) },
      withCredentials: true,
    });
  }

  private idempotencyKey(): string {
    return globalThis.crypto?.randomUUID?.() ?? `inventory-${Date.now()}-${Math.random()}`;
  }
}
