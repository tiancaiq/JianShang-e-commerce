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
    publicRegion: 'Orange County',
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

  it('presents verified business storefronts instead of individual listings', () => {
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
    expect(text).toContain('Browse verified stores');
    expect(text).toContain('Shop from approved business sellers and discover local storefronts.');
    expect(text).toContain('Business Storefronts');
    expect(text).toContain('1 store found');
    expect(text).toContain('Mochi Store');
    expect(text).toContain('Verified');
    expect(text).toContain('Irvine, Orange County');
    expect(text).toContain('1 active listing');
    expect(text).toContain(businessListing.categoryName);
    expect(text).toContain('Visit store');
    expect(text).toContain('Create store');
    expect(text).not.toContain('Individual bike');
    expect(text).not.toContain('Condition');
    expect(text).not.toContain('Min price');
    expect(text).not.toContain('Max price');
    expect(text).not.toContain('No approved business storefront listings yet');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');

    const createStoreLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Create store');
    expect(createStoreLink?.getAttribute('href')).toBe('/business/apply');

    const visitStoreLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Visit store');
    expect(visitStoreLink?.getAttribute('href')).toBe('/stores');
  });

  it('shows the requested empty state when there are no business stores', () => {
    listingService.searchBusinessStoreListings.and.returnValue(of(searchPage([individualListing])));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('0 stores found');
    expect(text).toContain('No verified stores found');
    expect(text).toContain('Try changing your filters or search another city.');
    expect(text).toContain('Clear filters');
    expect(text).not.toContain('No approved business storefront listings yet');
  });

  it('shows a load error state', () => {
    listingService.searchBusinessStoreListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business stores could not be loaded. HTTP 503.');
  });

  it('sends store-relevant filters to backend search', () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    component.searchTerm = 'plush';
    component.selectedCategoryId = category.id;
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.runSearch();

    expect(listingService.searchBusinessStoreListings).toHaveBeenCalledWith({
      q: 'plush',
      categoryId: category.id,
      condition: null,
      minPrice: null,
      maxPrice: null,
      city: 'Irvine',
      county: 'Orange County',
      sort: null,
      cursor: null,
    });
    expect(component.hasActiveSearch()).toBeTrue();
  });

  it('clears storefront filters back to the verified active default', () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    component.searchTerm = 'plush';
    component.selectedCategoryId = category.id;
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.verifiedOnly = false;
    component.hasActiveListingsOnly = false;
    component.clearFilters();

    expect(component.searchTerm).toBe('');
    expect(component.selectedCategoryId).toBe('ALL');
    expect(component.city).toBe('');
    expect(component.county).toBe('');
    expect(component.verifiedOnly).toBeTrue();
    expect(component.hasActiveListingsOnly).toBeTrue();
    expect(component.hasActiveSearch()).toBeFalse();
    expect(listingService.searchBusinessStoreListings).toHaveBeenCalled();
  });

  it('loads the next business store page with the returned cursor', () => {
    const secondBusinessListing = publicListing({
      id: '01L00000000000000000000003',
      sellerType: 'BUSINESS',
      sellerDisplayName: 'Mochi Store',
      title: 'Store figure',
      publicCity: 'Irvine',
      publicRegion: 'Orange County',
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
    expect(fixture.componentInstance.stores()[0].activeListings).toBe(2);
  });
});
