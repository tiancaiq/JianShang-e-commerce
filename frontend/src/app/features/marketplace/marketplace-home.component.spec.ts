import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing, publicListingImage } from '../../testing/listing-test-fixtures';
import { MarketplaceHomeComponent } from './marketplace-home.component';

describe('MarketplaceHomeComponent', () => {
  let fixture: ComponentFixture<MarketplaceHomeComponent>;
  let component: MarketplaceHomeComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const listing = publicListing({
    conditionNotes: null,
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
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getPublicListings', 'mediaUrl']);
    listingService.getPublicListings.and.returnValue(of([listing]));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [MarketplaceHomeComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MarketplaceHomeComponent);
    component = fixture.componentInstance;
  });

  it('shows approved public listings on the marketplace home page', () => {
    fixture.detectChanges();

    expect(listingService.getPublicListings).toHaveBeenCalled();
    expect(component.listings()).toEqual([listing]);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('250.00 USD');
    expect(fixture.nativeElement.textContent).toContain('Irvine, CA');
    expect(fixture.nativeElement.querySelector('img')?.getAttribute('src')).toBe('/api/v1/public/listing-media/01I00000000000000000000001');
  });

  it('keeps the marketplace page focused on individual listings', () => {
    const businessListing = publicListing({
      id: '01L00000000000000000000002',
      sellerType: 'BUSINESS',
      sellerDisplayName: 'Mochi Store',
      title: 'Business plush',
    });
    listingService.getPublicListings.and.returnValue(of([listing, businessListing]));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).not.toContain('Business plush');
    expect(fixture.nativeElement.textContent).not.toContain('All sellers');
  });

  it('routes the listing call to action through the marketplace account seller entry', () => {
    fixture.detectChanges();

    const sellLink = fixture.nativeElement.querySelector('.secondary-link') as HTMLAnchorElement;

    expect(sellLink.textContent?.trim()).toBe('My Listings');
    expect(sellLink.getAttribute('href')).toBe('/account/listings');
  });

  it('shows an empty state when no public listings exist', () => {
    listingService.getPublicListings.and.returnValue(of([]));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approved individual listings yet.');
  });

  it('shows a load error state', () => {
    listingService.getPublicListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(component.errorMsg()).toBe('Approved listings could not be loaded. HTTP 503.');
    expect(fixture.nativeElement.textContent).toContain('HTTP 503');
  });
});
