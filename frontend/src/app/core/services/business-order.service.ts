import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BusinessOrderDetail,
  BusinessOrderPage,
  BusinessOrderStatus,
} from '../models/business-order.model';

@Injectable({ providedIn: 'root' })
export class BusinessOrderService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/businesses`;

  constructor(private readonly http: HttpClient) {}

  // Reads the authenticated actor's business-scoped fulfillment queue.
  list(
    businessId: string,
    options: {
      status?: BusinessOrderStatus | null;
      cursor?: string | null;
      limit?: number;
    } = {},
  ): Observable<BusinessOrderPage> {
    let params = new HttpParams().set('limit', String(options.limit ?? 20));
    if (options.status) {
      params = params.set('status', options.status);
    }
    if (options.cursor) {
      params = params.set('cursor', options.cursor);
    }
    return this.http.get<BusinessOrderPage>(
      `${this.baseUrl}/${encodeURIComponent(businessId)}/orders`,
      { params, withCredentials: true },
    );
  }

  // Reads one immutable business-order snapshot through the non-enumerating API.
  detail(businessId: string, businessOrderId: string): Observable<BusinessOrderDetail> {
    return this.http.get<BusinessOrderDetail>(
      `${this.baseUrl}/${encodeURIComponent(businessId)}/orders/${encodeURIComponent(businessOrderId)}`,
      { withCredentials: true },
    );
  }
}
