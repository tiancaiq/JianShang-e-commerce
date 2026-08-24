import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminOrderDetail } from '../../core/models/admin-order.model';
import { AdminOrderService } from '../../core/services/admin-order.service';
import { AdminOrderDetailComponent } from './admin-order-detail.component';

describe('AdminOrderDetailComponent', () => {
  let fixture:ComponentFixture<AdminOrderDetailComponent>;let service:jasmine.SpyObj<AdminOrderService>;
  beforeEach(async()=>{service=jasmine.createSpyObj<AdminOrderService>('AdminOrderService',['detail','previewCancellation','cancel']);
    service.detail.and.returnValue(of(detail()));service.previewCancellation.and.returnValue(of({orderId:id(1),
      currentOrderStatus:'CONFIRMED',currentOrderVersion:4,allowed:true,nextOrderStatus:'CANCELLATION_REQUESTED',
      inventoryImpact:'Restock inventory',paymentImpact:'Refund payment',buyerImpact:'Buyer unchanged',
      sellerImpact:'Business unchanged',warnings:['Asynchronous compensation'],blockerCode:null,blockerMessage:null}));
    service.cancel.and.returnValue(of({orderId:id(1),cancellationRequestId:id(20),status:'CANCELLATION_REQUESTED',
      version:5,requestedAt:'2026-08-16T01:01:00Z',replayed:false}));
    await TestBed.configureTestingModule({imports:[AdminOrderDetailComponent],providers:[provideZonelessChangeDetection(),
      provideRouter([]),{provide:ActivatedRoute,useValue:{snapshot:{paramMap:{get:()=>id(1)}}}},
      {provide:AdminOrderService,useValue:service}]}).compileComponents();
    fixture=TestBed.createComponent(AdminOrderDetailComponent);fixture.detectChanges();});

  it('distinguishes historical listing data, masks PII, and renders enforcement and timeline',()=>{
    const text=fixture.nativeElement.textContent;
    expect(text).toContain('Purchase-time item snapshots');expect(text).toContain('Snapshot camera');
    expect(text).toContain('Current camera');expect(text).toContain('Sensitive fields are masked');
    expect(text).toContain('User buying');expect(text).toContain('Order confirmed');
  });

  it('previews, explicitly confirms, executes, and refreshes safe cancellation',()=>{
    fixture.componentInstance.reasonCode='FRAUD_PREVENTION';fixture.componentInstance.reason='Risk review';
    fixture.componentInstance.previewCancellation();fixture.componentInstance.confirmed=true;
    fixture.componentInstance.confirmCancellation();
    expect(service.previewCancellation).toHaveBeenCalledWith(id(1),jasmine.objectContaining({expectedOrderVersion:4}));
    expect(service.cancel).toHaveBeenCalledWith(id(1),jasmine.objectContaining({
      expectedOrderVersion:4,idempotencyKey:jasmine.any(String)}));
    expect(service.detail).toHaveBeenCalledTimes(2);
  });

  it('discards a stale preview, reports conflict, and reloads without retrying',()=>{
    fixture.componentInstance.reasonCode='FRAUD_PREVENTION';fixture.componentInstance.reason='Risk review';
    fixture.componentInstance.previewCancellation();fixture.componentInstance.confirmed=true;
    service.cancel.and.returnValue(throwError(()=>({status:409,error:{error:{message:'stale'}}})));
    fixture.componentInstance.confirmCancellation();
    expect(fixture.componentInstance.preview()).toBeNull();expect(fixture.componentInstance.actionError())
      .toContain('old preview was discarded');expect(service.cancel).toHaveBeenCalledTimes(1);
    expect(service.detail).toHaveBeenCalledTimes(2);
  });
});

function detail():AdminOrderDetail{return{orderId:id(1),orderNumber:'ORD-1',status:'CONFIRMED',paymentStatus:'SUCCEEDED',version:4,
  createdAt:'2026-08-16T01:00:00Z',updatedAt:'2026-08-16T01:00:00Z',buyerSummary:{userId:id(2),displayName:'Buyer One',adminPath:`/admin/users/${id(2)}`},
  businesses:[{businessId:id(3),legalName:'Camera LLC',storeId:id(4),storeNameAtPurchase:'Camera House',currentStoreName:'Camera House',fulfillmentStatus:'PENDING_ACCEPTANCE',cancellationStatus:'NONE',version:0,adminPath:`/admin/businesses/${id(3)}`}],
  orderItems:[{listingId:id(5),lineNumber:1,titleAtPurchase:'Snapshot camera',skuAtPurchase:'CAM-1',conditionAtPurchase:'NEW',imageReferenceAtPurchase:null,catalogVersionAtPurchase:2,businessId:id(3),storeId:id(4),unitPrice:125,quantity:1,lineSubtotal:125,shipping:0,taxes:0,discounts:0,lineTotal:125,currency:'USD',policyVersion:'V1',currentListing:{available:true,title:'Current camera',status:'ACTIVE',price:130,currency:'USD',version:3,enforcement:[]},adminPath:`/admin/listings/moderation/${id(5)}`}],
  pricingSummary:{subtotal:125,fees:0,taxes:0,shipping:0,discounts:0,total:125,currency:'USD'},shippingAddressSafe:{masked:true,label:null,recipientName:null,phone:null,line1:null,line2:null,city:'Irvine',region:'CA',postalCode:'92***',countryCode:'US'},
  checkoutSnapshot:{checkoutId:id(6),status:'COMPLETED',version:1,cartVersion:1,cartSnapshotHash:'hash',expiresAt:'2026-08-16T02:00:00Z',policies:[]},inventoryReservationSummary:{reservationId:id(7),status:'COMMITTED',live:true,usable:false,version:1,expiresAt:null,items:[],releaseStatus:'NOT_REQUIRED',availabilityNote:null},paymentSummary:{paymentIntentId:id(8),status:'SUCCEEDED',provider:'FAKE',providerPaymentReference:'safe-ref',authorizedAmount:125,capturedAmount:125,refundedAmount:0,currency:'USD',live:true,reconciliationState:'CURRENT',availabilityNote:null},refundSummary:[],cancellationSummary:null,
  relatedEnforcement:[{targetType:'USER',targetId:id(2),actionType:'RESTRICT',scopes:['USER_BUYING'],adminPath:`/admin/users/${id(2)}`}],auditTimeline:[{eventId:id(9),occurredAt:'2026-08-16T01:00:00Z',eventType:'ORDER_CONFIRMED',actorType:'SYSTEM',actorId:null,actorDisplayName:null,source:'SYSTEM',previousState:null,newState:'CONFIRMED',reason:'PAYMENT_SUCCEEDED',correlationId:'correlation',requestId:id(10),safeMetadata:{}}],availableAdminCapabilities:{canRead:true,canCancel:true,canManage:false,canViewPii:false,isCancellationAllowed:true,isReadOnly:false,readOnlyReason:null}}}
function id(value:number){return `01${String(value).padStart(24,'0')}`}
