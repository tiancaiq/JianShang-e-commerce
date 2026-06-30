import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  BusinessApplication,
  BusinessApplicationDecisionRequest,
  BusinessApplicationDraftRequest,
} from '../models/business-application.model';
import { unwrapData } from './api-response';

@Injectable({ providedIn: 'root' })
export class BusinessApplicationService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/business-applications`;

  constructor(private http: HttpClient) {}

  createDraft(request: BusinessApplicationDraftRequest): Observable<BusinessApplication> {
    return this.http.post<ApiDataResponse<BusinessApplication>>(this.baseUrl, request, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  getApplication(id: string): Observable<BusinessApplication> {
    return this.http.get<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}`, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  updateDraft(
    id: string,
    request: BusinessApplicationDraftRequest,
    version: number
  ): Observable<BusinessApplication> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.patch<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}`, request, {
      headers,
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  submit(id: string, version: number): Observable<BusinessApplication> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.post<ApiDataResponse<BusinessApplication>>(`${this.baseUrl}/${id}/submit`, null, {
      headers,
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  decide(id: string, request: BusinessApplicationDecisionRequest): Observable<BusinessApplication> {
    return this.http.post<ApiDataResponse<BusinessApplication>>(
      `${environment.apiGatewayUrl}/api/v1/admin/business-applications/${id}/decision`,
      request,
      { withCredentials: true }
    ).pipe(map(unwrapData));
  }
}
