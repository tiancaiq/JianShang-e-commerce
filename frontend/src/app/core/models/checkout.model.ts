export type CheckoutStatus =
  | 'RESERVING'
  | 'PENDING_PAYMENT'
  | 'PAYMENT_PROCESSING'
  | 'PAYMENT_REVIEW'
  | 'REFUND_REQUIRED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export interface CreateCheckoutRequest {
  cartVersion: number;
  addressId: string;
}

export interface Checkout {
  id: string;
  status: CheckoutStatus;
  cartVersion: number;
  currency: string;
  subtotal: number;
  shipping: number;
  tax: number;
  discount: number;
  total: number;
  expiresAt: string;
  reservation: {
    id: string | null;
    status: string | null;
    releaseStatus: 'NOT_REQUIRED' | 'PENDING' | 'COMPLETE';
  };
  address: CheckoutAddress;
  items: CheckoutItem[];
  shippingQuotes: CheckoutShippingQuote[];
  taxQuote: {
    amount: number;
    adapter: string;
  };
  policies: CheckoutPolicy[];
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CheckoutAddress {
  sourceAddressId: string;
  sourceVersion: number;
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

export interface CheckoutItem {
  listingId: string;
  businessId: string;
  storeId: string;
  storeName: string | null;
  catalogVersion: number;
  title: string;
  sku: string | null;
  condition: string;
  thumbnailUrl: string | null;
  quantity: number;
  unitPrice: number;
  lineSubtotal: number;
  shippingAllocation: number;
  taxAllocation: number;
  discountAllocation: number;
  lineTotal: number;
  policyVersion: string;
}

export interface CheckoutShippingQuote {
  businessId: string;
  storeId: string;
  methodCode: string;
  amount: number;
  adapter: string;
}

export interface CheckoutPolicy {
  businessId: string;
  storeId: string;
  source: string;
  version: string;
  shippingText: string;
  cancellationText: string;
  returnText: string;
}

export interface CheckoutPaymentIntent {
  id: string;
  checkoutId: string;
  status: 'REQUIRES_ACTION' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
  version: number;
  amount: number;
  currency: string;
  expiresAt: string;
  action: { type: string; reference: string } | null;
  error: { code: string; message: string } | null;
}

export interface DemoPaymentCompletion {
  paymentIntentId: string;
  status: string;
  outcome: string;
  replayed: boolean;
}

export interface CheckoutOrderResolution {
  orderId: string | null;
  confirmed: boolean;
}
