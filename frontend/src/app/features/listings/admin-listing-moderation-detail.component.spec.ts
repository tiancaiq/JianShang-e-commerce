import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminListingModerationCaseDetail } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminListingModerationDetailComponent } from './admin-listing-moderation-detail.component';

describe('AdminListingModerationDetailComponent', () => {
  let fixture: ComponentFixture<AdminListingModerationDetailComponent>;
  let component: AdminListingModerationDetailComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let toastService: jasmine.SpyObj<ToastService>;

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
      'resolveListingModerationCase',
      'updateActiveListingByAdmin',
      'removeActiveListingByAdmin',
      'mediaUrl',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    listingService.getListingModerationCaseDetail.and.returnValue(of(detail));
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

    expect(listingService.updateActiveListingByAdmin).not.toHaveBeenCalled();
    expect(component.activeActionErrorMsg()).toBe('Edit reason is required.');
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
