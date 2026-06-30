import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { forkJoin, map, Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminBusinessSummary,
  AdminDashboardSummary,
  AdminListingModerationSummary,
  PlatformAdmin,
} from '../models/admin.model';
import { ApiDataResponse } from '../models/auth.model';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1`;

  constructor(private http: HttpClient) {}

  getCurrentAdmin(): Observable<ApiDataResponse<PlatformAdmin>> {
    return this.http.get<ApiDataResponse<PlatformAdmin>>(`${this.baseUrl}/admin/me`, {
      withCredentials: true,
    });
  }

  getDashboardSummary(): Observable<AdminDashboardSummary> {
    return forkJoin({
      business: this.http.get<ApiDataResponse<AdminBusinessSummary>>(`${this.baseUrl}/admin/dashboard-summary`, {
        withCredentials: true,
      }),
      listings: this.http.get<AdminListingModerationSummary>(`${this.baseUrl}/admin/listings/moderation/summary`, {
        withCredentials: true,
      }),
    }).pipe(
      map(({ business, listings }) => ({
        pendingBusinessApplications: business.data.pendingBusinessApplications,
        pendingListingReviews: listings.pendingListingReviews,
        assignedToMeListingReviews: listings.assignedToMeListingReviews,
      }))
    );
  }
}
