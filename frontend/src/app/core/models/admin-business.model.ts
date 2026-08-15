export type BusinessEnforcementActionType = 'RESTRICT' | 'SUSPEND' | 'BAN';
export type BusinessEnforcementScope = 'BUSINESS_LISTING_CREATION' | 'BUSINESS_LISTING_PUBLICATION' | 'BUSINESS_NEW_SALES';

export interface BusinessEffectiveRestriction {
  scope: BusinessEnforcementScope;
  actionType: BusinessEnforcementActionType;
  enforcementActionId: string;
}

export interface BusinessEnforcementAction {
  enforcementActionId: string | null;
  targetType: 'BUSINESS';
  targetId: string;
  actionType: BusinessEnforcementActionType;
  scopes: BusinessEnforcementScope[];
  lifecycleState: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  effectiveAt: string;
  expiresAt: string | null;
  version: number;
  createdAt: string;
  revokedAt: string | null;
  reasonCode: string;
  reason: string;
  effectiveRestrictions: BusinessEffectiveRestriction[];
  correlationId: string;
  dryRun: boolean;
}

export interface AdminBusinessSummary {
  businessId: string; displayName: string; businessState: string; createdAt: string; updatedAt: string;
  version: number; ownerUserId: string | null; memberCount: number; activeListingCount: number;
  strongestActiveAction: BusinessEnforcementActionType | null; activeScopes: BusinessEnforcementScope[];
  activeEnforcementCount: number;
}

export interface AdminBusinessSearchPage {
  items: AdminBusinessSummary[]; page: number; size: number; totalElements: number; totalPages: number; sort: string;
}

export interface AdminBusinessCapabilities {
  canReadBusiness: boolean; canRestrict: boolean; canSuspend: boolean; canBan: boolean; canReinstate: boolean;
  protectedBusiness: boolean; readOnlyReason: string | null; operationalScopes: BusinessEnforcementScope[];
}

export interface AdminBusinessDetail {
  businessId: string; displayName: string; legalName: string; businessState: string; storeState: string;
  verificationState: string; verificationReference: string; createdAt: string; updatedAt: string; version: number;
  ownerSummary: null | { userId: string; safeDisplayName: string; membershipState: string; strongestUserEnforcement: string | null };
  membershipSummaries: Array<{ userId: string; safeDisplayName: string; role: string; membershipState: string; joinedAt: string; strongestUserEnforcement: string | null }>;
  listingSummary: { totalCount: number; draftCount: number; pendingReviewCount: number; activeCount: number; pausedCount: number; removedCount: number };
  orderSummary: { available: boolean; openOrderCount: number | null; historicalOrderCount: number | null; note: string | null };
  strongestActiveAction: BusinessEnforcementActionType | null; effectiveRestrictions: BusinessEffectiveRestriction[];
  activeEnforcementActions: BusinessEnforcementAction[]; historicalEnforcementActions: BusinessEnforcementAction[];
  availableAdminCapabilities: AdminBusinessCapabilities;
}

export interface BusinessEnforcementPreview {
  proposedAction: BusinessEnforcementAction; overlappingActions: BusinessEnforcementAction[];
  effectiveRestrictionsAfter: BusinessEffectiveRestriction[]; warnings: string[]; permanent: boolean;
  targetVersionCurrent: boolean; impactSummary: string[];
}

export interface CreateBusinessEnforcementRequest {
  actionType: BusinessEnforcementActionType; scopes: BusinessEnforcementScope[]; reasonCode: string; reason: string;
  effectiveAt: null; expiresAt: string | null; expectedBusinessVersion: number; idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface RevokeBusinessEnforcementRequest {
  expectedEnforcementVersion: number; reasonCode: string; reason: string; idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface AdminBusinessTimelineEntry {
  eventId: string; occurredAt: string; eventType: string; actorType: string; actorId: string | null;
  actorDisplayName: string; source: string; targetType: string; targetId: string;
  enforcementActionId: string | null; actionType: BusinessEnforcementActionType | null;
  scopes: BusinessEnforcementScope[]; previousState: string | null; newState: string | null;
  reasonCode: string | null; reason: string | null; caseId: string | null; correlationId: string | null;
  requestId: string | null; safeMetadata: Record<string, string>;
}
