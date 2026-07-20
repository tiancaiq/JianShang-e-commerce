import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Checkout } from '../../core/models/checkout.model';
import { CheckoutService } from '../../core/services/checkout.service';

@Component({
  selector: 'app-checkout-detail',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="detail-page">
      <header>
        <a routerLink="/cart">Back to cart</a>
        <p>Local demo checkout</p>
        <h1>Checkout</h1>
      </header>

      @if (error()) {
        <div class="notice error" role="alert">{{ error() }}</div>
      } @else if (!checkout()) {
        <div class="notice">Loading checkout...</div>
      } @else if (checkout(); as current) {
        <div class="status" [attr.data-status]="current.status">
          <strong>{{ statusLabel(current.status) }}</strong>
          <span>Expires {{ current.expiresAt }}</span>
        </div>

        <div class="layout">
          <div class="content">
            <section>
              <h2>Stored items</h2>
              @for (item of current.items; track item.listingId) {
                <div class="line">
                  <span>
                    <strong>{{ item.title }}</strong>
                    {{ item.quantity }} × {{ item.unitPrice | number: '1.2-4' }} {{ current.currency }}
                  </span>
                  <strong>{{ item.lineTotal | number: '1.2-4' }} {{ current.currency }}</strong>
                </div>
              }
            </section>

            <section>
              <h2>Stored delivery address</h2>
              <p>{{ current.address.recipientName }} · {{ current.address.phone }}</p>
              <p>{{ current.address.line1 }}{{ current.address.line2 ? ', ' + current.address.line2 : '' }}</p>
              <p>{{ current.address.city }}, {{ current.address.region }}
                {{ current.address.postalCode }} {{ current.address.countryCode }}</p>
            </section>

            @for (policy of current.policies; track policy.businessId) {
              <section>
                <h2>Policy {{ policy.version }}</h2>
                <p><strong>Shipping:</strong> {{ policy.shippingText }}</p>
                <p><strong>Cancellation:</strong> {{ policy.cancellationText }}</p>
                <p><strong>Returns:</strong> {{ policy.returnText }}</p>
              </section>
            }
          </div>

          <aside>
            <div><span>Subtotal</span><strong>{{ current.subtotal | number: '1.2-4' }} {{ current.currency }}</strong></div>
            <div><span>Local demo shipping</span><strong>{{ current.shipping | number: '1.2-4' }} {{ current.currency }}</strong></div>
            <small>{{ current.shippingQuotes[0]?.adapter }}</small>
            <div><span>Local demo tax</span><strong>{{ current.tax | number: '1.2-4' }} {{ current.currency }}</strong></div>
            <small>{{ current.taxQuote.adapter }}</small>
            <div class="total"><span>Total</span><strong>{{ current.total | number: '1.2-4' }} {{ current.currency }}</strong></div>
            @if (current.status === 'PENDING_PAYMENT') {
              <button type="button" [disabled]="cancelling()" (click)="cancel()">
                {{ cancelling() ? 'Cancelling...' : 'Cancel checkout' }}
              </button>
            }
            <p class="boundary">Payment and order confirmation are not available in this slice.</p>
          </aside>
        </div>
      }
    </section>
  `,
  styles: [`
    .detail-page { max-width: 1120px; margin: 0 auto; display: grid; gap: 1rem; color: var(--market-ink); }
    header { border-bottom: 1px solid var(--market-line); padding-bottom: 1rem; }
    header a { color: var(--market-accent-dark); font-weight: 800; text-decoration: none; }
    header p { margin: .7rem 0 .2rem; color: var(--market-accent-dark); font-size: .78rem; font-weight: 900; text-transform: uppercase; }
    h1 { margin: 0; font-family: var(--font-display); font-size: 2.5rem; letter-spacing: 0; }
    h2 { margin: 0 0 .8rem; font-size: 1.1rem; letter-spacing: 0; }
    .status { display: flex; justify-content: space-between; gap: 1rem; padding: .8rem 1rem; border-left: 4px solid #8a6a99; background: #faf6fc; }
    .status[data-status="PENDING_PAYMENT"] { border-left-color: #168168; background: #edf9f5; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 1rem; align-items: start; }
    .content { display: grid; gap: 1rem; }
    section section, aside, .notice { border: 1px solid var(--market-line); border-radius: 8px; background: #fff; padding: 1rem; }
    section section p { margin: .35rem 0; color: var(--market-muted); }
    .line, aside div { display: flex; justify-content: space-between; gap: 1rem; padding: .7rem 0; border-bottom: 1px solid var(--market-line); }
    .line span { display: grid; gap: .2rem; }
    aside { display: grid; gap: .45rem; }
    aside small { color: var(--market-muted); overflow-wrap: anywhere; }
    aside .total { font-size: 1.08rem; border-bottom: 0; }
    button { min-height: 44px; border: 1px solid #b73572; border-radius: 6px; background: #fff; color: #a52d65; font: inherit; font-weight: 900; cursor: pointer; }
    button:disabled { opacity: .5; cursor: wait; }
    .boundary { margin: .4rem 0 0; color: var(--market-muted); font-size: .8rem; }
    .notice.error { color: #9d285d; border-color: #e9a7c7; }
    @media (max-width: 760px) { .layout { grid-template-columns: 1fr; } aside { order: -1; } }
  `],
})
export class CheckoutDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly checkoutService = inject(CheckoutService);

  readonly checkout = signal<Checkout | null>(null);
  readonly error = signal('');
  readonly cancelling = signal(false);

  ngOnInit(): void {
    const checkoutId = this.route.snapshot.paramMap.get('checkoutId');
    if (!checkoutId) {
      this.error.set('Checkout was not found.');
      return;
    }
    this.checkoutService.get(checkoutId).subscribe({
      next: checkout => this.checkout.set(checkout),
      error: error => this.error.set(error?.error?.error?.message || 'Checkout could not be loaded.'),
    });
  }

  cancel(): void {
    const checkout = this.checkout();
    if (!checkout || checkout.status !== 'PENDING_PAYMENT' || this.cancelling()) {
      return;
    }
    this.cancelling.set(true);
    this.checkoutService.cancel(checkout.id).subscribe({
      next: updated => {
        this.checkout.set(updated);
        this.cancelling.set(false);
      },
      error: error => {
        this.error.set(error?.error?.error?.message || 'Checkout could not be cancelled.');
        this.cancelling.set(false);
      },
    });
  }

  statusLabel(status: Checkout['status']): string {
    return {
      RESERVING: 'Reservation pending',
      PENDING_PAYMENT: 'Reserved · awaiting payment support',
      FAILED: 'Checkout failed',
      CANCELLED: 'Checkout cancelled',
      EXPIRED: 'Checkout expired',
    }[status];
  }
}
