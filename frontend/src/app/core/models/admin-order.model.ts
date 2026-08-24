export interface AdminOrderPage {
  content: AdminOrderSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
}

export interface AdminOrderSummary {
  orderId: string;
  buyerUserId: string;
  buyerDisplayName: string | null;
  sellerType: 'BUSINESS';
  businessIds: string[];
  businessDisplayNames: string[];
  status: string;
  paymentStatus: string;
  fulfillmentStatus: string;
  totalAmount: number;
  currency: string;
  itemCount: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface AdminOrderDetail {
  orderId: string;
  orderNumber: string;
  status: string;
  paymentStatus: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  buyerSummary: { userId: string; displayName: string | null; adminPath: string };
  businesses: Array<{
    businessId: string; legalName: string | null; storeId: string; storeNameAtPurchase: string;
    currentStoreName: string | null; fulfillmentStatus: string; cancellationStatus: string;
    version: number; adminPath: string;
  }>;
  orderItems: Array<{
    listingId: string; lineNumber: number; titleAtPurchase: string; skuAtPurchase: string | null;
    conditionAtPurchase: string; imageReferenceAtPurchase: string | null; catalogVersionAtPurchase: number;
    businessId: string; storeId: string; unitPrice: number; quantity: number; lineSubtotal: number;
    shipping: number; taxes: number; discounts: number; lineTotal: number; currency: string;
    policyVersion: string; currentListing: null | { available: boolean; title: string; status: string;
      price: number; currency: string; version: number; enforcement: AdminOrderEnforcement[] };
    adminPath: string;
  }>;
  pricingSummary: { subtotal: number; fees: number; taxes: number; shipping: number;
    discounts: number; total: number; currency: string };
  shippingAddressSafe: null | { masked: boolean; label: string | null; recipientName: string | null;
    phone: string | null; line1: string | null; line2: string | null; city: string; region: string;
    postalCode: string | null; countryCode: string };
  checkoutSnapshot: { checkoutId: string; status: string; version: number; cartVersion: number;
    cartSnapshotHash: string; expiresAt: string; policies: Array<{ businessId: string; version: string;
      cancellationMode: string; cancellationText: string; shippingText: string; returnText: string }> };
  inventoryReservationSummary: { reservationId: string | null; status: string | null; live: boolean;
    usable: boolean; version: number | null; expiresAt: string | null;
    items: Array<{ listingId: string; quantity: number }>; releaseStatus: string; availabilityNote: string | null };
  paymentSummary: { paymentIntentId: string; status: string; provider: string | null;
    providerPaymentReference: string | null; authorizedAmount: number; capturedAmount: number;
    refundedAmount: number; currency: string; live: boolean; reconciliationState: string;
    availabilityNote: string | null };
  refundSummary: Array<{ source: string; status: string; refundId: string | null;
    providerReference: string | null; amount: number | null; currency: string | null; completedAt: string | null }>;
  cancellationSummary: null | { requestId: string; requestStatus: string; actorType: string;
    actorId: string | null; reasonCode: string | null; reason: string | null; requestedAt: string;
    decidedAt: string | null; completedAt: string | null; inventoryStatus: string | null; refundStatus: string | null };
  relatedEnforcement: AdminOrderEnforcement[];
  auditTimeline: AdminOrderTimelineEntry[];
  availableAdminCapabilities: AdminOrderCapabilities;
}

export interface AdminOrderEnforcement {
  targetType: string; targetId: string; actionType: string; scopes: string[]; adminPath: string;
}

export interface AdminOrderTimelineEntry {
  eventId: string; occurredAt: string; eventType: string; actorType: string; actorId: string | null;
  actorDisplayName: string | null; source: string; previousState: string | null; newState: string | null;
  reason: string | null; correlationId: string | null; requestId: string | null;
  safeMetadata: Record<string, string>;
}

export interface AdminOrderCapabilities {
  canRead: boolean; canCancel: boolean; canManage: boolean; canViewPii: boolean;
  isCancellationAllowed: boolean; isReadOnly: boolean; readOnlyReason: string | null;
}

export interface AdminCancelOrderRequest {
  reasonCode: string;
  reason: string;
  expectedOrderVersion: number;
  idempotencyKey: string | null;
}

export interface AdminOrderCancellationPreview {
  orderId: string; currentOrderStatus: string; currentOrderVersion: number; allowed: boolean;
  nextOrderStatus: string; inventoryImpact: string; paymentImpact: string; buyerImpact: string;
  sellerImpact: string; warnings: string[]; blockerCode: string | null; blockerMessage: string | null;
}

export interface AdminOrderCancellationResult {
  orderId: string; cancellationRequestId: string; status: string; version: number;
  requestedAt: string; replayed: boolean;
}
