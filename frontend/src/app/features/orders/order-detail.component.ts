import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BusinessOrderReturn, BuyerOrderDetail, BuyerOrderGroup, ReturnReason } from '../../core/models/order.model';
import { OrderService } from '../../core/services/order.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-order-detail', standalone: true, imports: [CurrencyPipe, DatePipe, RouterLink],
  template: `
    <section class="detail-page">
      <a routerLink="/account/orders">Back to orders</a>
      @if (error()) { <div class="card error" role="alert">{{ error() }}</div> }
      @else if (!order()) { <div class="card">Loading order...</div> }
      @else if (order(); as current) {
        <header><p>{{ current.status === 'CANCELLED' ? 'Order cancelled' : 'Order confirmed' }}</p><h1>{{ current.orderId }}</h1><span>{{ current.createdAt | date:'medium' }}</span></header>
        <div class="layout"><div class="groups">
          @for (group of current.groups; track group.businessOrderId) {
            <section class="card group-card">
              <div class="group-heading"><h2>{{ group.storeName || 'Store' }}</h2><span class="group-status">{{ current.status === 'CANCELLED' ? 'Cancelled' : label(group.status) }}</span></div>
              @for (item of group.items; track item.listingId) {
                <div class="line"><span><strong>{{ item.title }}</strong><small>Qty {{ item.quantity }} · {{ item.unitPrice | currency:item.currency }}</small></span><strong>{{ item.lineTotal | currency:item.currency }}</strong></div>
              }
              <div class="line total"><span>Group total</span><strong>{{ group.totalAmount | currency:group.currency }}</strong></div>
              @if (current.status !== 'CANCELLED' && group.status !== 'PENDING_ACCEPTANCE') {
                <a class="problem-link" [routerLink]="['/account/orders', current.orderId, 'disputes', 'new']"
                  [queryParams]="{ businessGroupId: group.businessOrderId }">Report a problem with this store group</a>
              }
              @if (group.shipment; as shipment) {
                <div class="shipment">
                  <small>Local demo manual shipment</small>
                  <strong>{{ shipment.carrierDisplayName }} · {{ shipment.serviceDisplayName }}</strong>
                  <code>{{ shipment.trackingNumber }}</code>
                  <span>Shipped {{ shipment.shippedAt | date:'medium' }}</span>
                  @if (shipment.deliveredAt) { <span>Demo delivery {{ shipment.deliveredAt | date:'medium' }}</span> }
                </div>
              }
              @if (group.timeline.length > 0) {
                <ol class="timeline">
                  @for (entry of group.timeline; track entry.status + entry.occurredAt) {
                    <li><strong>{{ label(entry.status) }}</strong><span>{{ entry.occurredAt | date:'medium' }}</span></li>
                  }
                </ol>
              } @else if (current.status !== 'CANCELLED') { <p class="awaiting">Awaiting seller acceptance</p> }
              @if (returnsEnabled && returnFor(group.businessOrderId); as returned) {
                <section class="return-card">
                  <p class="eyebrow">Post-delivery return</p>
                  @if (!returned.returnId && returned.eligible) {
                    <h3>Return items</h3><p>This returns the entire {{ group.storeName || 'store' }} group.</p>
                    <label>Reason<select #returnReason><option value="NO_LONGER_NEEDED">No longer needed</option><option value="NOT_AS_EXPECTED">Item not as expected</option><option value="DAMAGED">Damaged item</option><option value="WRONG_ITEM">Wrong item</option><option value="OTHER">Other</option></select></label>
                    <label>Comment (optional)<textarea #returnComment maxlength="500"></textarea></label>
                    <button type="button" (click)="requestReturn(group, returnReason.value, returnComment.value)">Return items</button>
                  } @else if (returned.returnId) {
                    <h3>{{ label(returned.status || 'Return requested') }}</h3>
                    <p>Reason: {{ label(returned.reasonCode || '') }}</p>
                    @if (returned.shipment; as rs) { <div class="shipment"><small>Demo return shipment</small><strong>{{ rs.carrierDisplayName }}</strong><code>{{ rs.trackingReference }}</code><span>{{ rs.disclosure }}</span></div> }
                    @if (returned.refundStatus === 'PENDING' || returned.refundStatus === 'PROCESSING') { <p>Demo refund processing</p> }
                    @if (returned.refundStatus === 'SUCCEEDED') { <p><strong>{{ returned.refundAmount | currency:returned.currency }} demo refund completed</strong><br>No real money is moved.</p> }
                    <ol class="timeline">@for(entry of returned.timeline; track entry.status+entry.occurredAt){<li><strong>{{ label(entry.status) }}</strong><span>{{ entry.occurredAt | date:'medium' }}</span></li>}</ol>
                  }
                </section>
              }
            </section>
          }
          <section class="card"><h2>Delivery address snapshot</h2><address><strong>{{ current.shippingAddress.recipientName }}</strong><br>{{ current.shippingAddress.line1 }}<br>@if(current.shippingAddress.line2){ {{ current.shippingAddress.line2 }}<br> }{{ current.shippingAddress.city }}, {{ current.shippingAddress.region }} {{ current.shippingAddress.postalCode }}<br>{{ current.shippingAddress.countryCode }}</address></section>
        </div><aside class="summary-stack">
          <section class="card order-summary"><div><span>Status</span><strong>{{ label(current.status) }}</strong></div><div><span>Payment</span><strong>{{ label(current.paymentStatus) }}</strong></div><div class="grand"><span>Grand total</span><strong>{{ current.totalAmount | currency:current.currency }}</strong></div></section>
          @if (orderCancellationEnabled && current.cancellation; as cancellation) {
            <section class="card cancellation-card" [class.cancelled]="!!cancellation.requestId">
              <p class="eyebrow">Whole-order cancellation</p>
              @if (cancellation.requestId) {
                <h2>{{ cancellation.requestStatus === 'COMPLETED' ? 'Cancellation complete' : 'Cancellation in progress' }}</h2>
                <p>Buyer requested cancellation before fulfillment began.</p>
                <dl class="compensation-ledger">
                  <div><dt>Inventory</dt><dd>{{ cancellation.inventoryStatus === 'SUCCEEDED' ? 'Restored' : 'Pending' }}</dd></div>
                  <div><dt>Local demo refund</dt><dd>{{ cancellation.refund?.status === 'SUCCEEDED' ? 'Succeeded' : 'Pending' }}</dd></div>
                </dl>
                @if (cancellation.refund; as refund) {
                  <div class="refund-evidence">
                    <strong>{{ refund.amount | currency:refund.currency }} · {{ refund.displayName }}</strong>
                    <span>{{ refund.disclosure }}</span>
                  </div>
                }
                @if (cancellation.completedAt) { <small>Completed {{ cancellation.completedAt | date:'medium' }}</small> }
              } @else if (cancellation.eligible) {
                <h2>Plans changed?</h2>
                <p>You can cancel every store group while all sellers are still awaiting acceptance.</p>
                <button type="button" class="cancel-action" (click)="openCancellationDialog()">Cancel entire order</button>
              } @else {
                <h2>Cancellation unavailable</h2>
                <p>{{ cancellationMessage(cancellation.ineligibilityCode) }}</p>
              }
              @if (actionError()) { <p class="action-error" role="alert">{{ actionError() }}</p> }
            </section>
          }
        </aside></div>

        <dialog #cancellationDialog class="cancellation-dialog" aria-labelledby="cancellation-title" aria-describedby="cancellation-description" (cancel)="closeCancellationDialog()">
          <div class="dialog-body">
            <p class="eyebrow">Entire order · {{ current.groups.length }} store groups</p>
            <h2 id="cancellation-title">Cancel this order?</h2>
            <p id="cancellation-description">This immediately stops every group before seller acceptance. Purchased quantities will be restored and the full {{ current.totalAmount | currency:current.currency }} local-demo payment will receive one fake refund.</p>
            <div class="demo-disclosure"><strong>Local demo refund</strong><span>No real money is moved.</span></div>
            <div class="dialog-actions">
              <button type="button" class="secondary-action" [disabled]="cancelling()" (click)="closeCancellationDialog()">Keep order</button>
              <button #confirmCancellationButton type="button" class="danger-action" [disabled]="cancelling()" (click)="confirmCancellation()">{{ cancelling() ? 'Cancelling…' : 'Cancel entire order' }}</button>
            </div>
          </div>
        </dialog>
      }
    </section>`,
  styles: [`
    .detail-page{max-width:1120px;margin:0 auto;display:grid;gap:1rem;color:var(--market-ink)}
    a{color:var(--market-accent-dark);font-weight:900}header p{color:#168168;font-weight:900;text-transform:uppercase}h1{overflow-wrap:anywhere}
    .layout{display:grid;grid-template-columns:1fr 320px;gap:1rem;align-items:start}.groups,.summary-stack{display:grid;gap:1rem}.card{border:1px solid var(--market-line);border-radius:8px;background:#fff;padding:1rem}
    .group-heading{display:flex;justify-content:space-between;align-items:start;gap:1rem}.group-heading h2{margin:0}.group-status{padding:.3rem .5rem;border-radius:4px;background:#e8f5f1;color:#126c59;font-size:.75rem;font-weight:900}
    .line,.order-summary>div{display:flex;justify-content:space-between;gap:1rem;padding:.7rem 0;border-bottom:1px solid var(--market-line)}.line span{display:grid}.line small{color:var(--market-muted)}.total,.grand{font-size:1.05rem}
    .problem-link{display:inline-flex;margin-top:.75rem;padding:.6rem .8rem;border:1px solid var(--market-line);text-decoration:none}
    .shipment{display:grid;gap:.35rem;margin-top:1rem;padding:.8rem;border-left:3px solid #168168;background:#f4faf8}.shipment small{color:#168168;font-weight:900;text-transform:uppercase}.shipment code{width:fit-content;padding:.15rem .3rem;background:#fff}.shipment span,.awaiting{color:var(--market-muted);font-size:.85rem}
    .timeline{display:grid;gap:.45rem;margin:1rem 0 0;padding:0;list-style:none}.timeline li{display:flex;justify-content:space-between;gap:1rem;padding-left:.65rem;border-left:2px solid #168168;font-size:.82rem}.timeline span{color:var(--market-muted)}.return-card{display:grid;gap:.65rem;margin-top:1rem;padding:1rem;border:1px solid #b6d8d0;background:#f6fbfa}.return-card h3,.return-card p{margin:0}.return-card label{display:grid;gap:.3rem;font-weight:800}.return-card select,.return-card textarea{max-width:100%;padding:.55rem;border:1px solid var(--market-line)}.return-card button{width:fit-content;padding:.65rem .9rem;border:0;background:#168168;color:#fff;font-weight:900}
    address{font-style:normal;line-height:1.6;color:var(--market-muted)}.error,.action-error{color:#9d285d}.eyebrow{margin:0;color:#7a4d12;font-size:.72rem;font-weight:900;letter-spacing:.08em;text-transform:uppercase}.cancellation-card{display:grid;gap:.8rem;border-color:#e5c99e;background:#fffdf8}.cancellation-card.cancelled{border-color:#b6d8d0;background:#f6fbfa}.cancellation-card h2,.cancellation-card p{margin:0}.cancellation-card>p:not(.eyebrow):not(.action-error){color:var(--market-muted);line-height:1.5}.cancel-action,.danger-action{border:0;border-radius:5px;background:#a83232;color:#fff;padding:.72rem 1rem;font-weight:900;cursor:pointer}.cancel-action:focus-visible,.cancellation-dialog button:focus-visible{outline:3px solid #e2a13a;outline-offset:2px}.compensation-ledger{display:grid;gap:.4rem;margin:0}.compensation-ledger div{display:flex;justify-content:space-between;gap:1rem;padding:.55rem .65rem;border-left:3px solid #168168;background:#fff}.compensation-ledger dt{color:var(--market-muted)}.compensation-ledger dd{margin:0;font-weight:900}.refund-evidence{display:grid;gap:.25rem;padding-top:.7rem;border-top:1px solid #cfe1dc}.refund-evidence span,.cancellation-card small{color:var(--market-muted);font-size:.82rem}.refund-evidence code{overflow-wrap:anywhere}.cancellation-dialog{width:min(92vw,520px);padding:0;border:1px solid #e5c99e;border-radius:10px;box-shadow:0 24px 70px rgba(20,24,28,.28)}.cancellation-dialog::backdrop{background:rgba(16,19,24,.66);backdrop-filter:blur(2px)}.dialog-body{display:grid;gap:1rem;padding:1.35rem}.dialog-body h2,.dialog-body>p{margin:0}.dialog-body>p:not(.eyebrow){color:var(--market-muted);line-height:1.55}.demo-disclosure{display:grid;gap:.2rem;padding:.8rem;border-left:4px solid #e2a13a;background:#fff8e8}.demo-disclosure span{color:#6e5a37}.dialog-actions{display:flex;justify-content:flex-end;gap:.7rem}.dialog-actions button{padding:.7rem .9rem;font-weight:900}.secondary-action{border:1px solid var(--market-line);border-radius:5px;background:#fff}.dialog-actions button:disabled{opacity:.6;cursor:wait}
    @media(max-width:760px){.layout{grid-template-columns:1fr}aside{order:-1}.group-heading,.timeline li{flex-direction:column;gap:.25rem}}
  `],
})
export class OrderDetailComponent implements OnInit {
  @ViewChild('cancellationDialog') private cancellationDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('confirmCancellationButton') private confirmCancellationButton?: ElementRef<HTMLButtonElement>;
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(OrderService);
  readonly order = signal<BuyerOrderDetail | null>(null);
  readonly error = signal('');
  readonly actionError = signal('');
  readonly cancelling = signal(false);
  readonly orderCancellationEnabled = environment.features.orderCancellation === true;
  readonly returnsEnabled = environment.features.returns === true;
  readonly returnStates = signal<Record<string, BusinessOrderReturn>>({});

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('orderId');
    if (!id) { this.error.set('Order was not found.'); return; }
    this.load(id);
  }

  private load(id: string): void {
    this.service.detail(id).subscribe({
      next: value => { this.order.set(value); if(this.returnsEnabled){for(const group of value.groups)this.service.returnDetail(value.orderId,group.businessOrderId).subscribe({next:r=>this.returnStates.update(all=>({...all,[group.businessOrderId]:r}))});} },
      error: error => this.error.set(error?.error?.error?.message || 'Order could not be loaded.'),
    });
  }

  returnFor(groupId:string):BusinessOrderReturn|null{return this.returnStates()[groupId]??null;}
  requestReturn(group:BuyerOrderGroup,reason:string,comment:string):void{const current=this.order();if(!current)return;this.actionError.set('');this.service.requestReturn(current.orderId,group.businessOrderId,group.version,`buyer-return:${group.businessOrderId}:${Date.now()}`,reason as ReturnReason,comment).subscribe({next:r=>this.returnStates.update(all=>({...all,[group.businessOrderId]:r})),error:e=>this.actionError.set(e?.error?.error?.message||'Return could not be requested.')});}

  openCancellationDialog(): void {
    this.actionError.set('');
    const dialog = this.cancellationDialog?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
      queueMicrotask(() => this.confirmCancellationButton?.nativeElement.focus());
    }
  }

  closeCancellationDialog(): void {
    if (this.cancelling()) return;
    const dialog = this.cancellationDialog?.nativeElement;
    if (dialog?.open) dialog.close();
  }

  confirmCancellation(): void {
    const current = this.order();
    if (!current || !current.cancellation?.eligible || this.cancelling()) return;
    this.cancelling.set(true);
    this.actionError.set('');
    const key = `buyer-cancel:${current.orderId}:${current.version}:${Date.now()}`;
    this.service.requestCancellation(current.orderId, current.version, key).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.closeCancellationDialog();
        this.load(current.orderId);
        window.setTimeout(() => this.load(current.orderId), 800);
        window.setTimeout(() => this.load(current.orderId), 2600);
      },
      error: error => {
        this.cancelling.set(false);
        this.actionError.set(error?.error?.error?.message || 'Cancellation could not be requested. Refresh the order and try again.');
        this.closeCancellationDialog();
      },
    });
  }

  cancellationMessage(code: string | null): string {
    const statuses = this.order()?.groups.map(group => group.status) ?? [];
    if (code === 'FULFILLMENT_STARTED' && statuses.includes('DELIVERED')) return 'This order has already been delivered and can no longer be cancelled. Eligible store groups can use the separate return workflow.';
    if (code === 'FULFILLMENT_STARTED' && statuses.includes('SHIPPED')) return 'This order has already shipped and can no longer be cancelled.';
    if (code === 'FULFILLMENT_STARTED') return 'A seller has already started fulfillment, so this order can no longer be cancelled.';
    if (code === 'WINDOW_CLOSED') return 'The cancellation window for this order has closed.';
    return 'The policy saved with this order does not allow cancellation.';
  }

  label(value: string): string {
    return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }
}
