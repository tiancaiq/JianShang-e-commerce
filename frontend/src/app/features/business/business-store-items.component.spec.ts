import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import {
  BusinessStoreItemSearchPage,
  ListingDraft,
  ListingImage,
} from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';
import { BusinessStoreItemsComponent } from './business-store-items.component';

describe('BusinessStoreItemsComponent', () => {
  let fixture: ComponentFixture<BusinessStoreItemsComponent>;
  let businessStoreService: jasmine.SpyObj<BusinessStoreService>;
  let listingService: jasmine.SpyObj<ListingService>;
  let router: Router;

  const context: BusinessStoreContext = {
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

  const image: ListingImage = {
    id: '01I00000000000000000000001',
    listingId: '01L00000000000000000000001',
    mediaObjectId: '01M00000000000000000000001',
    displayOrder: 0,
    altText: 'Keyboard product image',
    moderationStatus: 'NOT_SUBMITTED',
    originalFileName: 'keyboard.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    uploadStatus: 'UPLOADED',
    objectBucket: 'listing-media',
    objectKey: 'keyboard.png',
    uploadUrl: 'local-demo://listing-media/keyboard.png',
    url: '/api/v1/listings/01L00000000000000000000001/media/01M00000000000000000000001/content',
    version: 0,
    createdAt: '2026-07-08T12:00:00Z',
    updatedAt: '2026-07-08T12:00:00Z',
  };

  const draftItem: ListingDraft = {
    id: '01L00000000000000000000001',
    sellerType: 'BUSINESS',
    individualSellerUserId: null,
    businessId: context.businessId,
    storeId: context.store.id,
    categoryId: '01K00000000000000000000001',
    title: 'Store keyboard',
    description: 'A store item draft.',
    condition: 'GOOD',
    conditionNotes: null,
    priceAmount: 49.99,
    currency: 'USD',
    negotiable: false,
    sku: 'SKU-STORE-1',
    quantity: 3,
    publicCity: null,
    publicRegion: null,
    status: 'DRAFT',
    moderationStatus: 'NOT_SUBMITTED',
    publicationSource: null,
    publishedAt: null,
    version: 0,
    createdAt: '2026-07-08T12:00:00Z',
    updatedAt: '2026-07-08T12:00:00Z',
    images: [image],
  };
  const activeItem: ListingDraft = {
    ...draftItem,
    id: '01L00000000000000000000002',
    title: 'Active desk mat',
    sku: 'SKU-STORE-2',
    status: 'ACTIVE',
    publicationSource: 'BUSINESS_SELF_PUBLISHED',
    publishedAt: '2026-07-09T12:00:00Z',
    version: 1,
    images: [{ ...image, listingId: '01L00000000000000000000002' }],
  };
  const pausedItem: ListingDraft = {
    ...activeItem,
    id: '01L00000000000000000000003',
    title: 'Paused mouse',
    sku: 'SKU-STORE-3',
    status: 'PAUSED',
    version: 2,
    images: [],
  };
  const page: BusinessStoreItemSearchPage = {
    data: [draftItem, activeItem, pausedItem],
    page: {
      nextCursor: null,
      hasMore: false,
    },
    summary: {
      total: 3,
      draft: 1,
      active: 1,
      paused: 1,
      removed: 0,
    },
  };

  beforeEach(async () => {
    businessStoreService = jasmine.createSpyObj<BusinessStoreService>(
      'BusinessStoreService',
      ['getCurrentStoreContext'],
    );
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'searchBusinessStoreItems',
      'publishBusinessStoreItem',
      'pauseBusinessStoreItem',
      'relistBusinessStoreItem',
      'mediaUrl',
    ]);
    businessStoreService.getCurrentStoreContext.and.returnValue(of(context));
    listingService.searchBusinessStoreItems.and.returnValue(of(page));
    listingService.mediaUrl.and.callFake(url => url || '');
    listingService.publishBusinessStoreItem.and.returnValue(of({
      ...draftItem,
      status: 'ACTIVE',
      publicationSource: 'BUSINESS_SELF_PUBLISHED',
      publishedAt: '2026-07-16T12:00:00Z',
      version: 1,
    }));
    listingService.pauseBusinessStoreItem.and.returnValue(of({
      ...activeItem,
      status: 'PAUSED',
      version: 2,
    }));
    listingService.relistBusinessStoreItem.and.returnValue(of({
      ...pausedItem,
      status: 'ACTIVE',
      version: 3,
    }));

    await TestBed.configureTestingModule({
      imports: [BusinessStoreItemsComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessStoreService, useValue: businessStoreService },
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(BusinessStoreItemsComponent);
  });

  it('loads the paged catalog and renders lifecycle counts and item thumbnails', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    expect(listingService.searchBusinessStoreItems).toHaveBeenCalledOnceWith(context.businessId, {
      q: null,
      status: null,
      cursor: null,
      limit: 24,
    });
    expect(host.textContent).toContain('Acme Trading');
    expect(host.textContent).toContain('Store keyboard');
    expect(host.textContent).toContain('SKU-STORE-1');
    expect(host.textContent).toContain('All');
    expect(host.textContent).toContain('Draft');
    expect(host.textContent).toContain('Active');
    expect(host.textContent).toContain('Paused');
    expect(host.textContent).toContain('Removed');
    expect(host.textContent).toContain('Quantity listed: 3');
    expect(host.textContent).toContain('Catalog information only');
    expect(host.textContent).not.toContain('Manage stock');
    expect(host.querySelector('img')?.getAttribute('alt')).toBe('Keyboard product image');
  });

  it('renders only the valid seller actions for each lifecycle state', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));
    const text = host.textContent || '';

    expect(links).toContain('/seller/store/items/01L00000000000000000000001/edit');
    expect(links).not.toContain('/seller/store/items/01L00000000000000000000002/edit');
    expect(links).toContain('/seller/store/items/01L00000000000000000000003/edit');
    expect(links).toContain('/listings/01L00000000000000000000002');
    expect(text).toContain('Publish');
    expect(text).toContain('Pause');
    expect(text).toContain('Relist');
    expect(text).toContain('View item');
  });

  it('updates one row and catalog counts after publishing without reloading the page', () => {
    fixture.detectChanges();

    fixture.componentInstance.publishItem(draftItem);
    fixture.detectChanges();

    expect(listingService.publishBusinessStoreItem)
      .toHaveBeenCalledOnceWith(context.businessId, draftItem.id, draftItem.version);
    expect(fixture.componentInstance.items().find(item => item.id === draftItem.id)?.status).toBe('ACTIVE');
    expect(fixture.componentInstance.summary()).toEqual({
      total: 3,
      draft: 0,
      active: 2,
      paused: 1,
      removed: 0,
    });
    expect(listingService.searchBusinessStoreItems).toHaveBeenCalledTimes(1);
  });

  it('keeps admin-removed items visible with the recorded reason and no lifecycle action', () => {
    const removedItem: ListingDraft = {
      ...activeItem,
      status: 'REMOVED_BY_ADMIN',
      moderationAction: 'ADMIN_REMOVE',
      moderationReason: 'Product imagery violates marketplace policy.',
      moderationActionAt: '2026-07-18T12:00:00Z',
      version: 2,
    };
    listingService.searchBusinessStoreItems.and.returnValue(of({
      data: [removedItem],
      page: { nextCursor: null, hasMore: false },
      summary: { total: 1, draft: 0, active: 0, paused: 0, removed: 1 },
    }));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const rowActionLabels = Array.from(host.querySelectorAll('.row-actions .row-action'))
      .map(action => action.textContent?.trim());
    expect(host.textContent).toContain('Removed by admin: Product imagery violates marketplace policy.');
    expect(rowActionLabels).not.toContain('Relist');
    expect(rowActionLabels).not.toContain('Pause');
    expect(host.querySelector(`a[href="/seller/store/items/${removedItem.id}/edit"]`)).toBeNull();
  });

  it('writes search and status state to the route query parameters', () => {
    fixture.detectChanges();
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);

    fixture.componentInstance.searchTerm = ' desk mat ';
    fixture.componentInstance.selectedStatus = 'ACTIVE';
    fixture.componentInstance.applySearch();

    expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
      queryParams: {
        q: 'desk mat',
        status: 'ACTIVE',
      },
    }));
  });

  it('routes users without approved store context back to business application', () => {
    businessStoreService.getCurrentStoreContext.and.returnValue(of(null));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));

    expect(host.textContent).toContain('No approved store yet');
    expect(listingService.searchBusinessStoreItems).not.toHaveBeenCalled();
    expect(links).toEqual(['/seller/business/apply']);
  });
});
