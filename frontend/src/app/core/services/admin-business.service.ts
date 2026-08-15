import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  AdminBusinessDetail, AdminBusinessSearchPage, AdminBusinessTimelineEntry, BusinessEnforcementAction,
  BusinessEnforcementPreview, BusinessEnforcementScope, CreateBusinessEnforcementRequest,
  RevokeBusinessEnforcementRequest,
} from '../models/admin-business.model';

@Injectable({ providedIn: 'root' })
export class AdminBusinessService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/admin/businesses`;
  constructor(private readonly http: HttpClient) {}

  search(filters: { q?: string; businessState?: string; enforcementState?: string;
    scope?: BusinessEnforcementScope | ''; page: number; size: number; sort: string }): Observable<ApiDataResponse<AdminBusinessSearchPage>> {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size).set('sort', filters.sort);
    if (filters.q) params = params.set('q', filters.q);
    if (filters.businessState) params = params.set('businessState', filters.businessState);
    if (filters.enforcementState) params = params.set('enforcementState', filters.enforcementState);
    if (filters.scope) params = params.set('scope', filters.scope);
    return this.http.get<ApiDataResponse<AdminBusinessSearchPage>>(this.baseUrl, { params, withCredentials: true });
  }
  detail(id: string) { return this.http.get<ApiDataResponse<AdminBusinessDetail>>(`${this.baseUrl}/${id}`, { withCredentials: true }); }
  timeline(id: string) { return this.http.get<ApiDataResponse<AdminBusinessTimelineEntry[]>>(`${this.baseUrl}/${id}/timeline`, { withCredentials: true }); }
  previewCreate(id: string, body: CreateBusinessEnforcementRequest) { return this.http.post<ApiDataResponse<BusinessEnforcementPreview>>(`${this.baseUrl}/${id}/enforcements/dry-run`, body, { withCredentials: true }); }
  create(id: string, body: CreateBusinessEnforcementRequest) { return this.http.post<ApiDataResponse<BusinessEnforcementAction>>(`${this.baseUrl}/${id}/enforcements`, body, { withCredentials: true }); }
  previewRevoke(id: string, enforcementId: string, body: RevokeBusinessEnforcementRequest) { return this.http.post<ApiDataResponse<BusinessEnforcementPreview>>(`${this.baseUrl}/${id}/enforcements/${enforcementId}/revoke/dry-run`, body, { withCredentials: true }); }
  revoke(id: string, enforcementId: string, body: RevokeBusinessEnforcementRequest) { return this.http.post<ApiDataResponse<BusinessEnforcementAction>>(`${this.baseUrl}/${id}/enforcements/${enforcementId}/revoke`, body, { withCredentials: true }); }
}
