import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CheckoutService } from '../../core/services/checkout.service';
import { CheckoutDetailComponent } from './checkout-detail.component';

describe('CheckoutDetailComponent', () => {
  let fixture: ComponentFixture<CheckoutDetailComponent>;
  let checkoutService: jasmine.SpyObj<CheckoutService>;

  beforeEach(async () => {
    checkoutService = jasmine.createSpyObj<CheckoutService>('CheckoutService', ['get', 'cancel']);
    checkoutService.get.and.returnValue(of(checkout()));
    checkoutService.cancel.and.returnValue(of({ ...checkout(), status: 'CANCELLED' }));

    await TestBed.configureTestingModule({
      imports: [CheckoutDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ checkoutId: '01C00000000000000000000001' }),
            },
          },
        },
        { provide: CheckoutService, useValue: checkoutService },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CheckoutDetailComponent);
  });

  it('renders stored snapshots and local-demo calculation metadata without payment controls', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Stored items');
    expect(text).toContain('Store item');
    expect(text).toContain('Local demo shipping');
    expect(text).toContain('FREE_LOCAL_DEMO_V1');
    expect(text).toContain('LOCAL_DEMO_V1');
    expect(text).toContain('Payment and order confirmation are not available');
    expect(text).not.toContain('Pay now');
  });

  it('cancels only a pending checkout', () => {
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click();

    expect(checkoutService.cancel).toHaveBeenCalledWith('01C00000000000000000000001');
    expect(fixture.componentInstance.checkout()?.status).toBe('CANCELLED');
  });

  function checkout(): any {
    return {
      id: '01C00000000000000000000001',
      status: 'PENDING_PAYMENT',
      cartVersion: 4,
      currency: 'USD',
      subtotal: 25,
      shipping: 0,
      tax: 0,
      discount: 0,
      total: 25,
      expiresAt: '2026-07-19T12:15:00Z',
      reservation: {
        id: '01R00000000000000000000001',
        status: 'ACTIVE',
        releaseStatus: 'NOT_REQUIRED',
      },
      address: {
        sourceAddressId: '01A00000000000000000000001',
        sourceVersion: 2,
        label: 'Home',
        recipientName: 'Buyer',
        phone: '+15550123456',
        line1: '1 Main St',
        line2: null,
        city: 'Irvine',
        region: 'CA',
        postalCode: '92618',
        countryCode: 'US',
      },
      items: [{
        listingId: '01L00000000000000000000001',
        businessId: '01B00000000000000000000001',
        storeId: '01S00000000000000000000001',
        catalogVersion: 7,
        title: 'Store item',
        sku: 'SKU-1',
        condition: 'NEW',
        thumbnailUrl: null,
        quantity: 2,
        unitPrice: 12.5,
        lineSubtotal: 25,
        shippingAllocation: 0,
        taxAllocation: 0,
        discountAllocation: 0,
        lineTotal: 25,
        policyVersion: 'LOCAL_DEMO_V1',
      }],
      shippingQuotes: [{
        businessId: '01B00000000000000000000001',
        storeId: '01S00000000000000000000001',
        methodCode: 'FREE_LOCAL_DEMO',
        amount: 0,
        adapter: 'FREE_LOCAL_DEMO_V1',
      }],
      taxQuote: { amount: 0, adapter: 'ZERO_LOCAL_DEMO_V1' },
      policies: [{
        businessId: '01B00000000000000000000001',
        storeId: '01S00000000000000000000001',
        source: 'PLATFORM_DEFAULT',
        version: 'LOCAL_DEMO_V1',
        shippingText: 'Shipping text',
        cancellationText: 'Cancellation text',
        returnText: 'Return text',
      }],
      failureCode: null,
      createdAt: '2026-07-19T12:00:00Z',
      updatedAt: '2026-07-19T12:00:00Z',
    };
  }
});
