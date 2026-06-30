import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { PublicListingDetailComponent } from './public-listing-detail.component';

describe('PublicListingDetailComponent', () => {
  let fixture: ComponentFixture<PublicListingDetailComponent>;
  let component: PublicListingDetailComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const listing: PublicListing = {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    categoryId: '01K00000000000000000000001',
    categorySlug: 'general',
    categoryName: 'General',
    title: 'Used bicycle',
    description: 'A reliable city bike.',
    condition: 'GOOD',
    conditionNotes: 'Small scratch on frame.',
    priceAmount: 250,
    currency: 'USD',
    negotiable: true,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    publishedAt: '2026-06-17T12:00:00Z',
    transactionNotice: 'Payment and delivery are arranged directly by participants.',
    images: [{
      id: '01I00000000000000000000001',
      displayOrder: 0,
      altText: 'Blue bike',
      originalFileName: 'bike.png',
      contentType: 'image/png',
      sizeBytes: 1024,
      uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/image.png',
      url: '/api/v1/public/listing-media/01I00000000000000000000001',
    }],
  };

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getPublicListing', 'mediaUrl']);
    listingService.getPublicListing.and.returnValue(of(listing));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [PublicListingDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => listing.id } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PublicListingDetailComponent);
    component = fixture.componentInstance;
  });

  it('loads public listing details', () => {
    fixture.detectChanges();

    expect(listingService.getPublicListing).toHaveBeenCalledOnceWith(listing.id);
    expect(component.listing()).toEqual(listing);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Individual seller');
    expect(fixture.nativeElement.textContent).toContain('Payment and delivery are arranged directly');
    expect(fixture.nativeElement.querySelector('img')?.getAttribute('src')).toBe('/api/v1/public/listing-media/01I00000000000000000000001');
  });

  it('shows unavailable state when public listing cannot be loaded', () => {
    listingService.getPublicListing.and.returnValue(throwError(() => ({ status: 404 })));

    fixture.detectChanges();

    expect(component.errorMsg()).toBe('This listing is not available.');
    expect(fixture.nativeElement.textContent).toContain('Listing unavailable');
  });
});
