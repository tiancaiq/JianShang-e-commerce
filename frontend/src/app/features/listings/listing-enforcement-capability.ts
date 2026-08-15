import { AdminListingEnforcementDetail } from '../../core/models/listing.model';
import { AdminService } from '../../core/services/admin.service';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';

export interface ListingEnforcementCapabilities {
  canApply: boolean;
  canReinstate: boolean;
  readOnlyReason: string | null;
}

export function listingEnforcementCapabilities(
  detail: AdminListingEnforcementDetail,
  admin: AdminService,
): ListingEnforcementCapabilities {
  const backend = detail.availableAdminCapabilities;
  return {
    canApply: backend.canSuspend && admin.hasPermission(ADMIN_PERMISSIONS.LISTING_SUSPEND)
      && !backend.removedByAdmin && detail.listingStatus === 'ACTIVE',
    canReinstate: backend.canReinstate && admin.hasPermission(ADMIN_PERMISSIONS.LISTING_REINSTATE),
    readOnlyReason: backend.readOnlyReason,
  };
}
