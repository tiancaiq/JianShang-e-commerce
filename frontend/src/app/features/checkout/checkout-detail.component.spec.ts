import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CartService } from '../../core/services/cart.service';
import { CheckoutService } from '../../core/services/checkout.service';
import { StripePaymentElementService } from '../../core/services/stripe-payment-element.service';
import { CheckoutDetailComponent } from './checkout-detail.component';

describe('CheckoutDetailComponent', () => {
  let fixture: ComponentFixture<CheckoutDetailComponent>;
  let checkoutService: jasmine.SpyObj<CheckoutService>;
  let cartService: jasmine.SpyObj<CartService>;
  let stripe: jasmine.SpyObj<StripePaymentElementService>;

  beforeEach(async () => {
    checkoutService = jasmine.createSpyObj<CheckoutService>('CheckoutService', [
      'get',
      'cancel',
      'createPaymentIntent',
      'completeDemoPayment',
      'confirmedOrder',
    ]);
    checkoutService.get.and.returnValue(of(checkout()));
    cartService = jasmine.createSpyObj<CartService>('CartService', [
      'refreshAfterConfirmedCheckout',
    ]);
    cartService.refreshAfterConfirmedCheckout.and.returnValue(of({} as any));
    stripe = jasmine.createSpyObj<StripePaymentElementService>('StripePaymentElementService', [
      'mount', 'confirm', 'unmount',
    ]);
    stripe.mount.and.resolveTo();

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
        { provide: CartService, useValue: cartService },
        { provide: StripePaymentElementService, useValue: stripe },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CheckoutDetailComponent);
  });

  it('renders stored snapshots and an explicit no-real-money demo payment control', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Shen Ban Demo Store');
    expect(text).not.toContain('Store 01S00000000000000000000001');
    expect(text).toContain('Store item');
    expect(text).toContain('Free shipping is local-demo behavior');
    expect(text).toContain('FREE_LOCAL_DEMO_V1');
    expect(text).toContain('LOCAL_DEMO_V1');
    expect(text).toContain('Local demo payment');
    expect(text).toContain('No real money will be charged');
    expect(text).toContain('Complete demo payment');
  });

  it('does not expose disabled cancellation controls', () => {
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button.cancel') as HTMLButtonElement;
    expect(button).toBeNull();
    expect(checkoutService.cancel).not.toHaveBeenCalled();
  });

  it('starts authoritative cart refresh as soon as the confirmed order is observed', () => {
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    checkoutService.createPaymentIntent.and.returnValue(of({ status: 'PENDING' } as any));
    checkoutService.completeDemoPayment.and.returnValue(of({} as any));
    checkoutService.confirmedOrder.and.returnValue(of({
      confirmed: true,
      orderId: '01O00000000000000000000001',
    }));
    fixture.detectChanges();

    fixture.componentInstance.pay();

    expect(cartService.refreshAfterConfirmedCheckout).toHaveBeenCalledOnceWith(4);
    expect(router.navigate).toHaveBeenCalledOnceWith(
      ['/account/orders', '01O00000000000000000000001'],
      { queryParams: { confirmed: 'true' } },
    );
  });

  it('mounts Stripe Payment Element from server-provided safe action data', async () => {
    checkoutService.createPaymentIntent.and.returnValue(of({
      status: 'REQUIRES_ACTION',
      action: {
        type: 'STRIPE_PAYMENT_ELEMENT',
        reference: 'pi_test_secret_example',
        publicKey: 'pk_test_example',
        returnUrl: 'http://localhost:4200/checkout/01C00000000000000000000001',
      },
    } as any));
    fixture.detectChanges();

    fixture.componentInstance.pay();
    fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(stripe.mount).toHaveBeenCalledOnceWith(
      'pk_test_example',
      'pi_test_secret_example',
      'http://localhost:4200/checkout/01C00000000000000000000001',
      '#stripe-payment-element',
    );
    expect(checkoutService.completeDemoPayment).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('No production charge will occur');
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
        storeName: 'Shen Ban Demo Store',
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
