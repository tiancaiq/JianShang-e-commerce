import { AdminListingModerationCase } from '../../core/models/listing.model';
import { listingModerationCapabilities } from './listing-moderation-capability';

describe('listingModerationCapabilities', () => {
  const adminId = '01A00000000000000000000001';
  const moderationCase: AdminListingModerationCase = {
    id: '01MC0000000000000000000001',
    caseStatus: 'OPEN',
    priority: 'NORMAL',
    assignedAdminUserId: null,
    assignedAdminDisplayName: null,
    version: 0,
    createdAt: '2026-08-12T08:00:00Z',
    updatedAt: '2026-08-12T08:00:00Z',
    resolvedAt: null,
    submittedByUserId: '01U00000000000000000000001',
    sellerId: '01U00000000000000000000001',
    sellerDisplayName: 'Seller',
    listingId: '01L00000000000000000000001',
    title: 'Listing',
    sellerType: 'INDIVIDUAL',
    listingStatus: 'PENDING_REVIEW',
    listingModerationStatus: 'PENDING',
    priceAmount: 10,
    currency: 'USD',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    sku: null,
    quantity: 1,
  };

  it('combines read-only permission state with case ownership capabilities', () => {
    const capabilities = listingModerationCapabilities({
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: adminId,
    }, adminId, { canClaim: false, canResolve: false });

    expect(capabilities.canClaim).toBeFalse();
    expect(capabilities.canRelease).toBeFalse();
    expect(capabilities.canResolve).toBeFalse();
    expect(capabilities.isReadOnly).toBeTrue();
    expect(capabilities.readOnlyReason).toBe('You have read-only access to listing moderation.');
  });

  it('allows an unassigned open case to be claimed', () => {
    expect(listingModerationCapabilities(moderationCase, adminId)).toEqual(jasmine.objectContaining({
      canClaim: true,
      canRelease: false,
      canResolve: false,
      isReadOnly: false,
    }));
  });

  it('allows the assigned admin to release and resolve', () => {
    const result = listingModerationCapabilities({
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: adminId,
    }, adminId);
    expect(result.canRelease).toBeTrue();
    expect(result.canResolve).toBeTrue();
    expect(result.isReadOnly).toBeFalse();
  });

  it('makes another admins claimed case read-only', () => {
    const result = listingModerationCapabilities({
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: '01A00000000000000000000002',
    }, adminId);
    expect(result.isReadOnly).toBeTrue();
    expect(result.canRelease).toBeFalse();
    expect(result.canResolve).toBeFalse();
    expect(result.readOnlyReason).toContain('assigned to another admin');
  });

  it('makes resolved cases read-only', () => {
    const result = listingModerationCapabilities({
      ...moderationCase,
      caseStatus: 'RESOLVED',
      resolvedAt: '2026-08-12T09:00:00Z',
    }, adminId);
    expect(result.isReadOnly).toBeTrue();
    expect(result.readOnlyReason).toContain('already resolved');
  });
});
