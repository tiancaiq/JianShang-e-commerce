import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Category, ListingDraft } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { ListingDraftFormComponent } from './listing-draft-form.component';

describe('ListingDraftFormComponent', () => {
  let fixture: ComponentFixture<ListingDraftFormComponent>;
  let component: ListingDraftFormComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  const category: Category = {
    id: '01K00000000000000000000001',
    slug: 'general',
    name: 'General',
    parentId: null,
    displayOrder: 0,
    attributes: [],
  };

  const draft: ListingDraft = {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: '01U00000000000000000000001',
    businessId: null,
    categoryId: category.id,
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
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getCategories', 'createDraft']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    listingService.getCategories.and.returnValue(of([category]));
    listingService.createDraft.and.returnValue(of(draft));

    await TestBed.configureTestingModule({
      imports: [ListingDraftFormComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ListingService, useValue: listingService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ListingDraftFormComponent);
    component = fixture.componentInstance;
  });

  it('loads categories and selects the first one', () => {
    fixture.detectChanges();

    expect(listingService.getCategories).toHaveBeenCalled();
    expect(component.categories()).toEqual([category]);
    expect(component.categoryId).toBe(category.id);
  });

  it('saves an individual draft with quantity fixed to one', () => {
    fixture.detectChanges();
    fillCommonFields();
    component.publicCity = ' Irvine ';
    component.publicRegion = ' CA ';
    component.negotiable = true;

    component.saveDraft();

    expect(listingService.createDraft).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      categoryId: category.id,
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      price: { amount: 250, currency: 'USD' },
      negotiable: true,
      quantity: 1,
      location: { city: 'Irvine', region: 'CA' },
    }));
    expect(component.savedId()).toBe(draft.id);
    expect(toastService.success).toHaveBeenCalledWith('Listing draft saved.');
  });

  it('saves a business draft with business fields', () => {
    listingService.createDraft.and.returnValue(of({
      ...draft,
      sellerType: 'BUSINESS',
      individualSellerUserId: null,
      businessId: '01B00000000000000000000001',
      sku: 'SKU-100',
      quantity: 3,
      negotiable: false,
    }));
    fixture.detectChanges();
    fillCommonFields();
    component.sellerType = 'BUSINESS';
    component.businessId = '01B00000000000000000000001';
    component.sku = ' SKU-100 ';
    component.quantity = 3;

    component.saveDraft();

    expect(listingService.createDraft).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sellerType: 'BUSINESS',
      businessId: '01B00000000000000000000001',
      sku: 'SKU-100',
      quantity: 3,
      negotiable: false,
      location: null,
    }));
  });

  it('shows forbidden errors without pretending the draft saved', () => {
    listingService.createDraft.and.returnValue(throwError(() => ({ status: 403 })));
    fixture.detectChanges();
    fillCommonFields();

    component.saveDraft();

    expect(component.errorMsg()).toBe('Your account does not have permission to create this listing draft.');
    expect(component.savedId()).toBe('');
  });

  it('validates required title before calling the API', () => {
    fixture.detectChanges();
    fillCommonFields();
    component.title = '';

    component.saveDraft();

    expect(listingService.createDraft).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Title is required.');
  });

  function fillCommonFields(): void {
    component.categoryId = category.id;
    component.title = ' Used bicycle ';
    component.description = ' A reliable city bike. ';
    component.condition = 'GOOD';
    component.price = 250;
    component.currency = 'usd';
  }
});
