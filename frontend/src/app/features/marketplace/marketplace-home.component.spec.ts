import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { MarketplaceHomeComponent } from './marketplace-home.component';

describe('MarketplaceHomeComponent', () => {
  let fixture: ComponentFixture<MarketplaceHomeComponent>;
  let component: MarketplaceHomeComponent;
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
    conditionNotes: null,
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

  it('routes the sell call to action into marketplace account listing creation', () => {
    fixture.detectChanges();

    const sellLink = fixture.nativeElement.querySelector('.secondary-link') as HTMLAnchorElement;

    expect(sellLink.getAttribute('href')).toBe('/account/listings/new');
  });

  it('shows an empty state when no public listings exist', () => {
    listingService.getPublicListings.and.returnValue(of([]));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approved listings yet.');
  });

  it('shows a load error state', () => {
    listingService.getPublicListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(component.errorMsg()).toBe('Approved listings could not be loaded. HTTP 503.');
    expect(fixture.nativeElement.textContent).toContain('HTTP 503');
  });
});
