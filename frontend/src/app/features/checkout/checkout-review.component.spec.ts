import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AddressBookService } from '../../core/services/address-book.service';
import { CartService } from '../../core/services/cart.service';
import { CheckoutService } from '../../core/services/checkout.service';
import { CheckoutReviewComponent } from './checkout-review.component';

describe('CheckoutReviewComponent', () => {
  let fixture: ComponentFixture<CheckoutReviewComponent>;
  let checkoutService: jasmine.SpyObj<CheckoutService>;

  beforeEach(async () => {
    const cart = {
      version: 4,
      items: [{ listingId: '01L00000000000000000000001' }],
    };
    const validation = {
      cartVersion: 4,
      checkoutReady: true,
      validatedTotals: [{ currency: 'USD', amount: 25 }],
      items: [{
        listingId: '01L00000000000000000000001',
        title: 'Store item',
        requestedQuantity: 2,
        currentPrice: 12.5,
        currentCurrency: 'USD',
      }],
      cartIssues: [],
    };
    const cartService: any = jasmine.createSpyObj<CartService>('CartService', ['load', 'validate']);
    cartService.cart = signal(cart).asReadonly();
    cartService.validation = signal(validation).asReadonly();
    cartService.load.and.returnValue(of(cart));
    cartService.validate.and.returnValue(of(validation));

    const addressBook = jasmine.createSpyObj<AddressBookService>('AddressBookService', ['list']);
    addressBook.list.and.returnValue(of([{
      id: '01A00000000000000000000001',
      label: 'Home',
      recipientName: 'Buyer',
      phone: '+15550123456',
      line1: '1 Main St',
      line2: null,
      city: 'Irvine',
      region: 'CA',
      postalCode: '92618',
      countryCode: 'US',
      isDefault: true,
      version: 2,
      createdAt: '2026-07-19T00:00:00Z',
      updatedAt: '2026-07-19T00:00:00Z',
    }]));

    checkoutService = jasmine.createSpyObj<CheckoutService>('CheckoutService', ['create']);
    checkoutService.create.and.returnValue(of({
      id: '01C00000000000000000000001',
    } as any));

    await TestBed.configureTestingModule({
      imports: [CheckoutReviewComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: CartService, useValue: cartService },
        { provide: AddressBookService, useValue: addressBook },
        { provide: CheckoutService, useValue: checkoutService },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CheckoutReviewComponent);
  });

  it('selects the default address and creates from only cart version and address ID', () => {
    fixture.detectChanges();

    expect(fixture.componentInstance.selectedAddressId())
      .toBe('01A00000000000000000000001');
    expect(fixture.nativeElement.textContent).toContain('Calculated when checkout starts');

    const button = fixture.nativeElement.querySelector('aside button') as HTMLButtonElement;
    button.click();

    expect(checkoutService.create).toHaveBeenCalledWith({
      cartVersion: 4,
      addressId: '01A00000000000000000000001',
    });
  });
});
