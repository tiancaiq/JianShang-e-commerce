import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { forkJoin, map, Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminBusinessSummary,
  AdminDashboardSummary,
  AdminListingModerationSummary,
  PlatformAdmin,
} from '../models/admin.model';
import { ApiDataResponse } from '../models/auth.model';
import { AdminPermission, AdminRole } from '../security/admin-permissions';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1`;
  readonly currentAdmin = signal<PlatformAdmin | null>(null);

  constructor(private http: HttpClient) {}

  getCurrentAdmin(): Observable<ApiDataResponse<PlatformAdmin>> {
    return this.http.get<ApiDataResponse<PlatformAdmin>>(`${this.baseUrl}/admin/me`, {
      withCredentials: true,
    }).pipe(
      map(response => ({ data: normalizeAdmin(response.data) })),
      tap(response => this.currentAdmin.set(response.data)),
    );
  }

  hasPermission(permission: AdminPermission): boolean {
    return this.currentAdmin()?.permissions.includes(permission) === true;
  }

  hasRole(role: AdminRole): boolean {
    return this.currentAdmin()?.roles.includes(role) === true;
  }

  clearCurrentAdmin(): void {
    this.currentAdmin.set(null);
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

function normalizeAdmin(admin: Partial<PlatformAdmin> & Pick<PlatformAdmin, 'userId' | 'role'>): PlatformAdmin {
  return {
    userId: admin.userId,
    role: admin.role,
    roles: admin.roles ?? [],
    permissions: admin.permissions ?? [],
    accountState: admin.accountState ?? 'SUSPENDED',
  };
}
