import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { ListingDraft } from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';
import { BusinessStoreItemsComponent } from './business-store-items.component';

describe('BusinessStoreItemsComponent', () => {
  let fixture: ComponentFixture<BusinessStoreItemsComponent>;
  let businessStoreService: jasmine.SpyObj<BusinessStoreService>;
  let listingService: jasmine.SpyObj<ListingService>;

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
      status: 'ACTIVE',
      version: 0,
      createdAt: '2026-07-08T12:00:00Z',
      updatedAt: '2026-07-08T12:00:00Z',
    },
  };

  const item: ListingDraft = {
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
    version: 0,
    createdAt: '2026-07-08T12:00:00Z',
    updatedAt: '2026-07-08T12:00:00Z',
  };

  beforeEach(async () => {
    businessStoreService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getCurrentStoreContext']);
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getBusinessStoreItems']);
    businessStoreService.getCurrentStoreContext.and.returnValue(of(context));
    listingService.getBusinessStoreItems.and.returnValue(of([item]));

    await TestBed.configureTestingModule({
      imports: [BusinessStoreItemsComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessStoreService, useValue: businessStoreService },
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BusinessStoreItemsComponent);
  });

  it('loads item drafts for the current approved business store', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));

    expect(businessStoreService.getCurrentStoreContext).toHaveBeenCalled();
    expect(listingService.getBusinessStoreItems).toHaveBeenCalledOnceWith(context.businessId);
    expect(host.textContent).toContain('Acme Trading');
    expect(host.textContent).toContain('Store keyboard');
    expect(host.textContent).toContain('SKU-STORE-1');
    expect(links).toContain('/seller/store/items/new');
    expect(links).toContain('/seller/store/items/01L00000000000000000000001/edit');
  });

  it('routes users without approved store context back to business application', () => {
    businessStoreService.getCurrentStoreContext.and.returnValue(of(null));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));

    expect(host.textContent).toContain('No approved store yet');
    expect(listingService.getBusinessStoreItems).not.toHaveBeenCalled();
    expect(links).toEqual(['/seller/business/apply']);
  });
});
