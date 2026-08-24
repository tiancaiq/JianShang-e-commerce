import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { InvestigationAssignment, InvestigationCaseDetail, InvestigationCasePage, InvestigationEvidenceType, InvestigationStatus } from '../models/investigation-case.model';
import { ReportSeverity, ReportTargetType } from '../models/report.model';

@Injectable({ providedIn: 'root' })
export class InvestigationCaseService {
  private readonly url = `${environment.apiGatewayUrl}/api/v1/admin/cases`;
  constructor(private readonly http: HttpClient) {}

  search(filters: { q?: string; status?: InvestigationStatus | ''; severity?: ReportSeverity | '';
    targetType?: ReportTargetType | ''; assignment?: InvestigationAssignment; createdFrom?: string;
    createdTo?: string; updatedFrom?: string; updatedTo?: string; page: number; size: number; sort: string }) {
    let params = new HttpParams().set('page', filters.page).set('size', filters.size).set('sort', filters.sort)
      .set('assignment', filters.assignment || 'ALL');
    for (const [key, value] of Object.entries(filters)) {
      if (!['page', 'size', 'sort', 'assignment'].includes(key) && value) params = params.set(key, value);
    }
    return this.http.get<ApiDataResponse<InvestigationCasePage>>(this.url, { params, withCredentials: true })
      .pipe(map(response => response.data));
  }
  detail(caseId: string) { return this.http.get<ApiDataResponse<InvestigationCaseDetail>>(`${this.url}/${caseId}`, { withCredentials: true }).pipe(map(r => r.data)); }
  claim(caseId: string, expectedVersion: number) { return this.post(caseId, 'claim', { expectedVersion }); }
  release(caseId: string, expectedVersion: number) { return this.post(caseId, 'release', { expectedVersion }); }
  start(caseId: string, expectedVersion: number, reason: string) { return this.post(caseId, 'start', { expectedVersion, reason }); }
  linkReport(caseId: string, reportId: string, expectedCaseVersion: number, expectedReportVersion: number) {
    return this.post(caseId, 'reports', { reportId, expectedCaseVersion, expectedReportVersion });
  }
  unlinkReport(caseId: string, reportId: string, expectedCaseVersion: number, expectedReportVersion: number, reason: string) {
    return this.post(caseId, `reports/${reportId}/unlink`, { expectedCaseVersion, expectedReportVersion, reason });
  }
  linkTarget(caseId: string, targetType: ReportTargetType, targetId: string, expectedCaseVersion: number) {
    return this.post(caseId, 'targets', { targetType, targetId, relationshipType: 'RELATED', expectedCaseVersion });
  }
  unlinkTarget(caseId: string, targetType: ReportTargetType, targetId: string, expectedCaseVersion: number, reason: string) {
    return this.post(caseId, `targets/${targetType}/${targetId}/unlink`, { expectedCaseVersion, reason });
  }
  addNote(caseId: string, expectedVersion: number, body: string, idempotencyKey: string = crypto.randomUUID()) {
    return this.post(caseId, 'notes', { expectedVersion, body, idempotencyKey });
  }
  addEvidence(caseId: string, expectedVersion: number, evidenceType: InvestigationEvidenceType,
              referenceType: 'REPORT' | 'CASE_TARGET', referenceId: string, label: string) {
    return this.post(caseId, 'evidence', { expectedVersion, evidenceType, referenceType, referenceId, label });
  }
  severity(caseId: string, expectedVersion: number, severity: ReportSeverity, reason: string) {
    return this.http.patch<ApiDataResponse<InvestigationCaseDetail>>(`${this.url}/${caseId}/severity`,
      { expectedVersion, severity, reason }, { withCredentials: true }).pipe(map(r => r.data));
  }
  readyForAction(caseId: string, expectedVersion: number, reason: string) {
    return this.post(caseId, 'ready-for-action', { expectedVersion, reason });
  }
  closeNoAction(caseId: string, expectedVersion: number, conclusionCode: string, reason: string) {
    return this.post(caseId, 'close-no-action', { expectedVersion, conclusionCode, reason });
  }
  createProposal(caseId: string, body: object) { return this.post(caseId, 'enforcement-proposals', body); }
  updateProposal(caseId: string, proposalId: string, body: object) {
    return this.http.patch<ApiDataResponse<InvestigationCaseDetail>>(
      `${this.url}/${caseId}/enforcement-proposals/${proposalId}`, body, { withCredentials: true })
      .pipe(map(r => r.data));
  }
  dryRunProposal(caseId: string, proposalId: string, expectedCaseVersion: number, expectedProposalVersion: number) {
    return this.post(caseId, `enforcement-proposals/${proposalId}/dry-run`, { expectedCaseVersion, expectedProposalVersion });
  }
  executeProposal(caseId: string, proposalId: string, expectedCaseVersion: number,
                  expectedProposalVersion: number, idempotencyKey: string) {
    return this.post(caseId, `enforcement-proposals/${proposalId}/execute`,
      { expectedCaseVersion, expectedProposalVersion, idempotencyKey });
  }
  cancelProposal(caseId: string, proposalId: string, expectedCaseVersion: number,
                 expectedProposalVersion: number, reason: string) {
    return this.post(caseId, `enforcement-proposals/${proposalId}/cancel`,
      { expectedCaseVersion, expectedProposalVersion, reason });
  }
  closeActioned(caseId: string, expectedCaseVersion: number, reason: string) {
    return this.post(caseId, 'close-actioned', { expectedCaseVersion, reason });
  }
  private post(caseId: string, action: string, body: object) {
    return this.http.post<ApiDataResponse<InvestigationCaseDetail>>(`${this.url}/${caseId}/${action}`, body,
      { withCredentials: true }).pipe(map(r => r.data));
  }
}
