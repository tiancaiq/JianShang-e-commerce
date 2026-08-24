import { AppealCapabilities, AppealDetail } from '../../core/models/appeal.model';

export function appealCapabilities(value: AppealDetail | null): AppealCapabilities {
  return value?.availableAdminCapabilities ?? {
    canRead: false, canClaim: false, canRelease: false, canStartReview: false,
    canAddNote: false, canReview: false, canRecommendUphold: false,
    canRecommendModify: false, canRecommendRevoke: false, canResolveUphold: false,
    canResolveModify: false, canResolveRevoke: false,
    isAssignedToMe: false, isAssignedToOther: false, isFinalRecommendation: false,
    isFinalResolution: false, isReadOnly: true, readOnlyReason: 'Appeal capabilities are unavailable.',
  };
}
