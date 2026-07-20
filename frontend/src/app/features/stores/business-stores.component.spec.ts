import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing } from '../../testing/listing-test-fixtures';
import { BusinessStoresComponent } from './business-stores.component';

describe('BusinessStoresComponent public business item regression', () => {
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
    storeId: '01S00000000000000000000001',
    storeSlug: 'mochi-store',
    storeName: 'Mochi Store',
    businessVerified: true,
    title: 'Business plush',
    publicCity: 'Irvine',
    publicRegion: 'Orange County',
    quantity: 4,
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

  it('presents approved business listing items instead of storefront cards', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';

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
    expect(text).toContain('Shop verified business items');
    expect(text).toContain('Browse catalog items published by approved business sellers.');
    expect(text).toContain('Business Items');
    expect(text).toContain('1 item found');
    expect(text).toContain('Business plush');
    expect(text).toContain('Sold by Mochi Store');
    expect(text).toContain('Verified');
    expect(text).toContain('Irvine, Orange County');
    expect(text).toContain('Business item');
    expect(text).not.toContain('4 available');
    expect(text).toContain(businessListing.categoryName);
    expect(text).toContain('View item');
    expect(text).toContain('Become a business seller');
    expect(text).not.toContain('Individual bike');
    expect(text).not.toContain('Business Storefronts');
    expect(text).not.toContain('Visit store');
    expect(text).not.toContain('store found');
    expect(text).not.toContain('Checkout');

    const applicationLink = Array.from(host.querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Become a business seller');
    expect(applicationLink?.getAttribute('href')).toBe('/business/apply');

    const publicStoreLink = host.querySelector('a[href="/stores/mochi-store"]');
    expect(publicStoreLink).not.toBeNull();

    const detailLinks = Array.from(host.querySelectorAll('a'))
      .filter(link => link.getAttribute('href') === `/listings/${businessListing.id}`);
    expect(detailLinks.length).toBeGreaterThan(0);
  });

  it('shows the item empty state when there are no business listings', () => {
    listingService.searchBusinessStoreListings.and.returnValue(of(searchPage([individualListing])));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('0 items found');
    expect(text).toContain('No business items found');
    expect(text).toContain('Approved business items will appear here once stores publish them.');
    expect(text).toContain('Clear filters');
    expect(text).not.toContain('No approved business storefront listings yet');
  });

  it('shows a load error state', () => {
    listingService.searchBusinessStoreListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business items could not be loaded. HTTP 503.');
  });

  it('sends item-relevant filters to backend search and stores them in the URL', async () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    const router = TestBed.inject(Router);
    component.searchTerm = 'plush';
    component.selectedCategoryId = category.id;
    component.selectedCondition = 'NEW';
    component.minPrice = 10;
    component.maxPrice = 50;
    component.sort = 'price_asc';
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.runSearch();

    expect(listingService.searchBusinessStoreListings).toHaveBeenCalledWith({
      q: 'plush',
      categoryId: category.id,
      condition: 'NEW',
      minPrice: 10,
      maxPrice: 50,
      city: 'Irvine',
      county: 'Orange County',
      sort: 'price_asc',
      cursor: null,
    });
    expect(component.hasActiveSearch()).toBeTrue();
    await fixture.whenStable();
    expect(router.url).toContain('q=plush');
    expect(router.url).toContain(`categoryId=${category.id}`);
    expect(router.url).toContain('condition=NEW');
    expect(router.url).toContain('sort=price_asc');
    expect(router.url).toContain('city=Irvine');
  });

  it('clears item filters back to the default active business item search', () => {
    fixture.detectChanges();
    listingService.searchBusinessStoreListings.calls.reset();

    const component = fixture.componentInstance;
    component.searchTerm = 'plush';
    component.selectedCategoryId = category.id;
    component.selectedCondition = 'GOOD';
    component.sort = 'newest';
    component.minPrice = 5;
    component.maxPrice = 45;
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.clearFilters();

    expect(component.searchTerm).toBe('');
    expect(component.selectedCategoryId).toBe('ALL');
    expect(component.selectedCondition).toBe('ALL');
    expect(component.sort).toBe('none');
    expect(component.minPrice).toBeNull();
    expect(component.maxPrice).toBeNull();
    expect(component.city).toBe('');
    expect(component.county).toBe('');
    expect(component.hasActiveSearch()).toBeFalse();
    expect(listingService.searchBusinessStoreListings).toHaveBeenCalled();
  });

  it('loads the next business item page with the returned cursor', () => {
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
  });
});
