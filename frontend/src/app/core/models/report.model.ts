export type ReportTargetType = 'USER' | 'BUSINESS' | 'LISTING';
export type ReportReason = 'SCAM' | 'COUNTERFEIT' | 'PROHIBITED_ITEM' | 'MISLEADING_LISTING' | 'HARASSMENT' | 'SPAM' | 'IMPERSONATION' | 'OTHER';
export type ReportSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ReportStatus = 'SUBMITTED' | 'UNDER_TRIAGE' | 'DISMISSED' | 'READY_FOR_INVESTIGATION' | 'LINKED_TO_CASE';
export type ReportAssignment = 'UNASSIGNED' | 'ASSIGNED_TO_ME' | 'ASSIGNED' | 'ALL';

export interface ReportSubmissionResult { reportId: string; status: ReportStatus; createdAt: string; supportReference: string; }
export interface AdminReportSummary {
  reportId: string; targetType: ReportTargetType; targetId: string; safeTargetLabel: string;
  reasonCode: ReportReason; severity: ReportSeverity; status: ReportStatus; reporterUserId: string;
  assignedAdminId: string | null; createdAt: string; updatedAt: string; version: number; relatedReportCount: number;
}
export interface AdminReportPage { items: AdminReportSummary[]; page: number; size: number; totalElements: number; totalPages: number; sort: string; }
export interface ReportTimelineEntry {
  eventId: string; occurredAt: string; eventType: string; actorType: string; actorId: string;
  actorDisplayName: string; source: string; previousState: string | null; newState: string;
  reasonCode: string | null; reason: string | null; correlationId: string; requestId: string;
  safeMetadata: Record<string, string>;
}
export interface ReportCapabilities {
  canRead: boolean; canClaim: boolean; canRelease: boolean; canChangeSeverity: boolean; canDismiss: boolean;
  canMarkReadyForInvestigation: boolean; assignedToMe: boolean; assignedToOther: boolean; resolved: boolean;
  readOnly: boolean; readOnlyReason: string | null;
  canCreateInvestigationCase: boolean; canLinkInvestigationCase: boolean; canOpenInvestigationCase: boolean;
}
export interface AdminReportDetail extends AdminReportSummary {
  reporterSummary: { userId: string; safeDisplayName: string };
  description: string | null; assignedAdmin: { userId: string; safeDisplayName: string } | null;
  targetSnapshot: Record<string, unknown>; currentTargetSummary: Record<string, unknown>;
  relatedReports: { reportId: string; reasonCode: ReportReason; severity: ReportSeverity; status: ReportStatus; createdAt: string }[];
  currentEnforcementSummary: { actionType: string; scopes: string[] }[];
  auditTimeline: ReportTimelineEntry[]; availableAdminCapabilities: ReportCapabilities;
  investigationCase: { caseId: string; title: string; status: string } | null;
}

export const REPORT_REASONS: Record<ReportTargetType, { value: ReportReason; label: string }[]> = {
  LISTING: [
    { value: 'SCAM', label: 'Scam' }, { value: 'COUNTERFEIT', label: 'Counterfeit item' },
    { value: 'PROHIBITED_ITEM', label: 'Prohibited item' }, { value: 'MISLEADING_LISTING', label: 'Misleading listing' },
    { value: 'SPAM', label: 'Spam' }, { value: 'OTHER', label: 'Other' },
  ],
  USER: [
    { value: 'SCAM', label: 'Scam' }, { value: 'HARASSMENT', label: 'Harassment' },
    { value: 'SPAM', label: 'Spam' }, { value: 'IMPERSONATION', label: 'Impersonation' }, { value: 'OTHER', label: 'Other' },
  ],
  BUSINESS: [
    { value: 'SCAM', label: 'Scam' }, { value: 'COUNTERFEIT', label: 'Counterfeit goods' },
    { value: 'HARASSMENT', label: 'Harassment' }, { value: 'SPAM', label: 'Spam' },
    { value: 'IMPERSONATION', label: 'Impersonation' }, { value: 'OTHER', label: 'Other' },
  ],
};
