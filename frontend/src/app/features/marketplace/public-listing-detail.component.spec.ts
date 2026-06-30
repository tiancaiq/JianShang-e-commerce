import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing, publicListingImage } from '../../testing/listing-test-fixtures';
import { PublicListingDetailComponent } from './public-listing-detail.component';

describe('PublicListingDetailComponent', () => {
  let fixture: ComponentFixture<PublicListingDetailComponent>;
  let component: PublicListingDetailComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const listing = publicListing({
    images: [
      publicListingImage(),
      publicListingImage({
        id: '01I00000000000000000000002',
        displayOrder: 1,
        url: '/api/v1/public/listing-media/01I00000000000000000000002',
      }),
    ],
  });

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
