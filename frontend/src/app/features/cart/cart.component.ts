import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  Cart,
  CartItem,
  CartValidationAction,
  CartValidationItem,
} from '../../core/models/cart.model';
import { CartService } from '../../core/services/cart.service';
import { ListingService } from '../../core/services/listing.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-cart',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="cart-page">
      <header class="cart-header">
        <div>
          <p>Business purchases</p>
          <h1>Your cart</h1>
        </div>
        <a routerLink="/stores">Continue shopping</a>
      </header>

      @if (errorMsg()) {
        <div class="cart-error" role="alert">{{ errorMsg() }}</div>
      }

      @if (loading() && !cartService.cart()) {
        <div class="cart-empty">Loading cart...</div>
      } @else if (!cartService.cart()?.items?.length) {
        <div class="cart-empty">
          <strong>Your cart is empty</strong>
          <p>Browse business items and add one you like.</p>
          <a routerLink="/stores">Browse business items</a>
        </div>
      } @else {
        <div class="cart-layout">
          <div class="cart-items" aria-label="Cart items">
            @for (item of cartService.cart()?.items || []; track item.listingId) {
              <article class="cart-item">
                <a [routerLink]="['/listings', item.listingId]" class="item-image">
                  @if (imageUrl(item)) {
                    <img [src]="imageUrl(item)" [alt]="item.title" />
                  } @else {
                    <span aria-hidden="true">B</span>
                  }
                </a>

                <div class="item-copy">
                  <a [routerLink]="['/listings', item.listingId]">{{ item.title }}</a>
                  <span>{{ item.observedPrice | number: '1.2-2' }} {{ item.currency }} each</span>
                  @if (validationItem(item.listingId); as validation) {
                    @if (validation.currentPrice !== null
                        && (validation.currentPrice !== item.observedPrice
                          || validation.currentCurrency !== item.currency)) {
                      <span class="current-price">
                        Current:
                        {{ validation.currentPrice | number: '1.2-2' }}
                        {{ validation.currentCurrency }}
                      </span>
                    }
                  }
                </div>

                <div class="quantity-control" aria-label="Quantity">
                  <button
                    type="button"
                    title="Decrease quantity"
                    aria-label="Decrease quantity"
                    [disabled]="busy() || item.quantity <= 1"
                    (click)="changeQuantity(item, item.quantity - 1)">-</button>
                  <strong>{{ item.quantity }}</strong>
                  <button
                    type="button"
                    title="Increase quantity"
                    aria-label="Increase quantity"
                    [disabled]="busy() || item.quantity >= 999"
                    (click)="changeQuantity(item, item.quantity + 1)">+</button>
                </div>

                <strong class="line-total">
                  {{ item.observedPrice * item.quantity | number: '1.2-2' }} {{ item.currency }}
                </strong>

                <button
                  type="button"
                  class="remove-button"
                  title="Remove item"
                  [attr.aria-label]="'Remove ' + item.title"
                  [disabled]="busy()"
                  (click)="remove(item)">x</button>

                @if (validationItem(item.listingId); as validation) {
                  <div
                    class="item-validation"
                    [class.validation-ready]="validation.status === 'READY'">
                    <strong>{{ statusLabel(validation.status) }}</strong>
                    @if (validation.status === 'READY') {
                      <span>{{ validation.availableQuantity }} currently available</span>
                    } @else {
                      <div class="validation-messages">
                        @for (issue of validation.issues; track issue.code) {
                          <span>{{ issue.message }}</span>
                        }
                      </div>
                      <div class="validation-actions">
                        @if (hasAction(validation, 'SET_AVAILABLE_QUANTITY')
                            && validation.availableQuantity !== null
                            && validation.availableQuantity > 0) {
                          <button
                            type="button"
                            [disabled]="busy()"
                            [attr.aria-label]="'Use available quantity for ' + item.title"
                            (click)="useAvailableQuantity(item, validation.availableQuantity)">
                            Use {{ validation.availableQuantity }} available
                          </button>
                        }
                        @if (hasAction(validation, 'ACCEPT_CURRENT_PRICE')) {
                          <button
                            type="button"
                            [disabled]="busy()"
                            [attr.aria-label]="'Accept current price for ' + item.title"
                            (click)="acceptCurrentPrice(item)">
                            Accept current price
                          </button>
                        }
                        @if (hasAction(validation, 'REMOVE_ITEM')) {
                          <button
                            type="button"
                            [disabled]="busy()"
                            [attr.aria-label]="'Remove unavailable ' + item.title"
                            (click)="remove(item)">
                            Remove item
                          </button>
                        }
                      </div>
                    }
                  </div>
                }
              </article>
            }
          </div>

          <aside class="cart-summary" aria-label="Cart summary">
            <div>
              <span>Items</span>
              <strong>{{ cartService.cart()?.totalQuantity }}</strong>
            </div>
            @if (cartService.validating()) {
              <div class="validation-summary validation-checking" aria-live="polite">
                <strong>Checking current price and stock...</strong>
              </div>
            } @else if (validationError()) {
              <div class="validation-summary validation-problem" role="alert">
                <strong>Validation unavailable</strong>
                <span>{{ validationError() }}</span>
                <button type="button" [disabled]="busy()" (click)="validate()">Retry</button>
              </div>
            } @else if (cartService.validation(); as validation) {
              <div
                class="validation-summary"
                [class.validation-ready]="validation.checkoutReady"
                [class.validation-problem]="!validation.checkoutReady">
                <strong>{{ validation.checkoutReady ? 'Ready for checkout' : 'Needs attention' }}</strong>
                <span>
                  {{ validation.checkoutReady
                    ? 'Prices and availability are current.'
                    : 'Resolve the highlighted items before checkout.' }}
                </span>
              </div>
              @for (issue of validation.cartIssues; track issue.code) {
                <div class="cart-issue" role="alert">{{ issue.message }}</div>
              }
              @for (total of validation.validatedTotals; track total.currency) {
                <div>
                  <span>Current subtotal</span>
                  <strong>{{ total.amount | number: '1.2-2' }} {{ total.currency }}</strong>
                </div>
              }
            } @else {
              @for (total of cartService.cart()?.totals || []; track total.currency) {
                <div>
                  <span>Saved subtotal</span>
                  <strong>{{ total.amount | number: '1.2-2' }} {{ total.currency }}</strong>
                </div>
              }
            }
            <button type="button" class="clear-button" [disabled]="busy()" (click)="clear()">Clear cart</button>
            @if (buyerCheckoutEnabled && cartService.validation()?.checkoutReady) {
              <a class="checkout-link" routerLink="/checkout">Continue to checkout</a>
            }
          </aside>
        </div>
      }
    </section>
  `,
  styles: [`
    .cart-page {
      max-width: 1180px;
      margin: 0 auto;
      display: grid;
      gap: 1rem;
      color: var(--market-ink);
    }

    .cart-header {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.5rem 0;
      border-bottom: 1px solid var(--market-line);
    }

    .cart-header p {
      margin: 0 0 0.25rem;
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .cart-header h1 {
      margin: 0;
      color: #352444;
      font-family: var(--font-display);
      font-size: clamp(2rem, 4vw, 3.2rem);
      letter-spacing: 0;
    }

    .cart-header a,
    .cart-empty a {
      color: var(--market-accent-dark);
      font-weight: 900;
      text-decoration: none;
    }

    .cart-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(240px, 300px);
      align-items: start;
      gap: 1rem;
    }

    .cart-items {
      display: grid;
      gap: 0.75rem;
    }

    .cart-item {
      display: grid;
      grid-template-columns: 88px minmax(0, 1fr) 116px minmax(110px, max-content) 40px;
      align-items: center;
      gap: 1rem;
      min-height: 112px;
      padding: 0.75rem;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 10px 24px rgba(132, 77, 160, 0.08);
    }

    .item-image {
      width: 88px;
      aspect-ratio: 1;
      display: grid;
      place-items: center;
      overflow: hidden;
      border-radius: 6px;
      background: #fff0f7;
      color: var(--market-accent-dark);
      font-size: 1.5rem;
      font-weight: 950;
    }

    .item-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .item-copy {
      min-width: 0;
      display: grid;
      gap: 0.4rem;
    }

    .item-copy a {
      overflow-wrap: anywhere;
      color: var(--market-ink);
      font-size: 1rem;
      font-weight: 900;
      text-decoration: none;
    }

    .item-copy span {
      color: var(--market-muted);
      font-size: 0.84rem;
      font-weight: 750;
    }

    .item-copy .current-price {
      color: #176452;
      font-weight: 900;
    }

    .quantity-control {
      width: 116px;
      height: 40px;
      display: grid;
      grid-template-columns: 38px 1fr 38px;
      align-items: center;
      border: 1px solid var(--market-line);
      border-radius: 6px;
      overflow: hidden;
      text-align: center;
    }

    .quantity-control button,
    .remove-button {
      width: 100%;
      height: 100%;
      border: 0;
      background: #fff7fb;
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-size: 1.15rem;
      font-weight: 900;
    }

    button:disabled {
      cursor: wait;
      opacity: 0.5;
    }

    .line-total {
      text-align: right;
      white-space: nowrap;
    }

    .remove-button {
      width: 40px;
      height: 40px;
      border-radius: 6px;
      font-size: 1.35rem;
    }

    .item-validation {
      grid-column: 2 / -1;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.7rem 0.8rem;
      border-left: 3px solid #b92f73;
      background: #fff2f7;
      color: #7d2453;
      font-size: 0.84rem;
    }

    .item-validation.validation-ready {
      border-left-color: #178269;
      background: #edf9f5;
      color: #176452;
    }

    .validation-messages {
      min-width: 0;
      display: grid;
      flex: 1;
      gap: 0.2rem;
    }

    .validation-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.4rem;
    }

    .validation-actions button,
    .validation-summary button {
      min-height: 34px;
      padding: 0.4rem 0.65rem;
      border: 1px solid currentColor;
      border-radius: 6px;
      background: #fff;
      color: inherit;
      cursor: pointer;
      font: inherit;
      font-weight: 900;
    }

    .cart-summary {
      display: grid;
      gap: 0.75rem;
      padding: 1rem;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 10px 24px rgba(132, 77, 160, 0.08);
    }

    .cart-summary div {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--market-line);
    }

    .cart-summary span {
      color: var(--market-muted);
      font-weight: 750;
    }

    .cart-summary .validation-summary {
      display: grid;
      justify-content: stretch;
      gap: 0.3rem;
      padding: 0.75rem;
      border: 0;
      border-left: 3px solid #846b92;
      background: #faf7fc;
    }

    .validation-summary span {
      color: inherit;
      font-size: 0.8rem;
    }

    .cart-summary .validation-summary.validation-ready {
      border-left-color: #178269;
      background: #edf9f5;
      color: #176452;
    }

    .cart-summary .validation-summary.validation-problem {
      border-left-color: #b92f73;
      background: #fff2f7;
      color: #7d2453;
    }

    .validation-checking {
      color: var(--market-muted);
    }

    .cart-issue {
      padding: 0.65rem 0.75rem;
      border-left: 3px solid #b92f73;
      background: #fff2f7;
      color: #7d2453;
      font-size: 0.82rem;
      font-weight: 800;
    }

    .clear-button {
      min-height: 42px;
      border: 1px solid var(--market-line);
      border-radius: 6px;
      background: #fff;
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-weight: 900;
    }

    .checkout-link {
      min-height: 42px;
      display: grid;
      place-items: center;
      border-radius: 6px;
      background: var(--market-accent-dark);
      color: #fff;
      font-weight: 900;
      text-decoration: none;
    }

    .cart-empty,
    .cart-error {
      padding: 2rem;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
    }

    .cart-empty {
      display: grid;
      justify-items: start;
      gap: 0.5rem;
    }

    .cart-empty p {
      margin: 0;
      color: var(--market-muted);
    }

    .cart-error {
      padding: 0.75rem 1rem;
      border-color: rgba(190, 58, 131, 0.35);
      color: #a72f68;
      font-weight: 800;
    }

    @media (max-width: 820px) {
      .cart-layout {
        grid-template-columns: 1fr;
      }

      .cart-summary {
        order: -1;
      }

      .cart-item {
        grid-template-columns: 72px minmax(0, 1fr) 40px;
      }

      .item-image {
        width: 72px;
      }

      .quantity-control {
        grid-column: 2;
      }

      .line-total {
        grid-column: 2;
        text-align: left;
      }

      .remove-button {
        grid-column: 3;
        grid-row: 1;
      }

      .item-validation {
        grid-column: 1 / -1;
        flex-direction: column;
      }

      .validation-actions {
        justify-content: flex-start;
      }
    }
  `],
})
export class CartComponent implements OnInit {
  readonly buyerCheckoutEnabled = environment.features.buyerCheckout;
  readonly cartService = inject(CartService);
  private readonly listingService = inject(ListingService);
  readonly errorMsg = signal('');
  readonly validationError = signal('');
  readonly loading = signal(false);

  ngOnInit(): void {
    this.loading.set(true);
    this.cartService.load().subscribe({
      next: cart => {
        if (cart.items.length) {
          this.validate();
        }
      },
      error: error => {
        this.errorMsg.set(error?.error?.error?.message || 'The cart could not be loaded.');
      },
      complete: () => this.loading.set(false),
    });
  }

  changeQuantity(item: CartItem, quantity: number): void {
    if (quantity < 1 || quantity > 999 || this.busy()) {
      return;
    }
    this.runMutation(this.cartService.update(item.listingId, { quantity }));
  }

  remove(item: CartItem): void {
    if (this.busy()) {
      return;
    }
    this.runMutation(this.cartService.remove(item.listingId));
  }

  clear(): void {
    if (this.busy()) {
      return;
    }
    this.runMutation(this.cartService.clear());
  }

  validate(): void {
    if (!this.cartService.cart()?.items.length || this.cartService.validating()) {
      return;
    }
    this.validationError.set('');
    this.cartService.validate().subscribe({
      error: error => {
        this.validationError.set(
          error?.error?.error?.message || 'Current price and stock could not be checked.',
        );
      },
    });
  }

  acceptCurrentPrice(item: CartItem): void {
    if (this.busy()) {
      return;
    }
    this.runMutation(this.cartService.add({
      listingId: item.listingId,
      quantity: item.quantity,
    }));
  }

  useAvailableQuantity(item: CartItem, quantity: number): void {
    if (this.busy() || quantity < 1) {
      return;
    }
    this.runMutation(this.cartService.update(item.listingId, { quantity }));
  }

  validationItem(listingId: string): CartValidationItem | undefined {
    return this.cartService.validation()?.items.find(item => item.listingId === listingId);
  }

  hasAction(item: CartValidationItem, action: CartValidationAction): boolean {
    return item.issues.some(issue => issue.action === action);
  }

  statusLabel(status: CartValidationItem['status']): string {
    return {
      READY: 'Ready',
      PRICE_CHANGED: 'Price changed',
      QUANTITY_REDUCED: 'Quantity changed',
      OUT_OF_STOCK: 'Out of stock',
      LISTING_UNAVAILABLE: 'Item unavailable',
      SELLER_UNAVAILABLE: 'Seller unavailable',
      CURRENCY_CONFLICT: 'Currency conflict',
    }[status];
  }

  busy(): boolean {
    return this.loading() || this.cartService.validating();
  }

  imageUrl(item: CartItem): string {
    return this.listingService.mediaUrl(item.thumbnailUrl);
  }

  private runMutation(request: Observable<Cart>): void {
    this.loading.set(true);
    this.errorMsg.set('');
    this.validationError.set('');
    request.subscribe({
      next: cart => {
        if (cart.items.length) {
          this.validate();
        }
      },
      error: error => {
        this.loading.set(false);
        this.errorMsg.set(error?.error?.error?.message || 'The cart could not be updated.');
      },
      complete: () => this.loading.set(false),
    });
  }
}
