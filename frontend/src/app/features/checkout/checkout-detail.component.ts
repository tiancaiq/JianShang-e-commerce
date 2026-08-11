import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Checkout, CheckoutPaymentIntent } from '../../core/models/checkout.model';
import { CartService } from '../../core/services/cart.service';
import { CheckoutService } from '../../core/services/checkout.service';
import { StripePaymentElementService } from '../../core/services/stripe-payment-element.service';

@Component({
  selector:'app-checkout-detail', standalone:true, imports:[CurrencyPipe,DatePipe,RouterLink],
  template:`
    <section class="detail-page"><header><a routerLink="/cart">Back to cart</a><p>{{stripeMode()?'Stripe sandbox checkout':'Local demo checkout'}}</p><h1>Review and pay</h1></header>
      @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
      @else if(!checkout()){<div class="notice">Loading checkout...</div>}
      @else if(checkout();as current){
        <div class="status"><strong>{{statusLabel(current.status)}}</strong><span>Reservation deadline {{current.expiresAt|date:'medium'}}</span></div>
        <div class="layout"><div class="content">
          @for(group of businessGroups(current);track group.businessId){<section><h2>{{group.storeName || 'Store'}}</h2>
            @for(item of group.items;track item.listingId){<div class="item"><img [src]="item.thumbnailUrl||'/assets/placeholder.svg'" alt=""><span><strong>{{item.title}}</strong><small>Qty {{item.quantity}} · {{item.unitPrice|currency:current.currency}}</small></span><strong>{{item.lineTotal|currency:current.currency}}</strong></div>}
          </section>}
          <section><h2>Delivery address snapshot</h2><address><strong>{{current.address.recipientName}}</strong><br>{{current.address.line1}}<br>@if(current.address.line2){ {{current.address.line2}}<br> }{{current.address.city}}, {{current.address.region}} {{current.address.postalCode}}<br>{{current.address.countryCode}}</address></section>
        </div><aside>
          <div><span>Subtotal</span><strong>{{current.subtotal|currency:current.currency}}</strong></div><div><span>Shipping</span><strong>{{current.shipping|currency:current.currency}}</strong></div><small>Free shipping is local-demo behavior · FREE_LOCAL_DEMO_V1</small><div><span>Tax</span><strong>{{current.tax|currency:current.currency}}</strong></div><small>Zero tax is local-demo behavior · ZERO_LOCAL_DEMO_V1</small><div class="grand"><span>Grand total</span><strong>{{current.total|currency:current.currency}}</strong></div>
          <div class="demo"><strong>{{stripeMode()?'Stripe test payment':'Local demo payment'}}</strong><span>{{stripeMode()?'No production charge will occur.':'No real money will be charged.'}}</span></div>
          @if(stripeMode()){<div id="stripe-payment-element" class="stripe-element" aria-label="Secure Stripe payment form"></div>}
          @if(current.status==='PENDING_PAYMENT'){<button class="pay" type="button" [disabled]="paying()" (click)="pay()">{{payLabel()}}</button>}
          @if(paymentIntent()){<small>Payment status: {{paymentIntent()?.status}}</small>}@if(waitingForOrder()){<p class="waiting">Payment is processing. We will confirm your order after the verified provider result arrives.</p>}
        </aside></div>
      }
    </section>`,
  styles:[`
    .detail-page{max-width:1120px;margin:0 auto;display:grid;gap:1rem;color:var(--market-ink)}header{border-bottom:1px solid var(--market-line)}header a{color:var(--market-accent-dark);font-weight:900}header p{color:var(--market-accent-dark);font-weight:900;text-transform:uppercase;font-size:.78rem}h1{font-family:var(--font-display);font-size:2.4rem}.status{display:flex;justify-content:space-between;gap:1rem;padding:.8rem 1rem;border-left:4px solid #168168;background:#edf9f5}.layout{display:grid;grid-template-columns:1fr 330px;gap:1rem;align-items:start}.content{display:grid;gap:1rem}section section,aside,.notice{border:1px solid var(--market-line);border-radius:8px;background:#fff;padding:1rem}.item,aside>div:not(.demo){display:flex;justify-content:space-between;gap:1rem;padding:.7rem 0;border-bottom:1px solid var(--market-line)}.item img{width:64px;height:64px;object-fit:cover}.item span{display:grid;flex:1}.item small,aside small,address{color:var(--market-muted)}address{font-style:normal;line-height:1.6}aside{display:grid;gap:.55rem}.grand{font-size:1.08rem}.demo{display:grid;gap:.2rem;padding:.8rem;background:#fff8e8;border:1px solid #e2bd64;border-radius:6px}.demo span{font-size:.85rem}.stripe-element{min-height:110px;padding:.8rem;border:1px solid var(--market-line);border-radius:6px;background:#fff}.pay,.cancel{min-height:44px;border-radius:6px;font:inherit;font-weight:900;cursor:pointer}.pay{border:0;background:var(--market-accent-dark);color:#fff}.cancel{border:1px solid var(--market-line);background:#fff}.waiting{color:#168168;font-weight:800}.error{color:#9d285d}@media(max-width:760px){.layout{grid-template-columns:1fr}aside{order:-1}}
  `],
})
export class CheckoutDetailComponent implements OnInit,OnDestroy{
  private readonly route=inject(ActivatedRoute);private readonly service=inject(CheckoutService);private readonly cartService=inject(CartService);private readonly stripe=inject(StripePaymentElementService);private readonly router=inject(Router);private checkoutId='';private pollTimer?:ReturnType<typeof setTimeout>;
  readonly checkout=signal<Checkout|null>(null);readonly paymentIntent=signal<CheckoutPaymentIntent|null>(null);readonly error=signal('');readonly paying=signal(false);readonly waitingForOrder=signal(false);readonly stripeMode=signal(false);readonly stripeReady=signal(false);
  ngOnInit():void{this.checkoutId=this.route.snapshot.paramMap.get('checkoutId')||'';if(!this.checkoutId){this.error.set('Checkout was not found.');return;}this.load();}
  ngOnDestroy():void{if(this.pollTimer)clearTimeout(this.pollTimer);this.stripe.unmount();}
  pay():void{if(this.paying())return;if(this.stripeReady()){void this.confirmStripe();return;}this.paying.set(true);this.error.set('');this.service.createPaymentIntent(this.checkoutId).subscribe({next:intent=>{this.paymentIntent.set(intent);if(intent.action?.type==='STRIPE_PAYMENT_ELEMENT'){this.stripeMode.set(true);setTimeout(()=>void this.mountStripe(intent),0);return;}if(intent.status==='PROCESSING'||intent.status==='SUCCEEDED'){this.waitForAuthoritativeOrder();return;}this.service.completeDemoPayment(this.checkoutId).subscribe({next:()=>this.waitForAuthoritativeOrder(),error:error=>this.fail(error)});},error:error=>this.fail(error)});}
  payLabel():string{if(this.paying())return this.stripeMode()?'Submitting secure payment...':'Confirming demo payment...';if(this.stripeReady())return 'Pay with Stripe test card';return this.stripeMode()?'Prepare Stripe test payment':'Complete demo payment';}
  businessGroups(current:Checkout){const groups=new Map<string,{businessId:string;storeId:string;storeName:string|null;items:Checkout['items']}>();for(const item of current.items){const group=groups.get(item.businessId)||{businessId:item.businessId,storeId:item.storeId,storeName:item.storeName,items:[]};group.items.push(item);groups.set(item.businessId,group);}return [...groups.values()];}
  statusLabel(status:Checkout['status']):string{return({RESERVING:'Reserving inventory',PENDING_PAYMENT:'Inventory reserved · ready for payment',PAYMENT_PROCESSING:'Payment received · confirming order',PAYMENT_REVIEW:'Payment review required',REFUND_REQUIRED:'Payment recovery required',COMPLETED:'Order confirmed',FAILED:'Checkout failed',CANCELLED:'Checkout cancelled',EXPIRED:'Checkout expired'})[status];}
  private load():void{this.service.get(this.checkoutId).subscribe({next:value=>this.checkout.set(value),error:error=>this.error.set(error?.error?.error?.message||'Checkout could not be loaded.')});}
  private async mountStripe(intent:CheckoutPaymentIntent):Promise<void>{try{const action=intent.action;if(!action?.publicKey||!action.returnUrl)throw new Error('Stripe configuration is incomplete.');await this.stripe.mount(action.publicKey,action.reference,action.returnUrl,'#stripe-payment-element');this.stripeReady.set(true);this.paying.set(false);}catch(error){this.fail(error);}}
  private async confirmStripe():Promise<void>{this.paying.set(true);this.error.set('');try{const result=await this.stripe.confirm();if(result.error){this.paying.set(false);this.error.set(result.error.message||'Stripe could not confirm this test payment.');return;}this.waitForAuthoritativeOrder();}catch(error){this.fail(error);}}
  private waitForAuthoritativeOrder():void{this.waitingForOrder.set(true);this.pollForOrder(0);}
  private pollForOrder(attempt:number):void{this.service.confirmedOrder(this.checkoutId).subscribe({next:result=>{if(result.confirmed&&result.orderId){const cartVersion=this.checkout()?.cartVersion;if(cartVersion!==undefined){this.cartService.refreshAfterConfirmedCheckout(cartVersion).subscribe();}void this.router.navigate(['/account/orders',result.orderId],{queryParams:{confirmed:'true'}});return;}if(attempt>=40){this.paying.set(false);this.error.set('Payment is still processing. Your order will appear after the verified payment result arrives.');return;}this.pollTimer=setTimeout(()=>this.pollForOrder(attempt+1),750);},error:error=>this.fail(error)});}
  private fail(error:unknown):void{const value=error as {message?:string;error?:{error?:{message?:string}}};this.paying.set(false);this.waitingForOrder.set(false);this.error.set(value?.error?.error?.message||value?.message||'Payment could not be completed.');this.load();}
}
