import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  BusinessApplication,
  BusinessApplicationDecisionRequest,
  BusinessApplicationDraftRequest,
} from '../models/business-application.model';

@Injectable({ providedIn: 'root' })
export class BusinessApplicationService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/business-applications`;

  constructor(private http: HttpClient) {}

  createDraft(request: BusinessApplicationDraftRequest): Observable<ApiDataResponse<BusinessApplication>> {
    return this.http.post<ApiDataResponse<BusinessApplication>>(this.baseUrl, request, {
      withCredentials: true,
    });
  }

  getApplication(id: string): Observable<ApiDataResponse<BusinessApplication>> {
    return this.http.get<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}`, {
      withCredentials: true,
    });
  }

  updateDraft(
    id: string,
    request: BusinessApplicationDraftRequest,
    version: number
  ): Observable<ApiDataResponse<BusinessApplication>> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.patch<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}`, request, {
      headers,
      withCredentials: true,
    });
  }

  submit(id: string, version: number): Observable<ApiDataResponse<BusinessApplication>> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.post<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}/submit`, null, {
      headers,
      withCredentials: true,
    });
  }

  decide(id: string, request: BusinessApplicationDecisionRequest): Observable<ApiDataResponse<BusinessApplication>> {
    return this.http.post<ApiDataResponse<BusinessApplication>>(
      `${environment.apiGatewayUrl}/api/v1/admin/business-applications/${id}/decision`,
      request,
      { withCredentials: true }
    );
  }
}
