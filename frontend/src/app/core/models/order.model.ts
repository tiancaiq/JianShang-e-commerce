export interface BuyerOrderPage {
  items: BuyerOrderSummary[];
  page: { nextCursor: string | null; hasMore: boolean };
}

// Legacy dashboard projection retained until that tutorial-only dashboard is removed.
export interface OrderResponse {
  id: string;
  orderNumber: string;
  skuCode: string;
  quantity: number;
  price: number;
}

export interface BuyerOrderSummary {
  orderId: string;
  status: string;
  paymentStatus: string;
  totalAmount: number;
  currency: string;
  createdAt: string;
  updatedAt: string;
  groups: BuyerOrderGroupSummary[];
}

export interface BuyerOrderGroupSummary {
  businessOrderId: string;
  businessId: string;
  storeName: string | null;
  status: string;
  totalAmount: number;
  currency: string;
}

export interface BuyerOrderDetail extends BuyerOrderSummary {
  version: number;
  groups: BuyerOrderGroup[];
  shippingAddress: BuyerOrderAddress;
  cancellation?: BuyerOrderCancellation | null;
}

export interface BuyerOrderCancellation {
  eligible: boolean;
  ineligibilityCode: 'FULFILLMENT_STARTED' | 'POLICY_NOT_ALLOWED' | 'WINDOW_CLOSED' | null;
  requestId: string | null;
  requestStatus: 'PENDING' | 'AUTO_APPROVED' | 'COMPLETED' | null;
  reasonCode: 'BUYER_CANCELLATION_REQUESTED' | null;
  requestedAt: string | null;
  decidedAt: string | null;
  completedAt: string | null;
  inventoryStatus: 'PENDING' | 'SUCCEEDED' | null;
  refund: BuyerOrderRefund | null;
}

export interface BuyerOrderRefund {
  status: 'PENDING' | 'SUCCEEDED';
  refundId: string | null;
  amount: number;
  currency: string;
  displayName: 'Local demo refund';
  disclosure: 'No real money is moved.';
}

export interface OrderCancellationResponse {
  orderId: string;
  cancellationRequestId: string;
  status: 'CANCELLATION_REQUESTED';
  requestStatus: 'PENDING';
  version: number;
  requestedAt: string;
}

export interface BuyerOrderGroup extends BuyerOrderGroupSummary {
  storeId: string;
  items: BuyerOrderItem[];
  version: number;
  timeline: BuyerOrderTimelineEntry[];
  shipment: BuyerOrderShipment | null;
}

export interface BuyerOrderTimelineEntry {
  status: string;
  occurredAt: string;
}

export interface BuyerOrderShipment {
  shipmentId: string;
  source: 'LOCAL_DEMO_MANUAL';
  carrierDisplayName: string;
  serviceDisplayName: string;
  trackingNumber: string;
  status: 'SHIPPED' | 'DELIVERED';
  version: number;
  shippedAt: string;
  deliveredAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BuyerOrderItem {
  listingId: string;
  title: string;
  businessId: string;
  storeId: string;
  unitPrice: number;
  currency: string;
  quantity: number;
  lineTotal: number;
  policyVersion: string;
}

export interface BuyerOrderAddress {
  label: string | null;
  recipientName: string;
  phone: string;
  line1: string;
  line2: string | null;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
}

export type ReturnReason = 'NO_LONGER_NEEDED'|'NOT_AS_EXPECTED'|'DAMAGED'|'WRONG_ITEM'|'OTHER';
export interface BusinessOrderReturn {
  eligible:boolean; ineligibilityCode:string|null; returnId:string|null; orderId:string;
  businessOrderId:string; storeName:string|null; reasonCode:ReturnReason|null; buyerComment:string|null;
  policyVersion:string; requestedAt:string|null; windowExpiresAt:string|null; status:string|null;
  refundStatus:string|null; inventoryDisposition:string|null; receivedAt:string|null;
  refundId:string|null; refundAmount:number|null; currency:string; completedAt:string|null; version:number;
  shipment:{carrierDisplayName:string;trackingReference:string;createdAt:string;inTransitAt:string;disclosure:string}|null;
  timeline:{status:string;occurredAt:string}[];
}
