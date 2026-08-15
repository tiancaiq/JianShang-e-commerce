import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  BusinessOrderDetail,
  BusinessOrderQueueStatus,
  BusinessOrderStatus,
  BusinessOrderSummary,
} from '../../core/models/business-order.model';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { BusinessOrderService } from '../../core/services/business-order.service';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { BUSINESS_ORDERS_ENABLED } from './business-orders.capability';
import { BUSINESS_ORDER_FULFILLMENT_ENABLED } from './business-order-fulfillment.capability';
import { BusinessOrderReturn } from '../../core/models/order.model';

const STATUS_OPTIONS: ReadonlyArray<{ value: BusinessOrderQueueStatus; label: string }> = [
  { value: 'PENDING_ACCEPTANCE', label: 'Pending acceptance' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'PROCESSING', label: 'Processing' },
  { value: 'SHIPPED', label: 'Shipped' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

type FulfillmentAction = 'accept' | 'processing' | 'shipment' | 'delivery';

interface FulfillmentConfirmation {
  action: FulfillmentAction;
  title: string;
  description: string;
  confirmLabel: string;
  fromStatus: BusinessOrderStatus;
  toStatus: BusinessOrderStatus;
  order: BusinessOrderDetail;
}

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
              <p>Marketplace buyer · Order {{ order.buyerOrderNumber }}</p>
            </div>
            <span class="status-pill">{{ sellerStatusLabel(order) }}</span>
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

          @if (order.cancellationStatus !== 'NONE') {
            <section class="cancellation-notice" role="status">
              <p class="eyebrow">Buyer cancellation</p>
              <h2>{{ order.cancellationStatus === 'CANCELLED' ? 'Order group cancelled' : 'Cancellation pending' }}</h2>
              <p>Buyer requested cancellation before fulfillment began. Fulfillment actions are disabled for this group.</p>
            </section>
          }

          @if (fulfillmentEnabled && canFulfill() && order.cancellationStatus === 'NONE') {
            <section class="fulfillment-panel" aria-labelledby="fulfillment-heading">
              <div class="panel-heading">
                <div>
                  <p class="eyebrow">Current step</p>
                  <h2 id="fulfillment-heading">Fulfillment</h2>
                </div>
                <span>Version {{ order.version }}</span>
              </div>
              @if (actionError()) {
                <p class="action-error" role="alert">{{ actionError() }}</p>
              }
              @if (order.status === 'PENDING_ACCEPTANCE') {
                <p>Confirm that this paid store group is ready for fulfillment.</p>
                <button type="button" class="primary-action" [disabled]="actionLoading()"
                  (click)="accept(order)">Accept order group</button>
              } @else if (order.status === 'ACCEPTED') {
                <p>Move this group into active preparation.</p>
                <button type="button" class="primary-action" [disabled]="actionLoading()"
                  (click)="startProcessing(order)">Mark processing</button>
              } @else if (order.status === 'PROCESSING') {
                <p>Record one manual demo shipment. No carrier will be contacted.</p>
                <form class="shipment-form" (ngSubmit)="createShipment(order)">
                  <label>Carrier
                    <input name="carrier" [(ngModel)]="carrierDisplayName" maxlength="80" required />
                  </label>
                  <label>Service
                    <input name="service" [(ngModel)]="serviceDisplayName" maxlength="80" required />
                  </label>
                  <label>Tracking number
                    <input name="tracking" [(ngModel)]="trackingNumber" maxlength="100" required />
                  </label>
                  <button type="submit" class="primary-action" [disabled]="actionLoading()">
                    Create manual shipment
                  </button>
                </form>
              } @else if (order.shipment; as shipment) {
                <div class="shipment-card">
                  <span class="demo-label">Local demo manual shipment</span>
                  <strong>{{ shipment.carrierDisplayName }} · {{ shipment.serviceDisplayName }}</strong>
                  <code>{{ shipment.trackingNumber }}</code>
                  <span>Shipped {{ shipment.shippedAt | date:'medium' }}</span>
                  @if (shipment.deliveredAt) {
                    <span>Demo delivery recorded {{ shipment.deliveredAt | date:'medium' }}</span>
                  }
                </div>
                @if (order.status === 'SHIPPED') {
                  <button type="button" class="secondary-action" [disabled]="actionLoading()"
                    (click)="recordDemoDelivery(order)">Simulate local-demo delivery</button>
                }
              }
            </section>
          }

          @if (returnState(); as returned) {
            <section class="fulfillment-panel" aria-labelledby="return-heading">
              <div class="panel-heading"><div><p class="eyebrow">Post-delivery return</p><h2 id="return-heading">{{ label(returned.status || 'Return requested') }}</h2></div><span>Version {{ returned.version }}</span></div>
              <p>Reason: {{ label(returned.reasonCode || '') }}</p>
              @if (returned.status === 'RETURN_REQUESTED') { <button type="button" class="primary-action" [disabled]="actionLoading()" (click)="authorizeReturn(returned)">Authorize return</button> }
              @if (returned.shipment; as shipment) { <div class="shipment-card"><span class="demo-label">Demo return shipment</span><strong>{{ shipment.carrierDisplayName }}</strong><code>{{ shipment.trackingReference }}</code><span>{{ shipment.disclosure }}</span></div> }
              @if (returned.status === 'RETURN_IN_TRANSIT') {
                <label>Inventory disposition<select [(ngModel)]="returnDisposition"><option value="RESTOCK_SELLABLE">Restock as sellable</option><option value="DO_NOT_RESTOCK">Do not restock</option></select></label>
                <button type="button" class="primary-action" [disabled]="actionLoading()" (click)="receiveReturn(returned)">Mark return received</button>
              }
              @if (returned.refundStatus === 'PENDING' || returned.refundStatus === 'PROCESSING') { <p>Return received · Demo refund processing</p> }
              @if (returned.inventoryDisposition) {
                <div class="return-disposition" aria-label="Inventory disposition">
                  <span>Inventory disposition</span>
                  <strong class="status-pill">{{ returnDispositionLabel(returned.inventoryDisposition) }}</strong>
                </div>
              }
              @if (returned.status === 'RETURN_COMPLETED') { <p><strong>Return completed</strong><br>{{ returned.refundAmount | currency:returned.currency }} demo refund completed. No real money is moved.</p> }
              <ol class="timeline">@for(entry of returned.timeline; track entry.status+entry.occurredAt){<li><strong>{{ label(entry.status) }} @if(entry.status === 'RETURN_RECEIVED' && returned.inventoryDisposition){<span class="timeline-disposition">&middot; {{ returnDispositionLabel(returned.inventoryDisposition) }}</span>}</strong><span>{{ entry.occurredAt | date:'medium' }}</span></li>}</ol>
            </section>
          }

          <section class="detail-section" aria-labelledby="timeline-heading">
            <h2 id="timeline-heading">Status history</h2>
            @if (order.timeline.length === 0) {
              <p class="muted">Awaiting the first seller action.</p>
            } @else {
              <ol class="timeline">
                @for (entry of order.timeline; track entry.status + entry.occurredAt) {
                  <li><strong>{{ statusLabel(entry.status) }}</strong><span>{{ entry.occurredAt | date:'medium' }}</span></li>
                }
              </ol>
            }
          </section>

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
            <h2>{{ runtimeMismatch() ? 'Commerce runtime check failed' : 'Orders could not be loaded' }}</h2>
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
                      <span>Marketplace buyer · Order {{ order.buyerOrderNumber }}</span>
                    </td>
                    <td data-label="Status"><span class="status-pill">{{ sellerStatusLabel(order) }}</span></td>
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

    <dialog #fulfillmentDialog class="fulfillment-dialog"
      aria-labelledby="fulfillment-dialog-title"
      aria-describedby="fulfillment-dialog-description"
      (cancel)="cancelFulfillmentAction($event)"
      (keydown.escape)="cancelFulfillmentAction($event)">
      @if (confirmation(); as pending) {
        <div class="dialog-body">
          <div class="dialog-heading">
            <p class="eyebrow">Order state change</p>
            <h2 id="fulfillment-dialog-title">{{ pending.title }}</h2>
          </div>
          <div class="transition-rail" aria-label="Status transition">
            <span>{{ statusLabel(pending.fromStatus) }}</span>
            <span class="transition-arrow" aria-hidden="true">→</span>
            <strong>{{ statusLabel(pending.toStatus) }}</strong>
          </div>
          <p id="fulfillment-dialog-description">{{ pending.description }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary-action" (click)="cancelFulfillmentAction()">
              Keep current status
            </button>
            <button #confirmActionButton type="button" class="primary-action"
              [disabled]="actionLoading()" (click)="confirmFulfillmentAction()">
              {{ pending.confirmLabel }}
            </button>
          </div>
        </div>
      }
    </dialog>
  `,
  styles: [`
    .orders-page { display: grid; min-width: 0; gap: 1.25rem; color: var(--color-text-primary); }
    .page-header, .detail-header {
      display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem;
      min-width: 0; padding-bottom: 1rem; border-bottom: 1px solid var(--color-border);
    }
    .page-header > *, .detail-header > *, .detail-header > div { min-width: 0; }
    .detail-header h2, .detail-header p { overflow-wrap: anywhere; }
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
    .summary-grid { display: grid; min-width: 0; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); margin: 0; border-block: 1px solid var(--color-border); }
    .summary-grid div { padding: 1rem; border-right: 1px solid var(--color-border); }
    .summary-grid dt { color: var(--color-text-muted); font-size: .75rem; }
    .summary-grid dd { margin: .35rem 0 0; font-weight: 750; }
    .cancellation-notice { display: grid; gap: .4rem; padding: 1rem; border: 1px solid #d7c29c; border-left: 4px solid #a83232; border-radius: var(--radius-md); background: #fffaf1; }
    .cancellation-notice h2, .cancellation-notice p { margin: 0; }
    .cancellation-notice > p:last-child { color: var(--color-text-secondary); line-height: 1.5; }
    .detail-section { min-width: 0; padding-top: .5rem; }
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
    .fulfillment-panel { display: grid; min-width: 0; gap: .85rem; padding: 1rem; border: 1px solid var(--color-border); border-left: 4px solid var(--color-accent); border-radius: var(--radius-md); background: var(--color-bg-secondary); }
    .panel-heading { display: flex; min-width: 0; align-items: start; justify-content: space-between; gap: 1rem; }
    .panel-heading h2, .panel-heading p, .fulfillment-panel p { margin: 0; }
    .panel-heading > span, .muted { color: var(--color-text-secondary); font-size: .8125rem; }
    .shipment-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .75rem; }
    .shipment-form label { display: grid; gap: .35rem; font-size: .8125rem; font-weight: 700; }
    .shipment-form input { min-width: 0; min-height: 40px; padding: .5rem .625rem; color: var(--color-text-primary); background: var(--color-bg-primary); border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
    .shipment-form button { grid-column: 1 / -1; justify-self: start; }
    .shipment-card { display: grid; min-width: 0; gap: .35rem; padding: .85rem; border: 1px solid var(--color-border); background: var(--color-bg-primary); }
    .shipment-card > * { min-width: 0; overflow-wrap: anywhere; }
    .shipment-card code { width: fit-content; max-width: 100%; padding: .2rem .35rem; background: var(--color-bg-secondary); }
    .shipment-card span { color: var(--color-text-secondary); font-size: .8125rem; }
    .demo-label { color: var(--color-accent) !important; font-weight: 800; text-transform: uppercase; }
    .return-disposition { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .75rem; padding: .75rem; border: 1px solid var(--color-border); background: var(--color-bg-primary); }
    .return-disposition > span { color: var(--color-text-secondary); font-size: .8125rem; font-weight: 700; }
    .timeline-disposition { color: var(--color-text-secondary); font-size: inherit; font-weight: 650; }
    .action-error { color: var(--color-danger); font-weight: 700; }
    .fulfillment-dialog {
      width: min(520px, calc(100vw - 2rem)); max-width: none; margin: auto; padding: 0;
      color: var(--color-text-primary); background: var(--color-bg-primary);
      border: 1px solid var(--color-border); border-top: 4px solid var(--color-accent);
      border-radius: var(--radius-md); box-shadow: 0 24px 64px rgba(6, 10, 18, .36);
    }
    .fulfillment-dialog::backdrop { background: rgba(6, 10, 18, .68); backdrop-filter: blur(2px); }
    .dialog-body { display: grid; gap: 1rem; padding: 1.25rem; }
    .dialog-heading { display: grid; gap: .25rem; }
    .dialog-heading h2, .dialog-heading p, .dialog-body > p { margin: 0; }
    .dialog-heading h2 { font-size: 1.25rem; }
    .dialog-body > p { color: var(--color-text-secondary); line-height: 1.55; }
    .transition-rail {
      display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center;
      gap: .75rem; margin: 0; padding: .8rem; background: var(--color-bg-secondary);
      border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: .8125rem;
    }
    .transition-rail span:first-child { color: var(--color-text-secondary); }
    .transition-rail strong { color: var(--color-accent); text-align: right; }
    .transition-arrow { color: var(--color-text-muted); font-size: 1.1rem; }
    .dialog-actions { display: flex; justify-content: flex-end; gap: .75rem; padding-top: .25rem; }
    .fulfillment-dialog button:focus-visible { outline: 3px solid var(--color-accent); outline-offset: 2px; }
    .timeline { display: grid; gap: .65rem; margin: 0; padding: 0; list-style: none; }
    .timeline li { display: flex; justify-content: space-between; gap: 1rem; padding-left: .85rem; border-left: 2px solid var(--color-accent); }
    .timeline span { color: var(--color-text-secondary); font-size: .8125rem; }
    .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
    @media (max-width: 680px) {
      .page-header, .detail-header { flex-direction: column; }
      .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .filters { align-items: stretch; flex-direction: column; }
      .filters select { width: 100%; min-width: 0; }
      .item-row { grid-template-columns: 48px minmax(0, 1fr); }
      .item-row > span, .item-row > strong { grid-column: 2; }
      .item-row img, .image-placeholder { width: 48px; height: 48px; }
      .pagination { justify-content: space-between; }
      .shipment-form { grid-template-columns: 1fr; }
      .timeline li { flex-direction: column; gap: .2rem; }
      .dialog-actions { align-items: stretch; flex-direction: column-reverse; }
      .dialog-actions button { width: 100%; }
    }
  `],
})
export class BusinessOrdersComponent implements OnInit {
  @ViewChild('fulfillmentDialog') private fulfillmentDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('confirmActionButton') private confirmActionButton?: ElementRef<HTMLButtonElement>;

  private readonly route = inject(ActivatedRoute);
  private readonly storeService = inject(BusinessStoreService);
  private readonly orderService = inject(BusinessOrderService);
  readonly featureEnabled = inject(BUSINESS_ORDERS_ENABLED);
  readonly fulfillmentEnabled = inject(BUSINESS_ORDER_FULFILLMENT_ENABLED);

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
  readonly actionLoading = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly confirmation = signal<FulfillmentConfirmation | null>(null);
  readonly runtimeMismatch = signal(false);
  readonly returnState = signal<BusinessOrderReturn | null>(null);

  carrierDisplayName = 'Demo Carrier';
  serviceDisplayName = 'Ground';
  trackingNumber = '';
  returnDisposition: 'RESTOCK_SELLABLE'|'DO_NOT_RESTOCK' = 'RESTOCK_SELLABLE';

  selectedStatus: BusinessOrderQueueStatus | '' = '';
  private currentCursor: string | null = null;
  private nextCursor: string | null = null;
  private readonly cursorHistory: Array<string | null> = [];
  private readonly businessOrderId = this.route.snapshot.paramMap.get('businessOrderId');
  private lastFocusedElement: HTMLElement | null = null;

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
          this.trackingNumber = `DEMO-${this.businessOrderId?.slice(-12) ?? 'SHIPMENT'}`;
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
        this.orderService.returnDetail(context.businessId, order.businessOrderId).subscribe({next:value=>this.returnState.set(value),error:()=>this.returnState.set(null)});
      },
      error: error => {
        this.detail.set(null);
        this.loadingDetail.set(false);
        this.errorMessage.set(this.readError(error));
      },
    });
  }

  authorizeReturn(returned:BusinessOrderReturn):void{const context=this.context();const order=this.detail();if(!context||!order||!returned.returnId)return;this.actionLoading.set(true);this.orderService.authorizeReturn(context.businessId,order.businessOrderId,returned.returnId,returned.version,`seller-return-authorize:${returned.returnId}:${Date.now()}`).subscribe({next:value=>{this.returnState.set(value);this.actionLoading.set(false);},error:error=>{this.actionLoading.set(false);this.actionError.set(this.readError(error));}});}
  receiveReturn(returned:BusinessOrderReturn):void{const context=this.context();const order=this.detail();if(!context||!order||!returned.returnId)return;this.actionLoading.set(true);this.orderService.receiveReturn(context.businessId,order.businessOrderId,returned.returnId,returned.version,`seller-return-receive:${returned.returnId}:${Date.now()}`,this.returnDisposition).subscribe({next:value=>{this.returnState.set(value);this.actionLoading.set(false);window.setTimeout(()=>this.loadDetail(),1200);},error:error=>{this.actionLoading.set(false);this.actionError.set(this.readError(error));}});}

  statusLabel(status: BusinessOrderStatus): string {
    return STATUS_OPTIONS.find(option => option.value === status)?.label ?? this.label(status);
  }

  // Cancellation is the seller's operative final state even though fulfillment history remains unchanged.
  sellerStatusLabel(order: BusinessOrderSummary): string {
    return order.cancellationStatus === 'CANCELLED'
      ? 'Cancelled'
      : this.statusLabel(order.status);
  }

  label(value: string): string {
    return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }

  // Keeps the persisted operational decision explicit after the receive control disappears.
  returnDispositionLabel(disposition: string): string {
    if (disposition === 'RESTOCK_SELLABLE') return 'Restocked as sellable';
    if (disposition === 'DO_NOT_RESTOCK') return 'Not restocked';
    return 'Disposition unavailable';
  }

  hasFinance(order: BusinessOrderSummary): boolean {
    return order.platformFeeProjection !== undefined && order.platformFeeProjection !== null;
  }

  canFulfill(): boolean {
    return Boolean(this.context()?.permissions.includes('ORDER_FULFILL'));
  }

  accept(order: BusinessOrderDetail): void {
    this.openConfirmation({
      action: 'accept',
      title: 'Accept this order group?',
      description: 'Confirm that this paid store group is ready for fulfillment.',
      confirmLabel: 'Accept order group',
      fromStatus: 'PENDING_ACCEPTANCE',
      toStatus: 'ACCEPTED',
      order,
    });
  }

  startProcessing(order: BusinessOrderDetail): void {
    this.openConfirmation({
      action: 'processing',
      title: 'Start preparing this order group?',
      description: 'This moves the group into active preparation.',
      confirmLabel: 'Mark processing',
      fromStatus: 'ACCEPTED',
      toStatus: 'PROCESSING',
      order,
    });
  }

  createShipment(order: BusinessOrderDetail): void {
    this.openConfirmation({
      action: 'shipment',
      title: 'Create this manual shipment?',
      description: 'The shipment record cannot be edited, and no carrier will be contacted.',
      confirmLabel: 'Create manual shipment',
      fromStatus: 'PROCESSING',
      toStatus: 'SHIPPED',
      order,
    });
  }

  recordDemoDelivery(order: BusinessOrderDetail): void {
    this.openConfirmation({
      action: 'delivery',
      title: 'Record local-demo delivery?',
      description: 'This simulation marks the shipment delivered. It is not carrier confirmation.',
      confirmLabel: 'Record demo delivery',
      fromStatus: 'SHIPPED',
      toStatus: 'DELIVERED',
      order,
    });
  }

  cancelFulfillmentAction(event?: Event): void {
    event?.preventDefault();
    this.closeConfirmation();
  }

  confirmFulfillmentAction(): void {
    const pending = this.confirmation();
    if (!pending || this.actionLoading()) {
      return;
    }
    this.closeConfirmation();
    const order = pending.order;
    switch (pending.action) {
      case 'accept':
        this.runAction(() => this.orderService.accept(
          order.businessId, order.businessOrderId, order.version, this.idempotencyKey('accept'),
        ));
        return;
      case 'processing':
        this.runAction(() => this.orderService.startProcessing(
          order.businessId, order.businessOrderId, order.version, this.idempotencyKey('processing'),
        ));
        return;
      case 'shipment':
        this.runAction(() => this.orderService.createShipment(
          order.businessId,
          order.businessOrderId,
          order.version,
          this.idempotencyKey('shipment'),
          {
            carrierDisplayName: this.carrierDisplayName,
            serviceDisplayName: this.serviceDisplayName,
            trackingNumber: this.trackingNumber,
            shippedAt: new Date().toISOString(),
          },
        ));
        return;
      case 'delivery':
        this.runAction(() => this.orderService.recordDemoDelivery(
          order.businessId, order.businessOrderId, order.version, this.idempotencyKey('delivery'),
        ));
    }
  }

  private openConfirmation(confirmation: FulfillmentConfirmation): void {
    this.lastFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.confirmation.set(confirmation);
    const dialog = this.fulfillmentDialog?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
      queueMicrotask(() => this.confirmActionButton?.nativeElement.focus());
    }
  }

  private closeConfirmation(): void {
    const dialog = this.fulfillmentDialog?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
    this.confirmation.set(null);
    this.lastFocusedElement?.focus();
    this.lastFocusedElement = null;
  }

  private runAction(
    action: () => import('rxjs').Observable<unknown>,
  ): void {
    if (this.actionLoading()) return;
    this.actionLoading.set(true);
    this.actionError.set(null);
    action().subscribe({
      next: () => {
        this.actionLoading.set(false);
        this.loadDetail();
      },
      error: error => {
        this.actionLoading.set(false);
        this.actionError.set(this.readActionError(error));
      },
    });
  }

  private idempotencyKey(action: string): string {
    return `seller-${action}-${crypto.randomUUID()}`;
  }

  private readActionError(error: unknown): string {
    if (error instanceof HttpErrorResponse && error.status === 400) {
      const code = (error.error as { error?: { code?: unknown } } | null)?.error?.code;
      if (code === 'BUSINESS_ORDER_VERSION_REQUIRED') {
        return 'This action is missing the current order version. Reload and try again.';
      }
      if (code === 'BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED') {
        return 'This action could not create a safe retry key. Reload and try again.';
      }
      return typeof code === 'string'
        ? `The fulfillment request was rejected (${code}).`
        : 'The fulfillment request was rejected. Reload and try again.';
    }
    if (error instanceof HttpErrorResponse && error.status === 409) {
      return 'This order changed in another session. Reload the detail and try again.';
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      return 'This action is unavailable or you do not have access.';
    }
    return 'The fulfillment action could not be completed. Try again.';
  }

  private loadPage(cursor: string | null): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.currentCursor = cursor;
    this.loadingList.set(true);
    this.errorMessage.set(null);
    this.runtimeMismatch.set(false);
    this.orderService.list(context.businessId, {
      status: this.selectedStatus || null,
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
        this.runtimeMismatch.set(error instanceof HttpErrorResponse && error.status === 404);
        this.errorMessage.set(this.readListError(error));
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

  // Queue-level 404s can expose local demo UI/gateway capability drift; detail 404s remain safely hidden.
  private readListError(error: unknown): string {
    if (error instanceof HttpErrorResponse && error.status === 404) {
      return 'The seller UI is enabled, but the gateway did not expose business orders. Confirm the cart-runtime overlay and your order access, then try again.';
    }
    return this.readError(error);
  }
}
