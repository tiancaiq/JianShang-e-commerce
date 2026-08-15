import { HttpClient } from '@angular/common/http';
import { Inject, Injectable } from '@angular/core';
import { map, Observable, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  EmbeddingRequestBackfillCommandOutcome,
  EmbeddingRequestBackfillState,
  EmbeddingRequestBackfillStatus,
  ListingSearchRebuildResult,
  VectorRebuildCommandOutcome,
  VectorRebuildErrorCode,
  VectorRebuildState,
  VectorRebuildStatus,
} from '../models/admin-search-maintenance.model';
import { ADMIN_SEARCH_MAINTENANCE_ENABLED } from '../../features/admin/admin-search-maintenance.capability';

const RUN_ID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const BACKFILL_SCHEMA = 'MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1';
const BACKFILL_KEYS = [
  'schemaVersion',
  'runId',
  'commandOutcome',
  'state',
  'pageCount',
  'processedCount',
  'createdCount',
  'alreadyPresentCount',
  'skippedCount',
  'failedCount',
  'errorCode',
  'startedAt',
  'updatedAt',
  'completedAt',
] as const;
const VECTOR_REBUILD_SCHEMA = 'MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2';
const VECTOR_REBUILD_KEYS = [
  'schemaVersion',
  'runId',
  'commandOutcome',
  'state',
  'schemaIdentity',
  'candidateRole',
  'previousGenerationRole',
  'authoritativeDocumentCount',
  'vectorDocumentCount',
  'catchUpWorkCount',
  'deferredReceiptCount',
  'canCatchUp',
  'canPromote',
  'canRecover',
  'errorCode',
  'startedAt',
  'updatedAt',
  'promotedAt',
] as const;

