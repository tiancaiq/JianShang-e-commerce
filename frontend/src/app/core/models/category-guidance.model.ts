export type CategoryGuidanceLifecycle = 'ACTIVE' | 'INVALIDATED';

export interface CategoryGuidanceSource {
  sourceType: 'CATEGORY_GUIDANCE';
  sourceId: string;
  sourceVersion: string;
  supersedesVersion: string | null;
  lifecycle: CategoryGuidanceLifecycle;
  visibility: 'PUBLIC';
  language: string;
  effectiveFrom: string | null;
  invalidatedAt: string | null;
  contentHash: string | null;
  content: {
    categorySlug: string;
    categoryName: string;
    title: string;
    body: string;
  } | null;
}

export interface CategoryGuidancePublishRequest {
  title: string;
  body: string;
}

export interface CategoryGuidanceHistoryPage {
  items: CategoryGuidanceSource[];
  nextCursor: string | null;
  hasMore: boolean;
  exportWatermark: string | null;
}
