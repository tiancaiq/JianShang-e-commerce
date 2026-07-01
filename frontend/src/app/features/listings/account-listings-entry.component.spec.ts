import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { IndividualSellerProfile } from '../../core/models/individual-seller.model';
import { ListingDraft } from '../../core/models/listing.model';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AccountListingsEntryComponent } from './account-listings-entry.component';

describe('AccountListingsEntryComponent', () => {
  let fixture: ComponentFixture<AccountListingsEntryComponent>;
  let individualSellerService: jasmine.SpyObj<IndividualSellerService>;
  let listingService: jasmine.SpyObj<ListingService>;

  const profile: IndividualSellerProfile = {
    id: '01S00000000000000000000001',
    userId: '01U00000000000000000000001',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    completedSalesCount: 0,
    termsVersion: '2026-01',
    version: 0,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  const draft: ListingDraft = {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: profile.userId,
    businessId: null,
    categoryId: '01K00000000000000000000001',
    title: 'Used bicycle',
    description: 'A reliable city bike.',
    condition: 'GOOD',
    conditionNotes: null,
    priceAmount: 250,
    currency: 'USD',
    negotiable: true,
    sku: null,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'DRAFT',
    moderationStatus: 'NOT_SUBMITTED',
    version: 0,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(async () => {
    individualSellerService = jasmine.createSpyObj<IndividualSellerService>('IndividualSellerService', ['getMe', 'activate']);
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getMyListings']);
    listingService.getMyListings.and.returnValue(of([draft]));

    await TestBed.configureTestingModule({
      imports: [AccountListingsEntryComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: IndividualSellerService, useValue: individualSellerService },
        { provide: ListingService, useValue: listingService },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success']) },
      ],
    }).compileComponents();
  });

  it('shows listing management for an active individual seller', () => {
    individualSellerService.getMe.and.returnValue(of(profile));
    fixture = TestBed.createComponent(AccountListingsEntryComponent);

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('My Listings');
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(listingService.getMyListings).toHaveBeenCalled();
  });

  it('shows individual seller activation when the profile does not exist', () => {
    individualSellerService.getMe.and.returnValue(throwError(() => ({ status: 404 })));
    fixture = TestBed.createComponent(AccountListingsEntryComponent);

    fixture.detectChanges();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Become a Seller');
    expect(fixture.nativeElement.textContent).toContain('Activate seller profile');
    expect(listingService.getMyListings).not.toHaveBeenCalled();
  });
});
