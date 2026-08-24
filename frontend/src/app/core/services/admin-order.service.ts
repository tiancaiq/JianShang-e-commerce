import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminCancelOrderRequest,
  AdminOrderCancellationPreview,
  AdminOrderCancellationResult,
  AdminOrderDetail,
  AdminOrderPage,
} from '../models/admin-order.model';

export interface AdminOrderSearch {
  q?: string; buyerUserId?: string; businessId?: string; listingId?: string; status?: string;
  paymentStatus?: string; fulfillmentStatus?: string; createdFrom?: string; createdTo?: string;
  page: number; size: number; sort: string;
}

@Injectable({ providedIn: 'root' })
export class AdminOrderService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/admin/orders`;
  constructor(private readonly http: HttpClient) {}

  search(filter: AdminOrderSearch): Observable<AdminOrderPage> {
    let params = new HttpParams().set('page', filter.page).set('size', filter.size).set('sort', filter.sort);
    for (const [key, value] of Object.entries(filter)) {
      if (!['page', 'size', 'sort'].includes(key) && value) params = params.set(key, String(value));
    }
    return this.http.get<AdminOrderPage>(this.baseUrl, { params, withCredentials: true });
  }

  detail(orderId: string): Observable<AdminOrderDetail> {
    return this.http.get<AdminOrderDetail>(`${this.baseUrl}/${orderId}`, { withCredentials: true });
  }

  previewCancellation(orderId: string, request: AdminCancelOrderRequest): Observable<AdminOrderCancellationPreview> {
    return this.http.post<AdminOrderCancellationPreview>(`${this.baseUrl}/${orderId}/cancel/dry-run`, request,
      { withCredentials: true });
  }

  cancel(orderId: string, request: AdminCancelOrderRequest): Observable<AdminOrderCancellationResult> {
    return this.http.post<AdminOrderCancellationResult>(`${this.baseUrl}/${orderId}/cancel`, request,
      { withCredentials: true });
  }
}
