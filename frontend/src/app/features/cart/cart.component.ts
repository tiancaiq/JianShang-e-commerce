import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  Cart,
  CartCurrencyTotal,
  CartItem,
  CartValidationAction,
  CartValidationItem,
} from '../../core/models/cart.model';
import { CartService } from '../../core/services/cart.service';
import { ListingService } from '../../core/services/listing.service';
import { environment } from '../../../environments/environment';

interface CartStoreGroup {
  key: string;
  storeName: string;
  storeSlug: string | null;
  businessVerified: boolean;
  location: string;
  items: CartItem[];
}

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

      <p
        class="cart-live-region cart-status-focus"
        tabindex="-1"
        aria-live="polite"
        [class.visible]="operationMsg()">
        {{ operationMsg() }}
      </p>

      @if (errorMsg()) {
        <div class="cart-error cart-status-focus" tabindex="-1" role="alert">{{ errorMsg() }}</div>
      }

      @if (loading() && !cartService.cart()) {
        <div class="cart-empty" aria-live="polite">Loading cart...</div>
      } @else if (!cartService.cart()?.items?.length) {
        <div class="cart-empty">
          <strong>Your cart is empty</strong>
          <p>Browse active business items and add one you like.</p>
          <a routerLink="/stores">Browse business items</a>
        </div>
      } @else {
        <div class="cart-layout">
          <section class="cart-items" aria-labelledby="cart-items-heading">
            <div class="section-title">
              <h2 id="cart-items-heading">Items from business sellers</h2>
              <span>{{ cartService.cart()?.totalQuantity }} total</span>
            </div>

            @for (group of storeGroups(); track group.key) {
              <section
                class="store-group"
                data-testid="cart-store-group"
                [attr.aria-label]="'Items from ' + group.storeName">
                <header class="store-group-header">
                  <div>
                    <span>Ships from</span>
                    @if (group.storeSlug) {
                      <a [routerLink]="['/stores', group.storeSlug]">{{ group.storeName }}</a>
                    } @else {
                      <strong>{{ group.storeName }}</strong>
                    }
                  </div>
                  <div class="store-group-meta">
                    @if (group.businessVerified) {
                      <span class="verified">Verified business</span>
                    }
                    @if (group.location) {
                      <span>{{ group.location }}</span>
                    }
                    <span>{{ group.items.length }} {{ group.items.length === 1 ? 'item' : 'items' }}</span>
                  </div>
                </header>

                @for (item of group.items; track item.listingId) {
                  @let validation = validationItem(item.listingId);
                  <article
                class="packing-card"
                [class.needs-repair]="validation && validation.status !== 'READY'"
                [class.ready]="validation?.status === 'READY'">
                <a [routerLink]="['/listings', item.listingId]" class="item-image">
                  @if (imageUrl(item)) {
                    <img [src]="imageUrl(item)" [alt]="item.title" />
                  } @else {
                    <span aria-hidden="true">{{ fallbackInitial(item) }}</span>
                  }
                </a>

                <div class="item-main">
                  <a [routerLink]="['/listings', item.listingId]" class="item-title">{{ item.title }}</a>

                  <div class="item-facts">
                    <span>{{ item.observedPrice | number: '1.2-2' }} {{ item.currency }} saved price</span>
                    @if (hasCurrentPriceInfo(validation)) {
                      <span [class.changed]="hasCurrentPriceChange(item, validation)">
                        {{ currentPriceLabel(validation) }} current price
                      </span>
                    }
                    @if (hasAvailabilityInfo(validation)) {
                      <span>{{ availabilityLabel(validation) }}</span>
                    }
                  </div>
                </div>

                <div class="quantity-block">
                  <span>Quantity</span>
                  <div class="quantity-control">
                    <button
                      type="button"
                      title="Decrease quantity"
                      aria-label="Decrease quantity"
                      [disabled]="busy() || item.quantity <= 1"
                      (click)="changeQuantity(item, item.quantity - 1, validation)">-</button>
                    <strong aria-live="polite">{{ item.quantity }}</strong>
                    <button
                      type="button"
                      title="Increase quantity"
                      aria-label="Increase quantity"
                      [disabled]="!canIncreaseQuantity(item, validation)"
                      (click)="changeQuantity(item, item.quantity + 1, validation)">+</button>
                  </div>
                </div>

                <div class="line-money">
                  <span>Line total</span>
                  <strong>{{ item.observedPrice * item.quantity | number: '1.2-2' }} {{ item.currency }}</strong>
                  @if (currentLineTotalLabel(item, validation)) {
                    <small>
                      Current:
                      {{ currentLineTotalLabel(item, validation) }}
                    </small>
                  }
                </div>

                <div class="line-actions">
                  <button
                    type="button"
                    class="text-action"
                    [attr.aria-label]="'Remove ' + item.title"
                    [disabled]="busy()"
                    (click)="remove(item)">Remove</button>
                </div>

                @if (validation) {
                  <section
                    class="repair-panel"
                    [class.validation-ready]="validation.status === 'READY'"
                    [attr.aria-label]="'Cart validation for ' + item.title">
                    <div>
                      <strong>{{ statusLabel(validation.status) }}</strong>
                      @if (validation.status === 'READY') {
                        <span>{{ availabilityLabel(validation) }}. Price is current.</span>
                      } @else {
                        <div class="repair-messages">
                          @for (issue of validation.issues; track issue.code) {
                            <span>{{ issue.message }}</span>
                          }
                        </div>
                      }
                    </div>

                    @if (validation.status !== 'READY') {
                      <div class="repair-actions">
                        @if (hasAction(validation, 'SET_AVAILABLE_QUANTITY')
                            && validation.availableQuantity !== null
                            && validation.availableQuantity > 0) {
                          <button
                            type="button"
                            class="text-action"
                            [disabled]="busy()"
                            [attr.aria-label]="'Use available quantity for ' + item.title"
                            (click)="useAvailableQuantity(item, validation.availableQuantity)">
                            Use {{ validation.availableQuantity }} available
                          </button>
                        }
                        @if (hasAction(validation, 'ACCEPT_CURRENT_PRICE')) {
                          <button
                            type="button"
                            class="text-action"
                            [disabled]="busy()"
                            [attr.aria-label]="'Accept current price for ' + item.title"
                            (click)="acceptCurrentPrice(item)">
                            Accept current price
                          </button>
                        }
                        @if (hasAction(validation, 'REMOVE_ITEM')) {
                          <button
                            type="button"
                            class="text-action"
                            [disabled]="busy()"
                            [attr.aria-label]="'Remove unavailable ' + item.title"
                            (click)="remove(item)">
                            Remove item
                          </button>
                        }
                      </div>
                    }
                  </section>
                }
                  </article>
                }
              </section>
            }
          </section>

          <aside class="receipt-rail" aria-label="Cart summary">
            <div class="receipt-card">
              <div class="receipt-heading">
                <span>Cart receipt</span>
                <strong>{{ cartReadinessLabel() }}</strong>
              </div>

              <dl>
                <div>
                  <dt>Items</dt>
                  <dd>{{ cartService.cart()?.totalQuantity }}</dd>
                </div>
                @for (total of savedTotals(); track total.currency) {
                  <div>
                    <dt>Saved subtotal</dt>
                    <dd>{{ total.amount | number: '1.2-2' }} {{ total.currency }}</dd>
                  </div>
                }
                @for (total of currentTotals(); track total.currency) {
                  <div class="current-total">
                    <dt>Current subtotal</dt>
                    <dd>{{ total.amount | number: '1.2-2' }} {{ total.currency }}</dd>
                  </div>
                }
              </dl>

              @if (cartService.validating()) {
                <div class="validation-summary validation-checking" aria-live="polite">
                  <strong>Checking current price and stock</strong>
                  <span>Cart contents stay unchanged while validation runs.</span>
                </div>
              } @else if (validationError()) {
                <div class="validation-summary validation-problem cart-status-focus" tabindex="-1" role="alert">
                  <strong>Validation unavailable</strong>
                  <span>{{ validationError() }}</span>
                  <button type="button" class="text-action" [disabled]="busy()" (click)="validate()">Retry</button>
                </div>
              } @else if (cartService.validation(); as validation) {
                <div
                  class="validation-summary"
                  [class.validation-ready]="validation.checkoutReady"
                  [class.validation-problem]="!validation.checkoutReady">
                  <strong>
                    {{ validation.checkoutReady
                      ? (buyerCheckoutEnabled ? 'Ready for checkout' : 'Cart checks passed')
                      : 'Needs attention' }}
                  </strong>
                  <span>
                    {{ validation.checkoutReady
                      ? 'Current price, stock, store and currency checks passed.'
                      : 'Use the repair actions on the affected items.' }}
                  </span>
                </div>
                @for (issue of validation.cartIssues; track issue.code) {
                  <div class="cart-issue" role="alert">{{ issue.message }}</div>
                }
              } @else {
                <div class="validation-summary">
                  <strong>Saved cart totals</strong>
                  <span>Open validation to confirm current price, stock, store, and currency.</span>
                </div>
              }

              <div class="summary-actions">
                @if (buyerCheckoutEnabled && cartService.validation()?.checkoutReady) {
                  <a class="checkout-link" routerLink="/checkout">Continue to checkout</a>
                } @else {
                  <p class="checkout-copy">
                    {{ buyerCheckoutEnabled
                      ? 'Resolve cart issues before checkout.'
                      : 'Checkout is not enabled yet.' }}
                  </p>
                }
                <button type="button" class="clear-button" [disabled]="busy()" (click)="clear()">Clear cart</button>
              </div>
            </div>
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
      padding: 0.5rem 0 0.9rem;
      border-bottom: 1px solid var(--market-line);
    }

    .cart-header p,
    .section-title span,
    .receipt-heading span,
    .quantity-block span,
    .line-money span {
      margin: 0;
      color: var(--market-accent-dark);
      font-size: 0.75rem;
      font-weight: 950;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .cart-header h1,
    .section-title h2 {
      margin: 0;
      color: #352444;
      font-family: var(--font-display);
      letter-spacing: 0;
    }

    .cart-header h1 {
      font-size: clamp(2rem, 4vw, 3.2rem);
    }

    .cart-header a,
    .cart-empty a,
    .store-stamp a,
    .item-title {
      color: var(--market-accent-dark);
      font-weight: 900;
      text-decoration: none;
    }

    .cart-live-region {
      width: 1px;
      height: 1px;
      overflow: hidden;
      position: absolute;
      white-space: nowrap;
      clip: rect(0, 0, 0, 0);
    }

    .cart-live-region.visible {
      width: auto;
      height: auto;
      clip: auto;
      position: static;
      padding: 0.7rem 0.85rem;
      border-left: 3px solid #178269;
      background: #edf9f5;
      color: #176452;
      font-weight: 850;
    }

    .cart-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(280px, 320px);
      align-items: start;
      gap: 1rem;
    }

    .cart-items,
    .section-title {
      display: grid;
      gap: 0.8rem;
    }

    .store-group {
      display: grid;
      gap: 0.72rem;
      padding-block: 0.25rem 0.85rem;
      border-bottom: 1px solid var(--market-line);
    }

    .store-group-header {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.2rem 0.15rem;
    }

    .store-group-header > div:first-child {
      display: grid;
      gap: 0.18rem;
    }

    .store-group-header > div:first-child > span {
      color: #725f7c;
      font-size: 0.72rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .store-group-header a,
    .store-group-header strong {
      color: var(--market-ink);
      font-family: var(--font-display);
      font-size: 1.08rem;
      font-weight: 900;
      letter-spacing: 0;
      text-decoration: none;
    }

    .store-group-meta {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.35rem 0.55rem;
      color: #6a5874;
      font-size: 0.8rem;
      font-weight: 800;
    }

    .store-group-meta > * + * {
      padding-left: 0.55rem;
      border-left: 1px solid rgba(73, 42, 84, 0.16);
    }

    .store-group-meta .verified {
      color: #176452;
      font-weight: 950;
    }

    .section-title {
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: end;
      padding-top: 0.15rem;
    }

    .section-title h2 {
      font-size: 1.18rem;
    }

    .packing-card {
      display: grid;
      grid-template-columns: 104px minmax(0, 1fr) 128px minmax(126px, max-content) minmax(72px, max-content);
      align-items: center;
      gap: 1rem;
      padding: 0.85rem;
      border: 1px solid rgba(73, 42, 84, 0.13);
      border-left: 4px solid #d89a2b;
      border-radius: 8px;
      background: #fffdf9;
      box-shadow: 0 10px 22px rgba(73, 42, 84, 0.07);
    }

    .packing-card.needs-repair {
      border-left-color: #b92f73;
    }

    .packing-card.ready {
      border-left-color: #178269;
    }

    .item-image {
      width: 104px;
      aspect-ratio: 1;
      display: grid;
      place-items: center;
      overflow: hidden;
      border: 1px solid rgba(73, 42, 84, 0.1);
      border-radius: 6px;
      background: #fff7fb;
      color: var(--market-accent-dark);
      font-size: 1.65rem;
      font-weight: 950;
    }

    .item-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .item-main {
      min-width: 0;
      display: grid;
      gap: 0.44rem;
    }

    .item-facts {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
      color: #6a5874;
      font-size: 0.82rem;
      font-weight: 780;
      line-height: 1.4;
    }

    .item-facts > * + * {
      padding-left: 0.55rem;
      border-left: 1px solid rgba(73, 42, 84, 0.16);
    }

    .item-title {
      overflow-wrap: anywhere;
      color: var(--market-ink);
      font-size: 1.04rem;
      line-height: 1.22;
    }

    .item-facts .changed {
      color: #9b275c;
      font-weight: 950;
    }

    .quantity-block,
    .line-money,
    .line-actions {
      display: grid;
      gap: 0.42rem;
    }

    .quantity-control {
      width: 128px;
      min-height: 44px;
      display: grid;
      grid-template-columns: 44px 1fr 44px;
      align-items: stretch;
      border: 1px solid rgba(73, 42, 84, 0.15);
      border-radius: 8px;
      overflow: hidden;
      background: #fff;
      text-align: center;
    }

    .quantity-control button,
    .text-action,
    .clear-button {
      min-height: 44px;
      border: 0;
      background: #fff7fb;
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-weight: 950;
    }

    .quantity-control button {
      font-size: 1.15rem;
    }

    .quantity-control strong {
      display: grid;
      place-items: center;
      color: var(--market-ink);
      font-weight: 950;
    }

    .line-money {
      text-align: right;
    }

    .line-money strong {
      color: var(--market-ink);
      font-size: 1rem;
      white-space: nowrap;
    }

    .line-money small {
      color: #6a5874;
      font-weight: 800;
      white-space: nowrap;
    }

    .line-actions {
      justify-items: end;
    }

    .text-action {
      min-height: 36px;
      padding: 0.35rem 0.62rem;
      border: 1px solid rgba(190, 47, 118, 0.22);
      border-radius: 7px;
      background: #fff;
      text-align: center;
    }

    .repair-panel {
      grid-column: 2 / -1;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.74rem 0.82rem;
      border: 1px solid rgba(185, 47, 115, 0.18);
      border-left: 3px solid #b92f73;
      border-radius: 8px;
      background: #fff5f8;
      color: #7d2453;
      font-size: 0.84rem;
      line-height: 1.45;
    }

    .repair-panel.validation-ready {
      border-color: rgba(23, 130, 105, 0.18);
      border-left-color: #178269;
      background: #edf9f5;
      color: #176452;
    }

    .repair-panel > div:first-child,
    .repair-messages {
      min-width: 0;
      display: grid;
      gap: 0.2rem;
    }

    .repair-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.45rem;
    }

    .receipt-rail {
      position: sticky;
      top: 0.85rem;
    }

    .receipt-card {
      display: grid;
      gap: 0.8rem;
      padding: 1rem;
      border: 1px solid rgba(73, 42, 84, 0.14);
      border-radius: 8px;
      background: #fffdf9;
      box-shadow: 0 10px 22px rgba(73, 42, 84, 0.07);
    }

    .receipt-heading {
      display: grid;
      gap: 0.22rem;
      padding-bottom: 0.72rem;
      border-bottom: 1px dashed rgba(73, 42, 84, 0.2);
    }

    .receipt-heading strong {
      color: var(--market-ink);
      font-size: 1.25rem;
      font-weight: 950;
    }

    .receipt-card dl,
    .receipt-card dl div {
      display: grid;
      gap: 0.62rem;
    }

    .receipt-card dl {
      margin: 0;
    }

    .receipt-card dl div {
      grid-template-columns: minmax(0, 1fr) max-content;
      padding-bottom: 0.62rem;
      border-bottom: 1px solid rgba(73, 42, 84, 0.1);
    }

    .receipt-card dt {
      color: var(--market-muted);
      font-weight: 800;
    }

    .receipt-card dd {
      margin: 0;
      color: var(--market-ink);
      font-weight: 950;
      white-space: nowrap;
    }

    .receipt-card .current-total dd {
      color: #176452;
    }

    .validation-summary,
    .cart-issue {
      display: grid;
      gap: 0.32rem;
      padding: 0.72rem;
      border-left: 3px solid #846b92;
      border-radius: 8px;
      background: #faf7fc;
      color: #6a5874;
      font-size: 0.84rem;
      font-weight: 800;
      line-height: 1.45;
    }

    .validation-summary.validation-ready {
      border-left-color: #178269;
      background: #edf9f5;
      color: #176452;
    }

    .validation-summary.validation-problem,
    .cart-issue {
      border-left-color: #b92f73;
      background: #fff5f8;
      color: #7d2453;
    }

    .validation-checking {
      color: var(--market-muted);
    }

    .summary-actions {
      display: grid;
      gap: 0.62rem;
    }

    .checkout-link,
    .clear-button,
    .cart-empty a {
      min-height: 44px;
      display: inline-grid;
      place-items: center;
      border-radius: 8px;
      text-align: center;
    }

    .checkout-link {
      border: 1px solid #b87713;
      background: #d89a2b;
      color: #2d2135;
      font-weight: 950;
      text-decoration: none;
      box-shadow: 0 12px 20px rgba(156, 99, 16, 0.16);
    }

    .checkout-copy {
      margin: 0;
      color: #6a5874;
      font-size: 0.84rem;
      font-weight: 850;
      line-height: 1.45;
    }

    .clear-button {
      border: 1px solid rgba(73, 42, 84, 0.14);
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
      gap: 0.55rem;
    }

    .cart-empty p {
      margin: 0;
      color: var(--market-muted);
    }

    .cart-error {
      padding: 0.75rem 1rem;
      border-color: rgba(190, 58, 131, 0.35);
      color: #a72f68;
      font-weight: 850;
    }

    button:disabled,
    .text-action:disabled,
    .clear-button:disabled {
      cursor: not-allowed;
      opacity: 0.56;
      box-shadow: none;
    }

    button:focus-visible,
    a:focus-visible,
    .cart-status-focus:focus-visible {
      outline: 2px solid rgba(216, 154, 43, 0.5);
      outline-offset: 3px;
    }

    @media (max-width: 1280px) {
      .cart-layout {
        grid-template-columns: 1fr;
      }

      .receipt-rail {
        order: -1;
        position: static;
      }

      .packing-card {
        grid-template-columns: 92px minmax(0, 1fr) minmax(72px, max-content);
      }

      .item-image {
        width: 92px;
      }

      .quantity-block,
      .line-money {
        grid-column: 2;
      }

      .line-money {
        text-align: left;
      }

      .line-actions {
        grid-column: 3;
        grid-row: 1;
      }

      .repair-panel {
        grid-column: 1 / -1;
      }
    }

    @media (max-width: 520px) {
      .cart-header,
      .section-title,
      .store-group-header {
        align-items: flex-start;
        grid-template-columns: 1fr;
        flex-direction: column;
      }

      .store-group-meta {
        justify-content: flex-start;
      }

      .packing-card {
        grid-template-columns: 76px minmax(0, 1fr);
        gap: 0.75rem;
      }

      .item-image {
        width: 76px;
      }

      .line-actions {
        grid-column: 2;
        grid-row: auto;
        justify-items: start;
      }

      .quantity-block,
      .line-money,
      .repair-panel {
        grid-column: 1 / -1;
      }

      .repair-panel {
        flex-direction: column;
      }

      .repair-actions {
        justify-content: flex-start;
      }

      .receipt-card dl div {
        grid-template-columns: 1fr;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      *,
      *::before,
      *::after {
        scroll-behavior: auto !important;
      }
    }
  `],
})
export class CartComponent implements OnInit {
  readonly buyerCheckoutEnabled = environment.features.buyerCheckout;
  readonly cartService = inject(CartService);
  private readonly listingService = inject(ListingService);
  private readonly host = inject(ElementRef<HTMLElement>);
  readonly errorMsg = signal('');
  readonly validationError = signal('');
  readonly operationMsg = signal('');
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
        this.focusStatus();
      },
      complete: () => this.loading.set(false),
    });
  }

  // Uses the latest validation as a UI guard while the backend remains authoritative for stock.
  changeQuantity(item: CartItem, quantity: number, validation?: CartValidationItem): void {
    if (quantity < 1
        || quantity > 999
        || (validation?.availableQuantity !== null
          && validation?.availableQuantity !== undefined
          && quantity > validation.availableQuantity)
        || this.busy()) {
      return;
    }
    this.runMutation(this.cartService.update(item.listingId, { quantity }), 'Quantity updated.');
  }

  canIncreaseQuantity(item: CartItem, validation?: CartValidationItem): boolean {
    if (this.busy() || item.quantity >= 999) {
      return false;
    }
    return validation?.availableQuantity === null
      || validation?.availableQuantity === undefined
      || item.quantity < validation.availableQuantity;
  }

  remove(item: CartItem): void {
    if (this.busy()) {
      return;
    }
    this.runMutation(this.cartService.remove(item.listingId), 'Item removed.');
  }

  clear(): void {
    if (this.busy()) {
      return;
    }
    this.runMutation(this.cartService.clear(), 'Cart cleared.');
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
        this.focusStatus();
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
    }), 'Current price accepted.');
  }

  useAvailableQuantity(item: CartItem, quantity: number): void {
    if (this.busy() || quantity < 1) {
      return;
    }
    this.runMutation(this.cartService.update(item.listingId, { quantity }), 'Quantity reduced to available stock.');
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
      QUANTITY_REDUCED: 'Quantity needs review',
      OUT_OF_STOCK: 'Out of stock',
      LISTING_UNAVAILABLE: 'Item unavailable',
      SELLER_UNAVAILABLE: 'Store unavailable',
      CURRENCY_CONFLICT: 'Currency conflict',
    }[status];
  }

  hasCurrentPriceInfo(item?: CartValidationItem): boolean {
    return !!item && item.currentPrice !== null && !!item.currentCurrency;
  }

  currentPriceLabel(item?: CartValidationItem): string {
    if (!this.hasCurrentPriceInfo(item)) {
      return '';
    }
    return `${Number(item?.currentPrice).toFixed(2)} ${item?.currentCurrency}`;
  }

  currentLineTotalLabel(cartItem: CartItem, validation?: CartValidationItem): string {
    if (!this.hasCurrentPriceInfo(validation)) {
      return '';
    }
    return `${(Number(validation?.currentPrice) * cartItem.quantity).toFixed(2)} ${validation?.currentCurrency}`;
  }

  hasAvailabilityInfo(item?: CartValidationItem): boolean {
    return !!item && item.availableQuantity !== null;
  }

  availabilityLabel(item?: CartValidationItem): string {
    if (!item || item.availableQuantity === null) {
      return 'Availability needs review';
    }
    if (item.availableQuantity === 0) {
      return 'Out of stock';
    }
    return `${item.availableQuantity} currently available`;
  }

  hasCurrentPriceChange(item: CartItem, validation?: CartValidationItem): boolean {
    return !!validation
      && ((validation.currentPrice !== null && validation.currentPrice !== item.observedPrice)
        || (validation.currentCurrency !== null && validation.currentCurrency !== item.currency));
  }

  // Groups buyer-visible lines by the authoritative live store slug without exposing internal IDs.
  storeGroups(): CartStoreGroup[] {
    const groups = new Map<string, CartStoreGroup>();
    for (const item of this.cartService.cart()?.items || []) {
      const validation = this.validationItem(item.listingId);
      const storeSlug = this.storeSlug(item, validation);
      const key = storeSlug ? `store:${storeSlug}` : `listing:${item.listingId}`;
      const existing = groups.get(key);
      if (existing) {
        existing.items.push(item);
        continue;
      }
      groups.set(key, {
        key,
        storeName: this.storeLabel(item, validation),
        storeSlug,
        businessVerified: this.businessVerified(item, validation),
        location: this.storeLocation(item, validation),
        items: [item],
      });
    }
    return [...groups.values()];
  }

  savedTotals(): CartCurrencyTotal[] {
    return this.cartService.cart()?.totals || [];
  }

  currentTotals(): CartCurrencyTotal[] {
    return this.cartService.validation()?.checkoutReady
      ? this.cartService.validation()?.validatedTotals || []
      : [];
  }

  cartReadinessLabel(): string {
    if (this.cartService.validating()) {
      return 'Checking';
    }
    if (this.validationError()) {
      return 'Retry needed';
    }
    const validation = this.cartService.validation();
    if (!validation) {
      return 'Saved totals';
    }
    return validation.checkoutReady ? 'Ready' : 'Repair needed';
  }

  busy(): boolean {
    return this.loading() || this.cartService.loading() || this.cartService.validating();
  }

  imageUrl(item: CartItem): string {
    return this.listingService.mediaUrl(item.thumbnailUrl);
  }

  fallbackInitial(item: CartItem): string {
    return (item.title.trim()[0] || 'B').toUpperCase();
  }

  storeLabel(item: CartItem, validation?: CartValidationItem): string {
    return validation?.storeName || item.storeName || 'Business store item';
  }

  storeSlug(item: CartItem, validation?: CartValidationItem): string | null {
    return validation?.storeSlug || item.storeSlug || null;
  }

  businessVerified(item: CartItem, validation?: CartValidationItem): boolean {
    return Boolean(validation?.businessVerified ?? item.businessVerified);
  }

  storeLocation(item: CartItem, validation?: CartValidationItem): string {
    const city = validation?.publicCity || item.publicCity || '';
    const region = validation?.publicRegion || item.publicRegion || '';
    return [city, region].filter(Boolean).join(', ');
  }

  // Runs one explicit buyer cart mutation, then revalidates without replaying failures.
  private runMutation(request: Observable<Cart>, successMessage: string): void {
    this.loading.set(true);
    this.errorMsg.set('');
    this.validationError.set('');
    this.operationMsg.set('');
    request.subscribe({
      next: cart => {
        this.operationMsg.set(successMessage);
        this.focusStatus();
        if (cart.items.length) {
          this.validate();
        }
      },
      error: error => {
        this.loading.set(false);
        this.errorMsg.set(error?.error?.error?.message || 'The cart could not be updated.');
        this.focusStatus();
      },
      complete: () => this.loading.set(false),
    });
  }

  private focusStatus(): void {
    setTimeout(() => {
      (this.host.nativeElement.querySelector('.cart-status-focus') as HTMLElement | null)?.focus();
    });
  }
}
