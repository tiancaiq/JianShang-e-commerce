import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  AdminUserDetail,
  AdminUserSearchPage,
  AdminUserTimelineEntry,
  CreateUserEnforcementRequest,
  EnforcementPreview,
  RevokeUserEnforcementRequest,
  UserEnforcementAction,
  UserEnforcementScope,
} from '../models/admin-user.model';

@Injectable({ providedIn: 'root' })
export class AdminUserService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/admin/users`;

  constructor(private readonly http: HttpClient) {}

  search(filters: {
    q?: string;
    enforcementState?: string;
    scope?: UserEnforcementScope | '';
    page: number;
    size: number;
    sort: string;
  }): Observable<ApiDataResponse<AdminUserSearchPage>> {
    let params = new HttpParams()
      .set('page', filters.page)
      .set('size', filters.size)
      .set('sort', filters.sort);
    if (filters.q) params = params.set('q', filters.q);
    if (filters.enforcementState) params = params.set('enforcementState', filters.enforcementState);
    if (filters.scope) params = params.set('scope', filters.scope);
    return this.http.get<ApiDataResponse<AdminUserSearchPage>>(this.baseUrl, {
      params,
      withCredentials: true,
    });
  }

  detail(userId: string): Observable<ApiDataResponse<AdminUserDetail>> {
    return this.http.get<ApiDataResponse<AdminUserDetail>>(`${this.baseUrl}/${userId}`, {
      withCredentials: true,
    });
  }

  timeline(userId: string): Observable<ApiDataResponse<AdminUserTimelineEntry[]>> {
    return this.http.get<ApiDataResponse<AdminUserTimelineEntry[]>>(`${this.baseUrl}/${userId}/timeline`, {
      withCredentials: true,
    });
  }

  previewCreate(userId: string, request: CreateUserEnforcementRequest): Observable<ApiDataResponse<EnforcementPreview>> {
    return this.http.post<ApiDataResponse<EnforcementPreview>>(
      `${this.baseUrl}/${userId}/enforcements/dry-run`, request, { withCredentials: true });
  }

  create(userId: string, request: CreateUserEnforcementRequest): Observable<ApiDataResponse<UserEnforcementAction>> {
    return this.http.post<ApiDataResponse<UserEnforcementAction>>(
      `${this.baseUrl}/${userId}/enforcements`, request, { withCredentials: true });
  }

  previewRevoke(
    userId: string,
    enforcementId: string,
    request: RevokeUserEnforcementRequest,
  ): Observable<ApiDataResponse<EnforcementPreview>> {
    return this.http.post<ApiDataResponse<EnforcementPreview>>(
      `${this.baseUrl}/${userId}/enforcements/${enforcementId}/revoke/dry-run`,
      request,
      { withCredentials: true },
    );
  }

  revoke(
    userId: string,
    enforcementId: string,
    request: RevokeUserEnforcementRequest,
  ): Observable<ApiDataResponse<UserEnforcementAction>> {
    return this.http.post<ApiDataResponse<UserEnforcementAction>>(
      `${this.baseUrl}/${userId}/enforcements/${enforcementId}/revoke`,
      request,
      { withCredentials: true },
    );
  }
}
