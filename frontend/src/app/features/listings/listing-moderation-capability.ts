import { AdminListingModerationCase } from '../../core/models/listing.model';

export interface ListingModerationCapabilities {
  canClaim: boolean;
  canRelease: boolean;
  canResolve: boolean;
  isReadOnly: boolean;
  readOnlyReason: string;
}

export interface ListingModerationPermissionSet {
  canClaim: boolean;
  canResolve: boolean;
}

export function listingModerationCapabilities(
  moderationCase: AdminListingModerationCase,
  currentAdminUserId: string,
  permissions: ListingModerationPermissionSet = { canClaim: true, canResolve: true },
): ListingModerationCapabilities {
  if (moderationCase.caseStatus === 'RESOLVED') {
    return readOnly('This case is read-only because it is already resolved.');
  }
  if (moderationCase.caseStatus === 'OPEN' && !moderationCase.assignedAdminUserId) {
    if (!permissions.canClaim) {
      return readOnly('You do not have permission to claim listing moderation cases.');
    }
    return {
      canClaim: true,
      canRelease: false,
      canResolve: false,
      isReadOnly: false,
      readOnlyReason: '',
    };
  }
  if (moderationCase.caseStatus === 'CLAIMED'
      && moderationCase.assignedAdminUserId === currentAdminUserId) {
    const listingCanResolve = moderationCase.listingStatus === 'PENDING_REVIEW'
      && moderationCase.listingModerationStatus === 'PENDING';
    const canResolve = permissions.canResolve && listingCanResolve;
    const canRelease = permissions.canClaim;
    return {
      canClaim: false,
      canRelease,
      canResolve,
      isReadOnly: !canResolve && !canRelease,
      readOnlyReason: canResolve
        ? ''
        : !listingCanResolve
          ? 'This case is read-only because the listing is no longer pending review.'
          : !permissions.canResolve && !permissions.canClaim
            ? 'You have read-only access to listing moderation.'
            : '',
    };
  }
  if (moderationCase.caseStatus === 'CLAIMED') {
    return readOnly('This case is read-only because it is assigned to another admin.');
  }
  return readOnly('This moderation case is not available for action.');
}

function readOnly(reason: string): ListingModerationCapabilities {
  return {
    canClaim: false,
    canRelease: false,
    canResolve: false,
    isReadOnly: true,
    readOnlyReason: reason,
  };
}
