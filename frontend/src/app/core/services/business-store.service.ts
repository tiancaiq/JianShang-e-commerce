import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { BusinessStore, BusinessStoreContext, BusinessStoreUpdateRequest } from '../models/business-store.model';
import { unwrapData } from './api-response';

@Injectable({ providedIn: 'root' })
export class BusinessStoreService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1`;

  constructor(private http: HttpClient) {}

  getCurrentStoreContext(): Observable<BusinessStoreContext | null> {
    return this.http.get<ApiDataResponse<BusinessStoreContext | null>>(`${this.baseUrl}/businesses/me/store-context`, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  getBusinessStore(businessId: string): Observable<BusinessStore> {
    return this.http.get<ApiDataResponse<BusinessStore>>(`${this.baseUrl}/businesses/${businessId}/store`, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  updateBusinessStore(
    businessId: string,
    request: BusinessStoreUpdateRequest,
    version: number
  ): Observable<BusinessStore> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.patch<ApiDataResponse<BusinessStore>>(
      `${this.baseUrl}/businesses/${businessId}/store`,
      request,
      {
        headers,
        withCredentials: true,
      }
    ).pipe(map(unwrapData));
  }

  getPublicStore(slug: string): Observable<BusinessStore> {
    return this.http.get<ApiDataResponse<BusinessStore>>(`${this.baseUrl}/stores/${slug}`)
      .pipe(map(unwrapData));
  }
}
