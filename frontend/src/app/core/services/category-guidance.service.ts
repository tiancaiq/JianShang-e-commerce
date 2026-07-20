import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CategoryGuidanceHistoryPage,
  CategoryGuidancePublishRequest,
  CategoryGuidanceSource,
} from '../models/category-guidance.model';

@Injectable({ providedIn: 'root' })
export class CategoryGuidanceService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/admin/categories`;

  constructor(private readonly http: HttpClient) {}

  current(categoryId: string, language: string): Observable<CategoryGuidanceSource> {
    return this.http.get<CategoryGuidanceSource>(this.streamUrl(categoryId, language), {
      withCredentials: true,
    });
  }

  history(
    categoryId: string,
    language: string,
    cursor?: string | null,
    limit = 20,
  ): Observable<CategoryGuidanceHistoryPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<CategoryGuidanceHistoryPage>(
      `${this.streamUrl(categoryId, language)}/versions`,
      { params, withCredentials: true },
    );
  }

  publish(
    categoryId: string,
    language: string,
    expectedVersion: string,
    request: CategoryGuidancePublishRequest,
  ): Observable<CategoryGuidanceSource> {
    return this.http.post<CategoryGuidanceSource>(
      `${this.streamUrl(categoryId, language)}/versions`,
      request,
      {
        headers: { 'If-Match': expectedVersion },
        withCredentials: true,
      },
    );
  }

  retire(
    categoryId: string,
    language: string,
    expectedVersion: string,
  ): Observable<CategoryGuidanceSource> {
    return this.http.post<CategoryGuidanceSource>(
      `${this.streamUrl(categoryId, language)}/retire`,
      null,
      {
        headers: { 'If-Match': expectedVersion },
        withCredentials: true,
      },
    );
  }

  private streamUrl(categoryId: string, language: string): string {
    return `${this.baseUrl}/${encodeURIComponent(categoryId)}/guidance/${encodeURIComponent(language)}`;
  }
}
