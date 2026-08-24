import { expect, Page, test } from '@playwright/test';

const ORDER_ID='01KZORDER00000000000000001';
const BUYER_ID='01KZBUYER00000000000000001';
const BUSINESS_ID='01KZBUSIN00000000000000001';
const LISTING_ID='01KZLISTIN00000000000000001';

test('admin reads an order and completes a safe cancellation preview/confirmation',async({page})=>{
  let status='CONFIRMED';let version=0;let cancellationRequested=false;
  await mockAdmin(page,['admin.order.read','admin.order.cancel','admin.audit.read']);
  await page.route('**/api/v1/admin/orders?**',route=>route.fulfill({json:pageResponse(status,version)}));
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}`,route=>route.fulfill({json:detail(status,version,true)}));
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}/cancel/dry-run`,route=>route.fulfill({json:{orderId:ORDER_ID,
    currentOrderStatus:status,currentOrderVersion:version,allowed:true,nextOrderStatus:'CANCELLATION_REQUESTED',
    inventoryImpact:'Restock the committed reservation through the existing compensation worker.',
    paymentImpact:'Refund the captured total through the existing idempotent payment adapter.',
    buyerImpact:'The buyer account remains unchanged.',sellerImpact:'Business and listing enforcement remain unchanged.',
    warnings:['Cancellation completes asynchronously after compensation succeeds.'],blockerCode:null,blockerMessage:null}}));
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}/cancel`,async route=>{cancellationRequested=true;status='CANCELLATION_REQUESTED';version=1;
    await route.fulfill({json:{orderId:ORDER_ID,cancellationRequestId:'01KZCANCEL0000000000000001',status,version,requestedAt:new Date().toISOString(),replayed:false}})});
  await page.goto('/admin/orders');await expect(page.getByRole('heading',{name:'Orders'})).toBeVisible();
  await page.getByRole('link',{name:/01KZOR/}).click();await expect(page.getByText('Purchase-time item snapshots')).toBeVisible();
  await expect(page.getByText('Snapshot camera')).toBeVisible();await expect(page.getByText('Current camera · Active')).toBeVisible();
  await page.getByLabel('Cancellation reason').selectOption('FRAUD_PREVENTION');
  await page.getByLabel('Operational explanation').fill('Risk review confirmed by operations.');
  await page.getByRole('button',{name:'Preview cancellation'}).click();
  await expect(page.getByRole('heading',{name:'Review impact'})).toBeVisible();
  await page.getByLabel(/I confirm this cancellation/).check();await page.getByRole('button',{name:'Cancel order'}).click();
  await expect.poll(()=>cancellationRequested).toBe(true);await expect(page.getByText('Cancellation requested',{exact:true}).first()).toBeVisible();
});

test('order reader can inspect but remains read-only',async({page})=>{
  await mockAdmin(page,['admin.order.read','admin.audit.read']);
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}`,route=>route.fulfill({json:detail('CONFIRMED',4,false)}));
  await page.goto(`/admin/orders/${ORDER_ID}`);
  await expect(page.getByText('You have read-only order access.')).toBeVisible();
  await expect(page.getByRole('button',{name:'Preview cancellation'})).toHaveCount(0);
});

