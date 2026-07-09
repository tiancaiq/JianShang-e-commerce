import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ListingDraft } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ListingManagementComponent } from './listing-management.component';

describe('ListingManagementComponent', () => {
  let fixture: ComponentFixture<ListingManagementComponent>;
  let component: ListingManagementComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const draft: ListingDraft = {
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
    status: 'DRAFT',
    moderationStatus: 'NOT_SUBMITTED',
    version: 0,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getMyListings']);
    listingService.getMyListings.and.returnValue(of([draft]));

    await TestBed.configureTestingModule({
      imports: [ListingManagementComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ListingManagementComponent);
    component = fixture.componentInstance;
  });

  it('uses marketplace account routes for individual seller listing management', () => {
    fixture.detectChanges();

    expect(component.newListingLink()).toEqual(['/account/listings', 'new']);
    expect(component.editListingLink(draft.id)).toEqual(['/account/listings', draft.id, 'edit']);
    expect(fixture.nativeElement.textContent).toContain('Marketplace account');
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
  });
});
