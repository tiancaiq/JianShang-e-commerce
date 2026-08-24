import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { AdminReportDetail, AdminReportPage, ReportAssignment, ReportReason, ReportSeverity, ReportStatus, ReportSubmissionResult, ReportTargetType } from '../models/report.model';
import { InvestigationCaseDetail } from '../models/investigation-case.model';

@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly publicUrl = `${environment.apiGatewayUrl}/api/v1/reports`;
  private readonly adminUrl = `${environment.apiGatewayUrl}/api/v1/admin/reports`;
  constructor(private readonly http: HttpClient) {}

  submit(targetType: ReportTargetType, targetId: string, reasonCode: ReportReason, description: string | null) {
    return this.http.post<ApiDataResponse<ReportSubmissionResult>>(this.publicUrl,
      { targetType, targetId, reasonCode, description }, { withCredentials: true }).pipe(map(response => response.data));
  }

  search(filters: { q?: string; status?: ReportStatus | ''; targetType?: ReportTargetType | ''; reasonCode?: ReportReason | '';
    severity?: ReportSeverity | ''; assignment?: ReportAssignment; unresolved?: boolean; createdFrom?: string; createdTo?: string;
    page: number; size: number; sort: string }) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size).set('sort', filters.sort)
      .set('assignment', filters.assignment || 'ALL');
    for (const [key, value] of Object.entries(filters)) {
      if (!['page', 'size', 'sort', 'assignment'].includes(key) && value) params = params.set(key, value);
    }
    return this.http.get<ApiDataResponse<AdminReportPage>>(this.adminUrl, { params, withCredentials: true }).pipe(map(r => r.data));
  }
  detail(id: string) { return this.http.get<ApiDataResponse<AdminReportDetail>>(`${this.adminUrl}/${id}`, { withCredentials: true }).pipe(map(r => r.data)); }
  claim(id: string, expectedVersion: number) { return this.post(id, 'claim', { expectedVersion }); }
  release(id: string, expectedVersion: number) { return this.post(id, 'release', { expectedVersion }); }
  dismiss(id: string, expectedVersion: number, reasonCode: string, reason: string) { return this.post(id, 'dismiss', { expectedVersion, reasonCode, reason }); }
  ready(id: string, expectedVersion: number, reason: string) { return this.post(id, 'ready-for-investigation', { expectedVersion, reason }); }
  severity(id: string, expectedVersion: number, severity: ReportSeverity, reason: string) {
    return this.http.patch<ApiDataResponse<AdminReportDetail>>(`${this.adminUrl}/${id}/severity`,
      { expectedVersion, severity, reason }, { withCredentials: true }).pipe(map(r => r.data));
  }
  createInvestigationCase(id: string, title: string, severity: ReportSeverity | null, expectedReportVersion: number) {
    return this.http.post<ApiDataResponse<InvestigationCaseDetail>>(`${this.adminUrl}/${id}/investigation-case`,
      { title, severity, expectedReportVersion }, { withCredentials: true }).pipe(map(r => r.data));
  }
  private post(id: string, action: string, body: object) {
    return this.http.post<ApiDataResponse<AdminReportDetail>>(`${this.adminUrl}/${id}/${action}`, body,
      { withCredentials: true }).pipe(map(r => r.data));
  }
}
