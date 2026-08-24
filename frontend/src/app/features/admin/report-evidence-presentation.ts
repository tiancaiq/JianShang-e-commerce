const EVIDENCE_LABELS: Readonly<Record<string, string>> = {
  accountState: 'Account state',
  available: 'Available',
  businessId: 'Business ID',
  businessState: 'Business state',
  capturedAt: 'Captured at',
  categoryId: 'Category ID',
  currency: 'Currency',
  description: 'Description',
  displayName: 'Display name',
  enforcement: 'Enforcement at capture',
  imageReferences: 'Image references',
  individualSellerUserId: 'Individual seller user ID',
  listingId: 'Listing ID',
  ownerUserId: 'Owner user ID',
  price: 'Price',
  reportable: 'Reportable',
  safeDisplayName: 'Display name',
  sellerType: 'Seller type',
  sku: 'SKU',
  state: 'Availability state',
  status: 'Status',
  storeId: 'Store ID',
  storeState: 'Store state',
  title: 'Title',
  updatedAt: 'Updated at',
  userId: 'User ID',
  version: 'Version',
};

export function reportEvidenceLabel(key: string): string {
  return EVIDENCE_LABELS[key] ?? key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replaceAll('_', ' ')
    .replace(/^./, character => character.toUpperCase());
}

export function isReportEvidenceTimestamp(key: string, value: unknown): value is string {
  return typeof value === 'string'
    && (key.endsWith('At') || key.toLowerCase().includes('timestamp'))
    && !Number.isNaN(Date.parse(value));
}

export function rawReportEvidenceValue(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
