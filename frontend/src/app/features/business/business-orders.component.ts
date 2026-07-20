import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  BusinessOrderDetail,
  BusinessOrderStatus,
  BusinessOrderSummary,
} from '../../core/models/business-order.model';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { BusinessOrderService } from '../../core/services/business-order.service';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { BUSINESS_ORDERS_ENABLED } from './business-orders.capability';

const STATUS_OPTIONS: ReadonlyArray<{ value: BusinessOrderStatus; label: string }> = [
  { value: 'PENDING_ACCEPTANCE', label: 'Pending acceptance' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'PARTIALLY_SHIPPED', label: 'Partially shipped' },
  { value: 'SHIPPED', label: 'Shipped' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'CANCELLATION_PENDING', label: 'Cancellation pending' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

@Component({
  selector: 'app-business-orders',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink],
  template: `
    <section class="orders-page">
      @if (!featureEnabled) {
        <p class="state-message" role="status">Business orders are unavailable.</p>
      } @else if (loadingContext()) {
        <p class="state-message" role="status">Loading store access.</p>
      } @else if (!context()) {
        <div class="state-message" role="status">
          <h2>Order access unavailable</h2>
          <p>An active business membership with order-view permission is required.</p>
        </div>
      } @else if (detailMode()) {
        <header class="page-header">
          <div>
            <p class="eyebrow">{{ context()?.store?.name }} fulfillment</p>
            <h1>Order detail</h1>
          </div>
          <a routerLink="/seller/orders" class="secondary-action">Back to orders</a>
        </header>

        @if (loadingDetail()) {
          <p class="state-message" role="status">Loading order detail.</p>
        } @else if (errorMessage()) {
          <div class="state-message error" role="alert">
            <h2>Order could not be loaded</h2>
            <p>{{ errorMessage() }}</p>
            <button type="button" class="secondary-action" (click)="loadDetail()">Try again</button>
          </div>
        } @else if (detail(); as order) {
          <section class="detail-header" aria-labelledby="order-number">
            <div>
              <p class="eyebrow">Seller order</p>
              <h2 id="order-number">{{ order.sellerOrderNumber }}</h2>
              <p>Buyer order {{ order.buyerOrderNumber }}</p>
            </div>
            <span class="status-pill">{{ statusLabel(order.status) }}</span>
          </section>

          <dl class="summary-grid" aria-label="Order summary">
            <div><dt>Confirmed</dt><dd>{{ order.confirmedAt | date:'medium' }}</dd></div>
            <div><dt>Payment</dt><dd>{{ label(order.paymentStatus) }}</dd></div>
            <div><dt>Items</dt><dd>{{ order.totalQuantity }}</dd></div>
            <div><dt>Total</dt><dd>{{ order.totalAmount | currency:order.currency }}</dd></div>
            @if (hasFinance(order)) {
              <div><dt>Platform fee projection</dt><dd>{{ order.platformFeeProjection | currency:order.currency }}</dd></div>
            }
          </dl>

          <section class="detail-section" aria-labelledby="items-heading">
            <h2 id="items-heading">Items</h2>
            <div class="item-list">
              @for (item of order.items; track item.listingId + ':' + item.sku) {
                <article class="item-row">
                  @if (item.thumbnailUrl) {
                    <img [src]="item.thumbnailUrl" [alt]="item.title" />
                  } @else {
                    <div class="image-placeholder" aria-hidden="true"></div>
                  }
                  <div class="item-name">
                    <strong>{{ item.title }}</strong>
                    <span>SKU {{ item.sku }} | {{ label(item.itemCondition) }}</span>
                    <span>Policy {{ item.policyVersion }}</span>
                  </div>
                  <span>{{ item.quantity }} x {{ item.unitPrice | currency:item.currency }}</span>
                  <strong>{{ item.lineTotal | currency:item.currency }}</strong>
                </article>
              }
            </div>
          </section>

          <section class="detail-section" aria-labelledby="address-heading">
            <h2 id="address-heading">Shipping address</h2>
            <address>
              <strong>{{ order.shippingAddress.recipientName }}</strong><br />
              {{ order.shippingAddress.line1 }}<br />
              @if (order.shippingAddress.line2) {
                {{ order.shippingAddress.line2 }}<br />
              }
              {{ order.shippingAddress.city }}, {{ order.shippingAddress.region }}
              {{ order.shippingAddress.postalCode }}<br />
              {{ order.shippingAddress.countryCode }}<br />
              <a [href]="'tel:' + order.shippingAddress.phone">{{ order.shippingAddress.phone }}</a>
            </address>
          </section>
        }
      } @else {
        <header class="page-header">
          <div>
            <p class="eyebrow">{{ context()?.store?.name }} fulfillment</p>
            <h1>Business orders</h1>
            <p>Review paid business orders and immutable fulfillment details.</p>
          </div>
        </header>

        <form class="filters" (ngSubmit)="applyFilter()" aria-label="Order queue filters">
          <label for="order-status">Status</label>
          <select id="order-status" name="orderStatus" [(ngModel)]="selectedStatus">
            <option value="">All statuses</option>
            @for (option of statusOptions; track option.value) {
              <option [value]="option.value">{{ option.label }}</option>
            }
          </select>
          <button type="submit" class="primary-action" [disabled]="loadingList()">Apply</button>
        </form>

        @if (loadingList()) {
          <p class="state-message" role="status">Loading business orders.</p>
        } @else if (errorMessage()) {
          <div class="state-message error" role="alert">
            <h2>Orders could not be loaded</h2>
            <p>{{ errorMessage() }}</p>
            <button type="button" class="secondary-action" (click)="reloadPage()">Try again</button>
          </div>
        } @else if (orders().length === 0) {
          <div class="state-message" role="status">
            <h2>No matching orders</h2>
            <p>Paid business orders will appear here when they match this status.</p>
          </div>
        } @else {
          <div class="table-scroll">
            <table>
              <caption>Business fulfillment queue</caption>
              <thead>
                <tr>
                  <th scope="col">Order</th>
                  <th scope="col">Status</th>
                  <th scope="col">Items</th>
                  <th scope="col">Total</th>
                  <th scope="col">Confirmed</th>
                  <th scope="col"><span class="visually-hidden">View</span></th>
                </tr>
              </thead>
              <tbody>
                @for (order of orders(); track order.businessOrderId) {
                  <tr>
                    <td data-label="Order">
                      <strong>{{ order.sellerOrderNumber }}</strong>
                      <span>Buyer {{ order.buyerOrderNumber }}</span>
                    </td>
                    <td data-label="Status"><span class="status-pill">{{ statusLabel(order.status) }}</span></td>
                    <td data-label="Items">{{ order.totalQuantity }} in {{ order.itemCount }} line(s)</td>
                    <td data-label="Total">
                      <strong>{{ order.totalAmount | currency:order.currency }}</strong>
                      @if (hasFinance(order)) {
                        <span>Fee {{ order.platformFeeProjection | currency:order.currency }}</span>
                      }
                    </td>
                    <td data-label="Confirmed">{{ order.confirmedAt | date:'mediumDate' }}</td>
                    <td><a [routerLink]="['/seller/orders', order.businessOrderId]">View</a></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <nav class="pagination" aria-label="Order pages">
            <button type="button" class="secondary-action" (click)="previousPage()" [disabled]="!canGoBack() || loadingList()">
              Previous
            </button>
            <span aria-live="polite">Page {{ pageNumber() }}</span>
            <button type="button" class="secondary-action" (click)="nextPage()" [disabled]="!hasMore() || loadingList()">
              Next
            </button>
          </nav>
        }
      }
    </section>
  `,
  styles: [`
    .orders-page { display: grid; gap: 1.25rem; color: var(--color-text-primary); }
    .page-header, .detail-header {
      display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem;
      padding-bottom: 1rem; border-bottom: 1px solid var(--color-border);
    }
    .page-header h1, .detail-header h2, .detail-section h2, .state-message h2 {
      margin: 0; letter-spacing: 0;
    }
    .page-header h1 { font-size: 1.75rem; }
    .page-header p, .detail-header p { margin: .35rem 0 0; color: var(--color-text-secondary); }
    .eyebrow { color: var(--color-accent) !important; font-size: .75rem; font-weight: 800; text-transform: uppercase; }
    .filters { display: flex; align-items: end; gap: .75rem; padding: 1rem 0; }
    .filters label { font-size: .8125rem; font-weight: 700; }
    .filters select {
      min-width: 220px; min-height: 40px; padding: .5rem .625rem; color: var(--color-text-primary);
      background: var(--color-bg-secondary); border: 1px solid var(--color-border); border-radius: var(--radius-md);
    }
    button, .primary-action, .secondary-action {
      min-height: 40px; display: inline-flex; align-items: center; justify-content: center;
      padding: .5rem .875rem; border-radius: var(--radius-md); font: inherit; font-weight: 700; cursor: pointer;
    }
    .primary-action { border: 1px solid var(--color-accent); background: var(--color-accent); color: #111216; }
    .secondary-action { border: 1px solid var(--color-border); background: transparent; color: var(--color-text-primary); text-decoration: none; }
    button:disabled { cursor: not-allowed; opacity: .5; }
    .state-message { padding: 2rem 1rem; text-align: center; color: var(--color-text-secondary); border: 1px dashed var(--color-border); }
    .state-message p { margin: .45rem 0 1rem; }
    .state-message.error { border-color: var(--color-danger); }
    .table-scroll { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
    table { width: 100%; min-width: 820px; border-collapse: collapse; font-size: .8125rem; }
    caption { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
    th { padding: .75rem; color: var(--color-text-muted); text-align: left; background: var(--color-bg-secondary); }
    td { padding: .875rem .75rem; border-top: 1px solid var(--color-border); vertical-align: middle; }
    td > span, td > strong { display: block; }
    td span { margin-top: .2rem; color: var(--color-text-secondary); }
    td a { color: var(--color-accent); font-weight: 750; }
    .status-pill {
      display: inline-flex; width: fit-content; padding: .25rem .5rem; border-radius: var(--radius-sm);
      color: var(--color-text-primary) !important; background: var(--color-accent-muted); font-size: .75rem; font-weight: 750;
    }
    .pagination { display: flex; align-items: center; justify-content: flex-end; gap: .75rem; }
    .pagination span { color: var(--color-text-secondary); font-size: .8125rem; }
    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); margin: 0; border-block: 1px solid var(--color-border); }
    .summary-grid div { padding: 1rem; border-right: 1px solid var(--color-border); }
    .summary-grid dt { color: var(--color-text-muted); font-size: .75rem; }
    .summary-grid dd { margin: .35rem 0 0; font-weight: 750; }
    .detail-section { padding-top: .5rem; }
    .detail-section h2 { margin-bottom: .75rem; font-size: 1rem; }
    .item-list { border-top: 1px solid var(--color-border); }
    .item-row {
      display: grid; grid-template-columns: 56px minmax(180px, 1fr) auto auto; align-items: center;
      gap: 1rem; padding: .875rem 0; border-bottom: 1px solid var(--color-border);
    }
    .item-row img, .image-placeholder { width: 56px; height: 56px; object-fit: cover; background: var(--color-bg-secondary); border-radius: var(--radius-sm); }
    .item-name { display: grid; gap: .2rem; min-width: 0; }
    .item-name strong { overflow-wrap: anywhere; }
    .item-name span { color: var(--color-text-secondary); font-size: .75rem; }
    address { color: var(--color-text-secondary); font-style: normal; line-height: 1.6; }
    address a { color: var(--color-accent); }
    .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
    @media (max-width: 680px) {
      .page-header, .detail-header { flex-direction: column; }
      .filters { align-items: stretch; flex-direction: column; }
      .filters select { width: 100%; min-width: 0; }
      .item-row { grid-template-columns: 48px minmax(0, 1fr); }
      .item-row > span, .item-row > strong { grid-column: 2; }
      .item-row img, .image-placeholder { width: 48px; height: 48px; }
      .pagination { justify-content: space-between; }
    }
  `],
})
export class BusinessOrdersComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly storeService = inject(BusinessStoreService);
  private readonly orderService = inject(BusinessOrderService);
  readonly featureEnabled = inject(BUSINESS_ORDERS_ENABLED);

  readonly statusOptions = STATUS_OPTIONS;
  readonly context = signal<BusinessStoreContext | null>(null);
  readonly loadingContext = signal(false);
  readonly loadingList = signal(false);
  readonly loadingDetail = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly orders = signal<BusinessOrderSummary[]>([]);
  readonly detail = signal<BusinessOrderDetail | null>(null);
  readonly hasMore = signal(false);
  readonly pageNumber = signal(1);

  selectedStatus = '';
  private currentCursor: string | null = null;
  private nextCursor: string | null = null;
  private readonly cursorHistory: Array<string | null> = [];
  private readonly businessOrderId = this.route.snapshot.paramMap.get('businessOrderId');

  ngOnInit(): void {
    if (!this.featureEnabled) {
      return;
    }
    this.loadingContext.set(true);
    this.storeService.getCurrentStoreContext().subscribe({
      next: context => {
        this.loadingContext.set(false);
        if (!this.canViewOrders(context)) {
          this.context.set(null);
          return;
        }
        this.context.set(context);
        if (this.detailMode()) {
          this.loadDetail();
        } else {
          this.loadPage(null);
        }
      },
      error: () => {
        this.loadingContext.set(false);
        this.context.set(null);
      },
    });
  }

  detailMode(): boolean {
    return Boolean(this.businessOrderId);
  }

  applyFilter(): void {
    this.cursorHistory.length = 0;
    this.pageNumber.set(1);
    this.loadPage(null);
  }

  reloadPage(): void {
    this.loadPage(this.currentCursor);
  }

  nextPage(): void {
    if (!this.nextCursor || this.loadingList()) {
      return;
    }
    this.cursorHistory.push(this.currentCursor);
    this.pageNumber.update(value => value + 1);
    this.loadPage(this.nextCursor);
  }

  previousPage(): void {
    if (!this.cursorHistory.length || this.loadingList()) {
      return;
    }
    const cursor = this.cursorHistory.pop() ?? null;
    this.pageNumber.update(value => Math.max(1, value - 1));
    this.loadPage(cursor);
  }

  canGoBack(): boolean {
    return this.cursorHistory.length > 0;
  }

  loadDetail(): void {
    const context = this.context();
    if (!context || !this.businessOrderId) {
      return;
    }
    this.loadingDetail.set(true);
    this.errorMessage.set(null);
    this.orderService.detail(context.businessId, this.businessOrderId).subscribe({
      next: order => {
        this.detail.set(order);
        this.loadingDetail.set(false);
      },
      error: error => {
        this.detail.set(null);
        this.loadingDetail.set(false);
        this.errorMessage.set(this.readError(error));
      },
    });
  }

  statusLabel(status: BusinessOrderStatus): string {
    return STATUS_OPTIONS.find(option => option.value === status)?.label ?? this.label(status);
  }

  label(value: string): string {
    return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }

  hasFinance(order: BusinessOrderSummary): boolean {
    return order.platformFeeProjection !== undefined && order.platformFeeProjection !== null;
  }

  private loadPage(cursor: string | null): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.currentCursor = cursor;
    this.loadingList.set(true);
    this.errorMessage.set(null);
    this.orderService.list(context.businessId, {
      status: (this.selectedStatus || null) as BusinessOrderStatus | null,
      cursor,
      limit: 20,
    }).subscribe({
      next: response => {
        this.orders.set(response.items);
        this.nextCursor = response.page.nextCursor;
        this.hasMore.set(response.page.hasMore);
        this.loadingList.set(false);
      },
      error: error => {
        this.orders.set([]);
        this.nextCursor = null;
        this.hasMore.set(false);
        this.loadingList.set(false);
        this.errorMessage.set(this.readError(error));
      },
    });
  }

  private canViewOrders(context: BusinessStoreContext | null): context is BusinessStoreContext {
    return Boolean(
      context
      && context.businessStatus === 'ACTIVE'
      && context.store?.status === 'ACTIVE'
      && context.permissions.includes('ORDER_VIEW'),
    );
  }

  private readError(error: unknown): string {
    if (error instanceof HttpErrorResponse && error.status === 404) {
      return 'The order is unavailable or you do not have access.';
    }
    return 'The order service is temporarily unavailable. Try again.';
  }
}
