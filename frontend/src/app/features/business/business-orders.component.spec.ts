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
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { BusinessOrderService } from '../../core/services/business-order.service';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { BUSINESS_ORDERS_ENABLED } from './business-orders.capability';
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
  };

  async function configure(options: {
    enabled?: boolean;
    orderId?: string | null;
    storeContext?: BusinessStoreContext | null;
    listResult?: Observable<BusinessOrderPage>;
    detailResult?: Observable<BusinessOrderDetail>;
  } = {}): Promise<void> {
    storeService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getCurrentStoreContext']);
    orderService = jasmine.createSpyObj<BusinessOrderService>('BusinessOrderService', ['list', 'detail']);
    storeService.getCurrentStoreContext.and.returnValue(of(options.storeContext === undefined ? context : options.storeContext));
    orderService.list.and.returnValue(options.listResult ?? of({
      items: [summary],
      page: { nextCursor: null, hasMore: false },
    }));
    orderService.detail.and.returnValue(options.detailResult ?? of(detail));

    await TestBed.configureTestingModule({
      imports: [BusinessOrdersComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BUSINESS_ORDERS_ENABLED, useValue: options.enabled ?? true },
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
    expect(text).toContain('1 Snapshot St');
    expect(host.querySelector('address a')?.getAttribute('href')).toBe('tel:+15550123456');
    expect(text).not.toContain(summary.buyerOrderId);
    expect(text).not.toContain(summary.businessOrderId);
    expect(text).not.toContain('provider');
    expect(host.querySelector('button')?.textContent).not.toContain('Ship');
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
