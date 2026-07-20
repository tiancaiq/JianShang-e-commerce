import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingService } from '../../core/services/listing.service';
import { publicListing } from '../../testing/listing-test-fixtures';
import { AgentListingSelection } from './agent-listing-context.model';
import { AgentListingContextPickerComponent } from './agent-listing-context-picker.component';

describe('AgentListingContextPickerComponent', () => {
  let fixture: ComponentFixture<AgentListingContextPickerComponent>;
  let component: AgentListingContextPickerComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const individual = publicListing({
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    title: 'Used bicycle',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    priceAmount: 125,
    currency: 'USD',
    images: [],
  });
  const business = publicListing({
    id: '01L00000000000000000000002',
    sellerType: 'BUSINESS',
    title: 'Store bicycle',
    images: [],
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>(
      'ListingService',
      ['searchMarketplaceListings', 'mediaUrl'],
    );
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [individual, business],
      page: { nextCursor: null, hasMore: false },
    }));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [AgentListingContextPickerComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentListingContextPickerComponent);
    component = fixture.componentInstance;
  });

  it('loads the approved public search projection and excludes non-individual results', () => {
    fixture.detectChanges();

    expect(listingService.searchMarketplaceListings).toHaveBeenCalledOnceWith({
      q: null,
      sort: 'newest',
      limit: 8,
    });
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Irvine, CA');
    expect(fixture.nativeElement.textContent).toContain('125.00');
    expect(fixture.nativeElement.textContent).not.toContain('Store bicycle');
  });

  it('searches only after explicit form submission and keeps the input keyboard labelled', async () => {
    fixture.detectChanges();
    listingService.searchMarketplaceListings.calls.reset();

    const input = fixture.nativeElement.querySelector('#agent-listing-search') as HTMLInputElement;
    component.query.set('bicycle');
    fixture.detectChanges();

    input.focus();
    expect(document.activeElement).toBe(input);
    expect(fixture.nativeElement.querySelector('label[for="agent-listing-search"]')).not.toBeNull();
    expect(listingService.searchMarketplaceListings).not.toHaveBeenCalled();

    fixture.nativeElement.querySelector('.search-form button').click();
    fixture.detectChanges();

    expect(listingService.searchMarketplaceListings).toHaveBeenCalledOnceWith({
      q: 'bicycle',
      sort: 'newest',
      limit: 8,
    });
  });

  it('emits only a normalized listing id and bounded display-safe title', () => {
    const selected: AgentListingSelection[] = [];
    const unsafe = publicListing({
      id: '01L00000000000000000000003',
      sellerType: 'INDIVIDUAL',
      title: '  Camera\u0000\nprivate-looking title  ',
      images: [],
    });
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [unsafe],
      page: { nextCursor: null, hasMore: false },
    }));
    component.listingSelected.subscribe(value => selected.push(value));
    fixture.detectChanges();

    const result = fixture.nativeElement.querySelector('.listing-result') as HTMLButtonElement;
    expect(result.getAttribute('aria-label')).toContain('Use Camera private-looking title');
    result.click();

    expect(selected).toEqual([{
      listingId: unsafe.id,
      title: 'Camera private-looking title',
    }]);
    expect(Object.keys(selected[0]).sort()).toEqual(['listingId', 'title']);
  });

  it('shows an empty state without widening the result boundary', () => {
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [],
      page: { nextCursor: null, hasMore: false },
    }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No matching listings');
    expect(fixture.nativeElement.querySelector('.listing-results')).toBeNull();
    expect(getComputedStyle(fixture.nativeElement).minWidth).toBe('0px');
  });

  it('shows a safe error and retries the same public search', () => {
    listingService.searchMarketplaceListings.and.returnValue(
      throwError(() => new Error('private upstream detail')),
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Marketplace browsing still works');
    expect(fixture.nativeElement.textContent).not.toContain('private upstream detail');

    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [individual],
      page: { nextCursor: null, hasMore: false },
    }));
    fixture.nativeElement.querySelector('.error-state button').click();
    fixture.detectChanges();

    expect(listingService.searchMarketplaceListings.calls.count()).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
  });
});
