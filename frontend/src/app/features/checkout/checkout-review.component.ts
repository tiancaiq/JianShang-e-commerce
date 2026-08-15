import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { BuyerAddress } from '../../core/models/address.model';
import { AddressBookService } from '../../core/services/address-book.service';
import { CartService } from '../../core/services/cart.service';
import { CheckoutService } from '../../core/services/checkout.service';

@Component({
  selector: 'app-checkout-review',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="checkout-page">
      <header>
        <a routerLink="/cart">Back to cart</a>
        <p>Local demo checkout</p>
        <h1>Review checkout</h1>
      </header>

      @if (error()) {
        <div class="notice error" role="alert">{{ error() }}</div>
      }

      @if (loading()) {
        <div class="notice">Checking your cart and addresses...</div>
      } @else if (!addresses().length) {
        <div class="notice">
          <strong>Add a delivery address first.</strong>
          <a routerLink="/account/addresses">Manage addresses</a>
        </div>
      } @else {
        <div class="checkout-layout">
          <div class="content">
            <section>
              <h2>Delivery address</h2>
              <div class="address-list">
                @for (address of addresses(); track address.id) {
                  <label [class.selected]="selectedAddressId() === address.id">
                    <input
                      type="radio"
                      name="address"
                      [checked]="selectedAddressId() === address.id"
                      (change)="selectedAddressId.set(address.id)"
                    />
                    <span>
                      <strong>{{ address.label || address.recipientName }}</strong>
                      {{ address.line1 }}, {{ address.city }}, {{ address.region }}
                      {{ address.postalCode }}
                    </span>
                  </label>
                }
              </div>
            </section>

            <section>
              <h2>Items</h2>
              @for (item of cartService.validation()?.items || []; track item.listingId) {
                <div class="line">
                  <span>{{ item.title }} × {{ item.requestedQuantity }}</span>
                  <strong>{{ (item.currentPrice || 0) * item.requestedQuantity | number: '1.2-2' }}
                    {{ item.currentCurrency }}</strong>
                </div>
              }
            </section>
          </div>

          <aside>
            @for (total of cartService.validation()?.validatedTotals || []; track total.currency) {
              <div><span>Subtotal</span><strong>{{ total.amount | number: '1.2-2' }} {{ total.currency }}</strong></div>
            }
            <div><span>Shipping</span><strong>Calculated when checkout starts</strong></div>
            <div><span>Tax</span><strong>Calculated when checkout starts</strong></div>
            <button
              type="button"
              [disabled]="creating() || !selectedAddressId() || !cartService.validation()?.checkoutReady"
              (click)="create()"
            >
              {{ creating() ? 'Starting checkout...' : 'Start checkout' }}
            </button>
            <small>Next: local demo payment. No real money will be charged.</small>
          </aside>
        </div>
      }
    </section>
  `,
  styles: [`
    .checkout-page { max-width: 1120px; margin: 0 auto; display: grid; gap: 1rem; color: var(--market-ink); }
    header { border-bottom: 1px solid var(--market-line); padding-bottom: 1rem; }
    header a { color: var(--market-accent-dark); font-weight: 800; text-decoration: none; }
    header p { margin: .7rem 0 .2rem; color: var(--market-accent-dark); font-size: .78rem; font-weight: 900; text-transform: uppercase; }
    h1 { margin: 0; font-family: var(--font-display); font-size: 2.5rem; letter-spacing: 0; }
    h2 { margin: 0 0 .8rem; font-size: 1.1rem; letter-spacing: 0; }
    .checkout-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 1rem; align-items: start; }
    .content { display: grid; gap: 1rem; }
    section section, aside, .notice { border: 1px solid var(--market-line); border-radius: 8px; background: #fff; padding: 1rem; }
    .address-list { display: grid; gap: .6rem; }
    .address-list label { display: flex; gap: .7rem; padding: .8rem; border: 1px solid var(--market-line); border-radius: 6px; cursor: pointer; }
    .address-list label.selected { border-color: var(--market-accent-dark); background: #fff5fa; }
    .address-list span { display: grid; gap: .2rem; }
    .line, aside div { display: flex; justify-content: space-between; gap: 1rem; padding: .7rem 0; border-bottom: 1px solid var(--market-line); }
    aside { display: grid; gap: .6rem; }
    aside strong { max-width: 170px; text-align: right; font-size: .86rem; }
    button { min-height: 44px; border: 0; border-radius: 6px; background: var(--market-accent-dark); color: #fff; font: inherit; font-weight: 900; cursor: pointer; }
    button:disabled { opacity: .5; cursor: wait; }
    small { color: var(--market-muted); text-align: center; }
    .notice { display: flex; gap: 1rem; justify-content: space-between; }
    .notice.error { color: #9d285d; border-color: #e9a7c7; }
    @media (max-width: 760px) { .checkout-layout { grid-template-columns: 1fr; } aside { order: -1; } }
  `],
})
export class CheckoutReviewComponent implements OnInit {
  readonly cartService = inject(CartService);
  private readonly addressBook = inject(AddressBookService);
  private readonly checkoutService = inject(CheckoutService);
  private readonly router = inject(Router);

  readonly addresses = signal<BuyerAddress[]>([]);
  readonly selectedAddressId = signal('');
  readonly loading = signal(true);
  readonly creating = signal(false);
  readonly error = signal('');

  ngOnInit(): void {
    this.cartService.load().subscribe({
      next: cart => {
        if (!cart.items.length) {
          void this.router.navigate(['/cart']);
          return;
        }
        this.cartService.validate().subscribe({
          next: validation => {
            if (!validation.checkoutReady) {
              void this.router.navigate(['/cart']);
              return;
            }
            this.loadAddresses();
          },
          error: () => this.fail('The cart could not be validated.'),
        });
      },
      error: () => this.fail('The cart could not be loaded.'),
    });
  }

  create(): void {
    const validation = this.cartService.validation();
    const addressId = this.selectedAddressId();
    if (!validation?.checkoutReady || !addressId || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.error.set('');
    this.checkoutService.create({ cartVersion: validation.cartVersion, addressId }).subscribe({
      next: checkout => void this.router.navigate(['/checkout', checkout.id]),
      error: error => {
        this.creating.set(false);
        const code = error?.error?.error?.code;
        if (code === 'CHECKOUT_ALREADY_ACTIVE') {
          const checkoutId = error?.error?.error?.details?.find(
            (detail: { field: string; code: string }) => detail.field === 'checkoutId',
          )?.code;
          if (checkoutId) {
            void this.router.navigate(['/checkout', checkoutId]);
            return;
          }
        }
        if (code === 'CHECKOUT_CART_VERSION_CONFLICT' || code === 'CHECKOUT_CART_INVALID') {
          void this.router.navigate(['/cart']);
          return;
        }
        this.error.set(error?.error?.error?.message || 'Checkout could not be started.');
      },
    });
  }

  private loadAddresses(): void {
    this.addressBook.list().subscribe({
      next: addresses => {
        this.addresses.set(addresses);
        this.selectedAddressId.set(addresses.find(address => address.isDefault)?.id || addresses[0]?.id || '');
        this.loading.set(false);
      },
      error: () => this.fail('Addresses could not be loaded.'),
    });
  }

  private fail(message: string): void {
    this.error.set(message);
    this.loading.set(false);
  }
}
