import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { AdminSearchMaintenanceService } from './admin-search-maintenance.service';
import { authInterceptor } from '../interceptors/auth.interceptor';
import { ADMIN_SEARCH_MAINTENANCE_ENABLED } from '../../features/admin/admin-search-maintenance.capability';
import {
  EmbeddingRequestBackfillStatus,
  VectorRebuildStatus,
} from '../models/admin-search-maintenance.model';
import { of } from 'rxjs';

describe('AdminSearchMaintenanceService', () => {
  let service: AdminSearchMaintenanceService;
  let httpMock: HttpTestingController;

  const runId = '01K00000000000000000000001';
  const status: EmbeddingRequestBackfillStatus = {
    schemaVersion: 'MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1',
    runId,
    commandOutcome: 'SUCCEEDED',
    state: 'PENDING',
    pageCount: 1,
    processedCount: 50,
    createdCount: 45,
    alreadyPresentCount: 3,
    skippedCount: 2,
    failedCount: 0,
    errorCode: null,
    startedAt: '2026-07-24T10:00:00Z',
    updatedAt: '2026-07-24T10:00:01Z',
    completedAt: null,
  };
  const vectorStatus: VectorRebuildStatus = {
    schemaVersion: 'MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2',
    runId: '01K00000000000000000000002',
    commandOutcome: 'SUCCEEDED',
    state: 'CATCHING_UP',
    schemaIdentity: 'marketplace-public-listing-v2-vector',
    candidateRole: 'INACTIVE_V2_CANDIDATE',
    previousGenerationRole: 'ACTIVE_PREVIOUS',
    authoritativeDocumentCount: 305,
    vectorDocumentCount: 165,
    catchUpWorkCount: 0,
    deferredReceiptCount: 0,
    canCatchUp: true,
    canPromote: true,
    canRecover: false,
    errorCode: null,
    startedAt: '2026-07-24T10:00:00Z',
    updatedAt: '2026-07-24T10:00:01Z',
    promotedAt: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: true },
        {
          provide: AuthService,
          useValue: {
            csrf: () => ({
              headerName: 'X-CSRF-TOKEN',
              parameterName: '_csrf',
              token: 'admin-search-csrf',
            }),
          },
        },
      ],
    });

    service = TestBed.inject(AdminSearchMaintenanceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('uses the BFF and interceptor-owned CSRF for the bodyless V1 rebuild command', () => {
    service.rebuildLegacyIndex().subscribe(result => {
      expect(result).toEqual({ indexedCount: 17 });
    });

    const request = httpMock.expectOne('/api/v1/admin/search/listings/rebuild');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Content-Type')).toBeFalse();
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('admin-search-csrf');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ engine: 'opensearch', index: 'marketplace-listings', indexedCount: 17 });
  });

  it('strictly parses the Product backfill response without sending client controls', () => {
    service.startEmbeddingRequestBackfill().subscribe(result => {
      expect(result).toEqual(status);
    });

    const request = httpMock.expectOne(
      '/api/v1/admin/search/listings/embedding-request-backfills',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Content-Type')).toBeFalse();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('admin-search-csrf');
    request.flush(status);
  });

  it('uses only a server-returned canonical run ID for refresh and one-page resume', () => {
    service.getEmbeddingRequestBackfill(runId).subscribe();
    const refresh = httpMock.expectOne(
      `/api/v1/admin/search/listings/embedding-request-backfills/${runId}`,
    );
    expect(refresh.request.method).toBe('GET');
    expect(refresh.request.headers.has('X-CSRF-TOKEN')).toBeFalse();
    refresh.flush({ ...status, commandOutcome: 'OBSERVED' });

    service.resumeEmbeddingRequestBackfill(runId).subscribe();
    const resume = httpMock.expectOne(
      `/api/v1/admin/search/listings/embedding-request-backfills/${runId}/resume`,
    );
    expect(resume.request.method).toBe('POST');
    expect(resume.request.body).toBeNull();
    expect(resume.request.headers.has('Content-Type')).toBeFalse();
    expect(resume.request.headers.get('X-CSRF-TOKEN')).toBe('admin-search-csrf');
    resume.flush({ ...status, commandOutcome: 'SUCCEEDED', pageCount: 2 });
  });

  it('strictly parses the Product V2 vector rebuild status and server eligibility', () => {
    service.startVectorRebuild().subscribe(result => {
      expect(result).toEqual(vectorStatus);
    });

    const request = httpMock.expectOne('/api/v1/admin/search/listings/vector-rebuilds');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Content-Type')).toBeFalse();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('admin-search-csrf');
    request.flush(vectorStatus);
  });

  it('uses only a server-returned V2 run ID for status and bodyless commands', () => {
    service.getVectorRebuild(vectorStatus.runId).subscribe();
    const refresh = httpMock.expectOne(
      `/api/v1/admin/search/listings/vector-rebuilds/${vectorStatus.runId}`,
    );
    expect(refresh.request.method).toBe('GET');
    expect(refresh.request.headers.has('X-CSRF-TOKEN')).toBeFalse();
    refresh.flush({ ...vectorStatus, commandOutcome: 'OBSERVED' });

    service.catchUpVectorRebuild(vectorStatus.runId).subscribe();
    const catchUp = httpMock.expectOne(
      `/api/v1/admin/search/listings/vector-rebuilds/${vectorStatus.runId}/catch-up`,
    );
    expect(catchUp.request.method).toBe('POST');
    expect(catchUp.request.body).toBeNull();
    expect(catchUp.request.headers.has('Content-Type')).toBeFalse();
    catchUp.flush({ ...vectorStatus, commandOutcome: 'REPLAYED' });

    service.promoteVectorRebuild(vectorStatus.runId).subscribe();
    const promote = httpMock.expectOne(
      `/api/v1/admin/search/listings/vector-rebuilds/${vectorStatus.runId}/promote`,
    );
    expect(promote.request.method).toBe('POST');
    expect(promote.request.body).toBeNull();
    expect(promote.request.headers.has('Content-Type')).toBeFalse();
    promote.flush({
      ...vectorStatus,
      state: 'PROMOTED',
      candidateRole: 'ACTIVE_V2',
      previousGenerationRole: 'RETAINED_PREVIOUS',
      canCatchUp: false,
      canPromote: false,
      promotedAt: '2026-07-24T10:00:02Z',
    });

    service.recoverVectorRebuild(vectorStatus.runId).subscribe();
    const recover = httpMock.expectOne(
      `/api/v1/admin/search/listings/vector-rebuilds/${vectorStatus.runId}/recover`,
    );
    expect(recover.request.method).toBe('POST');
    expect(recover.request.body).toBeNull();
    expect(recover.request.headers.has('Content-Type')).toBeFalse();
    recover.flush({ ...vectorStatus, commandOutcome: 'SUCCEEDED' });
  });

  it('rejects extra or malformed response fields instead of exposing a raw response', () => {
    let receivedError: unknown;
    service.startEmbeddingRequestBackfill().subscribe({
      error: error => receivedError = error,
    });
    const request = httpMock.expectOne(
      '/api/v1/admin/search/listings/embedding-request-backfills',
    );
    request.flush({ ...status, listingId: '01D00000000000000000101' });

    expect(receivedError).toEqual(jasmine.any(Error));
  });

  it('rejects unsafe or malformed V2 status fields', () => {
    let receivedError: unknown;
    service.startVectorRebuild().subscribe({
      error: error => receivedError = error,
    });
    const request = httpMock.expectOne('/api/v1/admin/search/listings/vector-rebuilds');
    request.flush({
      ...vectorStatus,
      canPromote: 'true',
      candidateGeneration: 'marketplace-listings-v2-secret',
    });

    expect(receivedError).toEqual(jasmine.any(Error));
  });

  it('rejects a noncanonical run ID before making a request', () => {
    let receivedError: unknown;
    service.getEmbeddingRequestBackfill('../unsafe').subscribe({
      error: error => receivedError = error,
    });

    expect(receivedError).toEqual(jasmine.any(Error));
    httpMock.expectNone(request => request.url.includes('embedding-request-backfills'));
  });

  it('rejects noncanonical V2 run IDs before making a request', () => {
    let receivedError: unknown;
    service.promoteVectorRebuild('../unsafe').subscribe({
      error: error => receivedError = error,
    });

    expect(receivedError).toEqual(jasmine.any(Error));
    httpMock.expectNone(request => request.url.includes('vector-rebuilds'));
  });
});

