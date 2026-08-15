export type UserEnforcementActionType = 'RESTRICT' | 'SUSPEND' | 'BAN';
export type UserEnforcementScope = 'USER_BUYING' | 'USER_SELLING';
export type UserEnforcementState = 'CLEAR' | 'RESTRICTED' | 'SUSPENDED' | 'BANNED';

export interface EffectiveRestriction {
  scope: UserEnforcementScope;
  actionType: UserEnforcementActionType;
  enforcementActionId: string;
}

export interface UserEnforcementAction {
  enforcementActionId: string | null;
  targetType: 'USER';
  targetId: string;
  actionType: UserEnforcementActionType;
  scopes: UserEnforcementScope[];
  lifecycleState: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  effectiveAt: string;
  expiresAt: string | null;
  version: number;
  createdAt: string;
  revokedAt: string | null;
  reasonCode: string;
  reason: string;
  effectiveRestrictions: EffectiveRestriction[];
  correlationId: string;
  dryRun: boolean;
}

export interface AdminUserSummary {
  userId: string;
  safeDisplayName: string;
  safeEmail: string | null;
  emailMasked: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
  individualSellerStatus: string;
  businessMembershipCount: number;
  platformAdmin: boolean;
  strongestActiveAction: UserEnforcementActionType | null;
  activeScopes: UserEnforcementScope[];
  activeEnforcementCount: number;
}

export interface AdminUserSearchPage {
  items: AdminUserSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
}

export interface AdminUserCapabilities {
  canReadUser: boolean;
  canViewPii: boolean;
  canRestrict: boolean;
  canSuspend: boolean;
  canBan: boolean;
  canReinstate: boolean;
  self: boolean;
  protectedAccount: boolean;
  readOnlyReason: string | null;
  operationalScopes: UserEnforcementScope[];
}

export interface AdminUserDetail {
  userId: string;
  safeDisplayName: string;
  safeEmail: string | null;
  emailMasked: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
  accountReference: string;
  authenticationState: string;
  emailVerified: boolean;
  accountType: string;
  individualSellerProfile: null | { status: string; createdAt: string; updatedAt: string; version: number };
  businessMemberships: Array<{
    businessId: string;
    businessDisplayName: string;
    businessStatus: string;
    membershipRole: string;
    membershipStatus: string;
  }>;
  platformAdmin: boolean;
  strongestActiveAction: UserEnforcementActionType | null;
  effectiveRestrictions: EffectiveRestriction[];
  activeEnforcementActions: UserEnforcementAction[];
  historicalEnforcementActions: UserEnforcementAction[];
  availableAdminCapabilities: AdminUserCapabilities;
}

export interface EnforcementPreview {
  proposedAction: UserEnforcementAction;
  overlappingActions: UserEnforcementAction[];
  effectiveRestrictionsAfter: EffectiveRestriction[];
  warnings: string[];
  permanent: boolean;
  targetVersionCurrent: boolean;
}

export interface CreateUserEnforcementRequest {
  actionType: UserEnforcementActionType;
  scopes: UserEnforcementScope[];
  reasonCode: string;
  reason: string;
  effectiveAt: null;
  expiresAt: string | null;
  expectedUserVersion: number;
  idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface RevokeUserEnforcementRequest {
  expectedEnforcementVersion: number;
  reasonCode: string;
  reason: string;
  idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface AdminUserTimelineEntry {
  eventId: string;
  occurredAt: string;
  eventType: string;
  actorType: string;
  actorId: string;
  actorDisplayName: string;
  source: string;
  targetType: string;
  targetId: string;
  enforcementActionId: string;
  caseId: string | null;
  previousState: string | null;
  newState: string;
  actionType: UserEnforcementActionType;
  scopes: UserEnforcementScope[];
  reasonCode: string;
  reason: string;
  correlationId: string;
  requestId: string;
  safeMetadata: Record<string, string>;
}
