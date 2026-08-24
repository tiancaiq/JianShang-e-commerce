import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  AdminCancelOrderRequest,
  AdminOrderCancellationPreview,
  AdminOrderDetail,
} from '../../core/models/admin-order.model';
import { AdminOrderService } from '../../core/services/admin-order.service';

@Component({
  selector: 'app-admin-order-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  template: `
    <a routerLink="/admin/orders" class="back">← Orders</a>
    @if (loading()) { <section class="state">Loading order…</section> }
    @else if (error()) { <section class="state error" role="alert">{{ error() }}</section> }
    @else if (order(); as value) {
      <header class="hero"><div><p class="eyebrow">Order record</p><h1>{{ value.orderNumber }}</h1>
        <p>Created {{ value.createdAt | date:'medium' }} · version {{ value.version }}</p></div>
        <span class="status">{{ label(value.status) }}</span></header>

      <section class="lifecycle" aria-label="Order lifecycle">
        <article><span>Payment</span><strong>{{ label(value.paymentSummary.status) }}</strong>
          <small>{{ value.paymentSummary.live ? 'Live from Payment' : 'Order projection' }}</small></article>
        <article><span>Inventory</span><strong>{{ label(value.inventoryReservationSummary.status || 'unknown') }}</strong>
          <small>{{ value.inventoryReservationSummary.live ? 'Live reservation' : 'Stored projection' }}</small></article>
        <article><span>Fulfillment</span><strong>{{ fulfillment(value) }}</strong><small>{{ value.businesses.length }} business group{{ value.businesses.length===1?'':'s' }}</small></article>
        <article><span>Cancellation</span><strong>{{ value.cancellationSummary ? label(value.cancellationSummary.requestStatus) : 'None' }}</strong>
          <small>{{ value.availableAdminCapabilities.isCancellationAllowed ? 'Action available' : value.availableAdminCapabilities.readOnlyReason }}</small></article>
      </section>

      <div class="grid two">
        <section class="panel"><h2>Buyer</h2><a [routerLink]="value.buyerSummary.adminPath">{{ value.buyerSummary.displayName || value.buyerSummary.userId }}</a>
          <p class="mono">{{ value.buyerSummary.userId }}</p></section>
        <section class="panel"><h2>Seller / business</h2>@for (business of value.businesses;track business.businessId){
          <div class="entity"><a [routerLink]="business.adminPath">{{ business.currentStoreName || business.storeNameAtPurchase || business.legalName || business.businessId }}</a>
            <span>{{ label(business.fulfillmentStatus) }} · {{ label(business.cancellationStatus) }} · v{{ business.version }}</span></div>}</section>
      </div>

      <section class="panel"><h2>Purchase-time item snapshots</h2><div class="items">
        @for (item of value.orderItems;track item.listingId){<article class="item">
          @if(item.imageReferenceAtPurchase){<img [src]="item.imageReferenceAtPurchase" alt="" />}
          <div><a [routerLink]="item.adminPath">{{ item.titleAtPurchase }}</a><p>{{ item.quantity }} × {{ item.unitPrice | number:'1.2-2' }} {{ item.currency }} · {{ item.conditionAtPurchase }}</p>
            <small>Listing {{ item.listingId }} · catalog v{{ item.catalogVersionAtPurchase }} · policy {{ item.policyVersion }}</small>
            @if(item.currentListing){<div class="current"><span>Current listing</span><strong>{{ item.currentListing.title }} · {{ label(item.currentListing.status) }}</strong>
              @if(item.currentListing.title!==item.titleAtPurchase){<em>Title changed after purchase</em>}</div>}
            @else{<div class="current unavailable">Current listing state unavailable; historical snapshot remains authoritative.</div>}</div>
          <strong>{{ item.lineTotal | number:'1.2-2' }} {{ item.currency }}</strong></article>}
      </div></section>

      <div class="grid two">
        <section class="panel"><h2>Pricing</h2><dl class="money"><div><dt>Subtotal</dt><dd>{{ value.pricingSummary.subtotal|number:'1.2-2' }}</dd></div>
          <div><dt>Shipping</dt><dd>{{ value.pricingSummary.shipping|number:'1.2-2' }}</dd></div><div><dt>Taxes</dt><dd>{{ value.pricingSummary.taxes|number:'1.2-2' }}</dd></div>
          <div><dt>Discounts</dt><dd>−{{ value.pricingSummary.discounts|number:'1.2-2' }}</dd></div><div class="total"><dt>Total</dt><dd>{{ value.pricingSummary.total|number:'1.2-2' }} {{ value.pricingSummary.currency }}</dd></div></dl></section>
        <section class="panel"><h2>Shipping address</h2>@if(value.shippingAddressSafe;as address){
          @if(address.masked){<p class="notice">Sensitive fields are masked. admin.user.pii.read is required for the complete order-time address.</p>}
          @if(address.recipientName){<strong>{{address.recipientName}}</strong>}@if(address.line1){<p>{{address.line1}} {{address.line2}}</p>}
          <p>{{address.city}}, {{address.region}} {{address.postalCode}} · {{address.countryCode}}</p>@if(address.phone){<p>{{address.phone}}</p>}}
        </section>
      </div>

      <div class="grid three">
        <section class="panel"><h2>Checkout snapshot</h2><p class="mono">{{value.checkoutSnapshot.checkoutId}}</p><p>{{label(value.checkoutSnapshot.status)}} · v{{value.checkoutSnapshot.version}}</p>
          <small>Cart v{{value.checkoutSnapshot.cartVersion}} · expires {{value.checkoutSnapshot.expiresAt|date:'medium'}}</small></section>
        <section class="panel"><h2>Inventory</h2><p class="mono">{{value.inventoryReservationSummary.reservationId||'No reservation reference'}}</p>
          <p>{{label(value.inventoryReservationSummary.status||'unknown')}} · release {{label(value.inventoryReservationSummary.releaseStatus)}}</p>
          @if(value.inventoryReservationSummary.availabilityNote){<p class="notice">{{value.inventoryReservationSummary.availabilityNote}}</p>}</section>
        <section class="panel"><h2>Payment</h2><p class="mono">{{value.paymentSummary.paymentIntentId}}</p><p>{{value.paymentSummary.capturedAmount|number:'1.2-2'}} {{value.paymentSummary.currency}} captured</p>
          <p>{{value.paymentSummary.refundedAmount|number:'1.2-2'}} refunded</p>@if(value.paymentSummary.providerPaymentReference){<small>{{value.paymentSummary.provider}} · {{value.paymentSummary.providerPaymentReference}}</small>}
          @if(value.paymentSummary.availabilityNote){<p class="notice">{{value.paymentSummary.availabilityNote}}</p>}</section>
      </div>

      <div class="grid two"><section class="panel"><h2>Refunds</h2>@if(value.refundSummary.length===0){<p class="muted">No refund record.</p>}@else{
        @for(refund of value.refundSummary;track refund.refundId||refund.source){<div class="entity"><strong>{{label(refund.source)}}</strong><span>{{label(refund.status)}} · {{refund.amount|number:'1.2-2'}} {{refund.currency}}</span></div>}}
        </section><section class="panel"><h2>Related enforcement</h2>@if(value.relatedEnforcement.length===0){<p class="muted">No active listing enforcement found.</p>}@else{
          @for(action of value.relatedEnforcement;track action.targetId+action.actionType){<div class="entity"><a [routerLink]="action.adminPath">{{label(action.targetType)}} {{action.targetId}}</a><span>{{label(action.actionType)}} · {{scopeLabels(action.scopes)}}</span></div>}}
        </section></div>

      <section class="panel"><h2>Admin actions</h2>
        @if(!value.availableAdminCapabilities.canCancel){<p class="notice">You have read-only order access.</p>}
        @else if(!value.availableAdminCapabilities.isCancellationAllowed&&!preview()){
          <p class="notice danger">{{value.availableAdminCapabilities.readOnlyReason}}</p>}
        @else{
          <div class="action-form"><label>Cancellation reason <select [(ngModel)]="reasonCode" (ngModelChange)="invalidatePreview()">
            <option value="">Select a reason</option><option>FRAUD_PREVENTION</option><option>SELLER_UNAVAILABLE</option>
            <option>BUYER_REQUEST_CONFIRMED</option><option>POLICY_VIOLATION</option><option>DUPLICATE_ORDER</option>
            <option>OPERATIONAL_ERROR</option><option>OTHER</option></select></label>
            <label>Operational explanation <textarea [(ngModel)]="reason" (ngModelChange)="invalidatePreview()" maxlength="1000" rows="3"></textarea></label>
            <button type="button" [disabled]="!reasonCode||previewing()" (click)="previewCancellation()">{{previewing()?'Checking impact…':'Preview cancellation'}}</button></div>
          @if(actionError()){<p class="notice danger" role="alert">{{actionError()}}</p>}
          @if(preview();as impact){<div class="impact" [class.blocked]="!impact.allowed"><h3>{{impact.allowed?'Review impact':'Cancellation blocked'}}</h3>
            <ul><li>{{impact.inventoryImpact}}</li><li>{{impact.paymentImpact}}</li><li>{{impact.buyerImpact}}</li><li>{{impact.sellerImpact}}</li></ul>
            @for(warning of impact.warnings;track warning){<p>{{warning}}</p>}
            @if(impact.allowed){<label class="confirm"><input type="checkbox" [(ngModel)]="confirmed" /> I confirm this cancellation and its refund/restock impact.</label>
              <button type="button" class="danger-button" [disabled]="!confirmed||saving()" (click)="confirmCancellation()">{{saving()?'Requesting cancellation…':'Cancel order'}}</button>}
          </div>}
        }
      </section>

      <section class="panel"><h2>Audit timeline</h2>@if(value.auditTimeline.length===0){<p class="muted">No order events are available.</p>}@else{
        <ol class="timeline">@for(event of value.auditTimeline;track event.eventId){<li><span></span><div><strong>{{label(event.eventType)}}</strong>
          <time>{{event.occurredAt|date:'medium'}}</time><p>{{event.actorDisplayName||event.actorId||event.actorType}}</p>
          @if(event.previousState||event.newState){<p class="transition">{{event.previousState||'None'}} → {{event.newState||'None'}}</p>}
          @if(event.reason){<small>{{event.reason}}</small>}@if(event.correlationId){<small>Correlation {{event.correlationId}}</small>}</div></li>}</ol>}
      </section>
    }
  `,
  styles: [`
    :host{display:grid;gap:1rem}.back{color:var(--color-text-muted);font-size:.78rem;text-decoration:none}.hero{display:flex;align-items:end;justify-content:space-between;gap:1rem}.hero h1{margin:.1rem 0;font-size:1.55rem}.hero p{margin:0;color:var(--color-text-muted);font-size:.75rem}.eyebrow{color:var(--color-info)!important;font-size:.68rem!important;font-weight:850;letter-spacing:.12em;text-transform:uppercase}.status{border:1px solid var(--color-info);border-radius:999px;padding:.35rem .65rem;color:var(--color-info);font-size:.72rem;font-weight:800}
    .lifecycle{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--color-border);border-radius:var(--radius-md);overflow:hidden;background:var(--color-bg-secondary)}.lifecycle article{position:relative;display:grid;gap:.25rem;padding:.85rem 1rem;border-right:1px solid var(--color-border)}.lifecycle article:last-child{border:0}.lifecycle article:not(:last-child)::after{position:absolute;right:-4px;top:50%;width:7px;height:7px;border:1px solid var(--color-info);border-radius:50%;background:var(--color-bg-secondary);content:''}.lifecycle span,.lifecycle small{color:var(--color-text-muted);font-size:.66rem}.lifecycle strong{color:var(--color-text-primary);font-size:.82rem}
    .grid{display:grid;gap:1rem}.two{grid-template-columns:1fr 1fr}.three{grid-template-columns:repeat(3,1fr)}.panel{border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-secondary);padding:1rem;min-width:0}.panel h2{margin:0 0 .8rem;font-size:.92rem}.panel p{margin:.35rem 0;color:var(--color-text-secondary);font-size:.78rem}.panel>a,.entity a,.item a{color:var(--color-info);font-weight:800;text-decoration:none}.entity{display:flex;justify-content:space-between;gap:1rem;padding:.55rem 0;border-top:1px solid var(--color-border)}.entity:first-of-type{border-top:0}.entity span{color:var(--color-text-muted);font-size:.72rem;text-align:right}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere}.muted{color:var(--color-text-muted)!important}.notice{padding:.65rem;border-left:3px solid var(--color-info);background:rgba(56,189,248,.06);font-size:.74rem!important}.notice.danger{border-color:var(--color-danger);background:rgba(248,113,113,.06);color:#fca5a5}.items{display:grid}.item{display:grid;grid-template-columns:auto 1fr auto;gap:.8rem;padding:.8rem 0;border-top:1px solid var(--color-border)}.item:first-child{border-top:0}.item img{width:58px;height:58px;border-radius:var(--radius-sm);object-fit:cover}.item p{margin:.2rem 0}.item small{display:block;color:var(--color-text-muted);font-size:.68rem}.current{display:grid;gap:.15rem;margin-top:.55rem;padding:.5rem;border-left:2px solid var(--color-border);background:var(--color-bg-primary);font-size:.7rem}.current span,.current em{color:var(--color-text-muted);font-style:normal}.current.unavailable{color:var(--color-text-muted)}.money{display:grid;gap:.45rem;margin:0}.money div{display:flex;justify-content:space-between;color:var(--color-text-secondary);font-size:.78rem}.money dd{margin:0;font-variant-numeric:tabular-nums}.money .total{padding-top:.5rem;border-top:1px solid var(--color-border);color:var(--color-text-primary);font-weight:850}
    .action-form{display:grid;grid-template-columns:1fr 2fr auto;align-items:end;gap:.7rem}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.72rem;font-weight:750}select,textarea{border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.6rem;font:inherit}button{min-height:40px;border:1px solid var(--color-info);border-radius:var(--radius-md);background:rgba(56,189,248,.12);color:var(--color-info);padding:.5rem .8rem;font:inherit;font-weight:800;cursor:pointer}button:disabled{opacity:.4;cursor:not-allowed}.impact{margin-top:1rem;padding:1rem;border:1px solid rgba(56,189,248,.35);border-radius:var(--radius-md);background:rgba(56,189,248,.05)}.impact.blocked{border-color:rgba(248,113,113,.4)}.impact h3{margin:0 0 .6rem;font-size:.85rem}.impact li,.impact p{color:var(--color-text-secondary);font-size:.75rem}.confirm{display:flex;align-items:center;gap:.5rem;margin:.75rem 0}.danger-button{border-color:var(--color-danger);background:rgba(248,113,113,.1);color:#fca5a5}
    .timeline{list-style:none;margin:0;padding:0}.timeline li{display:grid;grid-template-columns:12px 1fr;gap:.55rem;padding:.7rem 0}.timeline li>span{width:9px;height:9px;margin-top:.25rem;border-radius:50%;background:var(--color-info)}.timeline li div{display:grid;grid-template-columns:1fr auto;gap:.2rem .8rem}.timeline time,.timeline small{color:var(--color-text-muted);font-size:.66rem}.timeline p{grid-column:1/-1;margin:0}.timeline small{grid-column:1/-1}.transition{color:var(--color-info)!important}.state{padding:2rem;border:1px dashed var(--color-border);border-radius:var(--radius-md);text-align:center;color:var(--color-text-muted)}.state.error{color:var(--color-danger)}
    @media(max-width:850px){.lifecycle,.three{grid-template-columns:1fr 1fr}.lifecycle article:nth-child(2){border-right:0}.lifecycle article:nth-child(-n+2){border-bottom:1px solid var(--color-border)}.action-form{grid-template-columns:1fr}}@media(max-width:580px){.hero{align-items:start;flex-direction:column}.two,.three,.lifecycle{grid-template-columns:1fr}.lifecycle article{border-right:0;border-bottom:1px solid var(--color-border)!important}.lifecycle article::after{display:none}.item{grid-template-columns:1fr}.item>strong{text-align:right}.entity{align-items:start;flex-direction:column}.entity span{text-align:left}}
  `],
})
export class AdminOrderDetailComponent implements OnInit {
  private readonly route=inject(ActivatedRoute); private readonly service=inject(AdminOrderService);
  readonly order=signal<AdminOrderDetail|null>(null);readonly loading=signal(true);readonly error=signal('');
  readonly actionError=signal('');readonly preview=signal<AdminOrderCancellationPreview|null>(null);
  readonly previewing=signal(false);readonly saving=signal(false);reasonCode='';reason='';confirmed=false;
  private idempotencyKey:string|null=null;private readonly orderId=this.route.snapshot.paramMap.get('orderId')??'';
  ngOnInit():void{this.load()}
  load():void{this.loading.set(true);this.error.set('');this.service.detail(this.orderId).subscribe({next:value=>{this.order.set(value);this.loading.set(false)},
    error:(response:HttpErrorResponse)=>{this.error.set(response.status===403?'You do not have permission to read this order.':response.status===404?'Order was not found.':response.error?.error?.message||'Order detail could not be loaded.');this.loading.set(false)}})}
  invalidatePreview():void{this.preview.set(null);this.idempotencyKey=null;this.confirmed=false;this.actionError.set('')}
  previewCancellation():void{const request=this.request(null);if(!request)return;this.previewing.set(true);this.actionError.set('');
    this.service.previewCancellation(this.orderId,request).subscribe({next:value=>{this.preview.set(value);this.idempotencyKey=value.allowed?this.newKey():null;this.previewing.set(false)},
      error:(response:HttpErrorResponse)=>{this.actionError.set(this.message(response,'Cancellation could not be previewed.'));this.previewing.set(false)}})}
  confirmCancellation():void{
    const request=this.request(this.idempotencyKey);if(!request||!this.preview()?.allowed)return;this.saving.set(true);
    this.service.cancel(this.orderId,request).subscribe({
      next:()=>{this.saving.set(false);this.invalidatePreview();this.load()},
      error:(response:HttpErrorResponse)=>{
        this.saving.set(false);
        if(response.status===409){
          this.preview.set(null);this.idempotencyKey=null;this.confirmed=false;
          this.actionError.set('Order state changed. The old preview was discarded and the order was reloaded.');this.load();
        }else{
          this.actionError.set(this.message(response,'Cancellation could not be requested.'));
        }
      },
    });
  }
  fulfillment(value:AdminOrderDetail):string{const states=[...new Set(value.businesses.map(x=>x.fulfillmentStatus))];return states.map(x=>this.label(x)).join(', ')}
  scopeLabels(values:string[]):string{return values.map(value=>this.label(value)).join(', ')}
  label(value:string):string{return value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())}
  private request(key:string|null):AdminCancelOrderRequest|null{const value=this.order();if(!value)return null;return{reasonCode:this.reasonCode,reason:this.reason.trim(),expectedOrderVersion:value.version,idempotencyKey:key}}
  private message(response:HttpErrorResponse,fallback:string):string{return response.error?.error?.message||fallback}
  private newKey():string{return globalThis.crypto?.randomUUID?.()??`admin-order-${Date.now()}-${Math.random().toString(36).slice(2)}`}
}
