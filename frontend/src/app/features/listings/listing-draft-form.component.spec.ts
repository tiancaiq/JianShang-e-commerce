import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { Category, ListingDraft, ListingMedia } from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { listingDraft, listingImage } from '../../testing/listing-test-fixtures';
import { AGENT_CUSTOMER_SERVICE_ENABLED } from '../agent/agent-customer-service.capability';
import { ListingProposalApplicationCommand } from '../agent/agent-listing-proposal.model';
import { ListingDraftFormComponent } from './listing-draft-form.component';

describe('ListingDraftFormComponent', () => {
  let fixture: ComponentFixture<ListingDraftFormComponent>;
  let component: ListingDraftFormComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let businessStoreService: jasmine.SpyObj<BusinessStoreService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  const category: Category = {
    id: '01K00000000000000000000001',
    slug: 'general',
    name: 'General',
    parentId: null,
    displayOrder: 0,
    attributes: [],
  };

  const draft = listingDraft({
    individualSellerUserId: '01U00000000000000000000001',
    categoryId: category.id,
    conditionNotes: null,
  });

  const media: ListingMedia = {
    id: '01M00000000000000000000001',
    listingId: draft.id,
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: draft.individualSellerUserId,
    businessId: null,
    objectBucket: 'listing-media-local',
    objectKey: 'listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
    originalFileName: 'bike.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    checksumSha256: null,
    uploadStatus: 'PENDING_UPLOAD',
    moderationStatus: 'NOT_SUBMITTED',
    uploadMethod: 'LOCAL_DEMO',
    uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
    version: 0,
    createdAt: '2026-06-16T12:01:00Z',
    updatedAt: '2026-06-16T12:01:00Z',
  };

  const image = listingImage({
    listingId: draft.id,
    mediaObjectId: media.id,
    altText: 'bike.png',
    objectKey: media.objectKey,
    uploadUrl: media.uploadUrl,
  });

  const storeContext: BusinessStoreContext = {
    businessId: '01B00000000000000000000001',
    businessLegalName: 'Acme Trading LLC',
    businessStatus: 'ACTIVE',
    membershipRole: 'OWNER',
    permissions: ['LISTING_DRAFT_CREATE'],
    store: {
      id: '01S00000000000000000000001',
      businessId: '01B00000000000000000000001',
      slug: 'acme-trading',
      name: 'Acme Trading',
      description: null,
      logoUrl: null,
      bannerUrl: null,
      supportEmail: null,
      supportPhone: null,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      status: 'ACTIVE',
      version: 0,
      createdAt: '2026-07-08T12:00:00Z',
      updatedAt: '2026-07-08T12:00:00Z',
    },
  };

  beforeEach(async () => {
    if (!URL.createObjectURL) {
      Object.defineProperty(URL, 'createObjectURL', { value: () => 'blob:listing-preview' });
    }
    if (!URL.revokeObjectURL) {
      Object.defineProperty(URL, 'revokeObjectURL', { value: () => undefined });
    }
    spyOn(URL, 'createObjectURL').and.returnValue('blob:listing-preview');
    spyOn(URL, 'revokeObjectURL');
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'getCategories',
      'createDraft',
      'getListing',
      'updateDraft',
      'submitForReview',
      'closeListing',
      'requestMediaUpload',
      'requestBusinessStoreItemMediaUpload',
      'uploadMediaFile',
      'confirmMediaUpload',
      'confirmBusinessStoreItemMediaUpload',
      'updateListingImages',
      'updateBusinessStoreItemImages',
      'getBusinessStoreItem',
      'createBusinessStoreItem',
      'updateBusinessStoreItem',
      'publishBusinessStoreItem',
      'pauseBusinessStoreItem',
      'relistBusinessStoreItem',
      'mediaUrl',
    ]);
    businessStoreService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getCurrentStoreContext']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    (router as unknown as { url: string }).url = '/account/listings/new';

    listingService.getCategories.and.returnValue(of([category]));
    listingService.createDraft.and.returnValue(of(draft));
    listingService.getListing.and.returnValue(of({ ...draft, images: [image] }));
    listingService.updateDraft.and.returnValue(of({ ...draft, title: 'Updated bicycle', version: 1 }));
    listingService.publishBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      sku: 'SKU-STORE-1',
      status: 'ACTIVE',
      publicationSource: 'BUSINESS_SELF_PUBLISHED',
      publishedAt: '2026-07-16T12:00:00Z',
      images: [image],
      version: 2,
    }));
    listingService.pauseBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      sku: 'SKU-STORE-1',
      status: 'PAUSED',
      publicationSource: 'BUSINESS_SELF_PUBLISHED',
      publishedAt: '2026-07-16T12:00:00Z',
      images: [image],
      version: 3,
    }));
    listingService.relistBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      sku: 'SKU-STORE-1',
      status: 'ACTIVE',
      publicationSource: 'BUSINESS_SELF_PUBLISHED',
      publishedAt: '2026-07-16T12:00:00Z',
      images: [image],
      version: 4,
    }));
    listingService.submitForReview.and.returnValue(of({
      ...draft,
      status: 'PENDING_REVIEW',
      moderationStatus: 'PENDING',
      version: 1,
      images: [image],
    }));
    listingService.closeListing.and.returnValue(of({ ...draft, status: 'CLOSED', version: 1 }));
    listingService.requestMediaUpload.and.returnValue(of(media));
    listingService.requestBusinessStoreItemMediaUpload.and.returnValue(of({
      ...media,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      uploadUrl: `/api/v1/businesses/${storeContext.businessId}/store/items/${draft.id}/media/${media.id}/content`,
    }));
    listingService.uploadMediaFile.and.returnValue(of(undefined));
    listingService.confirmMediaUpload.and.returnValue(of({
      ...media,
      uploadStatus: 'UPLOADED' as const,
      version: 1,
    }));
    listingService.confirmBusinessStoreItemMediaUpload.and.returnValue(of({
      ...media,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      uploadStatus: 'UPLOADED' as const,
      version: 1,
    }));
    listingService.updateListingImages.and.returnValue(of([image]));
    listingService.updateBusinessStoreItemImages.and.returnValue(of([image]));
    listingService.getBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      negotiable: false,
      sku: 'SKU-STORE-1',
      quantity: 3,
      publicCity: null,
      publicRegion: null,
    }));
    listingService.createBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      negotiable: false,
      sku: 'SKU-STORE-1',
      quantity: 3,
      publicCity: null,
      publicRegion: null,
    }));
    listingService.updateBusinessStoreItem.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: storeContext.businessId,
      storeId: storeContext.store.id,
      negotiable: false,
      sku: 'SKU-STORE-1',
      quantity: 4,
      publicCity: null,
      publicRegion: null,
      version: 1,
    }));
    listingService.mediaUrl.and.callFake(url => url || '');
    businessStoreService.getCurrentStoreContext.and.returnValue(of(storeContext));

    await TestBed.configureTestingModule({
      imports: [ListingDraftFormComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ListingService, useValue: listingService },
        { provide: BusinessStoreService, useValue: businessStoreService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => null } } } },
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: false },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ListingDraftFormComponent);
    component = fixture.componentInstance;
  });

  it('loads categories and selects the first one', () => {
    fixture.detectChanges();

    expect(listingService.getCategories).toHaveBeenCalled();
    expect(component.categories()).toEqual([category]);
    expect(component.categoryId).toBe(category.id);
  });

  it('saves an individual draft with seller-entered quantity', () => {
    fixture.detectChanges();
    fillCommonFields();
    component.publicCity = ' Irvine ';
    component.publicRegion = ' CA ';
    component.negotiable = true;
    component.quantity = 2;

    component.saveDraft();

    expect(listingService.createDraft).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      categoryId: category.id,
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      price: { amount: 250, currency: 'USD' },
      negotiable: true,
      quantity: 2,
      location: { city: 'Irvine', region: 'CA' },
    }));
    expect(component.savedId()).toBe(draft.id);
    expect(toastService.success).toHaveBeenCalledWith('Listing draft saved.');
  });

  it('redirects new marketplace account drafts to the account edit route', () => {
    (router as unknown as { url: string }).url = '/account/listings/new';
    fixture.detectChanges();
    fillCommonFields();

    component.saveDraft();

    expect(router.navigate).toHaveBeenCalledWith(
      ['/account/listings', draft.id, 'edit'],
      { queryParamsHandling: 'preserve' },
    );
  });

  it('saves the selected image immediately after creating the draft', () => {
    fixture.detectChanges();
    fillCommonFields();

    component.handleMediaSelected(fileInputEvent(new File(['x'], 'bike.png', { type: 'image/png' })));
    component.saveDraft();

    expect(listingService.createDraft).toHaveBeenCalled();
    expect(listingService.requestMediaUpload).toHaveBeenCalledOnceWith(draft.id, {
      contentType: 'image/png',
      fileName: 'bike.png',
      sizeBytes: 1,
    });
    expect(listingService.uploadMediaFile).toHaveBeenCalledOnceWith(media.uploadUrl, jasmine.any(File));
    expect(listingService.confirmMediaUpload).toHaveBeenCalledOnceWith(draft.id, media.id, {
      sizeBytes: 1,
    });
    expect(listingService.updateListingImages).toHaveBeenCalledOnceWith(draft.id, {
      images: [{ mediaId: media.id, altText: 'bike.png' }],
    });
    expect(component.savedId()).toBe(draft.id);
    expect(component.pendingMediaItems()).toEqual([]);
    expect(component.mediaItems()[0]).toEqual(jasmine.objectContaining({
      id: image.id,
      mediaObjectId: media.id,
      uploadStatus: 'UPLOADED',
    }));
    expect(toastService.success).toHaveBeenCalledWith('Listing draft and images saved.');
  });

  it('shows the selected image before the draft is saved', () => {
    fixture.detectChanges();

    component.handleMediaSelected(fileInputEvent(new File(['x'], 'bike.png', { type: 'image/png' })));

    expect(component.pendingMediaItems()[0].file.name).toBe('bike.png');
    expect(listingService.requestMediaUpload).not.toHaveBeenCalled();
  });

  it('saves a business draft with business fields', () => {
    (router as unknown as { url: string }).url = '/seller/listings/new';
    listingService.createDraft.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: '01B00000000000000000000001',
      sku: 'SKU-100',
      quantity: 3,
      negotiable: false,
    }));
    fixture.detectChanges();
    fillCommonFields();
    component.sellerType = 'BUSINESS';
    component.businessId = '01B00000000000000000000001';
    component.sku = ' SKU-100 ';
    component.quantity = 3;

    component.saveDraft();

    expect(listingService.createDraft).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sellerType: 'BUSINESS',
      businessId: '01B00000000000000000000001',
      sku: 'SKU-100',
      quantity: 3,
      negotiable: false,
      location: null,
    }));
  });

  it('shows forbidden errors without pretending the draft saved', () => {
    listingService.createDraft.and.returnValue(throwError(() => ({ status: 403 })));
    fixture.detectChanges();
    fillCommonFields();

    component.saveDraft();

    expect(component.errorMsg()).toBe('Your account does not have permission to create this listing draft.');
    expect(component.savedId()).toBe('');
  });

  it('validates required title before calling the API', () => {
    fixture.detectChanges();
    fillCommonFields();
    component.title = '';

    component.saveDraft();

    expect(listingService.createDraft).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Title is required.');
  });

  it('requests and confirms image metadata after the draft is saved', () => {
    fixture.detectChanges();
    component.savedId.set(draft.id);

    component.handleMediaSelected(fileInputEvent(new File(['x'], 'bike.png', { type: 'image/png' })));

    expect(listingService.requestMediaUpload).toHaveBeenCalledOnceWith(draft.id, {
      contentType: 'image/png',
      fileName: 'bike.png',
      sizeBytes: 1,
    });
    expect(listingService.uploadMediaFile).toHaveBeenCalledOnceWith(media.uploadUrl, jasmine.any(File));
    expect(listingService.confirmMediaUpload).toHaveBeenCalledOnceWith(draft.id, media.id, {
      sizeBytes: 1,
    });
    expect(listingService.updateListingImages).toHaveBeenCalledOnceWith(draft.id, {
      images: [{ mediaId: media.id, altText: 'bike.png' }],
    });
    expect(component.mediaItems()[0]).toEqual(jasmine.objectContaining({
      id: image.id,
      mediaObjectId: media.id,
      uploadStatus: 'UPLOADED',
    }));
    expect(component.mediaMessage()).toBe('Images attached to listing.');
    expect(toastService.success).toHaveBeenCalledWith('Listing images saved.');
  });

  it('rejects unsupported image types before calling the API', () => {
    fixture.detectChanges();
    component.savedId.set(draft.id);

    component.handleMediaSelected(fileInputEvent(new File(['x'], 'bike.gif', { type: 'image/gif' })));

    expect(component.mediaError()).toBe('Use a JPEG, PNG, or WebP image.');
    expect(listingService.requestMediaUpload).not.toHaveBeenCalled();
  });

  it('submits an editable draft with an attached image for review', () => {
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    (component as unknown as { currentVersion: number }).currentVersion = draft.version;
    component.isEditMode.set(true);
    component.mediaItems.set([image]);

    component.submitForReview();

    expect(listingService.submitForReview).toHaveBeenCalledOnceWith(draft.id, draft.version);
    expect(component.listingStatus()).toBe('PENDING_REVIEW');
    expect(component.canEditDraft()).toBeFalse();
    expect(component.pageTitle()).toBe('Pending review');
    expect(toastService.success).toHaveBeenCalledWith('Listing submitted for review.');
  });

  it('keeps the ordinary editor Agent-hidden and network-silent while the capability is disabled', () => {
    fixture.detectChanges();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    component.isEditMode.set(true);
    (component as unknown as { populateFromDraft: (listing: ListingDraft) => void })
      .populateFromDraft({ ...draft, status: 'DRAFT', images: [{ ...image, uploadStatus: 'UPLOADED' }] });
    fixture.detectChanges();

    expect(component.listingProposalReviewEnabled).toBeFalse();
    expect(fixture.nativeElement.querySelector('app-agent-listing-proposal-review')).toBeNull();
  });

  it('maps only Product-eligible attached images into the proposal boundary', () => {
    fixture.detectChanges();
    component.mediaItems.set([
      { ...image, uploadStatus: 'UPLOADED', moderationStatus: 'NOT_SUBMITTED', contentType: 'image/png' },
      {
        ...image,
        id: '01I00000000000000000000002',
        mediaObjectId: '01M00000000000000000000002',
        uploadStatus: 'UPLOADED',
        moderationStatus: 'REJECTED',
        contentType: 'image/png',
      },
      {
        ...image,
        id: '01I00000000000000000000003',
        mediaObjectId: '01M00000000000000000000003',
        uploadStatus: 'PENDING_UPLOAD',
        moderationStatus: 'NOT_SUBMITTED',
        contentType: 'image/webp',
      },
    ]);

    const mapped = component.proposalReviewMedia();

    expect(mapped.map(item => item.eligible)).toEqual([true, false, false]);
    expect(mapped[0]).toEqual(jasmine.objectContaining({
      mediaId: media.id,
      altText: 'bike.png',
    }));
  });

  it('allows proposal review only for marketplace DRAFT or CHANGES_REQUESTED edit state', () => {
    (router as unknown as { url: string }).url = `/account/listings/${draft.id}/edit`;
    fixture.detectChanges();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    component.isEditMode.set(true);
    component.savedId.set(draft.id);

    component.listingStatus.set('DRAFT');
    expect(component.proposalReviewEligible()).toBeTrue();

    component.listingStatus.set('CHANGES_REQUESTED');
    expect(component.proposalReviewEligible()).toBeTrue();

    component.listingStatus.set('ACTIVE');
    expect(component.proposalReviewEligible()).toBeFalse();

    (router as unknown as { url: string }).url = `/seller/store/items/${draft.id}/edit`;
    component.listingStatus.set('DRAFT');
    expect(component.proposalReviewEligible()).toBeFalse();
  });

  it('applies only explicitly selected seller-final fields with the proposal source version', () => {
    const source = {
      ...draft,
      title: 'Loaded title',
      description: 'Loaded description',
      priceAmount: 250,
      publicCity: 'Irvine',
      negotiable: true,
      quantity: 1,
      version: 12,
      images: [image],
    };
    const applied = {
      ...source,
      title: 'Seller final title',
      version: 13,
    };
    enableProposalApplication(source);
    listingService.updateDraft.and.returnValue(of(applied));
    const command: ListingProposalApplicationCommand = {
      proposalId: '01K00000000000000000000099',
      listingId: draft.id,
      sourceListingVersion: 12,
      fields: { title: ' Seller final title ' },
    };

    component.applyListingProposal(command);

    expect(listingService.updateDraft).toHaveBeenCalledOnceWith(
      draft.id,
      12,
      jasmine.objectContaining({
        title: 'Seller final title',
        description: 'Loaded description',
        categoryId: category.id,
        price: { amount: 250, currency: source.currency },
        negotiable: true,
        quantity: 1,
      }),
    );
    expect(listingService.submitForReview).not.toHaveBeenCalled();
    expect(component.title).toBe('Seller final title');
    expect(component.description).toBe('Loaded description');
    expect(component.proposalApplicationState()).toBe('APPLIED');
    expect(component.proposalAppliedVersion()).toBe(13);
    expect(toastService.success)
      .toHaveBeenCalledWith('Selected proposal fields applied to the listing draft.');
  });

  it('requires a real current category ID and never converts an AI category label', () => {
    enableProposalApplication({ ...draft, version: 12, images: [image] });

    component.applyListingProposal({
      proposalId: '01K00000000000000000000099',
      listingId: draft.id,
      sourceListingVersion: 12,
      fields: { categoryId: 'Bicycles' },
    });

    expect(listingService.updateDraft).not.toHaveBeenCalled();
    expect(component.proposalApplicationState()).toBe('FAILED');
    expect(component.proposalApplicationMessage()).toContain('current category');
  });

  it('does not apply while ordinary editor changes are unsaved', () => {
    enableProposalApplication({ ...draft, version: 12, images: [image] });
    component.price = 999;

    component.applyListingProposal({
      proposalId: '01K00000000000000000000099',
      listingId: draft.id,
      sourceListingVersion: 12,
      fields: { title: 'Seller final title' },
    });

    expect(listingService.updateDraft).not.toHaveBeenCalled();
    expect(component.proposalApplicationState()).toBe('FAILED');
    expect(component.proposalApplicationMessage()).toContain('ordinary editor changes');
  });

  it('shows a stale Product conflict without retrying or marking the proposal applied', () => {
    enableProposalApplication({ ...draft, version: 12, images: [image] });
    listingService.updateDraft.and.returnValue(throwError(() => ({
      status: 409,
      error: { error: { code: 'LISTING_VERSION_CONFLICT' } },
    })));

    component.applyListingProposal({
      proposalId: '01K00000000000000000000099',
      listingId: draft.id,
      sourceListingVersion: 12,
      fields: { description: 'Seller final description' },
    });

    expect(listingService.updateDraft).toHaveBeenCalledTimes(1);
    expect(component.proposalApplicationState()).toBe('CONFLICT');
    expect(component.proposalApplicationMessage()).toContain('no automatic retry');
    expect(component.proposalAppliedVersion()).toBeNull();
    expect(listingService.submitForReview).not.toHaveBeenCalled();
  });

  it('keeps Product failure distinct from an applied local outcome', () => {
    enableProposalApplication({ ...draft, version: 12, images: [image] });
    listingService.updateDraft.and.returnValue(throwError(() => ({ status: 503 })));

    component.applyListingProposal({
      proposalId: '01K00000000000000000000099',
      listingId: draft.id,
      sourceListingVersion: 12,
      fields: { title: 'Seller final title' },
    });

    expect(component.proposalApplicationState()).toBe('FAILED');
    expect(component.proposalApplicationMessage()).toContain('Nothing is marked applied');
    expect(component.proposalAppliedVersion()).toBeNull();
    expect(listingService.submitForReview).not.toHaveBeenCalled();
  });

  it('enables submit after an updated draft response includes an attached image', () => {
    listingService.updateDraft.and.returnValue(of({
      ...draft,
      version: 1,
      images: [image],
    }));
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    (component as unknown as { currentVersion: number }).currentVersion = draft.version;
    component.isEditMode.set(true);
    component.mediaItems.set([]);

    component.saveDraft();
    fixture.detectChanges();

    const submitButton = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('button'))
      .find(button => button.textContent?.includes('Submit for review')) as HTMLButtonElement;
    expect(component.mediaItems()).toEqual([image]);
    expect(submitButton.disabled).toBeFalse();
  });

  it('requires an attached image before submitting for review', () => {
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    (component as unknown as { currentVersion: number }).currentVersion = draft.version;
    component.isEditMode.set(true);
    component.mediaItems.set([]);

    component.submitForReview();

    expect(listingService.submitForReview).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Add at least one image before submitting for review.');
  });

  it('saves active listing edits before submitting for review', () => {
    listingService.updateDraft.and.returnValue(of({ ...draft, status: 'DRAFT', version: 3, images: [image] }));
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    (component as unknown as { currentVersion: number }).currentVersion = 2;
    component.isEditMode.set(true);
    component.listingStatus.set('ACTIVE');
    component.mediaItems.set([image]);
    (component as unknown as { rememberCurrentFormSnapshot: () => void }).rememberCurrentFormSnapshot();
    component.title = 'Updated bicycle';
    fixture.detectChanges();

    const submitButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Submit for review'));

    expect(component.pageTitle()).toBe('Edit Active Listing');
    expect(component.pageDescription()).toBe(
      'This listing is approved and public. Save changes and submit them for review to update the public listing.',
    );
    expect(component.saveButtonLabel()).toBe('Update active listing');
    expect(submitButton).toBeTruthy();
    expect(submitButton?.disabled).toBeFalse();

    component.submitForReview();

    expect(listingService.updateDraft).toHaveBeenCalledOnceWith(draft.id, 2, jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      title: 'Updated bicycle',
    }));
    expect(listingService.submitForReview).toHaveBeenCalledOnceWith(draft.id, 3);
    expect(component.errorMsg()).toBe('');
  });

  it('creates store item drafts from the approved business store context', () => {
    (router as unknown as { url: string }).url = '/seller/store/items/new';
    fixture.detectChanges();
    fillCommonFields();
    component.sku = ' SKU-STORE-1 ';
    component.quantity = 3;
    component.negotiable = true;

    component.saveDraft();

    expect(businessStoreService.getCurrentStoreContext).toHaveBeenCalled();
    expect(listingService.createBusinessStoreItem).toHaveBeenCalledOnceWith(
      storeContext.businessId,
      jasmine.objectContaining({
        sellerType: 'BUSINESS',
        businessId: storeContext.businessId,
        sku: 'SKU-STORE-1',
        quantity: 3,
        negotiable: false,
        location: null,
      }),
    );
    expect(listingService.createDraft).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(
      ['/seller/store/items', draft.id, 'edit'],
      { queryParamsHandling: 'preserve' },
    );
  });

  it('explains duplicate business SKUs instead of reporting a stale draft', () => {
    (router as unknown as { url: string }).url = '/seller/store/items/new';
    listingService.createBusinessStoreItem.and.returnValue(throwError(() => ({
      status: 409,
      error: {
        error: {
          code: 'BUSINESS_SKU_CONFLICT',
          message: 'This store already has an item with that SKU. Edit the existing item or use a different SKU.',
        },
      },
    })));
    fixture.detectChanges();
    fillCommonFields();
    component.sku = '2';
    component.quantity = 1;

    component.saveDraft();

    expect(component.errorMsg()).toBe(
      'This store already has an item with that SKU. Edit the existing item or use a different SKU.',
    );
    expect(component.savedId()).toBe('');
  });

  it('allows paused business items to be edited but keeps active business items read-only', () => {
    (router as unknown as { url: string }).url = `/seller/store/items/${draft.id}/edit`;
    fixture.detectChanges();
    component.isEditMode.set(true);

    component.listingStatus.set('ACTIVE');
    fixture.detectChanges();

    expect(component.canEditDraft()).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Pause it before changing item details or images.');

    component.listingStatus.set('PAUSED');
    fixture.detectChanges();

    expect(component.canEditDraft()).toBeTrue();
    const updateButton = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find(button => button.textContent?.includes('Update item'));
    expect(updateButton).toBeTruthy();
  });

  it('preserves catalog filters when returning from the business item form', () => {
    (router as unknown as { url: string }).url = `/seller/store/items/${draft.id}/edit`;
    fixture.detectChanges();

    component.cancelDraft();

    expect(router.navigate).toHaveBeenCalledWith(
      ['/seller/store/items'],
      { queryParamsHandling: 'preserve' },
    );
  });

  it('uploads selected images through business store item media routes after creating a store item', () => {
    (router as unknown as { url: string }).url = '/seller/store/items/new';
    fixture.detectChanges();
    fillCommonFields();
    component.sku = ' SKU-STORE-1 ';
    component.quantity = 3;

    component.handleMediaSelected(fileInputEvent(new File(['x'], 'keyboard.png', { type: 'image/png' })));
    component.saveDraft();

    expect(listingService.createBusinessStoreItem).toHaveBeenCalled();
    expect(listingService.requestBusinessStoreItemMediaUpload).toHaveBeenCalledOnceWith(storeContext.businessId, draft.id, {
      contentType: 'image/png',
      fileName: 'keyboard.png',
      sizeBytes: 1,
    });
    expect(listingService.confirmBusinessStoreItemMediaUpload).toHaveBeenCalledOnceWith(storeContext.businessId, draft.id, media.id, {
      sizeBytes: 1,
    });
    expect(listingService.updateBusinessStoreItemImages).toHaveBeenCalledOnceWith(storeContext.businessId, draft.id, {
      images: [{ mediaId: media.id, altText: 'bike.png' }],
    });
    expect(listingService.updateListingImages).not.toHaveBeenCalled();
    expect(toastService.success).toHaveBeenCalledWith('Listing draft and images saved.');
  });

  it('shows pending review listings as read-only with clear image moderation status', () => {
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    component.isEditMode.set(true);
    component.listingStatus.set('PENDING_REVIEW');
    component.mediaItems.set([{
      ...image,
      moderationStatus: 'PENDING',
      uploadStatus: 'UPLOADED',
    }]);
    (component as unknown as { rememberCurrentFormSnapshot: () => void }).rememberCurrentFormSnapshot();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    const submitButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Submit for review'));
    const removeButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Remove'));

    expect(component.pageTitle()).toBe('Pending review');
    expect(component.canEditDraft()).toBeFalse();
    expect(text).toContain('Your listing and uploaded images are locked while an admin reviews them.');
    expect(text).toContain('Upload: Uploaded');
    expect(text).toContain('Review: Pending');
    expect(submitButton).toBeUndefined();
    expect(removeButton?.disabled).toBeTrue();
  });

  it('keeps pending review listings locked instead of allowing resubmission edits', () => {
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    component.isEditMode.set(true);
    component.listingStatus.set('PENDING_REVIEW');
    component.mediaItems.set([image]);
    (component as unknown as { rememberCurrentFormSnapshot: () => void }).rememberCurrentFormSnapshot();
    fixture.detectChanges();

    let submitButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.includes('Submit for review'));

    expect(component.canSubmitForReview()).toBeFalse();
    expect(component.canEditDraft()).toBeFalse();
    expect(submitButton).toBeUndefined();

    component.description = 'Updated description.';

    expect(component.canSubmitForReview()).toBeFalse();
    expect(component.canEditDraft()).toBeFalse();
  });

  it('shows the exact moderation reason and allows requested changes to be edited and resubmitted', () => {
    const moderationReason = 'MOD-E2E request changes 1784458645266';
    const changesRequestedListing: ListingDraft = {
      ...draft,
      status: 'CHANGES_REQUESTED',
      moderationStatus: 'CHANGES_REQUESTED',
      moderationAction: 'REQUEST_CHANGES',
      moderationReason,
      version: 2,
      images: [{ ...image, moderationStatus: 'CHANGES_REQUESTED' }],
    };
    listingService.updateDraft.and.returnValue(of({
      ...draft,
      status: 'DRAFT',
      moderationStatus: 'NOT_SUBMITTED',
      moderationAction: 'REQUEST_CHANGES',
      moderationReason,
      version: 3,
      images: [image],
    }));
    fixture.detectChanges();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    component.isEditMode.set(true);
    (component as unknown as { populateFromDraft: (listing: ListingDraft) => void })
      .populateFromDraft(changesRequestedListing);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    const titleInput = fixture.nativeElement.querySelector('input[name="title"]') as HTMLInputElement;
    const fileInput = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    const removeButton = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find(button => button.textContent?.includes('Remove'));

    expect(component.pageTitle()).toBe('Update requested changes');
    expect(component.pageDescription()).toContain('Review the admin feedback');
    expect(component.canEditDraft()).toBeTrue();
    expect(text).toContain(moderationReason);
    expect(text).toContain('Update the listing details or images, then submit it for review again.');
    expect(titleInput.disabled).toBeFalse();
    expect(fileInput.disabled).toBeFalse();
    expect(removeButton?.disabled).toBeFalse();

    component.title = 'Updated after moderation';
    expect(component.canSubmitForReview()).toBeTrue();

    component.submitForReview();

    expect(listingService.updateDraft).toHaveBeenCalledOnceWith(draft.id, 2, jasmine.objectContaining({
      title: 'Updated after moderation',
    }));
    expect(listingService.submitForReview).toHaveBeenCalledOnceWith(draft.id, 3);
  });

  it('allows closed listings to be edited and resubmitted without showing another close action', () => {
    listingService.updateDraft.and.returnValue(of({ ...draft, status: 'DRAFT', version: 4, images: [image] }));
    fixture.detectChanges();
    fillCommonFields();
    (component as unknown as { editListingId: string }).editListingId = draft.id;
    (component as unknown as { currentVersion: number }).currentVersion = 3;
    component.isEditMode.set(true);
    component.listingStatus.set('CLOSED');
    component.mediaItems.set([image]);
    (component as unknown as { rememberCurrentFormSnapshot: () => void }).rememberCurrentFormSnapshot();
    component.title = 'Reopened bicycle';
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>);
    const closeButton = buttons.find(button => button.textContent?.includes('Close listing'));
    const submitButton = buttons.find(button => button.textContent?.includes('Submit for review'));

    expect(component.canEditDraft()).toBeTrue();
    expect(component.canCloseListing()).toBeFalse();
    expect(component.canSubmitForReview()).toBeTrue();
    expect(closeButton).toBeUndefined();
    expect(submitButton?.disabled).toBeFalse();

    component.submitForReview();

    expect(listingService.updateDraft).toHaveBeenCalledOnceWith(draft.id, 3, jasmine.objectContaining({
      title: 'Reopened bicycle',
    }));
    expect(listingService.submitForReview).toHaveBeenCalledOnceWith(draft.id, 4);
  });

  function fillCommonFields(): void {
    component.categoryId = category.id;
    component.title = ' Used bicycle ';
    component.description = ' A reliable city bike. ';
    component.condition = 'GOOD';
    component.price = 250;
    component.currency = 'usd';
  }

  function enableProposalApplication(source: ListingDraft): void {
    (router as unknown as { url: string }).url = `/account/listings/${source.id}/edit`;
    (component as unknown as { listingProposalReviewEnabled: boolean })
      .listingProposalReviewEnabled = true;
    (component as unknown as { editListingId: string }).editListingId = source.id;
    component.isEditMode.set(true);
    (component as unknown as { populateFromDraft: (listing: ListingDraft) => void })
      .populateFromDraft(source);
  }

  function fileInputEvent(file: File): Event {
    return {
      target: {
        files: {
          0: file,
          length: 1,
          item: () => file,
        },
        value: '',
      },
    } as unknown as Event;
  }
});
