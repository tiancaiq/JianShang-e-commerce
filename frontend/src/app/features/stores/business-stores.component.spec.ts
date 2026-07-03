import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing } from '../../testing/listing-test-fixtures';
import { BusinessStoresComponent } from './business-stores.component';

describe('BusinessStoresComponent public storefront regression', () => {
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
  const category = {
    id: businessListing.categoryId,
    slug: businessListing.categorySlug,
    name: businessListing.categoryName,
    parentId: null,
    displayOrder: 0,
    attributes: [],
  };
  const searchPage = (items = [individualListing, businessListing], nextCursor: string | null = null, hasMore = false) => ({
    data: items,
    page: { nextCursor, hasMore },
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getCategories', 'searchBusinessStoreListings', 'mediaUrl']);
    listingService.getCategories.and.returnValue(of([category]));
    listingService.searchBusinessStoreListings.and.returnValue(of(searchPage()));
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

    expect(listingService.getCategories).toHaveBeenCalled();
    expect(listingService.searchBusinessStoreListings).toHaveBeenCalledWith({
      q: '',
      categoryId: null,
      condition: null,
      minPrice: null,
      maxPrice: null,
      city: '',
      county: '',
      sort: null,
      cursor: null,
    });
    expect(text).toContain('Mochi Store');
    expect(text).toContain('Business plush');
    expect(text).not.toContain('Individual bike');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
  });

  it('shows an empty state when there are no business listings', () => {
    listingService.searchBusinessStoreListings.and.returnValue(of(searchPage([individualListing])));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approved business storefront listings yet.');
  });

  it('shows a load error state', () => {
    listingService.searchBusinessStoreListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business stores could not be loaded. HTTP 503.');
  });

  it('sends store filters to backend search', () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    component.searchTerm = 'plush';
    component.selectedCategoryId = category.id;
    component.selectedCondition = 'NEW';
    component.sortMode = 'price_desc';
    component.minPrice = '5';
    component.maxPrice = '80';
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.runSearch();

    expect(listingService.searchBusinessStoreListings).toHaveBeenCalledWith({
      q: 'plush',
      categoryId: category.id,
      condition: 'NEW',
      minPrice: 5,
      maxPrice: 80,
      city: 'Irvine',
      county: 'Orange County',
      sort: 'price_desc',
      cursor: null,
    });
  });

  it('handles numeric price inputs from browser number controls', () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    component.minPrice = 1;
    component.maxPrice = 2;
    component.runSearch();

    expect(listingService.searchBusinessStoreListings).toHaveBeenCalledWith(jasmine.objectContaining({
      minPrice: 1,
      maxPrice: 2,
    }));
    expect(component.hasActiveSearch()).toBeTrue();
    expect(component.loading()).toBeFalse();
  });

  it('loads the next business store page with the returned cursor', () => {
    const secondBusinessListing = publicListing({
      id: '01L00000000000000000000003',
      sellerType: 'BUSINESS',
      sellerDisplayName: 'Mochi Store',
      title: 'Store figure',
    });
    listingService.searchBusinessStoreListings.and.returnValues(
      of(searchPage([businessListing], 'cursor-2', true)),
      of(searchPage([secondBusinessListing])),
    );

    fixture.detectChanges();
    fixture.componentInstance.loadMore();

    expect(listingService.searchBusinessStoreListings.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      cursor: 'cursor-2',
    }));
    expect(fixture.componentInstance.listings()).toEqual([businessListing, secondBusinessListing]);
  });
});
