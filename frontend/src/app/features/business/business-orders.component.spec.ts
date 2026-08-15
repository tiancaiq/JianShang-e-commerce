import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import {
  BusinessOrderDetail,
  BusinessOrderPage,
  BusinessOrderSummary,
} from '../../core/models/business-order.model';
import { BusinessOrderReturn } from '../../core/models/order.model';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { BusinessOrderService } from '../../core/services/business-order.service';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { BUSINESS_ORDERS_ENABLED } from './business-orders.capability';
import { BUSINESS_ORDER_FULFILLMENT_ENABLED } from './business-order-fulfillment.capability';
import { BusinessOrdersComponent } from './business-orders.component';

describe('BusinessOrdersComponent', () => {
  let fixture: ComponentFixture<BusinessOrdersComponent>;
  let storeService: jasmine.SpyObj<BusinessStoreService>;
  let orderService: jasmine.SpyObj<BusinessOrderService>;

  const businessId = '01B00000000000000000000001';
  const context: BusinessStoreContext = {
    businessId,
    businessLegalName: 'Acme LLC',
    businessStatus: 'ACTIVE',
    membershipRole: 'OWNER',
    permissions: ['ORDER_VIEW', 'ORDER_FINANCE_VIEW'],
    store: {
      id: '01S00000000000000000000001',
      businessId,
      slug: 'acme',
      name: 'Acme Store',
      description: null,
      logoUrl: null,
      bannerUrl: null,
      supportEmail: null,
      supportPhone: null,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      status: 'ACTIVE',
      version: 1,
      createdAt: '2026-07-20T01:00:00Z',
      updatedAt: '2026-07-20T01:00:00Z',
    },
  };
  const summary: BusinessOrderSummary = {
    businessOrderId: '01O00000000000000000000001',
    sellerOrderNumber: 'MSB-BIZ-1001',
    businessId,
    storeId: context.store.id,
    status: 'PENDING_ACCEPTANCE',
    cancellationStatus: 'NONE',
    buyerOrderId: '01O00000000000000000000002',
    buyerOrderNumber: 'MSB-1000',
    itemCount: 1,
    totalQuantity: 2,
    subtotal: 25,
    totalAmount: 25,
    currency: 'USD',
    confirmedAt: '2026-07-20T01:00:00Z',
    createdAt: '2026-07-20T01:00:00Z',
    updatedAt: '2026-07-20T01:00:00Z',
  };
  const detail: BusinessOrderDetail = {
    ...summary,
    paymentStatus: 'SUCCEEDED',
    platformFeeProjection: 2.5,
    items: [{
      listingId: '01L00000000000000000000001',
      title: 'Persisted keyboard title',
      sku: 'KEY-1',
      itemCondition: 'NEW',
      thumbnailUrl: null,
      unitPrice: 12.5,
      currency: 'USD',
      quantity: 2,
      lineTotal: 25,
      policyVersion: 'LOCAL_DEMO_V1',
    }],
    shippingAddress: {
      recipientName: 'Snapshot Buyer',
      phone: '+15550123456',
      line1: '1 Snapshot St',
      line2: null,
      city: 'Irvine',
      region: 'CA',
      postalCode: '92618',
      countryCode: 'US',
    },
    version: 0,
    timeline: [],
    shipment: null,
  };

  async function configure(options: {
    enabled?: boolean;
    orderId?: string | null;
    storeContext?: BusinessStoreContext | null;
    listResult?: Observable<BusinessOrderPage>;
    detailResult?: Observable<BusinessOrderDetail>;
    returnResult?: Observable<BusinessOrderReturn | null>;
    fulfillmentEnabled?: boolean;
  } = {}): Promise<void> {
    storeService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getCurrentStoreContext']);
    orderService = jasmine.createSpyObj<BusinessOrderService>(
      'BusinessOrderService',
      ['list', 'detail', 'accept', 'startProcessing', 'createShipment', 'recordDemoDelivery',
        'returnDetail', 'authorizeReturn', 'receiveReturn'],
    );
    storeService.getCurrentStoreContext.and.returnValue(of(options.storeContext === undefined ? context : options.storeContext));
    orderService.list.and.returnValue(options.listResult ?? of({
      items: [summary],
      page: { nextCursor: null, hasMore: false },
    }));
    orderService.detail.and.returnValue(options.detailResult ?? of(detail));
    orderService.returnDetail.and.returnValue(options.returnResult ?? of(null));
    orderService.accept.and.returnValue(of({
      businessOrderId: summary.businessOrderId,
      fulfillmentStatus: 'ACCEPTED', version: 1,
      updatedAt: '2026-08-03T15:00:00Z', shipment: null,
    }));

    await TestBed.configureTestingModule({
      imports: [BusinessOrdersComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BUSINESS_ORDERS_ENABLED, useValue: options.enabled ?? true },
        { provide: BUSINESS_ORDER_FULFILLMENT_ENABLED, useValue: options.fulfillmentEnabled ?? false },
        { provide: BusinessStoreService, useValue: storeService },
        { provide: BusinessOrderService, useValue: orderService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(options.orderId ? { businessOrderId: options.orderId } : {}),
            },
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BusinessOrdersComponent);
  }

  afterEach(() => TestBed.resetTestingModule());

  it('is network silent and exposes no queue while the capability is disabled', async () => {
    await configure({ enabled: false });
    fixture.detectChanges();

    expect(storeService.getCurrentStoreContext).not.toHaveBeenCalled();
    expect(orderService.list).not.toHaveBeenCalled();
    expect(orderService.detail).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('loads the owner queue with bounded defaults and omits absent finance fields', async () => {
    await configure();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(orderService.list).toHaveBeenCalledOnceWith(businessId, {
      status: null,
      cursor: null,
      limit: 20,
    });
    expect(host.textContent).toContain('Acme Store fulfillment');
    expect(host.textContent).toContain('MSB-BIZ-1001');
    expect(host.textContent).toContain('Pending acceptance');
    expect(host.textContent).not.toContain('Fee');
    expect(host.querySelector('caption')?.textContent).toContain('Business fulfillment queue');
    expect(host.querySelector('nav[aria-label="Order pages"]')).not.toBeNull();
  });

  it('uses cancellation as the primary seller queue status', async () => {
    await configure({
      listResult: of({
        items: [{ ...summary, cancellationStatus: 'CANCELLED' }],
        page: { nextCursor: null, hasMore: false },
      }),
    });
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const statusCell = host.querySelector('td[data-label="Status"]');
    const filterLabels = Array.from(host.querySelectorAll('#order-status option'))
      .map(option => option.textContent?.trim());

    expect(statusCell?.textContent?.trim()).toBe('Cancelled');
    expect(statusCell?.textContent).not.toContain('Pending acceptance');
    expect(filterLabels).toContain('Cancelled');
  });

  it('allows a manager with ORDER_VIEW but performs no order request without permission', async () => {
    await configure({
      storeContext: { ...context, membershipRole: 'MANAGER', permissions: ['ORDER_VIEW'] },
    });
    fixture.detectChanges();
    expect(orderService.list).toHaveBeenCalled();

    TestBed.resetTestingModule();
    await configure({
      storeContext: { ...context, membershipRole: 'MANAGER', permissions: [] },
    });
    fixture.detectChanges();
    expect(orderService.list).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Order access unavailable');
  });

  it('shows the state action only to a fulfillment owner and reloads after acceptance', async () => {
    await configure({
      orderId: summary.businessOrderId,
      fulfillmentEnabled: true,
      storeContext: { ...context, permissions: ['ORDER_VIEW', 'ORDER_FULFILL'] },
    });
    fixture.detectChanges();
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find(candidate => candidate.textContent?.includes('Accept order group')) as HTMLButtonElement;
    expect(button).toBeTruthy();

    button.click();
    fixture.detectChanges();

    const dialog = (fixture.nativeElement as HTMLElement).querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    expect(dialog.getAttribute('aria-labelledby')).toBe('fulfillment-dialog-title');
    expect(dialog.textContent).toContain('Pending acceptance');
    expect(dialog.textContent).toContain('Accepted');
    expect(orderService.accept).not.toHaveBeenCalled();

    const confirmButton = Array.from(dialog.querySelectorAll('button'))
      .find(candidate => candidate.textContent?.includes('Accept order group')) as HTMLButtonElement;
    confirmButton.click();
    fixture.detectChanges();

    expect(orderService.accept).toHaveBeenCalledWith(
      businessId, summary.businessOrderId, 0, jasmine.stringMatching(/^seller-accept-/),
    );
    expect(orderService.detail).toHaveBeenCalledTimes(2);
    expect(dialog.open).toBeFalse();
  });

  it('cancels a fulfillment confirmation without performing a mutation', async () => {
    await configure({
      orderId: summary.businessOrderId,
      fulfillmentEnabled: true,
      storeContext: { ...context, permissions: ['ORDER_VIEW', 'ORDER_FULFILL'] },
    });
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const actionButton = Array.from(host.querySelectorAll('button'))
      .find(candidate => candidate.textContent?.includes('Accept order group')) as HTMLButtonElement;

    actionButton.click();
    fixture.detectChanges();
    const dialog = host.querySelector('dialog') as HTMLDialogElement;
    const cancelButton = Array.from(dialog.querySelectorAll('button'))
      .find(candidate => candidate.textContent?.includes('Keep current status')) as HTMLButtonElement;
    cancelButton.click();
    fixture.detectChanges();

    expect(dialog.open).toBeFalse();
    expect(orderService.accept).not.toHaveBeenCalled();
  });

  it('dismisses the in-app confirmation with Escape', async () => {
    await configure({
      orderId: summary.businessOrderId,
      fulfillmentEnabled: true,
      storeContext: { ...context, permissions: ['ORDER_VIEW', 'ORDER_FULFILL'] },
    });
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const actionButton = Array.from(host.querySelectorAll('button'))
      .find(candidate => candidate.textContent?.includes('Accept order group')) as HTMLButtonElement;

    actionButton.click();
    fixture.detectChanges();
    const dialog = host.querySelector('dialog') as HTMLDialogElement;
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();

    expect(dialog.open).toBeFalse();
    expect(orderService.accept).not.toHaveBeenCalled();
  });

  it('applies an approved status and traverses the server cursor without modifying it', async () => {
    await configure({
      listResult: of({
        items: [summary],
        page: { nextCursor: 'v1.next-cursor', hasMore: true },
      }),
    });
    fixture.detectChanges();
    fixture.componentInstance.selectedStatus = 'ACCEPTED';
    fixture.componentInstance.applyFilter();
    fixture.componentInstance.nextPage();

    expect(orderService.list.calls.argsFor(1)).toEqual([businessId, {
      status: 'ACCEPTED',
      cursor: null,
      limit: 20,
    }]);
    expect(orderService.list.calls.argsFor(2)).toEqual([businessId, {
      status: 'ACCEPTED',
      cursor: 'v1.next-cursor',
      limit: 20,
    }]);
  });

  it('sends the cancelled queue filter to the server', async () => {
    await configure();
    fixture.detectChanges();
    fixture.componentInstance.selectedStatus = 'CANCELLED';
    fixture.componentInstance.applyFilter();

    expect(orderService.list.calls.mostRecent().args).toEqual([businessId, {
      status: 'CANCELLED',
      cursor: null,
      limit: 20,
    }]);
  });

  it('renders immutable detail, permitted finance, and only the approved address snapshot', async () => {
    await configure({ orderId: summary.businessOrderId });
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';

    expect(orderService.detail).toHaveBeenCalledOnceWith(businessId, summary.businessOrderId);
    expect(text).toContain('Persisted keyboard title');
    expect(text).toContain('LOCAL_DEMO_V1');
    expect(text).toContain('Platform fee projection');
    expect(text).toContain('Snapshot Buyer');
    expect(text).toContain('Marketplace buyer · Order MSB-1000');
    expect(text).toContain('1 Snapshot St');
    expect(host.querySelector('address a')?.getAttribute('href')).toBe('tel:+15550123456');
    expect(text).not.toContain(summary.buyerOrderId);
    expect(text).not.toContain(summary.businessOrderId);
    expect(text).not.toContain('provider');
    expect(host.querySelector('button')?.textContent).not.toContain('Ship');
  });

  for (const [inventoryDisposition, expectedLabel] of [
    ['RESTOCK_SELLABLE', 'Restocked as sellable'],
    ['DO_NOT_RESTOCK', 'Not restocked'],
  ] as const) {
    it(`keeps the ${expectedLabel.toLowerCase()} disposition visible on a completed return`, async () => {
      const returned: BusinessOrderReturn = {
        eligible: false,
        ineligibilityCode: null,
        returnId: '01R00000000000000000000001',
        orderId: summary.buyerOrderId,
        businessOrderId: summary.businessOrderId,
        storeName: context.store.name,
        reasonCode: 'NOT_AS_EXPECTED',
        buyerComment: null,
        policyVersion: 'LOCAL_DEMO_RETURN_POLICY_V1',
        requestedAt: '2026-08-10T01:00:00Z',
        windowExpiresAt: '2026-09-09T01:00:00Z',
        status: 'RETURN_COMPLETED',
        refundStatus: 'SUCCEEDED',
        inventoryDisposition,
        receivedAt: '2026-08-10T02:00:00Z',
        refundId: '01F00000000000000000000001',
        refundAmount: 25,
        currency: 'USD',
        completedAt: '2026-08-10T02:01:00Z',
        version: 5,
        shipment: null,
        timeline: [{ status: 'RETURN_RECEIVED', occurredAt: '2026-08-10T02:00:00Z' }],
      };
      await configure({
        orderId: summary.businessOrderId,
        fulfillmentEnabled: true,
        storeContext: { ...context, permissions: ['ORDER_VIEW', 'ORDER_FULFILL'] },
        returnResult: of(returned),
      });
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      const disposition = host.querySelector('[aria-label="Inventory disposition"]');

      expect(disposition?.textContent).toContain(expectedLabel);
      expect(host.textContent).toContain(`Return Received · ${expectedLabel}`);
      expect(host.textContent).not.toContain('Mark return received');
    });
  }

  it('uses cancelled as the detail header status and exposes no fulfillment action', async () => {
    await configure({
      orderId: summary.businessOrderId,
      fulfillmentEnabled: true,
      storeContext: { ...context, permissions: ['ORDER_VIEW', 'ORDER_FULFILL'] },
      detailResult: of({ ...detail, cancellationStatus: 'CANCELLED' }),
    });
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const headerStatus = host.querySelector('.detail-header .status-pill');

    expect(headerStatus?.textContent?.trim()).toBe('Cancelled');
    expect(host.textContent).toContain('Order group cancelled');
    expect(host.textContent).not.toContain('Accept order group');
  });

  it('shows a retryable outage state without leaking an upstream response', async () => {
    await configure({
      listResult: throwError(() => new HttpErrorResponse({
        status: 503,
        error: { providerReference: 'private-provider-id', message: 'database host' },
      })),
    });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent || '';

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(text).toContain('temporarily unavailable');
    expect(text).not.toContain('private-provider-id');
    expect(text).not.toContain('database host');
  });

  it('identifies a likely split runtime when the enabled seller queue route is missing', async () => {
    await configure({
      listResult: throwError(() => new HttpErrorResponse({ status: 404 })),
    });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent || '';

    expect(text).toContain('Commerce runtime check failed');
    expect(text).toContain('gateway did not expose business orders');
    expect(text).toContain('cart-runtime overlay');
  });

  it('uses semantic controls and responsive layout hooks without fulfillment mutations', async () => {
    await configure();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('form[aria-label="Order queue filters"] label[for="order-status"]')).not.toBeNull();
    expect(host.querySelector('table thead th[scope="col"]')).not.toBeNull();
    expect(host.querySelector('.table-scroll')).not.toBeNull();
    expect(Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim()))
      .not.toContain('Accept');
    expect(Array.from(host.querySelectorAll('button')).map(button => button.textContent?.trim()))
      .not.toContain('Ship');
  });
});
