import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AdminService } from '../../core/services/admin.service';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminListingEnforcementComponent } from './admin-listing-enforcement.component';

describe('AdminListingEnforcementComponent', () => {
  const listingId = '01ARZ3NDEKTSV4RRFFQ69G5FDA';
  let listings: jasmine.SpyObj<ListingService>;
  let component: AdminListingEnforcementComponent;

  beforeEach(() => {
    listings = jasmine.createSpyObj<ListingService>('ListingService', [
      'getAdminListingEnforcement', 'getAdminListingEnforcementTimeline',
      'previewAdminListingEnforcement', 'createAdminListingEnforcement',
      'previewAdminListingReinstatement', 'reinstateAdminListing',
    ]);
    listings.getAdminListingEnforcement.and.returnValue(of(detail() as any));
    listings.getAdminListingEnforcementTimeline.and.returnValue(of({ entries: [] } as any));
    listings.previewAdminListingEnforcement.and.returnValue(of(preview() as any));
    listings.createAdminListingEnforcement.and.returnValue(of({} as any));
    listings.previewAdminListingReinstatement.and.returnValue(of(preview() as any));
    listings.reinstateAdminListing.and.returnValue(of({} as any));

    TestBed.configureTestingModule({
      imports: [AdminListingEnforcementComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ListingService, useValue: listings },
        { provide: AdminService, useValue: { hasPermission: () => true } },
        { provide: ToastService, useValue: { success: jasmine.createSpy('success') } },
      ],
    });
    const fixture = TestBed.createComponent(AdminListingEnforcementComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('listingId', listingId);
    fixture.detectChanges();
  });

  it('uses no idempotency key for preview and a new key for confirmed enforcement', () => {
    component.actionType = 'SUSPEND';
    component.actionChanged();
    component.reasonCode = 'POLICY_REVIEW';
    component.reason = 'Temporary listing policy review.';
    component.indefiniteAcknowledged = true;

    component.previewApply();
    expect(listings.previewAdminListingEnforcement.calls.mostRecent().args[1].idempotencyKey).toBeNull();

    component.confirmPreview();
    expect(listings.createAdminListingEnforcement.calls.mostRecent().args[1].idempotencyKey)
      .toMatch(/^[0-9a-f-]{36}$/i);
  });

  it('uses no idempotency key for reinstatement preview and a new key for confirmed revocation', () => {
    const action = detail().activeEnforcementActions[0];
    spyOn(window, 'prompt').and.returnValue('Policy review completed.');

    component.previewReinstate(action as any);
    expect(listings.previewAdminListingReinstatement.calls.mostRecent().args[2].idempotencyKey).toBeNull();

    component.confirmPreview();
    expect(listings.reinstateAdminListing.calls.mostRecent().args[2].idempotencyKey)
      .toMatch(/^[0-9a-f-]{36}$/i);
  });

  function detail(): any {
    return {
      listingId, listingStatus: 'ACTIVE', moderationStatus: 'APPROVED', listingVersion: 3,
      publicVisibilityAllowed: false, purchasabilityAllowed: false, strongestActiveAction: 'SUSPEND',
      effectiveRestrictions: [], historicalEnforcementActions: [],
      activeEnforcementActions: [{
        enforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5FDB', actionType: 'SUSPEND',
        scopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'], lifecycleState: 'ACTIVE',
        effectiveAt: '2026-08-15T00:00:00Z', expiresAt: null, version: 0,
        reasonCode: 'POLICY_REVIEW', reason: 'Temporary listing policy review.',
        actorId: '01ARZ3NDEKTSV4RRFFQ69G5FDC', actorDisplayName: 'Platform administrator',
        createdAt: '2026-08-15T00:00:00Z', revokedAt: null,
      }],
      availableAdminCapabilities: {
        canRead: true, canSuspend: true, canReinstate: true, removedByAdmin: false,
        readOnlyReason: null,
        operationalScopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
      },
    };
  }

  function preview(): any {
    return {
      actionType: 'SUSPEND', explicitScopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
      currentListingVersion: 3, existingActiveEnforcement: [], overlappingActions: [],
      effectiveRestrictionsAfter: [], expiresAt: null, strongerRestrictionAlreadyExists: false,
      predictedPublicVisibility: false, predictedPurchasability: false, impactSummary: [], warnings: [],
    };
  }
});
