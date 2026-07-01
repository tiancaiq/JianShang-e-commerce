import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing } from '../../testing/listing-test-fixtures';
import { BusinessStoresComponent } from './business-stores.component';

describe('BusinessStoresComponent', () => {
  let fixture: ComponentFixture<BusinessStoresComponent>;
  let listingService: jasmine.SpyObj<ListingService>;

  const individualListing = publicListing({
    title: 'Individual bike',
    sellerType: 'INDIVIDUAL',
  });
  const businessListing = publicListing({
    id: '01L00000000000000000000002',
    sellerType: 'BUSINESS',
    sellerDisplayName: 'Mochi Store',
    title: 'Business plush',
    publicCity: 'Irvine',
    publicRegion: 'CA',
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getPublicListings', 'mediaUrl']);
    listingService.getPublicListings.and.returnValue(of([individualListing, businessListing]));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [BusinessStoresComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BusinessStoresComponent);
  });

  it('shows business storefront listings separately from individual listings', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('Mochi Store');
    expect(text).toContain('Business plush');
    expect(text).not.toContain('Individual bike');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
  });

  it('shows an empty state when there are no business listings', () => {
    listingService.getPublicListings.and.returnValue(of([individualListing]));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approved business storefront listings yet.');
  });

  it('shows a load error state', () => {
    listingService.getPublicListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business stores could not be loaded. HTTP 503.');
  });
});
