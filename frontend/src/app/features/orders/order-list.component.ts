import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BuyerOrderSummary } from '../../core/models/order.model';
import { OrderService } from '../../core/services/order.service';

@Component({
  selector: 'app-order-list',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, RouterLink],
  template: `
    <section class="orders-page">
      <header><p>Purchases</p><h1>Your orders</h1></header>
      @if (loading()) { <div class="state">Loading orders...</div> }
      @else if (error()) { <div class="state error" role="alert">{{ error() }}</div> }
      @else if (!orders().length) { <div class="state">No confirmed orders yet.</div> }
      @else {
        <div class="order-list">
          @for (order of orders(); track order.orderId) {
            <article>
              <div><strong>Order {{ order.orderId }}</strong><span>{{ order.createdAt | date:'medium' }}</span></div>
              <div><span>{{ order.groups.length }} store group(s)</span><strong>{{ order.totalAmount | currency:order.currency }}</strong></div>
              <div><span>{{ order.status }} · Payment {{ order.paymentStatus }}</span><a [routerLink]="['/account/orders', order.orderId]">View order</a></div>
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .orders-page{max-width:1000px;margin:0 auto;display:grid;gap:1rem;color:var(--market-ink)}
    header{border-bottom:1px solid var(--market-line)} header p{margin:0;color:var(--market-accent-dark);font-weight:900;text-transform:uppercase;font-size:.78rem}
    h1{margin:.25rem 0 1rem;font-family:var(--font-display);font-size:2.4rem}.order-list{display:grid;gap:.8rem}
    article,.state{border:1px solid var(--market-line);border-radius:8px;background:#fff;padding:1rem;display:grid;gap:.7rem}
    article>div{display:flex;justify-content:space-between;gap:1rem}article span{color:var(--market-muted)}a{font-weight:900;color:var(--market-accent-dark)}.error{color:#9d285d}
  `],
})
export class OrderListComponent implements OnInit {
  private readonly service = inject(OrderService);
  readonly orders = signal<BuyerOrderSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnInit(): void {
    this.service.list().subscribe({
      next: page => { this.orders.set(page.items); this.loading.set(false); },
      error: error => { this.error.set(this.readError(error)); this.loading.set(false); },
    });
  }

  private readError(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 401) {
        return 'Your session expired. Sign in again to view orders.';
      }
      if (error.status === 403 || error.status === 404) {
        return 'Order history is unavailable for this account.';
      }
    }
    return 'The order service is temporarily unavailable. Try again.';
  }
}
