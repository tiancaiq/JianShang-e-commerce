import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminListingModerationCaseDetail } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminService } from '../../core/services/admin.service';
import { AdminListingModerationDetailComponent } from './admin-listing-moderation-detail.component';

describe('AdminListingModerationDetailComponent', () => {
  let fixture: ComponentFixture<AdminListingModerationDetailComponent>;
  let component: AdminListingModerationDetailComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let adminService: jasmine.SpyObj<AdminService>;

  const detail: AdminListingModerationCaseDetail = {
    moderationCase: {
      id: '01MC0000000000000000000001',
      caseStatus: 'CLAIMED',
      priority: 'NORMAL',
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
      createdAt: '2026-06-17T12:00:00Z',
      updatedAt: '2026-06-17T12:10:00Z',
      resolvedAt: null,
      submittedByUserId: '01U00000000000000000000001',
      sellerId: '01U00000000000000000000001',
      sellerDisplayName: 'Alex Seller',
      listingId: '01L00000000000000000000001',
      title: 'Used bicycle',
      sellerType: 'INDIVIDUAL',
      listingStatus: 'PENDING_REVIEW',
      listingModerationStatus: 'PENDING',
      priceAmount: 250,
      currency: 'USD',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      sku: null,
      quantity: 1,
    },
    listing: {
      id: '01L00000000000000000000001',
      sellerType: 'INDIVIDUAL',
      individualSellerUserId: '01U00000000000000000000001',
      businessId: null,
      storeId: null,
      categoryId: '01K00000000000000000000001',
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      condition: 'GOOD',
      conditionNotes: null,
      priceAmount: 250,
      currency: 'USD',
      negotiable: true,
      sku: null,
      quantity: 1,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      status: 'PENDING_REVIEW',
      moderationStatus: 'PENDING',
      publicationSource: null,
      publishedAt: null,
      version: 1,
      createdAt: '2026-06-17T11:00:00Z',
      updatedAt: '2026-06-17T12:00:00Z',
      images: [{
        id: '01I00000000000000000000001',
        listingId: '01L00000000000000000000001',
        mediaObjectId: '01M00000000000000000000001',
        displayOrder: 0,
        altText: 'Blue bike',
        moderationStatus: 'PENDING',
        originalFileName: 'bike.png',
        contentType: 'image/png',
        sizeBytes: 1024,
        uploadStatus: 'UPLOADED',
        objectBucket: 'listing-media-local',
        objectKey: 'listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
        uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
        url: '/api/v1/listings/01L00000000000000000000001/media/01M00000000000000000000001/content',
        version: 1,
        createdAt: '2026-06-17T11:30:00Z',
        updatedAt: '2026-06-17T12:00:00Z',
      }],
    },
    decisions: [],
  };

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'getListingModerationCaseDetail',
      'getListingModerationTimeline',
      'resolveListingModerationCase',
      'updateActiveListingByAdmin',
      'removeActiveListingByAdmin',
      'mediaUrl',
      'getAdminListingEnforcement',
      'getAdminListingEnforcementTimeline',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    adminService = jasmine.createSpyObj<AdminService>('AdminService', ['getCurrentAdmin', 'hasPermission']);
    adminService.hasPermission.and.returnValue(true);
    adminService.getCurrentAdmin.and.returnValue(of({
      data: {
        userId: '01A00000000000000000000001',
        role: 'PLATFORM_ADMIN',
        roles: ['SUPER_ADMIN'],
        permissions: [
          'admin.listing.moderation.read',
          'admin.listing.moderation.claim',
          'admin.listing.moderation.resolve',
          'admin.listing.edit',
          'admin.listing.remove',
        ],
        accountState: 'ACTIVE',
      },
    }));
    listingService.getListingModerationCaseDetail.and.returnValue(of(detail));
    listingService.getAdminListingEnforcement.and.returnValue(of({
      listingId: detail.listing.id,
      listingStatus: detail.listing.status,
      moderationStatus: detail.listing.moderationStatus,
      listingVersion: detail.listing.version,
      publicVisibilityAllowed: true,
      purchasabilityAllowed: true,
      strongestActiveAction: null,
      effectiveRestrictions: [],
      activeEnforcementActions: [],
      historicalEnforcementActions: [],
      availableAdminCapabilities: {
        canRead: true,
        canSuspend: false,
        canReinstate: false,
        removedByAdmin: false,
        readOnlyReason: 'Only active listings can receive temporary enforcement.',
        operationalScopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
      },
    }));
    listingService.getAdminListingEnforcementTimeline.and.returnValue(of({ entries: [] }));
    listingService.getListingModerationTimeline.and.returnValue(of([{
      eventId: '01EV0000000000000000000001',
      occurredAt: '2026-06-17T12:10:00Z',
      eventType: 'CASE_CLAIMED',
      actorType: 'PLATFORM_ADMIN',
      actorId: '01A00000000000000000000001',
      actorDisplay: 'Morgan Admin',
      source: 'HUMAN_ADMIN',
      targetType: 'LISTING',
      targetId: detail.listing.id,
      moderationCaseId: detail.moderationCase.id,
      previousState: 'OPEN',
      newState: 'CLAIMED',
      reason: null,
      correlationId: 'listing-claim-correlation',
      metadata: {},
    }]));
    listingService.resolveListingModerationCase.and.returnValue(of({
      ...detail,
      moderationCase: {
        ...detail.moderationCase,
        caseStatus: 'RESOLVED',
        version: 2,
        resolvedAt: '2026-06-17T12:30:00Z',
      },
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
        version: 2,
      },
      decisions: [{
        id: '01D00000000000000000000001',
        listingId: detail.listing.id,
        decision: 'APPROVE',
        reason: 'Listing looks good',
        reviewerUserId: '01A00000000000000000000001',
        listingVersion: 2,
        createdAt: '2026-06-17T12:30:00Z',
      }],
    }));
    listingService.updateActiveListingByAdmin.and.returnValue(of({
      ...detail.listing,
      title: 'Admin edited bicycle',
      status: 'ACTIVE',
      moderationStatus: 'APPROVED',
      version: 3,
    }));
    listingService.removeActiveListingByAdmin.and.returnValue(of({
      ...detail.listing,
      status: 'REMOVED_BY_ADMIN',
      moderationStatus: 'APPROVED',
      version: 3,
    }));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [AdminListingModerationDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ caseId: detail.moderationCase.id }) } },
        },
        { provide: ListingService, useValue: listingService },
        { provide: ToastService, useValue: toastService },
        { provide: AdminService, useValue: adminService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminListingModerationDetailComponent);
    component = fixture.componentInstance;
  });

  it('loads and displays the selected listing moderation case', () => {
    fixture.detectChanges();

    expect(listingService.getListingModerationCaseDetail).toHaveBeenCalledWith(detail.moderationCase.id);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Used bicycle');
    expect(text).toContain('Alex Seller');
    expect(text).toContain('Morgan Admin');
    expect(text).toContain('PENDING_REVIEW / PENDING');
    expect(text).toContain('Blue bike');
    expect(component.decision).toBe('');
    expect(text).toContain('Select decision');
    expect(text).toContain('Case claimed');
    expect(text).toContain('listing-claim-correlation');
  });

  it('shows a case assigned to another admin as read-only before an action', () => {
    adminService.getCurrentAdmin.and.returnValue(of({
      data: {
        userId: '01A00000000000000000000002',
        role: 'PLATFORM_ADMIN',
        roles: ['SUPER_ADMIN'],
        permissions: [
          'admin.listing.moderation.read',
          'admin.listing.moderation.claim',
          'admin.listing.moderation.resolve',
          'admin.listing.edit',
          'admin.listing.remove',
        ],
        accountState: 'ACTIVE',
      },
    }));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('assigned to another admin');
    expect(text).not.toContain('Resolve case');
  });

  it('requires admins to select a decision before resolving', () => {
    fixture.detectChanges();
    component.decision = '';
    component.reason = 'Looks good';

    component.resolveCase();

    expect(listingService.resolveListingModerationCase).not.toHaveBeenCalled();
    expect(component.decisionErrorMsg()).toBe('Select a decision before resolving this case.');
  });

  it('loads review images through the admin media endpoint', () => {
    fixture.detectChanges();

    const image = fixture.nativeElement.querySelector('.image-preview img') as HTMLImageElement;

    expect(image.getAttribute('src'))
      .toBe('/api/v1/admin/listings/01L00000000000000000000001/media/01M00000000000000000000001/content');
    expect(listingService.mediaUrl).toHaveBeenCalledWith(
      '/api/v1/admin/listings/01L00000000000000000000001/media/01M00000000000000000000001/content'
    );
  });

  it('requires a resolution reason before calling the API', () => {
    fixture.detectChanges();
    component.decision = 'APPROVE';
    component.reason = ' ';

    component.resolveCase();
    fixture.detectChanges();

    expect(listingService.resolveListingModerationCase).not.toHaveBeenCalled();
    expect(component.decisionErrorMsg()).toBe('Decision reason is required.');
    const reason = fixture.nativeElement.querySelector('[data-testid="listing-decision-reason"]') as HTMLTextAreaElement;
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(reason.getAttribute('aria-describedby')).toBe('listing-decision-error');
    expect(fixture.nativeElement.querySelector('#listing-decision-error')?.getAttribute('role')).toBe('alert');
  });

  it('resolves a claimed case with the loaded case version', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    fixture.detectChanges();
    component.decision = 'APPROVE';
    component.reason = ' Listing looks good ';

    component.resolveCase();

    expect(listingService.resolveListingModerationCase).toHaveBeenCalledOnceWith(detail.moderationCase.id, 1, {
      decision: 'APPROVE',
      reason: 'Listing looks good',
    });
    expect(component.detail()?.moderationCase.caseStatus).toBe('RESOLVED');
    expect(toastService.success).toHaveBeenCalledWith('Listing moderation case resolved.');
  });

  it('shows conflict guidance when resolution is stale', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    listingService.resolveListingModerationCase.and.returnValue(throwError(() => ({ status: 409 })));
    fixture.detectChanges();
    component.decision = 'APPROVE';
    component.reason = 'Listing looks good';

    component.resolveCase();

    expect(component.decisionErrorMsg()).toBe('The case changed. Refresh and try again.');
  });

  it('shows resolved cases as read-only review details', () => {
    listingService.getListingModerationCaseDetail.and.returnValue(of({
      ...detail,
      moderationCase: {
        ...detail.moderationCase,
        caseStatus: 'RESOLVED',
        resolvedAt: '2026-06-17T12:30:00Z',
      },
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
      },
    }));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('This case is read-only because it is already resolved.');
    expect(text).not.toContain('Resolve case');
  });

  it('edits active listings with the loaded listing version', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    listingService.getListingModerationCaseDetail.and.returnValue(of({
      ...detail,
      moderationCase: {
        ...detail.moderationCase,
        caseStatus: 'RESOLVED',
        resolvedAt: '2026-06-17T12:30:00Z',
      },
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
        version: 2,
      },
    }));
    fixture.detectChanges();

    component.activeEdit.title = 'Admin edited bicycle';
    component.activeEdit.priceAmount = 240;
    component.activeEditReason = 'Removed unsafe wording';
    component.saveActiveListing();

    expect(listingService.updateActiveListingByAdmin).toHaveBeenCalledOnceWith(detail.listing.id, 2, jasmine.objectContaining({
      title: 'Admin edited bicycle',
      price: { amount: 240, currency: 'USD' },
      reason: 'Removed unsafe wording',
    }));
    expect(component.detail()?.listing.title).toBe('Admin edited bicycle');
    expect(toastService.success).toHaveBeenCalledWith('Active listing updated.');
  });

  it('requires a reason before editing active listings', () => {
    listingService.getListingModerationCaseDetail.and.returnValue(of({
      ...detail,
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
      },
    }));
    fixture.detectChanges();

    component.activeEditReason = ' ';
    component.saveActiveListing();
    fixture.detectChanges();

    expect(listingService.updateActiveListingByAdmin).not.toHaveBeenCalled();
    expect(component.activeEditErrorMsg()).toBe('Edit reason is required.');
    expect(component.activeRemoveErrorMsg()).toBe('');
    const editReason = fixture.nativeElement.querySelector('[data-testid="active-edit-reason"]') as HTMLTextAreaElement;
    const removeReason = fixture.nativeElement.querySelector('[data-testid="active-remove-reason"]') as HTMLTextAreaElement;
    expect(editReason.getAttribute('aria-invalid')).toBe('true');
    expect(editReason.getAttribute('aria-describedby')).toBe('active-listing-edit-error');
    expect(removeReason.getAttribute('aria-invalid')).toBe('false');
    expect(removeReason.getAttribute('aria-describedby')).toBeNull();
  });

  it('associates an empty remove reason error only with the remove action', () => {
    listingService.getListingModerationCaseDetail.and.returnValue(of({
      ...detail,
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
      },
    }));
    fixture.detectChanges();

    component.activeEditErrorMsg.set('Old edit error');
    component.activeRemoveReason = ' ';
    component.removeActiveListing();
    fixture.detectChanges();

    expect(listingService.removeActiveListingByAdmin).not.toHaveBeenCalled();
    expect(component.activeEditErrorMsg()).toBe('');
    expect(component.activeRemoveErrorMsg()).toBe('Remove reason is required.');
    const editReason = fixture.nativeElement.querySelector('[data-testid="active-edit-reason"]') as HTMLTextAreaElement;
    const removeReason = fixture.nativeElement.querySelector('[data-testid="active-remove-reason"]') as HTMLTextAreaElement;
    expect(editReason.getAttribute('aria-invalid')).toBe('false');
    expect(editReason.getAttribute('aria-describedby')).toBeNull();
    expect(removeReason.getAttribute('aria-invalid')).toBe('true');
    expect(removeReason.getAttribute('aria-describedby')).toBe('active-listing-remove-error');
  });

  it('removes active listings from the marketplace with the loaded listing version', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    listingService.getListingModerationCaseDetail.and.returnValue(of({
      ...detail,
      listing: {
        ...detail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
        version: 2,
      },
    }));
    fixture.detectChanges();

    component.activeRemoveReason = 'Violates marketplace policy';
    component.removeActiveListing();

    expect(listingService.removeActiveListingByAdmin).toHaveBeenCalledOnceWith(detail.listing.id, 2, {
      reason: 'Violates marketplace policy',
    });
    expect(component.detail()?.listing.status).toBe('REMOVED_BY_ADMIN');
    expect(toastService.success).toHaveBeenCalledWith('Listing removed from marketplace.');
  });
});
