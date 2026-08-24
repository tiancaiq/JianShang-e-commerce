import { ReportTargetType } from './report.model';

export type AppealRecommendationOutcome = 'UPHOLD_RECOMMENDED' | 'MODIFY_RECOMMENDED' | 'REVOKE_RECOMMENDED';
export type AppealFinalOutcome = 'UPHELD' | 'MODIFIED' | 'REVOKED';
export type AppealStatus = 'SUBMITTED' | 'UNDER_REVIEW' | AppealRecommendationOutcome | AppealFinalOutcome;
export type AppealReason = 'DECISION_INCORRECT' | 'NEW_EVIDENCE' | 'ACCOUNT_COMPROMISED' | 'MISIDENTIFICATION' | 'ACTION_TOO_SEVERE' | 'POLICY_MISAPPLIED' | 'OTHER';
export type AppealAssignment = 'UNASSIGNED' | 'ASSIGNED_TO_ME' | 'ASSIGNED' | 'ALL';
export type AppealOutcome = AppealRecommendationOutcome;
export type EnforcementActionType = 'RESTRICT' | 'SUSPEND' | 'BAN';

export interface EnforcementNotice {
  enforcementActionId: string; targetType: ReportTargetType; targetId: string; safeTargetLabel: string;
  actionType: EnforcementActionType; scopes: string[]; effectiveAt: string; expiresAt: string | null;
  supportReference: string; appealEligible: boolean; appealIneligibilityReason: string | null;
  appealId: string | null; appealStatus: AppealStatus | null;
}
export interface MyAppeal {
  appealId: string; enforcementActionId: string; targetType: ReportTargetType; targetId: string;
  safeTargetLabel: string; actionType: EnforcementActionType; status: AppealStatus;
  submittedAt: string; updatedAt: string; supportReference: string; resolvedAt: string | null;
  safeOutcomeSummary: string | null; currentEffectiveEnforcementState: string | null;
}
export interface AppealSummary {
  appealId: string; enforcementActionId: string; targetType: ReportTargetType; targetId: string;
  safeTargetLabel: string; actionType: EnforcementActionType; appealReasonCode: AppealReason;
  status: AppealStatus; assignedAdminId: string | null; submittedAt: string; updatedAt: string; version: number;
}
export interface AppealPage { items: AppealSummary[]; page: number; size: number; totalElements: number; totalPages: number; sort: string; }
export interface AppealCapabilities {
  canRead: boolean; canClaim: boolean; canRelease: boolean; canStartReview: boolean; canAddNote: boolean;
  canReview: boolean; canRecommendUphold: boolean; canRecommendModify: boolean; canRecommendRevoke: boolean;
  canResolveUphold: boolean; canResolveModify: boolean; canResolveRevoke: boolean;
  isAssignedToMe: boolean; isAssignedToOther: boolean; isFinalRecommendation: boolean;
  isFinalResolution: boolean; isReadOnly: boolean; readOnlyReason: string | null;
}
export interface AppealTimelineEntry {
  eventId: string; occurredAt: string; eventType: string; actorType: string; actorId: string | null;
  actorDisplayName: string; source: string; previousState: string | null; newState: string;
  reasonCode: string | null; reason: string | null; correlationId: string | null;
  requestId: string | null; safeMetadata: Record<string, string>;
}
export interface ReplacementProposal {
  targetType?: ReportTargetType; targetId?: string; actionType: EnforcementActionType; scopes: string[];
  expiresAt: string | null; reasonCode: string; reason: string; expectedTargetVersion: number;
}
export interface AppealEnforcementSummary {
  enforcementActionId: string; targetType: ReportTargetType; targetId: string;
  actionType: EnforcementActionType; scopes: string[]; lifecycleState: string; effectiveAt: string;
  expiresAt: string | null; version: number; createdAt: string; reasonCode: string; reason: string;
  caseId: string | null;
}
export interface AppealEffectiveRestriction {
  scope: string; actionType: EnforcementActionType; enforcementActionId: string;
}
export interface AppealResolutionPreview {
  outcome: AppealFinalOutcome; appealVersion: number; enforcementVersion: number;
  targetVersion: number;
  originalEnforcementActionId: string; replacementProposal: ReplacementProposal | null;
  predictedEffectiveEnforcementState: string; effectiveRestrictionsAfter: AppealEffectiveRestriction[];
  impactSummary: string[]; warnings: string[];
  previewToken: string; expiresAt: string;
}
export interface AppealResolutionPreviewRequest {
  expectedAppealVersion: number; expectedEnforcementVersion: number;
  expectedTargetVersion: number;
}
export interface AppealResolutionRequest extends AppealResolutionPreviewRequest {
  previewToken: string; idempotencyKey: string; confirmed: boolean;
}
export interface AppealDetail {
  appealId: string; enforcementActionId: string; targetType: ReportTargetType; targetId: string;
  safeTargetLabel: string; status: AppealStatus; submittedAt: string; updatedAt: string; version: number;
  appellantSummary: { appellantType: string; userId: string; safeDisplayName: string };
  reasonCode: AppealReason; explanation: string | null; safeEvidenceReferences: string[];
  assignedAdmin: { userId: string; safeDisplayName: string } | null; reviewStartedAt: string | null;
  reviewedAt: string | null; reviewOutcome: AppealOutcome | null; reviewReasonCode: string | null;
  reviewReason: string | null; replacementProposal: ReplacementProposal | null;
  resolvedAt: string | null; finalOutcome: AppealFinalOutcome | null;
  resolutionSummary: string | null; replacementEnforcementSummary: AppealEnforcementSummary | null;
  enforcementSummary: AppealEnforcementSummary;
  currentEnforcementState: string; originalCaseSummary: { caseId: string; title: string; status: string;
    severity: string; closedAt: string | null; conclusionCode: string | null } | null;
  linkedReports: { reportId: string; reasonCode: string; severity: string; status: string;
    safeTargetLabel: string; createdAt: string }[];
  originalTargetSnapshotContext: Record<string, unknown> | null; currentTargetSummary: Record<string, unknown>;
  enforcementTimeline: AppealTimelineEntry[]; caseTimeline: AppealTimelineEntry[]; appealTimeline: AppealTimelineEntry[];
  internalReviewNotes: { noteId: string; body: string; authorAdminId: string; authorDisplayName: string; createdAt: string }[];
  createdAt: string; availableAdminCapabilities: AppealCapabilities;
}
