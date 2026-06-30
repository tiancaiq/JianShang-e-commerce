import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminListingModerationCase } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminListingModerationComponent } from './admin-listing-moderation.component';

describe('AdminListingModerationComponent', () => {
  let fixture: ComponentFixture<AdminListingModerationComponent>;
  let component: AdminListingModerationComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  const moderationCase: AdminListingModerationCase = {
    id: '01MC0000000000000000000001',
    caseStatus: 'OPEN',
    priority: 'NORMAL',
    assignedAdminUserId: null,
    assignedAdminDisplayName: null,
    version: 0,
    createdAt: '2026-06-17T12:00:00Z',
    updatedAt: '2026-06-17T12:00:00Z',
    resolvedAt: null,
    submittedByUserId: '01U00000000000000000000001',
    sellerId: '01U00000000000000000000001',
    sellerDisplayName: 'Alex Seller',
    listingId: '01L00000000000000000000001',
    title: 'Used bicycle',
    sellerType: 'INDIVIDUAL',
    listingStatus: 'PENDING_REVIEW',
    listingModerationStatus: 'PENDING',
    priceAmount: 250,
    currency: 'USD',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    sku: null,
    quantity: 1,
  };

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'getListingModerationCases',
      'claimListingModerationCase',
      'releaseListingModerationCase',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    listingService.getListingModerationCases.and.returnValue(of([moderationCase]));
    listingService.claimListingModerationCase.and.returnValue(of({
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
    }));
    listingService.releaseListingModerationCase.and.returnValue(of({
      ...moderationCase,
      version: 2,
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

  it('loads listing moderation cases for the default open filter', () => {
    expect(listingService.getListingModerationCases).toHaveBeenCalledOnceWith('open', '');
    expect(component.cases()).toEqual([moderationCase]);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('OPEN');
    expect(fixture.nativeElement.textContent).toContain('NORMAL');
    expect(fixture.nativeElement.textContent).toContain('Alex Seller');
    expect(fixture.nativeElement.textContent).toContain('01U00000000000000000000001');
  });

  it('reloads the queue when the filter changes', () => {
    component.setFilter('assigned_to_me');

    expect(component.selectedFilter()).toBe('assigned_to_me');
    expect(listingService.getListingModerationCases).toHaveBeenCalledWith('assigned_to_me', '');
  });

  it('searches listing review cases with the current filter', () => {
    const input = fixture.nativeElement.querySelector('[data-testid="listing-case-search"]') as HTMLInputElement | null;
    const form = fixture.nativeElement.querySelector('[data-testid="listing-case-search-form"]') as HTMLFormElement | null;
    expect(input).toBeTruthy();
    expect(form).toBeTruthy();

    input!.value = '  Used bicycle  ';
    input!.dispatchEvent(new Event('input'));
    form!.dispatchEvent(new Event('submit'));

    expect(listingService.getListingModerationCases).toHaveBeenCalledWith('open', 'Used bicycle');
  });

  it('clears listing review search and reloads the current filter', () => {
    component.setFilter('assigned_to_me');
    listingService.getListingModerationCases.calls.reset();
    const input = fixture.nativeElement.querySelector('[data-testid="listing-case-search"]') as HTMLInputElement | null;
    const form = fixture.nativeElement.querySelector('[data-testid="listing-case-search-form"]') as HTMLFormElement | null;
    expect(input).toBeTruthy();
    expect(form).toBeTruthy();

    input!.value = 'bike';
    input!.dispatchEvent(new Event('input'));
    form!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const clearButton = fixture.nativeElement.querySelector('[data-testid="listing-case-search-clear"]') as HTMLButtonElement | null;
    expect(clearButton).toBeTruthy();
    clearButton!.click();

    expect(listingService.getListingModerationCases).toHaveBeenCalledWith('assigned_to_me', '');
  });

  it('reloads the current filter when an active search is erased', () => {
    const input = fixture.nativeElement.querySelector('[data-testid="listing-case-search"]') as HTMLInputElement | null;
    const form = fixture.nativeElement.querySelector('[data-testid="listing-case-search-form"]') as HTMLFormElement | null;
    expect(input).toBeTruthy();
    expect(form).toBeTruthy();

    input!.value = 'bike';
    input!.dispatchEvent(new Event('input'));
    form!.dispatchEvent(new Event('submit'));
    listingService.getListingModerationCases.calls.reset();

    component.onSearchInputChange('');

    expect(listingService.getListingModerationCases).toHaveBeenCalledOnceWith('open', '');
  });

  it('opens the listing review detail page', () => {
    component.openDetail(moderationCase);

    expect(router.navigate).toHaveBeenCalledOnceWith(['/admin/listings/moderation', moderationCase.id]);
  });

  it('shows an open review action for claimed cases', () => {
    const claimedCase = {
      ...moderationCase,
      caseStatus: 'CLAIMED' as const,
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
    };
    component.cases.set([claimedCase]);
    fixture.detectChanges();

    const actionButtons = Array.from(fixture.nativeElement.querySelectorAll('.action-bar button')) as HTMLButtonElement[];
    const reviewButton = actionButtons.find(button => button.textContent?.trim() === 'Open review');
    expect(reviewButton).toBeTruthy();

    reviewButton?.click();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/admin/listings/moderation', claimedCase.id]);
  });

  it('claims a case with the current case version', () => {
    component.claim(moderationCase);

    expect(listingService.claimListingModerationCase).toHaveBeenCalledOnceWith(moderationCase.id, 0);
    expect(component.cases()[0]).toEqual(jasmine.objectContaining({
      caseStatus: 'CLAIMED',
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
    }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Morgan Admin');
    expect(fixture.nativeElement.textContent).not.toContain('01A00000000000000000000001');
    expect(toastService.success).toHaveBeenCalledWith('Listing moderation case claimed.');
  });

  it('releases a case with the current case version', () => {
    const claimedCase = {
      ...moderationCase,
      caseStatus: 'CLAIMED' as const,
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
    };
    component.cases.set([claimedCase]);

    component.release(claimedCase);

    expect(listingService.releaseListingModerationCase).toHaveBeenCalledOnceWith(moderationCase.id, 1);
    expect(component.cases()[0]).toEqual(jasmine.objectContaining({
      caseStatus: 'OPEN',
      assignedAdminUserId: null,
      version: 2,
    }));
    expect(toastService.success).toHaveBeenCalledWith('Listing moderation case released.');
  });

  it('shows stale case conflicts clearly', () => {
    listingService.claimListingModerationCase.and.returnValue(throwError(() => ({ status: 409 })));

    component.claim(moderationCase);

    expect(component.errorMsg()).toBe('Case changed while you were working. Refresh the queue and try again.');
  });

  it('shows empty and forbidden states', () => {
    listingService.getListingModerationCases.and.returnValue(of([]));
    component.loadQueue();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No open listing review cases.');

    listingService.getListingModerationCases.and.returnValue(throwError(() => ({ status: 403 })));
    component.loadQueue();

    expect(component.errorMsg()).toBe('You need platform admin access for this action.');
  });

  it('explains when no cases are assigned to the current admin', () => {
    listingService.getListingModerationCases.and.returnValue(of([]));

    component.setFilter('assigned_to_me');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No listing review cases are assigned to you.');
  });
});
