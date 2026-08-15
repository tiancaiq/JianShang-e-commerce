import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminBusinessDetail } from '../../core/models/admin-business.model';
import { AdminBusinessService } from '../../core/services/admin-business.service';
import { AdminService } from '../../core/services/admin.service';
import { AdminBusinessDetailComponent } from './admin-business-detail.component';

describe('AdminBusinessDetailComponent', () => {
  let fixture: ComponentFixture<AdminBusinessDetailComponent>;
  const businessService = jasmine.createSpyObj<AdminBusinessService>('AdminBusinessService', [
    'detail', 'timeline', 'previewCreate', 'create', 'previewRevoke', 'revoke',
  ]);
  const adminService = jasmine.createSpyObj<AdminService>('AdminService', ['hasPermission']);

  beforeEach(async () => {
    businessService.detail.calls.reset();
    businessService.timeline.calls.reset();
    businessService.previewCreate.calls.reset();
    adminService.hasPermission.and.returnValue(true);
    businessService.detail.and.returnValue(of({ data: detailFixture() }));
    businessService.timeline.and.returnValue(of({ data: [] }));
    await TestBed.configureTestingModule({
      imports: [AdminBusinessDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '01B00000000000000000000001' } } } },
        { provide: AdminBusinessService, useValue: businessService },
        { provide: AdminService, useValue: adminService },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminBusinessDetailComponent);
    fixture.detectChanges();
  });

  it('shows preserved records and keeps payouts unavailable', () => {
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Existing orders remain accessible and fulfillable');
    expect(text).toContain('Business payouts');
    expect(text).toContain('Reserved');
  });

  it('expands a marketplace ban to every operational scope before preview', () => {
    const component = fixture.componentInstance;
    component.actionType = 'BAN';
    component.actionChanged();
    component.reasonCode = 'POLICY_VIOLATION';
    component.reason = 'Marketplace safety review.';
    businessService.previewCreate.and.returnValue(of({
      data: {
        proposedAction: {
          enforcementActionId: null,
          targetType: 'BUSINESS',
          targetId: detailFixture().businessId,
          actionType: 'BAN',
          scopes: detailFixture().availableAdminCapabilities.operationalScopes,
          lifecycleState: 'ACTIVE',
          effectiveAt: '2026-08-15T00:00:00Z',
          expiresAt: null,
          version: 0,
          createdAt: '2026-08-15T00:00:00Z',
          revokedAt: null,
          reasonCode: 'POLICY_VIOLATION',
          reason: 'Marketplace safety review.',
          effectiveRestrictions: [],
          correlationId: 'test-correlation',
          dryRun: true,
        },
        overlappingActions: [],
        effectiveRestrictionsAfter: [],
        warnings: [],
        permanent: true,
        targetVersionCurrent: true,
        impactSummary: [],
      },
    }));

    component.previewCreate();

    expect(businessService.previewCreate).toHaveBeenCalledWith(
      '01B00000000000000000000001', jasmine.objectContaining({
      actionType: 'BAN',
      scopes: [
        'BUSINESS_LISTING_CREATION',
        'BUSINESS_LISTING_PUBLICATION',
        'BUSINESS_NEW_SALES',
      ],
      expectedBusinessVersion: 7,
      }),
    );
  });
});

function detailFixture(): AdminBusinessDetail {
  return {
    businessId: '01B00000000000000000000001',
    displayName: 'Harbor Workshop',
    legalName: 'Harbor Workshop LLC',
    businessState: 'ACTIVE',
    storeState: 'ACTIVE',
    verificationState: 'APPROVED',
    verificationReference: '01A00000000000000000000001',
    createdAt: '2026-08-15T00:00:00Z',
    updatedAt: '2026-08-15T00:00:00Z',
    version: 7,
    ownerSummary: null,
    membershipSummaries: [],
    listingSummary: {
      totalCount: 1, draftCount: 0, pendingReviewCount: 0, activeCount: 1,
      pausedCount: 0, removedCount: 0,
    },
    orderSummary: {
      available: false,
      openOrderCount: null,
      historicalOrderCount: null,
      note: 'Order summary is unavailable in this slice.',
    },
    strongestActiveAction: null,
    effectiveRestrictions: [],
    activeEnforcementActions: [],
    historicalEnforcementActions: [],
    availableAdminCapabilities: {
      canReadBusiness: true,
      canRestrict: true,
      canSuspend: true,
      canBan: true,
      canReinstate: true,
      protectedBusiness: false,
      readOnlyReason: null,
      operationalScopes: [
        'BUSINESS_LISTING_CREATION',
        'BUSINESS_LISTING_PUBLICATION',
        'BUSINESS_NEW_SALES',
      ],
    },
  };
}
