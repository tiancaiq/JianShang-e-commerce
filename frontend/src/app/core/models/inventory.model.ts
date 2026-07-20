export type InventoryState = 'INITIALIZED' | 'NOT_INITIALIZED';
export type InventoryOperation = 'SET' | 'ADJUST';
export type InventoryReason =
  | 'INITIAL_STOCK'
  | 'RESTOCK'
  | 'STOCK_COUNT_CORRECTION'
  | 'DAMAGED'
  | 'LOST'
  | 'RETURNED'
  | 'OTHER';

export interface InventoryBalance {
  id: string;
  businessId: string;
  listingId: string;
  skuSnapshot: string;
  onHand: number;
  reserved: number;
  available: number;
  version: number;
  initializedAt: string;
  updatedAt: string;
}

export interface InventoryCatalogItem {
  listingId: string;
  title: string;
  sku: string;
  listingStatus: string;
  catalogQuantitySuggestion: number;
  catalogVersion: number;
  inventoryState: InventoryState;
  inventory: InventoryBalance | null;
}

export interface InventoryCatalogPage {
  data: InventoryCatalogItem[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}

export interface InventoryMovement {
  id: string;
  operation: InventoryOperation;
  reason: InventoryReason;
  quantityDelta: number;
  onHandBefore: number;
  onHandAfter: number;
  reservedSnapshot: number;
  note: string | null;
  createdAt: string;
}

export interface InventoryMovementPage {
  data: InventoryMovement[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}

export interface InventoryInitializeRequest {
  onHand: number;
  note: string | null;
}

export interface InventoryAdjustmentRequest {
  operation: InventoryOperation;
  quantity: number;
  reason: InventoryReason;
  note: string | null;
}