describe('AdminSearchMaintenanceService bodyless request construction', () => {
  it('omits the body/options argument for every maintenance POST', () => {
    const http = jasmine.createSpyObj<HttpClient>('HttpClient', ['request']);
    const runId = '01K00000000000000000000001';
    const status: EmbeddingRequestBackfillStatus = {
      schemaVersion: 'MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1',
      runId,
      commandOutcome: 'SUCCEEDED',
      state: 'PENDING',
      pageCount: 1,
      processedCount: 1,
      createdCount: 1,
      alreadyPresentCount: 0,
      skippedCount: 0,
      failedCount: 0,
      errorCode: null,
      startedAt: '2026-07-24T10:00:00Z',
      updatedAt: '2026-07-24T10:00:01Z',
      completedAt: null,
    };
    const vectorStatus: VectorRebuildStatus = {
      schemaVersion: 'MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2',
      runId,
      commandOutcome: 'SUCCEEDED',
      state: 'CATCHING_UP',
      schemaIdentity: 'marketplace-public-listing-v2-vector',
      candidateRole: 'INACTIVE_V2_CANDIDATE',
      previousGenerationRole: 'ACTIVE_PREVIOUS',
      authoritativeDocumentCount: 1,
      vectorDocumentCount: 1,
      catchUpWorkCount: 0,
      deferredReceiptCount: 0,
      canCatchUp: true,
      canPromote: false,
      canRecover: false,
      errorCode: null,
      startedAt: '2026-07-24T10:00:00Z',
      updatedAt: '2026-07-24T10:00:01Z',
      promotedAt: null,
    };
    http.request.and.returnValues(
      of({ engine: 'opensearch', index: 'marketplace-listings', indexedCount: 1 }),
      of(status),
      of(status),
      of(vectorStatus),
      of(vectorStatus),
      of(vectorStatus),
      of(vectorStatus),
    );
    const service = new AdminSearchMaintenanceService(http, true);

    service.rebuildLegacyIndex().subscribe();
    service.startEmbeddingRequestBackfill().subscribe();
    service.resumeEmbeddingRequestBackfill(runId).subscribe();
    service.startVectorRebuild().subscribe();
    service.catchUpVectorRebuild(runId).subscribe();
    service.promoteVectorRebuild(runId).subscribe();
    service.recoverVectorRebuild(runId).subscribe();

    const calls = http.request.calls.allArgs();
    expect(calls).toEqual([
      ['POST', '/api/v1/admin/search/listings/rebuild'],
      ['POST', '/api/v1/admin/search/listings/embedding-request-backfills'],
      ['POST', `/api/v1/admin/search/listings/embedding-request-backfills/${runId}/resume`],
      ['POST', '/api/v1/admin/search/listings/vector-rebuilds'],
      ['POST', `/api/v1/admin/search/listings/vector-rebuilds/${runId}/catch-up`],
      ['POST', `/api/v1/admin/search/listings/vector-rebuilds/${runId}/promote`],
      ['POST', `/api/v1/admin/search/listings/vector-rebuilds/${runId}/recover`],
    ]);
    for (const call of calls) {
      expect(call.length).withContext('body/options argument must be omitted').toBe(2);
    }
  });
});

describe('AdminSearchMaintenanceService default-off boundary', () => {
  it('makes zero operator calls when the capability is disabled', () => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: false },
      ],
    });
    const service = TestBed.inject(AdminSearchMaintenanceService);
    const httpMock = TestBed.inject(HttpTestingController);
    let failures = 0;

    service.rebuildLegacyIndex().subscribe({ error: () => failures++ });
    service.startEmbeddingRequestBackfill().subscribe({ error: () => failures++ });
    service.getEmbeddingRequestBackfill('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });
    service.resumeEmbeddingRequestBackfill('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });
    service.startVectorRebuild().subscribe({ error: () => failures++ });
    service.getVectorRebuild('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });
    service.catchUpVectorRebuild('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });
    service.promoteVectorRebuild('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });
    service.recoverVectorRebuild('01K00000000000000000000001')
      .subscribe({ error: () => failures++ });

    expect(failures).toBe(9);
    httpMock.expectNone(() => true);
    httpMock.verify();
  });
});
