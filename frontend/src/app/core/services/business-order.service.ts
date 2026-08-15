import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BusinessOrderDetail,
  BusinessOrderFulfillmentResponse,
  BusinessOrderPage,
  BusinessOrderQueueStatus,
  CreateManualShipmentRequest,
} from '../models/business-order.model';
import { BusinessOrderReturn } from '../models/order.model';

@Injectable({ providedIn: 'root' })
export class BusinessOrderService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/businesses`;

  constructor(private readonly http: HttpClient) {}

  // Reads the authenticated actor's business-scoped fulfillment queue.
  list(
    businessId: string,
    options: {
      status?: BusinessOrderQueueStatus | null;
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

  accept(
    businessId: string, businessOrderId: string, version: number, idempotencyKey: string,
  ): Observable<BusinessOrderFulfillmentResponse> {
    return this.command(businessId, businessOrderId, 'accept', version, idempotencyKey, null);
  }

  startProcessing(
    businessId: string, businessOrderId: string, version: number, idempotencyKey: string,
  ): Observable<BusinessOrderFulfillmentResponse> {
    return this.command(businessId, businessOrderId, 'processing', version, idempotencyKey, null);
  }

  createShipment(
    businessId: string,
    businessOrderId: string,
    version: number,
    idempotencyKey: string,
    request: CreateManualShipmentRequest,
  ): Observable<BusinessOrderFulfillmentResponse> {
    return this.command(
      businessId, businessOrderId, 'shipments', version, idempotencyKey, request,
    );
  }

  recordDemoDelivery(
    businessId: string, businessOrderId: string, version: number, idempotencyKey: string,
  ): Observable<BusinessOrderFulfillmentResponse> {
    return this.command(
      businessId, businessOrderId, 'delivery-demo', version, idempotencyKey, null,
    );
  }

  returnDetail(businessId:string,groupId:string):Observable<BusinessOrderReturn|null>{return this.http.get<BusinessOrderReturn|null>(`${this.baseUrl}/${encodeURIComponent(businessId)}/orders/${encodeURIComponent(groupId)}/return`,{withCredentials:true});}
  authorizeReturn(businessId:string,groupId:string,returnId:string,version:number,key:string):Observable<BusinessOrderReturn>{return this.returnCommand(businessId,groupId,returnId,'authorize',version,key,null);}
  receiveReturn(businessId:string,groupId:string,returnId:string,version:number,key:string,disposition:'RESTOCK_SELLABLE'|'DO_NOT_RESTOCK'):Observable<BusinessOrderReturn>{return this.returnCommand(businessId,groupId,returnId,'receive',version,key,{inventoryDisposition:disposition});}
  private returnCommand(businessId:string,groupId:string,returnId:string,action:string,version:number,key:string,body:unknown):Observable<BusinessOrderReturn>{const headers=new HttpHeaders({'If-Match':String(version),'Idempotency-Key':key});return this.http.post<BusinessOrderReturn>(`${this.baseUrl}/${encodeURIComponent(businessId)}/orders/${encodeURIComponent(groupId)}/returns/${encodeURIComponent(returnId)}/${action}`,body,{headers,withCredentials:true});}

  private command(
    businessId: string,
    businessOrderId: string,
    action: string,
    version: number,
    idempotencyKey: string,
    body: CreateManualShipmentRequest | null,
  ): Observable<BusinessOrderFulfillmentResponse> {
    const headers = new HttpHeaders({
      'If-Match': String(version),
      'Idempotency-Key': idempotencyKey,
    });
    return this.http.post<BusinessOrderFulfillmentResponse>(
      `${this.baseUrl}/${encodeURIComponent(businessId)}/orders/`
        + `${encodeURIComponent(businessOrderId)}/${action}`,
      body,
      { headers, withCredentials: true },
    );
  }
}
