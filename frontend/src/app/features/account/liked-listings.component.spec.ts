import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing, publicListingImage } from '../../testing/listing-test-fixtures';
import { LikedListingsComponent } from './liked-listings.component';

describe('LikedListingsComponent', () => {
  let fixture: ComponentFixture<LikedListingsComponent>;
  let listingService: jasmine.SpyObj<ListingService>;

  const likedListing = publicListing({
    title: 'Liked camera',
    likeCount: 4,
    visitCount: 9,
    images: [publicListingImage()],
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getMyLikedListings', 'mediaUrl']);
    listingService.getMyLikedListings.and.returnValue(of([likedListing]));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [LikedListingsComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LikedListingsComponent);
  });

  it('loads current user liked listings', () => {
    fixture.detectChanges();

    expect(listingService.getMyLikedListings).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Liked Listings');
    expect(fixture.nativeElement.textContent).toContain('Liked camera');
    expect(fixture.nativeElement.textContent).toContain('♡ 4');
    expect(fixture.nativeElement.textContent).toContain('👁 9');
  });

  it('shows an empty state when the user has not liked public listings', () => {
    listingService.getMyLikedListings.and.returnValue(of([]));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No liked listings yet');
    expect(fixture.nativeElement.textContent).toContain('Find listings');
  });

  it('shows an error state when liked listings cannot be loaded', () => {
    listingService.getMyLikedListings.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Liked listings could not be loaded.');
    expect(fixture.nativeElement.textContent).toContain('HTTP 503.');
  });
});
