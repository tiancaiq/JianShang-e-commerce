export type BusinessOrderStatus =
  | 'PENDING_ACCEPTANCE'
  | 'ACCEPTED'
  | 'PARTIALLY_SHIPPED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELLATION_PENDING'
  | 'CANCELLED';

export interface BusinessOrderSummary {
  businessOrderId: string;
  sellerOrderNumber: string;
  businessId: string;
  storeId: string;
  status: BusinessOrderStatus;
  cancellationStatus: string;
  buyerOrderId: string;
  buyerOrderNumber: string;
  itemCount: number;
  totalQuantity: number;
  subtotal: number;
  totalAmount: number;
  currency: string;
  platformFeeProjection?: number | null;
  confirmedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessOrderPage {
  items: BusinessOrderSummary[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}

export interface BusinessOrderItem {
  listingId: string;
  title: string;
  sku: string;
  itemCondition: string;
  thumbnailUrl: string | null;
  unitPrice: number;
  currency: string;
  quantity: number;
  lineTotal: number;
  policyVersion: string;
}

export interface BusinessOrderShippingAddress {
  recipientName: string;
  phone: string;
  line1: string;
  line2: string | null;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
}

export interface BusinessOrderDetail extends BusinessOrderSummary {
  paymentStatus: string;
  items: BusinessOrderItem[];
  shippingAddress: BusinessOrderShippingAddress;
}
