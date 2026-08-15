import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BusinessOrderReturn, BuyerOrderDetail, BuyerOrderPage, OrderCancellationResponse, OrderResponse, ReturnReason } from '../models/order.model';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/orders`;

  constructor(private readonly http: HttpClient) {}

  list(cursor?: string | null): Observable<BuyerOrderPage> {
    let params = new HttpParams().set('limit', '20');
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<BuyerOrderPage>(this.baseUrl, { params, withCredentials: true });
  }

  detail(orderId: string): Observable<BuyerOrderDetail> {
    return this.http.get<BuyerOrderDetail>(
      `${this.baseUrl}/${encodeURIComponent(orderId)}`,
      { withCredentials: true },
    );
  }

  requestCancellation(
    orderId: string,
    version: number,
    idempotencyKey: string,
  ): Observable<OrderCancellationResponse> {
    const headers = new HttpHeaders()
      .set('If-Match', `"${version}"`)
      .set('Idempotency-Key', idempotencyKey);
    return this.http.post<OrderCancellationResponse>(
      `${this.baseUrl}/${encodeURIComponent(orderId)}/cancellation-requests`,
      null,
      { headers, withCredentials: true },
    );
  }

  returnDetail(orderId:string,groupId:string):Observable<BusinessOrderReturn>{return this.http.get<BusinessOrderReturn>(`${this.baseUrl}/${encodeURIComponent(orderId)}/groups/${encodeURIComponent(groupId)}/return`,{withCredentials:true});}
  requestReturn(orderId:string,groupId:string,version:number,key:string,reasonCode:ReturnReason,comment:string):Observable<BusinessOrderReturn>{
    const headers=new HttpHeaders({'If-Match':String(version),'Idempotency-Key':key});
    return this.http.post<BusinessOrderReturn>(`${this.baseUrl}/${encodeURIComponent(orderId)}/groups/${encodeURIComponent(groupId)}/returns`,{reasonCode,comment:comment||null},{headers,withCredentials:true});
  }

  getAllOrders(): Observable<OrderResponse[]> {
    return this.list().pipe(map(page => page.items.map(order => ({
      id: order.orderId,
      orderNumber: order.orderId,
      skuCode: `${order.groups.length} store group(s)`,
      quantity: order.groups.length,
      price: order.totalAmount,
    }))));
  }
}
