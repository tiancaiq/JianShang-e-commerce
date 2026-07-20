import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { InventoryBalance, InventoryCatalogItem } from '../../core/models/inventory.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { InventoryService } from '../../core/services/inventory.service';
import { BusinessInventoryComponent } from './business-inventory.component';

describe('BusinessInventoryComponent', () => {
  let fixture: ComponentFixture<BusinessInventoryComponent>;
  let inventoryService: jasmine.SpyObj<InventoryService>;

  const businessId = '01B00000000000000000000001';
  const context: BusinessStoreContext = {
    businessId,
    businessLegalName: 'Acme LLC',
    businessStatus: 'ACTIVE',
    membershipRole: 'OWNER',
    permissions: ['INVENTORY_VIEW', 'INVENTORY_MANAGE'],
    store: {
      id: '01S00000000000000000000001',
      businessId,
      slug: 'acme',
      name: 'Acme',
      description: null,
      logoUrl: null,
      bannerUrl: null,
      supportEmail: null,
      supportPhone: null,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      status: 'ACTIVE',
      version: 0,
      createdAt: '2026-07-17T08:00:00Z',
      updatedAt: '2026-07-17T08:00:00Z',
    },
  };
  const balance: InventoryBalance = {
    id: '01I00000000000000000000001',
    businessId,
    listingId: '01L00000000000000000000001',
    skuSnapshot: 'SKU-1',
    onHand: 10,
    reserved: 2,
    available: 8,
    version: 1,
    initializedAt: '2026-07-17T08:00:00Z',
    updatedAt: '2026-07-17T08:00:00Z',
  };
  const initialized: InventoryCatalogItem = {
    listingId: balance.listingId,
    title: 'Business keyboard',
    sku: 'SKU-1',
    listingStatus: 'ACTIVE',
    catalogQuantitySuggestion: 4,
    catalogVersion: 2,
    inventoryState: 'INITIALIZED',
    inventory: balance,
  };
  const uninitialized: InventoryCatalogItem = {
    listingId: '01L00000000000000000000002',
    title: 'Business mouse',
    sku: 'SKU-2',
    listingStatus: 'PAUSED',
    catalogQuantitySuggestion: 3,
    catalogVersion: 1,
    inventoryState: 'NOT_INITIALIZED',
    inventory: null,
  };

  beforeEach(async () => {
    const businessStoreService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getCurrentStoreContext']);
    businessStoreService.getCurrentStoreContext.and.returnValue(of(context));
    inventoryService = jasmine.createSpyObj<InventoryService>('InventoryService', [
      'list',
      'initialize',
      'adjust',
      'movements',
    ]);
    inventoryService.list.and.returnValue(of({
      data: [initialized, uninitialized],
      page: { nextCursor: null, hasMore: false },
    }));
    inventoryService.initialize.and.returnValue(of({
      ...balance,
      id: '01I00000000000000000000002',
      listingId: uninitialized.listingId,
      skuSnapshot: uninitialized.sku,
      onHand: 3,
      reserved: 0,
      available: 3,
      version: 0,
    }));
    inventoryService.adjust.and.returnValue(of({
      ...balance,
      onHand: 8,
      available: 6,
      version: 2,
    }));
    inventoryService.movements.and.returnValue(of({
      data: [{
        id: '01M00000000000000000000001',
        operation: 'ADJUST',
        reason: 'STOCK_COUNT_CORRECTION',
        quantityDelta: -2,
        onHandBefore: 10,
        onHandAfter: 8,
        reservedSnapshot: 2,
        note: 'Cycle count',
        createdAt: '2026-07-17T09:00:00Z',
      }],
      page: { nextCursor: null, hasMore: false },
    }));

    await TestBed.configureTestingModule({
      imports: [BusinessInventoryComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessStoreService, useValue: businessStoreService },
        { provide: InventoryService, useValue: inventoryService },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BusinessInventoryComponent);
  });

  it('renders authoritative balances and initialization state', () => {
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(inventoryService.list).toHaveBeenCalledOnceWith(businessId, {
      q: null,
      listingStatus: null,
      limit: 24,
    });
    expect(text).toContain('Business keyboard');
    expect(text).toContain('Business mouse');
    expect(text).toContain('Not initialized');
    expect(text).toContain('Initialize');
    expect(text).toContain('Adjust');
    expect(text).toContain('History');
  });

  it('initializes a paused item from its explicit opening balance', () => {
    fixture.detectChanges();
    fixture.componentInstance.openInitialize(uninitialized);
    expect(fixture.componentInstance.quantity).toBe(3);

    fixture.componentInstance.saveInventory();
    fixture.detectChanges();

    expect(inventoryService.initialize).toHaveBeenCalledOnceWith(businessId, uninitialized.listingId, {
      onHand: 3,
      note: null,
    });
    expect(fixture.componentInstance.items()[1].inventory?.onHand).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('Inventory initialized.');
  });

  it('prevents an adjustment that would make on-hand negative', () => {
    fixture.detectChanges();
    fixture.componentInstance.openAdjust(initialized);
    fixture.componentInstance.operation = 'ADJUST';
    fixture.componentInstance.quantity = -11;

    expect(fixture.componentInstance.projectedOnHand()).toBe(-1);
    expect(fixture.componentInstance.canSave()).toBeFalse();
  });

  it('loads and renders movement history', () => {
    fixture.detectChanges();
    fixture.componentInstance.openHistory(initialized);
    fixture.detectChanges();

    expect(inventoryService.movements).toHaveBeenCalledOnceWith(businessId, initialized.listingId);
    expect(fixture.nativeElement.textContent).toContain('Stock Count Correction');
    expect(fixture.nativeElement.textContent).toContain('Cycle count');
  });
});