@Injectable({ providedIn: 'root' })
export class AdminSearchMaintenanceService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/admin/search/listings`;

  constructor(
    private readonly http: HttpClient,
    @Inject(ADMIN_SEARCH_MAINTENANCE_ENABLED) private readonly enabled: boolean,
  ) {}

  // Rebuilds only the legacy derived V1 projection after an explicit administrator confirmation.
  rebuildLegacyIndex(): Observable<ListingSearchRebuildResult> {
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/rebuild`,
    ).pipe(map(parseLegacyRebuild)));
  }

  // Starts one bounded server-owned embedding-request backfill page with no client controls.
  startEmbeddingRequestBackfill(): Observable<EmbeddingRequestBackfillStatus> {
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/embedding-request-backfills`,
    ).pipe(map(parseBackfillStatus)));
  }

  // Reads status only for the opaque run ID returned by the Product service.
  getEmbeddingRequestBackfill(runId: string): Observable<EmbeddingRequestBackfillStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid embedding backfill run ID.'));
    }
    return this.whenEnabled(() => this.http.get<unknown>(
      `${this.baseUrl}/embedding-request-backfills/${runId}`,
    ).pipe(map(parseBackfillStatus)));
  }

  // Advances exactly one bounded page; callers must never automatically replay the POST.
  resumeEmbeddingRequestBackfill(runId: string): Observable<EmbeddingRequestBackfillStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid embedding backfill run ID.'));
    }
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/embedding-request-backfills/${runId}/resume`,
    ).pipe(map(parseBackfillStatus)));
  }

  // Registers one server-owned inactive V2 vector rebuild run after administrator confirmation.
  startVectorRebuild(): Observable<VectorRebuildStatus> {
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/vector-rebuilds`,
    ).pipe(map(parseVectorRebuildStatus)));
  }

  // Reads only the bounded Product-owned V2 rebuild status for a server-returned run ID.
  getVectorRebuild(runId: string): Observable<VectorRebuildStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid vector rebuild run ID.'));
    }
    return this.whenEnabled(() => this.http.get<unknown>(
      `${this.baseUrl}/vector-rebuilds/${runId}`,
    ).pipe(map(parseVectorRebuildStatus)));
  }

  // Runs one explicit Product-owned catch-up command; readiness comes only from Product booleans.
  catchUpVectorRebuild(runId: string): Observable<VectorRebuildStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid vector rebuild run ID.'));
    }
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/vector-rebuilds/${runId}/catch-up`,
    ).pipe(map(parseVectorRebuildStatus)));
  }

  // Runs one explicit fenced promotion command; Product rechecks all promotion preconditions.
  promoteVectorRebuild(runId: string): Observable<VectorRebuildStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid vector rebuild run ID.'));
    }
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/vector-rebuilds/${runId}/promote`,
    ).pipe(map(parseVectorRebuildStatus)));
  }

  // Runs one explicit recovery command for a Product-declared recoverable state.
  recoverVectorRebuild(runId: string): Observable<VectorRebuildStatus> {
    if (!RUN_ID.test(runId)) {
      return throwError(() => new Error('Invalid vector rebuild run ID.'));
    }
    return this.whenEnabled(() => this.http.request<unknown>(
      'POST',
      `${this.baseUrl}/vector-rebuilds/${runId}/recover`,
    ).pipe(map(parseVectorRebuildStatus)));
  }

  private whenEnabled<T>(request: () => Observable<T>): Observable<T> {
    return this.enabled
      ? request()
      : throwError(() => new Error('Admin search maintenance is disabled.'));
  }
}

function parseLegacyRebuild(value: unknown): ListingSearchRebuildResult {
  const record = exactRecord(value, ['engine', 'index', 'indexedCount']);
  if (!boundedString(record['engine'], 1, 32)
    || !boundedString(record['index'], 1, 255)
    || !nonnegativeInteger(record['indexedCount'])) {
    throw new Error('Legacy rebuild response does not match its contract.');
  }
  return { indexedCount: record['indexedCount'] };
}

function parseBackfillStatus(value: unknown): EmbeddingRequestBackfillStatus {
  const record = exactRecord(value, BACKFILL_KEYS);
  const commandOutcomes: EmbeddingRequestBackfillCommandOutcome[] =
    ['SUCCEEDED', 'REPLAYED', 'OBSERVED'];
  const states: EmbeddingRequestBackfillState[] =
    ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'];
  const errorCodes = [
    'LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED',
    'LISTING_EMBEDDING_BACKFILL_UNAVAILABLE',
    'INTERNAL_FAILURE',
  ];
  const countKeys = [
    'pageCount',
    'processedCount',
    'createdCount',
    'alreadyPresentCount',
    'skippedCount',
    'failedCount',
  ];

  if (record['schemaVersion'] !== BACKFILL_SCHEMA
    || typeof record['runId'] !== 'string'
    || !RUN_ID.test(record['runId'])
    || !commandOutcomes.includes(record['commandOutcome'] as EmbeddingRequestBackfillCommandOutcome)
    || !states.includes(record['state'] as EmbeddingRequestBackfillState)
    || countKeys.some(key => !nonnegativeInteger(record[key]))
    || !(record['errorCode'] === null
      || errorCodes.includes(record['errorCode'] as string))
    || !timestamp(record['startedAt'])
    || !timestamp(record['updatedAt'])
    || !nullableTimestamp(record['completedAt'])) {
    throw new Error('Embedding backfill response does not match its contract.');
  }

  return record as unknown as EmbeddingRequestBackfillStatus;
}

function parseVectorRebuildStatus(value: unknown): VectorRebuildStatus {
  const record = exactRecord(value, VECTOR_REBUILD_KEYS);
  const commandOutcomes: VectorRebuildCommandOutcome[] =
    ['SUCCEEDED', 'REPLAYED', 'OBSERVED'];
  const states: VectorRebuildState[] = [
    'PREPARING',
    'DUAL_WRITE',
    'BACKFILLING',
    'CATCHING_UP',
    'PROMOTION_FENCED',
    'ROLLBACK_REQUIRED',
    'PROMOTED',
    'FAILED',
  ];
  const errorCodes: Exclude<VectorRebuildErrorCode, null>[] = [
    'PREPARATION_FAILED',
    'PROMOTION_FAILED',
    'ALIAS_OUTCOME_UNKNOWN',
    'INTERNAL_FAILURE',
  ];
  const countKeys = [
    'authoritativeDocumentCount',
    'vectorDocumentCount',
    'catchUpWorkCount',
    'deferredReceiptCount',
  ];

  if (record['schemaVersion'] !== VECTOR_REBUILD_SCHEMA
    || typeof record['runId'] !== 'string'
    || !RUN_ID.test(record['runId'])
    || !commandOutcomes.includes(record['commandOutcome'] as VectorRebuildCommandOutcome)
    || !states.includes(record['state'] as VectorRebuildState)
    || record['schemaIdentity'] !== 'marketplace-public-listing-v2-vector'
    || !['INACTIVE_V2_CANDIDATE', 'ACTIVE_V2'].includes(record['candidateRole'] as string)
    || !['ACTIVE_PREVIOUS', 'RETAINED_PREVIOUS'].includes(record['previousGenerationRole'] as string)
    || countKeys.some(key => !nonnegativeInteger(record[key]))
    || typeof record['canCatchUp'] !== 'boolean'
    || typeof record['canPromote'] !== 'boolean'
    || typeof record['canRecover'] !== 'boolean'
    || !(record['errorCode'] === null
      || errorCodes.includes(record['errorCode'] as Exclude<VectorRebuildErrorCode, null>))
    || !timestamp(record['startedAt'])
    || !timestamp(record['updatedAt'])
    || !nullableTimestamp(record['promotedAt'])) {
    throw new Error('Vector rebuild response does not match its contract.');
  }

  return record as unknown as VectorRebuildStatus;
}

function exactRecord(
  value: unknown,
  keys: readonly string[],
): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Response is not an object.');
  }
  const record = value as Record<string, unknown>;
  const actual = Object.keys(record).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length
    || actual.some((key, index) => key !== expected[index])) {
    throw new Error('Response fields do not match the contract.');
  }
  return record;
}

function nonnegativeInteger(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= 0;
}

function boundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string'
    && value.length >= min
    && value.length <= max;
}

function timestamp(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= 40
    && Number.isFinite(Date.parse(value));
}

function nullableTimestamp(value: unknown): value is string | null {
  return value === null || timestamp(value);
}
