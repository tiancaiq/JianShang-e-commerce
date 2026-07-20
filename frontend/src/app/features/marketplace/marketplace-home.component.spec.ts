import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { AuthService } from '../../core/services/auth.service';
import { publicListing, publicListingImage } from '../../testing/listing-test-fixtures';
import { MarketplaceHomeComponent } from './marketplace-home.component';

describe('MarketplaceHomeComponent public browse regression', () => {
  let fixture: ComponentFixture<MarketplaceHomeComponent>;
  let component: MarketplaceHomeComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const listing = publicListing({
    conditionNotes: null,
    visitCount: 7,
    likeCount: 3,
    images: [
      publicListingImage(),
      publicListingImage({
        id: '01I00000000000000000000002',
        displayOrder: 1,
        url: '/api/v1/public/listing-media/01I00000000000000000000002',
      }),
    ],
  });
  const category = {
    id: listing.categoryId,
    slug: listing.categorySlug,
    name: listing.categoryName,
    parentId: null,
    displayOrder: 0,
    attributes: [],
  };
  const searchPage = (items = [listing], nextCursor: string | null = null, hasMore = false) => ({
    data: items,
    page: { nextCursor, hasMore },
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getCategories', 'searchMarketplaceListings', 'mediaUrl']);
    listingService.getCategories.and.returnValue(of([category]));
    listingService.searchMarketplaceListings.and.returnValue(of(searchPage()));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [MarketplaceHomeComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
        {
          provide: AuthService,
          useValue: {
            user: () => ({
              id: '01USER',
              keycloakSub: 'keycloak-sub',
              email: 'shenban2@example.com',
              emailVerified: true,
              displayName: 'Ban Shen',
              phone: null,
              phoneVerified: false,
              avatarUrl: null,
              status: 'ACTIVE',
              version: 0,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MarketplaceHomeComponent);
    component = fixture.componentInstance;
  });

  it('shows approved public listings on the marketplace home page', () => {
    fixture.detectChanges();

    expect(listingService.getCategories).toHaveBeenCalled();
    expect(listingService.searchMarketplaceListings).toHaveBeenCalledWith({
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
    expect(component.listings()).toEqual([listing]);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('250.00 USD');
    expect(fixture.nativeElement.textContent).toContain('Irvine, CA');
    expect(fixture.nativeElement.textContent).toContain('♡ 3');
    expect(fixture.nativeElement.textContent).toContain('👁 7');
    expect(fixture.nativeElement.querySelector('.listing-image img')?.getAttribute('src')).toBe('/api/v1/public/listing-media/01I00000000000000000000001');
  });

  it('shows the signed-in marketplace profile card in the main marketplace rail', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent || '';
    const sellLink = fixture.nativeElement.querySelector('.sell-action') as HTMLAnchorElement;

    expect(text).toContain('Ban Shen');
    expect(text).toContain('@shenban2');
    expect(text).toContain('Marketplace seller');
    expect(text).toContain('Listings');
    expect(text).toContain('Trades');
    expect(text).toContain('Rating');
    expect(sellLink.getAttribute('href')).toBe('/account/listings/new');
  });

  it('keeps the marketplace page focused on individual listings', () => {
    const businessListing = publicListing({
      id: '01L00000000000000000000002',
      sellerType: 'BUSINESS',
      sellerDisplayName: 'Mochi Store',
      title: 'Business plush',
    });
    listingService.searchMarketplaceListings.and.returnValue(of(searchPage([listing, businessListing])));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).not.toContain('Business plush');
    expect(fixture.nativeElement.textContent).not.toContain('All sellers');
  });

  it('surfaces marketplace hero calls to action while preserving the browse-tab listing entry', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const hero = host.querySelector('app-marketplace-hero-banner');
    const listItemLink = Array.from(host.querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'List an Item') as HTMLAnchorElement | undefined;

    expect(hero?.textContent).toContain('Browse Listings');
    expect(hero?.textContent).toContain('Sell an Item');
    expect(listItemLink?.getAttribute('href')).toBe('/account/listings');
  });

  it('shows an empty state when no public listings exist', () => {
    listingService.searchMarketplaceListings.and.returnValue(of(searchPage([])));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approved individual listings yet');
  });

  it('shows a load error state', () => {
    listingService.searchMarketplaceListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(component.errorMsg()).toBe('Approved listings could not be loaded. HTTP 503.');
    expect(fixture.nativeElement.textContent).toContain('HTTP 503');
  });

  it('sends marketplace filter controls to backend search and stores them in the URL', async () => {
    fixture.detectChanges();
    listingService.searchMarketplaceListings.calls.reset();
    const router = TestBed.inject(Router);

    component.searchTerm = 'bike';
    component.selectedCondition = 'GOOD';
    component.sortMode = 'price_asc';
    component.minPrice = '10';
    component.maxPrice = '300';
    component.city = 'Irvine';
    component.county = 'Orange County';
    component.selectCategory(category.id);

    expect(listingService.searchMarketplaceListings).toHaveBeenCalledWith({
      q: 'bike',
      categoryId: category.id,
      condition: 'GOOD',
      minPrice: 10,
      maxPrice: 300,
      city: 'Irvine',
      county: 'Orange County',
      sort: 'price_asc',
      cursor: null,
    });
    await fixture.whenStable();
    expect(router.url).toContain('q=bike');
    expect(router.url).toContain(`categoryId=${category.id}`);
    expect(router.url).toContain('condition=GOOD');
    expect(router.url).toContain('sort=price_asc');
    expect(router.url).toContain('city=Irvine');
  });

  it('handles numeric price inputs from browser number controls', () => {
    fixture.detectChanges();
    listingService.searchMarketplaceListings.calls.reset();

    component.minPrice = 1;
    component.maxPrice = 2;
    component.runSearch();

    expect(listingService.searchMarketplaceListings).toHaveBeenCalledWith(jasmine.objectContaining({
      minPrice: 1,
      maxPrice: 2,
    }));
    expect(component.hasActiveSearch()).toBeTrue();
    expect(component.loading()).toBeFalse();
  });

  it('loads the next marketplace search page with the returned cursor', () => {
    const nextListing = publicListing({
      id: '01L00000000000000000000003',
      title: 'Second bike',
    });
    listingService.searchMarketplaceListings.and.returnValues(
      of(searchPage([listing], 'cursor-1', true)),
      of(searchPage([nextListing])),
    );

    fixture.detectChanges();
    component.loadMore();

    expect(listingService.searchMarketplaceListings.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      cursor: 'cursor-1',
    }));
    expect(component.listings()).toEqual([listing, nextListing]);
  });
});
