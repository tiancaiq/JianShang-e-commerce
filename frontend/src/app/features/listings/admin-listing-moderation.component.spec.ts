import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ListingDraft } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminListingModerationComponent } from './admin-listing-moderation.component';

describe('AdminListingModerationComponent', () => {
  let fixture: ComponentFixture<AdminListingModerationComponent>;
  let component: AdminListingModerationComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  const listing: ListingDraft = {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: '01U00000000000000000000001',
    businessId: null,
    categoryId: '01C00000000000000000000001',
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
    status: 'PENDING_REVIEW',
    moderationStatus: 'PENDING',
    version: 1,
    createdAt: '2026-06-17T12:00:00Z',
    updatedAt: '2026-06-17T12:00:00Z',
    images: [{
      id: '01I00000000000000000000001',
      listingId: '01L00000000000000000000001',
      mediaObjectId: '01M00000000000000000000001',
      displayOrder: 0,
      altText: 'Blue bike',
      moderationStatus: 'PENDING',
      originalFileName: 'bike.png',
      contentType: 'image/png',
      sizeBytes: 1024,
      uploadStatus: 'UPLOADED',
      objectBucket: 'listing-media-local',
      objectKey: 'listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
      uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
      version: 1,
      createdAt: '2026-06-17T12:00:00Z',
      updatedAt: '2026-06-17T12:00:00Z',
    }],
  };

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'getPendingModerationListings',
      'decideListing',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    listingService.getPendingModerationListings.and.returnValue(of([listing]));
    listingService.decideListing.and.returnValue(of({
      id: '01D00000000000000000000001',
      listingId: listing.id,
      decision: 'APPROVE',
      reason: 'Looks complete.',
      reviewerUserId: '01A00000000000000000000001',
      listingVersion: 2,
      createdAt: '2026-06-17T12:05:00Z',
    }));

    await TestBed.configureTestingModule({
      imports: [AdminListingModerationComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ListingService, useValue: listingService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminListingModerationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads submitted listings for admin review', () => {
    expect(listingService.getPendingModerationListings).toHaveBeenCalled();
    expect(component.listings()).toEqual([listing]);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('bike.png');
  });

  it('requires a decision reason', () => {
    component.decide(listing, 'APPROVE');

    expect(component.errorMsg()).toBe('Decision reason is required.');
    expect(listingService.decideListing).not.toHaveBeenCalled();
  });

  it('saves an admin listing decision and removes it from the queue', () => {
    component.setReason(listing.id, 'Looks complete.');

    component.decide(listing, 'APPROVE');

    expect(listingService.decideListing).toHaveBeenCalledOnceWith(listing.id, 1, {
      decision: 'APPROVE',
      reason: 'Looks complete.',
    });
    expect(component.listings()).toEqual([]);
    expect(toastService.success).toHaveBeenCalledWith('Listing moderation decision saved.');
  });

  it('shows missing platform admin access', () => {
    listingService.decideListing.and.returnValue(throwError(() => ({ status: 403 })));
    component.setReason(listing.id, 'Needs admin.');

    component.decide(listing, 'REJECT');

    expect(component.errorMsg()).toBe('You need platform admin access for this action.');
  });
});
