import { AdminListingEnforcementDetail } from '../../core/models/listing.model';
import { AdminService } from '../../core/services/admin.service';
import { listingEnforcementCapabilities } from './listing-enforcement-capability';

describe('listingEnforcementCapabilities', () => {
  const detail = (overrides: Partial<AdminListingEnforcementDetail> = {}): AdminListingEnforcementDetail => ({
    listingId: '01ARZ3NDEKTSV4RRFFQ69G5FDA',
    listingStatus: 'ACTIVE',
    moderationStatus: 'APPROVED',
    listingVersion: 3,
    publicVisibilityAllowed: true,
    purchasabilityAllowed: true,
    strongestActiveAction: null,
    effectiveRestrictions: [],
    activeEnforcementActions: [],
    historicalEnforcementActions: [],
    availableAdminCapabilities: {
      canRead: true,
      canSuspend: true,
      canReinstate: true,
      removedByAdmin: false,
      readOnlyReason: null,
      operationalScopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
    },
    ...overrides,
  });

  it('requires both backend and session permission signals', () => {
    const allowed = { hasPermission: () => true } as unknown as AdminService;
    const denied = { hasPermission: () => false } as unknown as AdminService;

    expect(listingEnforcementCapabilities(detail(), allowed).canApply).toBeTrue();
    expect(listingEnforcementCapabilities(detail(), denied).canApply).toBeFalse();
  });

  it('keeps administrative removal read-only', () => {
    const admin = { hasPermission: () => true } as unknown as AdminService;
    const removed = detail({
      listingStatus: 'REMOVED_BY_ADMIN',
      availableAdminCapabilities: {
        ...detail().availableAdminCapabilities,
        removedByAdmin: true,
        readOnlyReason: 'Administratively removed listings are read-only.',
      },
    });

    expect(listingEnforcementCapabilities(removed, admin).canApply).toBeFalse();
    expect(listingEnforcementCapabilities(removed, admin).readOnlyReason).toContain('read-only');
  });
});