test('financially unsupported cancellation is explained and cannot be confirmed',async({page})=>{
  await mockAdmin(page,['admin.order.read','admin.order.cancel']);
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}`,route=>route.fulfill({json:detail('CONFIRMED',4,true,false,
    'Cancellation cannot be completed through admin tools because compensation is unavailable.')}));
  await page.goto(`/admin/orders/${ORDER_ID}`);
  await expect(page.getByText(/compensation is unavailable/).first()).toBeVisible();
  await expect(page.getByRole('button',{name:'Preview cancellation'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Cancel order'})).toHaveCount(0);
});

test('auditor can inspect order detail and has no mutation controls',async({page})=>{
  await mockAdmin(page,['admin.order.read','admin.audit.read'],['AUDITOR']);
  await page.route(`**/api/v1/admin/orders/${ORDER_ID}`,route=>route.fulfill({json:detail('CONFIRMED',4,false)}));
  await page.goto(`/admin/orders/${ORDER_ID}`);
  await expect(page.getByText('Purchase-time item snapshots')).toBeVisible();
  await expect(page.getByText('You have read-only order access.')).toBeVisible();
});

async function mockAdmin(page:Page,permissions:string[],roles=['SUPPORT_ADMIN']){
  await page.route('**/api/v1/auth/session',route=>route.fulfill({json:{
    authenticated:true,
    user:{subject:'01KZADMIN00000000000000001',email:'admin@msb.local',displayName:'Order Admin',roles,expiresAt:null},
    csrf:{headerName:'X-XSRF-TOKEN',parameterName:'_csrf',token:'playwright-csrf'},
  }}));
  await page.route('**/api/v1/users/me',route=>route.fulfill({status:503,json:{error:{message:'Use the authenticated session fallback.'}}}));
  await page.route('**/api/v1/admin/me',route=>route.fulfill({json:{data:{userId:'01KZADMIN00000000000000001',role:'PLATFORM_ADMIN',roles,permissions,accountState:'ACTIVE'}}}));
}
function pageResponse(status:string,version:number){return{content:[{orderId:ORDER_ID,buyerUserId:BUYER_ID,buyerDisplayName:'Buyer One',sellerType:'BUSINESS',businessIds:[BUSINESS_ID],businessDisplayNames:['Camera House'],status,paymentStatus:'SUCCEEDED',fulfillmentStatus:'PENDING_ACCEPTANCE',totalAmount:125,currency:'USD',itemCount:1,createdAt:'2026-08-16T01:00:00Z',updatedAt:'2026-08-16T01:00:00Z',version}],page:0,size:25,totalElements:1,totalPages:1,sort:'createdAt,desc'}}
function detail(status:string,version:number,canCancel:boolean,cancellationAllowed=canCancel,readOnlyReason=canCancel?null:'Administrative cancellation permission is required.'){return{orderId:ORDER_ID,orderNumber:ORDER_ID,status,paymentStatus:'SUCCEEDED',version,createdAt:'2026-08-16T01:00:00Z',updatedAt:'2026-08-16T01:00:00Z',buyerSummary:{userId:BUYER_ID,displayName:'Buyer One',adminPath:`/admin/users/${BUYER_ID}`},businesses:[{businessId:BUSINESS_ID,legalName:'Camera House LLC',storeId:'01KZSTORE00000000000000001',storeNameAtPurchase:'Camera House',currentStoreName:'Camera House',fulfillmentStatus:'PENDING_ACCEPTANCE',cancellationStatus:'NONE',version:0,adminPath:`/admin/businesses/${BUSINESS_ID}`}],orderItems:[{listingId:LISTING_ID,lineNumber:1,titleAtPurchase:'Snapshot camera',skuAtPurchase:'CAM-1',conditionAtPurchase:'NEW',imageReferenceAtPurchase:null,catalogVersionAtPurchase:3,businessId:BUSINESS_ID,storeId:'01KZSTORE00000000000000001',unitPrice:125,quantity:1,lineSubtotal:125,shipping:0,taxes:0,discounts:0,lineTotal:125,currency:'USD',policyVersion:'LOCAL_DEMO_CANCELLATION_V1',currentListing:{available:true,title:'Current camera',status:'ACTIVE',price:130,currency:'USD',version:5,enforcement:[]},adminPath:`/admin/listings/moderation/${LISTING_ID}`}],pricingSummary:{subtotal:125,fees:0,taxes:0,shipping:0,discounts:0,total:125,currency:'USD'},shippingAddressSafe:{masked:true,label:null,recipientName:null,phone:null,line1:null,line2:null,city:'Irvine',region:'CA',postalCode:'92***',countryCode:'US'},checkoutSnapshot:{checkoutId:'01KZCHECK00000000000000001',status:'COMPLETED',version:4,cartVersion:2,cartSnapshotHash:'abc',expiresAt:'2026-08-16T02:00:00Z',policies:[]},inventoryReservationSummary:{reservationId:'01KZRESER00000000000000001',status:'COMMITTED',live:true,usable:false,version:1,expiresAt:null,items:[{listingId:LISTING_ID,quantity:1}],releaseStatus:'NOT_REQUIRED',availabilityNote:null},paymentSummary:{paymentIntentId:'01KZPAYME00000000000000001',status:'SUCCEEDED',provider:'FAKE',providerPaymentReference:'safe-ref',authorizedAmount:125,capturedAmount:125,refundedAmount:0,currency:'USD',live:true,reconciliationState:'CURRENT',availabilityNote:null},refundSummary:[],cancellationSummary:null,relatedEnforcement:[],auditTimeline:[{eventId:'01KZEVENT00000000000000001',occurredAt:'2026-08-16T01:00:00Z',eventType:'ORDER_CONFIRMED',actorType:'PAYMENT_SERVICE',actorId:null,actorDisplayName:null,source:'SYSTEM',previousState:null,newState:'CONFIRMED',reason:'PAYMENT_SUCCEEDED',correlationId:'order-correlation',requestId:'01KZREQ000000000000000001',safeMetadata:{}}],availableAdminCapabilities:{canRead:true,canCancel,canManage:false,canViewPii:false,isCancellationAllowed:cancellationAllowed,isReadOnly:!cancellationAllowed,readOnlyReason}}}
