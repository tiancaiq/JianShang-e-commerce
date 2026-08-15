import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { BuyerOrderDetail } from '../../core/models/order.model';
import { OrderService } from '../../core/services/order.service';
import { OrderDetailComponent } from './order-detail.component';

describe('OrderDetailComponent', () => {
  let fixture: ComponentFixture<OrderDetailComponent>;

  beforeEach(async () => {
    const service = jasmine.createSpyObj<OrderService>('OrderService', ['detail', 'returnDetail', 'requestReturn']);
    service.detail.and.returnValue(of(order));
    service.returnDetail.and.returnValue(of({} as import('../../core/models/order.model').BusinessOrderReturn));
    await TestBed.configureTestingModule({
      imports: [OrderDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ orderId: order.orderId }) } },
        },
        { provide: OrderService, useValue: service },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(OrderDetailComponent);
  });

  it('renders the immutable store name without exposing the raw store ID as a heading', () => {
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('Shen Ban Demo Store');
    expect(text).not.toContain('Store group 01S00000000000000000000001');
  });
});

const order: BuyerOrderDetail = {
  orderId: '01O00000000000000000000001',
  status: 'CONFIRMED',
  paymentStatus: 'SUCCEEDED',
  totalAmount: 1,
  currency: 'USD',
  version: 1,
  createdAt: '2026-08-03T12:00:00Z',
  updatedAt: '2026-08-03T12:00:00Z',
  groups: [{
    businessOrderId: '01G00000000000000000000001',
    businessId: '01B00000000000000000000001',
    storeId: '01S00000000000000000000001',
    storeName: 'Shen Ban Demo Store',
    status: 'PENDING_ACCEPTANCE',
    totalAmount: 1,
    currency: 'USD',
      items: [{
      listingId: '01L00000000000000000000001',
      title: 'Demo item',
      businessId: '01B00000000000000000000001',
      storeId: '01S00000000000000000000001',
      unitPrice: 1,
      currency: 'USD',
      quantity: 1,
      lineTotal: 1,
        policyVersion: 'LOCAL_DEMO_V1',
      }],
      version: 0,
      timeline: [],
      shipment: null,
    }],
  shippingAddress: {
    label: 'Home',
    recipientName: 'Demo Buyer',
    phone: '+15550123456',
    line1: '1 Main St',
    line2: null,
    city: 'Irvine',
    region: 'CA',
    postalCode: '92618',
    countryCode: 'US',
  },
};
