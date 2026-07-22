export interface CartItem {
  listingId: string;
  title: string;
  thumbnailUrl: string | null;
  storeName?: string | null;
  storeSlug?: string | null;
  businessVerified?: boolean | null;
  publicCity?: string | null;
  publicRegion?: string | null;
  quantity: number;
  observedPrice: number;
  currency: string;
  addedAt: string;
}

export interface CartCurrencyTotal {
  currency: string;
  amount: number;
}

export interface Cart {
  version: number;
  expiresAt: string | null;
  itemCount: number;
  totalQuantity: number;
  totals: CartCurrencyTotal[];
  items: CartItem[];
}

export interface AddCartItemRequest {
  listingId: string;
  quantity: number;
}

export interface UpdateCartItemRequest {
  quantity: number;
}

export type CartValidationStatus =
  | 'READY'
  | 'PRICE_CHANGED'
  | 'QUANTITY_REDUCED'
  | 'OUT_OF_STOCK'
  | 'LISTING_UNAVAILABLE'
  | 'SELLER_UNAVAILABLE'
  | 'CURRENCY_CONFLICT';

export type CartValidationAction =
  | 'ACCEPT_CURRENT_PRICE'
  | 'SET_AVAILABLE_QUANTITY'
  | 'REMOVE_ITEM'
  | 'RETRY_VALIDATION';

export interface CartValidationIssue {
  code: string;
  message: string;
  action: CartValidationAction | null;
}

export interface CartValidationItem {
  listingId: string;
  title: string;
  thumbnailUrl: string | null;
  storeName?: string | null;
  storeSlug?: string | null;
  businessVerified?: boolean | null;
  publicCity?: string | null;
  publicRegion?: string | null;
  requestedQuantity: number;
  availableQuantity: number | null;
  observedPrice: number;
  currentPrice: number | null;
  observedCurrency: string;
  currentCurrency: string | null;
  status: CartValidationStatus;
  issues: CartValidationIssue[];
}

export interface CartValidation {
  cartVersion: number;
  validatedAt: string;
  checkoutReady: boolean;
  itemCount: number;
  totalQuantity: number;
  validatedTotals: CartCurrencyTotal[];
  cartIssues: CartValidationIssue[];
  items: CartValidationItem[];
}
