import { ReportSeverity, ReportStatus, ReportTargetType } from './report.model';

export type InvestigationStatus = 'OPEN' | 'UNDER_INVESTIGATION' | 'READY_FOR_ACTION' | 'CLOSED_NO_ACTION' | 'CLOSED_ACTIONED';
export type CaseProposalStatus = 'DRAFT' | 'VALIDATED' | 'EXECUTED' | 'FAILED' | 'CANCELLED';
export type InvestigationAssignment = 'UNASSIGNED' | 'ASSIGNED_TO_ME' | 'ASSIGNED' | 'ALL';
export type InvestigationEvidenceType = 'REPORT_SNAPSHOT' | 'CURRENT_TARGET_SNAPSHOT' | 'EXISTING_ENFORCEMENT';

export interface InvestigationCaseSummary {
  caseId: string; title: string; status: InvestigationStatus; severity: ReportSeverity;
  primaryTargetType: ReportTargetType; primaryTargetId: string; safePrimaryTargetLabel: string;
  assignedAdminId: string | null; linkedReportCount: number; linkedTargetCount: number; noteCount: number;
  createdAt: string; updatedAt: string; version: number;
}
export interface InvestigationCasePage {
  items: InvestigationCaseSummary[]; page: number; size: number; totalElements: number; totalPages: number; sort: string;
}
export interface InvestigationTarget {
  targetType: ReportTargetType; targetId: string; safeTargetLabel: string; relationshipType: 'PRIMARY' | 'RELATED';
  linkedAt: string; currentState: Record<string, unknown>;
  currentEnforcement: { actionType: string; scopes: string[] }[]; adminDetailPath: string;
}
export interface InvestigationLinkedReport {
  reportId: string; reasonCode: string; severity: ReportSeverity; status: ReportStatus; safeTargetLabel: string;
  description: string | null; targetSnapshot: Record<string, unknown>; createdAt: string; version: number;
}
export interface InvestigationNote {
  noteId: string; body: string; authorAdminId: string; authorDisplayName: string; createdAt: string; correlationId: string;
}
export interface InvestigationEvidence {
  evidenceId: string; evidenceType: InvestigationEvidenceType; referenceType: 'REPORT' | 'CASE_TARGET';
  referenceId: string; label: string; snapshotMetadata: Record<string, unknown>; addedByAdminId: string; createdAt: string;
}
export interface InvestigationTimelineEntry {
  eventId: string; occurredAt: string; eventType: string; actorType: string; actorId: string;
  actorDisplayName: string; source: string; previousState: string | null; newState: string;
  reasonCode: string | null; reason: string | null; correlationId: string; requestId: string;
  safeMetadata: Record<string, string>;
}
export interface CaseProposalCapabilities {
  canEdit: boolean; canCancel: boolean; canDryRun: boolean; canExecute: boolean; canRetry: boolean;
  hasRequiredTargetPermission: boolean;
}
export interface CaseEnforcementProposal {
  proposalId: string; targetType: ReportTargetType; targetId: string; safeTargetLabel: string;
  actionType: 'RESTRICT' | 'SUSPEND' | 'BAN'; scopes: string[]; reasonCode: string; reason: string;
  effectiveAt: string | null; expiresAt: string | null; expectedTargetVersion: number; status: CaseProposalStatus;
  createdByAdminId: string; createdAt: string; updatedAt: string; version: number;
  dryRunValidatedAt: string | null; dryRunTargetVersion: number | null; dryRunResult: Record<string, unknown> | null;
  resultingEnforcementActionId: string | null; executionErrorCode: string | null;
  executionErrorSummary: string | null; correlationId: string;
  availableAdminCapabilities: CaseProposalCapabilities;
}
export interface CaseEnforcementLink {
  proposalId: string; targetType: ReportTargetType; targetId: string; enforcementActionId: string;
  executedAt: string; executedByAdminId: string; correlationId: string;
}
export interface InvestigationCapabilities {
  canRead: boolean; canClaim: boolean; canRelease: boolean; canStartInvestigation: boolean;
  canLinkReport: boolean; canUnlinkReport: boolean; canLinkTarget: boolean; canUnlinkTarget: boolean;
  canAddNote: boolean; canAddEvidence: boolean; canChangeSeverity: boolean; canMarkReadyForAction: boolean;
  canCloseNoAction: boolean; canCreateEnforcementProposal: boolean; canCloseActioned: boolean;
  isCaseReadyForAction: boolean; isAssignedToMe: boolean; isAssignedToOther: boolean;
  isClosed: boolean; isReadOnly: boolean; readOnlyReason: string | null;
}
export interface InvestigationCaseDetail {
  caseId: string; title: string; status: InvestigationStatus; severity: ReportSeverity;
  primaryTarget: InvestigationTarget; linkedTargets: InvestigationTarget[]; linkedReports: InvestigationLinkedReport[];
  assignedAdmin: { userId: string; safeDisplayName: string } | null; notes: InvestigationNote[];
  evidence: InvestigationEvidence[]; enforcementProposals: CaseEnforcementProposal[];
  resultingEnforcement: CaseEnforcementLink[]; caseTimeline: InvestigationTimelineEntry[];
  conclusionCode: string | null; conclusionReason: string | null; readyForActionAt: string | null;
  closedAt: string | null; createdAt: string; updatedAt: string; version: number;
  availableAdminCapabilities: InvestigationCapabilities;
}
