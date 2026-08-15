export interface ListingSearchRebuildResult {
  indexedCount: number;
}

export type EmbeddingRequestBackfillState =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED';

export type EmbeddingRequestBackfillCommandOutcome =
  | 'SUCCEEDED'
  | 'REPLAYED'
  | 'OBSERVED';

export interface EmbeddingRequestBackfillStatus {
  schemaVersion: 'MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1';
  runId: string;
  commandOutcome: EmbeddingRequestBackfillCommandOutcome;
  state: EmbeddingRequestBackfillState;
  pageCount: number;
  processedCount: number;
  createdCount: number;
  alreadyPresentCount: number;
  skippedCount: number;
  failedCount: number;
  errorCode:
    | 'LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED'
    | 'LISTING_EMBEDDING_BACKFILL_UNAVAILABLE'
    | 'INTERNAL_FAILURE'
    | null;
  startedAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export type VectorRebuildCommandOutcome = 'SUCCEEDED' | 'REPLAYED' | 'OBSERVED';

export type VectorRebuildState =
  | 'PREPARING'
  | 'DUAL_WRITE'
  | 'BACKFILLING'
  | 'CATCHING_UP'
  | 'PROMOTION_FENCED'
  | 'ROLLBACK_REQUIRED'
  | 'PROMOTED'
  | 'FAILED';

export type VectorRebuildErrorCode =
  | 'PREPARATION_FAILED'
  | 'PROMOTION_FAILED'
  | 'ALIAS_OUTCOME_UNKNOWN'
  | 'INTERNAL_FAILURE'
  | null;

export interface VectorRebuildStatus {
  schemaVersion: 'MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2';
  runId: string;
  commandOutcome: VectorRebuildCommandOutcome;
  state: VectorRebuildState;
  schemaIdentity: 'marketplace-public-listing-v2-vector';
  candidateRole: 'INACTIVE_V2_CANDIDATE' | 'ACTIVE_V2';
  previousGenerationRole: 'ACTIVE_PREVIOUS' | 'RETAINED_PREVIOUS';
  authoritativeDocumentCount: number;
  vectorDocumentCount: number;
  catchUpWorkCount: number;
  deferredReceiptCount: number;
  canCatchUp: boolean;
  canPromote: boolean;
  canRecover: boolean;
  errorCode: VectorRebuildErrorCode;
  startedAt: string;
  updatedAt: string;
  promotedAt: string | null;
}
