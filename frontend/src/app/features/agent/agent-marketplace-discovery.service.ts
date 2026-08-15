import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Inject, Injectable, InjectionToken } from '@angular/core';
import {
  Observable,
  defer,
  finalize,
  map,
  of,
  shareReplay,
  switchMap,
  take,
  throwError,
} from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthState } from '../../core/models/auth.model';
import { AuthService } from '../../core/services/auth.service';
import { AGENT_DISCOVERY_ENABLED } from './agent-customer-service.capability';
import {
  DiscoveryHistoryMessage,
  DiscoveryHistoryPage,
  DiscoveryConstraintCoverage,
  DiscoveryExclusion,
  DiscoveryExclusionReason,
  DiscoveryExclusionResponse,
  DiscoveryOutcome,
  DiscoveryPreferenceState,
  DiscoveryProvenance,
  DiscoveryRecommendation,
  DiscoverySearchOutcome,
  DiscoveryResolution,
  DiscoverySession,
  DiscoveryStreamEvent,
  DiscoveryProgressStage,
  DiscoveryTurnResult,
  SendDiscoveryMessageResponse,
  StopDiscoveryResponse,
} from './agent-marketplace-discovery.model';

const ULID_PATTERN = /^[0-9A-Z]{26}$/;
const HASH_PATTERN = /^[0-9a-f]{64}$/;
const CURRENCY_PATTERN = /^[A-Z]{3}$/;
const CONTROL_PATTERN = /[\u0000-\u001f\u007f]/;
const PUBLIC_LISTING_MEDIA_PATTERN = /^\/api\/v1\/public\/listing-media\/[0-9A-Z]{26}$/;
const IDEMPOTENCY_KEY_PATTERN = /^[\x21-\x7e]{16,128}$/;
const SESSION_STATUSES = ['OPEN', 'CLOSED'] as const;
const OUTCOMES = [
  'ANSWER', 'CLARIFY', 'SEARCH', 'ACTION_REQUIRED',
  'ASK_CLARIFY', 'RECOMMEND', 'COMPARE', 'DETAIL', 'NO_RESULTS',
  'REFUSE', 'REFUSED', 'HANDOFF',
] as const;
const INTENTS = [
  'GENERAL_CONVERSATION', 'MARKETPLACE_DISCOVERY', 'LISTING_QUESTION',
  'CUSTOMER_SUPPORT', 'SELLER_SUPPORT', 'CLARIFICATION', 'HANDOFF', 'REFUSED',
] as const;
const RESOLUTIONS = ['ANSWERED', 'CLARIFY', 'RECOMMEND', 'NO_RESULTS', 'REFUSED', 'PARTIAL', 'HANDOFF'] as const;
const CONDITIONS = ['NEW', 'OPEN_BOX', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS'] as const;
const CONSTRAINT_COVERAGE = [
  'QUERY',
  'CATEGORY',
  'CONDITION',
  'PRICE',
  'CITY',
  'COUNTY_OR_REGION',
] as const;
const EXCLUSION_REASONS = [
  'NOT_RELEVANT',
  'TOO_EXPENSIVE',
  'TOO_FAR',
  'WRONG_CONDITION',
  'ALREADY_HAVE',
  'OTHER',
] as const;
const STREAM_SCHEMA_VERSION = 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2';
const STREAM_STAGES = [
  'MESSAGE_ACCEPTED',
  'UNDERSTANDING',
  'CHECKING_AVAILABILITY',
  'SEARCHING',
  'CHECKING',
  'COMPOSING',
] as const;
const STREAM_FAILURE_CODES = [
  'VALIDATION_ERROR',
  'AGENT_DISCOVERY_FEATURE_DISABLED',
  'AUTHENTICATION_REQUIRED',
  'AGENT_DISCOVERY_SESSION_NOT_FOUND',
  'AGENT_DISCOVERY_SESSION_CLOSED',
  'AGENT_DISCOVERY_REQUEST_CONFLICT',
  'AGENT_DISCOVERY_PREFERENCE_VERSION_CONFLICT',
  'AGENT_DISCOVERY_MESSAGE_IN_PROGRESS',
  'AGENT_DISCOVERY_UNAVAILABLE',
  'AGENT_DISCOVERY_FINAL_STREAM_INTERRUPTED',
] as const;

export type DiscoveryFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export const AGENT_DISCOVERY_FETCH = new InjectionToken<DiscoveryFetch>(
  'AGENT_DISCOVERY_FETCH',
  { providedIn: 'root', factory: () => globalThis.fetch.bind(globalThis) },
);

export type DiscoveryContractFailureKind =
  | 'CONTRACT'
  | 'CONTENT_TYPE'
  | 'BODY_MISSING'
  | 'EVENT_AFTER_TERMINAL'
  | 'ANSWER_LIMIT'
  | 'FINAL_TEXT_MISMATCH'
  | 'FINAL_METADATA_MISMATCH'
  | 'TERMINAL_MISSING'
  | 'BUFFER_LIMIT'
  | 'INCOMPLETE_FRAME'
  | 'FRAME_SHAPE'
  | 'FRAME_JSON'
  | 'SCHEMA_VERSION'
  | 'SEQUENCE'
  | 'EVENT_TYPE';

export class DiscoveryContractError extends Error {
  constructor(readonly kind: DiscoveryContractFailureKind = 'CONTRACT') {
    super('Marketplace discovery returned an invalid response.');
    this.name = 'DiscoveryContractError';
  }
}

export class DiscoveryAuthenticationRequiredError extends Error {
  constructor() {
    super('An authenticated marketplace session is required.');
    this.name = 'DiscoveryAuthenticationRequiredError';
  }
}

export class DiscoveryFeatureDisabledError extends Error {
  constructor() {
    super('Marketplace discovery is disabled.');
    this.name = 'DiscoveryFeatureDisabledError';
  }
}

export class DiscoveryStreamFailureError extends Error {
  readonly status: number;
  readonly code: string;
  readonly publicMessage: string;
  readonly retryable: boolean;

  constructor(
    codeOrStatus: string | number,
    codeOrMessage = 'Marketplace discovery stream failed.',
    retryable = false,
  ) {
    const legacy = typeof codeOrStatus === 'number';
    const code = legacy ? codeOrMessage : codeOrStatus;
    const message = legacy ? 'Marketplace discovery stream failed.' : codeOrMessage;
    super(message);
    this.name = 'DiscoveryStreamFailureError';
    this.status = legacy ? codeOrStatus : 503;
    this.code = code;
    this.publicMessage = message;
    this.retryable = retryable;
  }
}

@Injectable({ providedIn: 'root' })
export class AgentMarketplaceDiscoveryService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/agent/discovery`;
  private sessionWarmup: Observable<void> | null = null;
  private sessionCreate: {
    newSearch: boolean;
    request: Observable<DiscoverySession>;
  } | null = null;
  private readonly messageSends = new Map<string, {
    fingerprint: string;
    request: Observable<SendDiscoveryMessageResponse>;
  }>();
  private readonly streamSends = new Map<string, {
    fingerprint: string;
    request: Observable<DiscoveryStreamEvent>;
  }>();
  private readonly responseRetries = new Map<string, Observable<DiscoveryStreamEvent>>();
  private readonly exclusionSends = new Map<string, {
    fingerprint: string;
    request: Observable<DiscoveryExclusionResponse>;
  }>();

  constructor(
    private readonly http: HttpClient,
    private readonly authService: AuthService,
    @Inject(AGENT_DISCOVERY_ENABLED) private readonly enabled: boolean,
    @Inject(AGENT_DISCOVERY_FETCH) private readonly fetchRequest: DiscoveryFetch,
  ) {}

  /** Creates or resumes the one actor-owned discovery session after explicit intent. */
  createOrResumeSession(newSearch: boolean): Observable<DiscoverySession> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    if (this.sessionCreate) {
      return this.sessionCreate.newSearch === newSearch
        ? this.sessionCreate.request
        : throwError(() => new DiscoveryContractError());
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(
        `${this.baseUrl}/sessions`,
        {
          sessionType: 'MARKETPLACE_DISCOVERY',
          newSearch,
        },
        {
          withCredentials: true,
          headers: this.csrfHeader(),
        },
      )),
      map(parseDiscoverySession),
      finalize(() => {
        this.sessionCreate = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.sessionCreate = { newSearch, request };
    return request;
  }

  /** Reads one actor-owned session through the strict discovery representation. */
  getSession(sessionId: string): Observable<DiscoverySession> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    return this.http.get<unknown>(
      `${this.baseUrl}/sessions/${trustedUlid(sessionId)}`,
      { withCredentials: true },
    ).pipe(map(parseDiscoverySession));
  }

  /** Reads bounded stored history without exposing tool traces or private fields. */
  getMessages(
    sessionId: string,
    cursor: string | null = null,
    limit = 50,
  ): Observable<DiscoveryHistoryPage> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      return throwError(() => new DiscoveryContractError());
    }
    const params: Record<string, string> = { limit: String(limit) };
    if (cursor) {
      params['cursor'] = boundedString(cursor, 1, 500);
    }
    return this.http.get<unknown>(
      `${this.baseUrl}/sessions/${trustedUlid(sessionId)}/messages`,
      { params, withCredentials: true },
    ).pipe(map(parseDiscoveryHistoryPage));
  }

  /** Sends one retry-safe turn without automatically replaying uncertain POSTs. */
  sendMessage(
    sessionId: string,
    clientMessageId: string,
    expectedPreferenceVersion: number,
    body: string,
  ): Observable<SendDiscoveryMessageResponse> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const trustedSessionId = trustedUlid(sessionId);
    const trustedMessageId = trustedUlid(clientMessageId);
    if (!Number.isInteger(expectedPreferenceVersion) || expectedPreferenceVersion < 0) {
      return throwError(() => new DiscoveryContractError());
    }
    const normalizedBody = boundedString(body, 1, 8_000);
    const operationKey = `${trustedSessionId}:${trustedMessageId}`;
    const fingerprint = JSON.stringify({
      expectedPreferenceVersion,
      body: normalizedBody,
    });
    const existing = this.messageSends.get(operationKey);
    if (existing) {
      return existing.fingerprint === fingerprint
        ? existing.request
        : throwError(() => new DiscoveryContractError());
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(
        `${this.baseUrl}/sessions/${trustedSessionId}/messages`,
        {
          clientMessageId: trustedMessageId,
          expectedPreferenceVersion,
          body: normalizedBody,
        },
        {
          withCredentials: true,
          headers: this.csrfHeader(),
        },
      )),
      map(parseSendDiscoveryMessageResponse),
      finalize(() => this.messageSends.delete(operationKey)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.messageSends.set(operationKey, { fingerprint, request });
    return request;
  }

  /** Streams one turn once; uncertain failures never fall back to a second POST. */
  streamMessage(
    sessionId: string,
    clientMessageId: string,
    expectedPreferenceVersion: number,
    body: string,
  ): Observable<DiscoveryStreamEvent> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const trustedSessionId = trustedUlid(sessionId);
    const trustedMessageId = trustedUlid(clientMessageId);
    if (!Number.isInteger(expectedPreferenceVersion) || expectedPreferenceVersion < 0) {
      return throwError(() => new DiscoveryContractError());
    }
    const normalizedBody = boundedString(body, 1, 8_000);
    const operationKey = `${trustedSessionId}:${trustedMessageId}`;
    const fingerprint = JSON.stringify({ expectedPreferenceVersion, body: normalizedBody });
    const existing = this.streamSends.get(operationKey);
    if (existing) {
      return existing.fingerprint === fingerprint
        ? existing.request
        : throwError(() => new DiscoveryContractError());
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.openDiscoveryStream(
        `/sessions/${trustedSessionId}/messages/stream`,
        {
          clientMessageId: trustedMessageId,
          expectedPreferenceVersion,
          body: normalizedBody,
        },
      )),
      finalize(() => this.streamSends.delete(operationKey)),
      shareReplay({ bufferSize: 32, refCount: true }),
    );
    this.streamSends.set(operationKey, { fingerprint, request });
    return request;
  }

  /** Cancels one active response and resolves its durable USER-turn outcome. */
  stopMessage(sessionId: string, clientMessageId: string): Observable<StopDiscoveryResponse> {
    const trustedSessionId = trustedUlid(sessionId);
    const trustedMessageId = trustedUlid(clientMessageId);
    return this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(
        `${this.baseUrl}/sessions/${trustedSessionId}/messages/${trustedMessageId}/stop`,
        {},
        { withCredentials: true, headers: this.csrfHeader() },
      )),
      map(value => parseStopDiscoveryResponse(value, trustedSessionId, trustedMessageId)),
    );
  }

  /** Owns the authenticated fetch reader and validates terminal stream integrity. */
  private openDiscoveryStream(
    path: string,
    requestBody: Record<string, unknown>,
  ): Observable<DiscoveryStreamEvent> {
    return new Observable(subscriber => {
      const csrf = this.authService.csrf();
      if (!csrf) {
        subscriber.error(new DiscoveryAuthenticationRequiredError());
        return undefined;
      }
      const abort = new AbortController();
      void (async () => {
        try {
          const response = await this.fetchRequest(
            `${this.baseUrl}${path}`,
            {
              method: 'POST',
              credentials: 'include',
              signal: abort.signal,
              headers: {
                'Accept': 'text/event-stream',
                'Content-Type': 'application/json',
                [csrf.headerName]: csrf.token,
              },
              body: JSON.stringify(requestBody),
            },
          );
          if (!response.ok) {
            throw new HttpErrorResponse({
              status: response.status,
              statusText: response.statusText,
              url: response.url,
            });
          }
          if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('text/event-stream')) {
            throw new DiscoveryContractError('CONTENT_TYPE');
          }
          const reader = response.body?.getReader();
          if (!reader) {
            throw new DiscoveryContractError('BODY_MISSING');
          }
          const parser = new DiscoverySseParser();
          let answer = '';
          let recommendations: readonly DiscoveryRecommendation[] | null = null;
          let metadataProvenance: readonly DiscoveryProvenance[] | null = null;
          let terminal = false;
          while (true) {
            const chunk = await reader.read();
            const events = chunk.done ? parser.finish() : parser.push(chunk.value);
            for (const event of events) {
              if (terminal) {
                throw new DiscoveryContractError('EVENT_AFTER_TERMINAL');
              }
              if (event.type === 'text_delta') {
                answer += event.delta;
                if (answer.length > 800) {
                  throw new DiscoveryContractError('ANSWER_LIMIT');
                }
              } else if (event.type === 'recommendations') {
                recommendations = event.items;
              } else if (event.type === 'metadata') {
                metadataProvenance = event.provenance;
              } else if (event.type === 'done') {
                if (answer !== event.response.result.message) {
                  throw new DiscoveryContractError('FINAL_TEXT_MISMATCH');
                }
                if (recommendations === null || metadataProvenance === null
                  || JSON.stringify(recommendations)
                    !== JSON.stringify(event.response.result.recommendations)
                  || JSON.stringify(metadataProvenance)
                    !== JSON.stringify(
                      event.response.result.recommendations.map(item => item.provenance),
                    )) {
                  throw new DiscoveryContractError('FINAL_METADATA_MISMATCH');
                }
                terminal = true;
              } else if (event.type === 'error') {
                subscriber.next(event);
                throw new DiscoveryStreamFailureError(
                  event.code, event.message, event.retryable,
                );
              }
              subscriber.next(event);
            }
            if (chunk.done) {
              if (!terminal) {
                throw new DiscoveryContractError('TERMINAL_MISSING');
              }
              subscriber.complete();
              return;
            }
          }
        } catch (error) {
          if (!subscriber.closed) {
            subscriber.error(error);
          }
        }
      })();
      return () => abort.abort();
    });
  }

  /** Explicitly retries response generation for one committed USER message. */
  retryResponse(
    sessionId: string,
    userMessageId: string,
    expectedPreferenceVersion: number,
  ): Observable<DiscoveryStreamEvent> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const trustedSessionId = trustedUlid(sessionId);
    const trustedUserMessageId = trustedUlid(userMessageId);
    if (!Number.isInteger(expectedPreferenceVersion) || expectedPreferenceVersion < 0) {
      return throwError(() => new DiscoveryContractError());
    }
    const operationKey = `${trustedSessionId}:${trustedUserMessageId}`;
    const existing = this.responseRetries.get(operationKey);
    if (existing) {
      return existing;
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.openDiscoveryStream(
        `/sessions/${trustedSessionId}/messages/${trustedUserMessageId}/response-retry/stream`,
        { expectedPreferenceVersion },
      )),
      finalize(() => this.responseRetries.delete(operationKey)),
      shareReplay({ bufferSize: 32, refCount: true }),
    );
    this.responseRetries.set(operationKey, request);
    return request;
  }

  /** Sends one explicit session exclusion without overloading chat messages. */
  excludeListing(
    sessionId: string,
    idempotencyKey: string,
    expectedPreferenceVersion: number,
    listingId: string,
    reasonCode: DiscoveryExclusionReason | null,
  ): Observable<DiscoveryExclusionResponse> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const trustedSessionId = trustedUlid(sessionId);
    const trustedListingId = trustedUlid(listingId);
    if (
      !IDEMPOTENCY_KEY_PATTERN.test(idempotencyKey)
      || !Number.isInteger(expectedPreferenceVersion)
      || expectedPreferenceVersion < 0
      || (reasonCode !== null && !EXCLUSION_REASONS.includes(reasonCode))
    ) {
      return throwError(() => new DiscoveryContractError());
    }
    const operationKey = `${trustedSessionId}:${idempotencyKey}`;
    const fingerprint = JSON.stringify({
      expectedPreferenceVersion,
      listingId: trustedListingId,
      reasonCode,
    });
    const existing = this.exclusionSends.get(operationKey);
    if (existing) {
      return existing.fingerprint === fingerprint
        ? existing.request
        : throwError(() => new DiscoveryContractError());
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(
        `${this.baseUrl}/sessions/${trustedSessionId}/exclusions`,
        {
          expectedPreferenceVersion,
          listingId: trustedListingId,
          reasonCode,
        },
        {
          headers: {
            ...this.csrfHeader(),
            'Idempotency-Key': idempotencyKey,
          },
          withCredentials: true,
        },
      )),
      map(value => {
        const response = parseDiscoveryExclusionResponse(value);
        if (
          response.sessionId !== trustedSessionId
          || response.listingId !== trustedListingId
        ) {
          throw new DiscoveryContractError();
        }
        return response;
      }),
      finalize(() => this.exclusionSends.delete(operationKey)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.exclusionSends.set(operationKey, { fingerprint, request });
    return request;
  }

  /** Coalesces the BFF session and CSRF warmup required before discovery writes. */
  private requireAuthenticatedSession(): Observable<void> {
    if (this.authService.isAuthenticated() && this.authService.csrf()) {
      return of(undefined);
    }
    if (!this.sessionWarmup) {
      this.sessionWarmup = defer(() => this.authService.ensureSession()).pipe(
        take(1),
        switchMap(state => this.refreshMissingCsrf(state)),
        map(state => {
          if (!state.authenticated || !this.authService.csrf()) {
            throw new DiscoveryAuthenticationRequiredError();
          }
        }),
        finalize(() => {
          this.sessionWarmup = null;
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.sessionWarmup;
  }

  private refreshMissingCsrf(state: AuthState): Observable<AuthState> {
    return state.authenticated && !this.authService.csrf()
      ? this.authService.refreshSession().pipe(take(1))
      : of(state);
  }

  private csrfHeader(): Record<string, string> {
    const csrf = this.authService.csrf();
    return csrf ? { [csrf.headerName]: csrf.token } : {};
  }

  private featureDisabled<T>(): Observable<T> {
    return throwError(() => new DiscoveryFeatureDisabledError());
  }
}

export function parseDiscoverySession(value: unknown): DiscoverySession {
  const record = exactRecord(value, [
    'id', 'sessionType', 'status', 'preferenceState', 'preferenceVersion',
    'clarificationTurnCount', 'clarificationQuestionCount', 'exclusions',
    'createdAt', 'updatedAt',
  ]);
  return {
    id: ulidValue(record['id']),
    sessionType: literalValue(record['sessionType'], ['MARKETPLACE_DISCOVERY'] as const),
    status: literalValue(record['status'], SESSION_STATUSES),
    preferenceState: parsePreferenceState(record['preferenceState']),
    preferenceVersion: integerValue(record['preferenceVersion'], 0),
    clarificationTurnCount: integerValue(record['clarificationTurnCount'], 0, 3),
    clarificationQuestionCount: integerValue(record['clarificationQuestionCount'], 0, 5),
    exclusions: arrayValue(record['exclusions'], 20).map(parseDiscoveryExclusion),
    createdAt: timestampValue(record['createdAt']),
    updatedAt: timestampValue(record['updatedAt']),
  };
}

export function parseDiscoveryExclusionResponse(
  value: unknown,
): DiscoveryExclusionResponse {
  const record = exactRecord(value, [
    'sessionId', 'listingId', 'reasonCode', 'outcome', 'preferenceVersion',
    'excludedCount', 'updatedAt',
  ]);
  return {
    sessionId: ulidValue(record['sessionId']),
    listingId: ulidValue(record['listingId']),
    reasonCode: nullableExclusionReason(record['reasonCode']),
    outcome: literalValue(record['outcome'], ['EXCLUDED', 'ALREADY_EXCLUDED'] as const),
    preferenceVersion: integerValue(record['preferenceVersion'], 0),
    excludedCount: integerValue(record['excludedCount'], 1, 20),
    updatedAt: timestampValue(record['updatedAt']),
  };
}

export function parseDiscoveryHistoryPage(value: unknown): DiscoveryHistoryPage {
  const record = exactRecord(value, ['data', 'nextCursor', 'hasMore']);
  const nextCursor = nullableBoundedString(record['nextCursor'], 500);
  const hasMore = booleanValue(record['hasMore']);
  if (hasMore !== (nextCursor !== null)) {
    throw new DiscoveryContractError();
  }
  return {
    data: arrayValue(record['data'], 100).map(parseHistoryMessage),
    nextCursor,
    hasMore,
  };
}

export function parseSendDiscoveryMessageResponse(
  value: unknown,
): SendDiscoveryMessageResponse {
  const record = exactRecord(value, ['userMessage', 'result', 'preferenceVersion']);
  const user = exactRecord(record['userMessage'], ['id', 'role', 'body', 'createdAt']);
  return {
    userMessage: {
      id: ulidValue(user['id']),
      role: literalValue(user['role'], ['USER'] as const),
      body: multilineText(user['body'], 8_000),
      createdAt: timestampValue(user['createdAt']),
    },
    result: parseTurnResult(record['result']),
    preferenceVersion: integerValue(record['preferenceVersion'], 0),
  };
}

function parseStopDiscoveryResponse(
  value: unknown,
  expectedSessionId: string,
  expectedClientMessageId: string,
): StopDiscoveryResponse {
  const record = exactRecord(value, ['sessionId', 'clientMessageId', 'outcome']);
  const sessionId = ulidValue(record['sessionId']);
  const clientMessageId = ulidValue(record['clientMessageId']);
  const outcome = literalValue(
    record['outcome'],
    ['NOT_COMMITTED', 'STOPPED', 'COMPLETED', 'TERMINAL'] as const,
  );
  if (sessionId !== expectedSessionId || clientMessageId !== expectedClientMessageId) {
    throw new DiscoveryContractError();
  }
  return { sessionId, clientMessageId, outcome };
}

function parseHistoryMessage(value: unknown): DiscoveryHistoryMessage {
  const record = exactRecord(value, [
    'id', 'role', 'body', 'clientMessageId', 'resolutionType', 'result',
    'responseFailure', 'responseRetry', 'createdAt',
  ]);
  const role = literalValue(record['role'], ['USER', 'ASSISTANT'] as const);
  const clientMessageId = record['clientMessageId'] === null
    ? null
    : ulidValue(record['clientMessageId']);
  const resolution = record['resolutionType'] === null
    ? null
    : literalValue(record['resolutionType'], RESOLUTIONS);
  const result = record['result'] === null ? null : parseTurnResult(record['result']);
  const responseFailure = record['responseFailure'] === null
    ? null
    : parseTurnResult(record['responseFailure']);
  const responseRetry = record['responseRetry'] === null
    ? null
    : parseResponseRetry(record['responseRetry']);
  const body = multilineText(record['body'], 12_000);
  if (
    (role === 'USER' && (
      clientMessageId === null || resolution !== null || result !== null
    ))
    || (role === 'ASSISTANT' && (
      clientMessageId !== null || resolution === null || result === null
    ))
    || (role === 'ASSISTANT' && (
      (resolution === 'PARTIAL'
        ? result!.outcome !== 'HANDOFF'
        : resolution !== resolutionForOutcome(result!.outcome))
      || body !== result!.message
    ))
    || (responseFailure !== null && (
      role !== 'USER'
      || responseFailure.outcome !== 'HANDOFF'
      || responseFailure.message.length < 1
    ))
    || (responseRetry !== null && role === 'USER' && responseFailure === null)
    || (responseRetry !== null && role === 'ASSISTANT' && result?.outcome !== 'HANDOFF')
  ) {
    throw new DiscoveryContractError();
  }
  return {
    id: ulidValue(record['id']),
    role,
    body,
    clientMessageId,
    resolutionType: resolution,
    result,
    responseFailure,
    responseRetry,
    createdAt: timestampValue(record['createdAt']),
  };
}

function parseResponseRetry(value: unknown): {
  invocationId: string;
  userMessageId: string;
} {
  const record = exactRecord(value, ['invocationId', 'userMessageId']);
  return {
    invocationId: ulidValue(record['invocationId']),
    userMessageId: ulidValue(record['userMessageId']),
  };
}

function parseTurnResult(value: unknown): DiscoveryTurnResult {
  const candidate = objectValue(value);
  const includesIntent = Object.prototype.hasOwnProperty.call(candidate, 'intent');
  const includesSearchOutcome = Object.prototype.hasOwnProperty.call(candidate, 'searchOutcome');
  const fields = [
    'outcome', 'message', 'questions', 'recommendations', 'preferenceState',
    'inputTokens', 'outputTokens', 'estimatedCost',
  ];
  if (includesIntent) {
    fields.splice(1, 0, 'intent');
  }
  if (includesSearchOutcome) {
    fields.push('searchOutcome');
  }
  const record = exactRecord(value, fields);
  const outcome = literalValue(record['outcome'], OUTCOMES);
  const intent = includesIntent
    ? (record['intent'] === null ? null : literalValue(record['intent'], INTENTS))
    : null;
  const questions = arrayValue(record['questions'], 2)
    .map(question => safeText(question, 240));
  const recommendations = arrayValue(record['recommendations'], 5)
    .map(parseRecommendation);
  const searchOutcome = includesSearchOutcome
    ? (record['searchOutcome'] === null ? null : parseSearchOutcome(record['searchOutcome']))
    : null;
  if (
    (['ASK_CLARIFY', 'CLARIFY'].includes(outcome)
      && (questions.length < 1 || recommendations.length > 0))
    || (outcome === 'RECOMMEND' && (recommendations.length < 3 || questions.length > 0))
    || (outcome === 'COMPARE' && (
      recommendations.length < 2 || questions.length > 0
    ))
    || (outcome === 'DETAIL' && (
      recommendations.length !== 1 || questions.length > 0
    ))
    || (outcome === 'SEARCH' && questions.length > 0)
    || (!['ASK_CLARIFY', 'CLARIFY', 'SEARCH', 'RECOMMEND', 'COMPARE', 'DETAIL'].includes(outcome)
      && (questions.length > 0 || recommendations.length > 0))
  ) {
    throw new DiscoveryContractError();
  }
  if (new Set(recommendations.map(item => item.listingId)).size !== recommendations.length) {
    throw new DiscoveryContractError();
  }
  if (
    recommendations.length > 0
    && intent !== null
    && !['MARKETPLACE_DISCOVERY', 'LISTING_QUESTION'].includes(intent)
  ) {
    throw new DiscoveryContractError();
  }
  if (
    outcome === 'SEARCH'
    && !['MARKETPLACE_DISCOVERY', 'LISTING_QUESTION'].includes(intent ?? '')
  ) {
    throw new DiscoveryContractError();
  }
  if (
    searchOutcome !== null
    && (
      intent !== 'MARKETPLACE_DISCOVERY'
      || (searchOutcome.reason !== 'RESULTS_AVAILABLE' && recommendations.length > 0)
      || (searchOutcome.mode === 'AVAILABILITY_PROBE' && recommendations.length > 0)
    )
  ) {
    throw new DiscoveryContractError();
  }
  return {
    outcome,
    intent,
    message: multilineText(record['message'], 800),
    questions,
    recommendations,
    preferenceState: parsePreferenceState(record['preferenceState']),
    searchOutcome,
    inputTokens: integerValue(record['inputTokens'], 0, 8_000),
    outputTokens: integerValue(record['outputTokens'], 0, 800),
    estimatedCost: decimalString(record['estimatedCost']),
  };
}

function parsePreferenceState(value: unknown): DiscoveryPreferenceState {
  const candidate = objectValue(value);
  const includesDiscoveryState = Object.prototype.hasOwnProperty.call(candidate, 'status');
  const includesControlledContext = Object.prototype.hasOwnProperty.call(candidate, 'activeGoal');
  const includesRecentObservations = Object.prototype.hasOwnProperty.call(
    candidate, 'recentObservations',
  );
  const fields = [
    'query', 'categoryId', 'condition', 'minPrice', 'maxPrice', 'city', 'county',
    'selectedListingId',
  ];
  if (includesDiscoveryState) {
    fields.push(
      'status', 'requestedCategory', 'categoryAvailability', 'categoryInventoryCount',
      'clarificationsAsked', 'lastSearchOutcome',
    );
  }
  if (includesControlledContext) {
    fields.push(
      'activeGoal', 'activeCategory', 'workflowStatus', 'referencedListings',
      'lastToolActions',
    );
    if (includesRecentObservations) {
      fields.push('recentObservations');
    }
  }
  const record = exactRecord(value, fields);
  const toolActions = includesControlledContext
    ? arrayValue(record['lastToolActions'], 5).map(value => {
      const action = exactRecord(value, ['action', 'tool', 'result']);
      return {
        action: literalValue(action['action'], [
          'CHECK_AVAILABILITY', 'SEARCH_LISTINGS', 'GET_LISTING_DETAILS',
        ] as const),
        tool: literalValue(action['tool'], [
          'CHECK_AVAILABILITY', 'SEARCH_INDIVIDUAL', 'GET_LISTING',
        ] as const),
        result: literalValue(action['result'], [
          'AVAILABLE', 'UNAVAILABLE', 'RESULTS_AVAILABLE', 'NO_RESULTS',
          'VERIFIED', 'NOT_FOUND', 'TEMPORARY_FAILURE',
        ] as const),
      };
    })
    : [];
  const recentObservations = includesControlledContext && includesRecentObservations
    ? arrayValue(record['recentObservations'], 5).map(value => {
      const observation = exactRecord(value, [
        'tool', 'normalizedQuery', 'filterCategories', 'observedAt', 'result', 'freshness',
      ]);
      return {
        tool: literalValue(observation['tool'], [
          'CHECK_AVAILABILITY', 'SEARCH_INDIVIDUAL', 'GET_LISTING',
        ] as const),
        normalizedQuery: safeText(observation['normalizedQuery'], 200),
        filterCategories: arrayValue(observation['filterCategories'], 6).map(item =>
          literalValue(item, [
            'CATEGORY', 'CONDITION', 'MINIMUM_PRICE', 'MAXIMUM_PRICE', 'CITY',
            'COUNTY_OR_REGION',
          ] as const)),
        observedAt: timestampValue(observation['observedAt']),
        result: literalValue(observation['result'], [
          'AVAILABLE', 'UNAVAILABLE', 'RESULTS_AVAILABLE', 'NO_RESULTS',
          'VERIFIED', 'NOT_FOUND', 'TEMPORARY_FAILURE',
        ] as const),
        freshness: literalValue(observation['freshness'], [
          'FRESH', 'POTENTIALLY_STALE',
        ] as const),
      };
    })
    : [];
  return {
    query: nullableSafeText(record['query'], 200),
    categoryId: nullableUlid(record['categoryId']),
    condition: record['condition'] === null
      ? null
      : literalValue(record['condition'], CONDITIONS),
    minPrice: nullableDecimalString(record['minPrice']),
    maxPrice: nullableDecimalString(record['maxPrice']),
    city: nullableSafeText(record['city'], 100),
    county: nullableSafeText(record['county'], 100),
    selectedListingId: nullableUlid(record['selectedListingId']),
    status: includesDiscoveryState
      ? literalValue(record['status'], [
        'IDLE', 'CHECKING_AVAILABILITY', 'COLLECTING_PREFERENCES', 'SEARCHING',
        'PRESENTING_RESULTS', 'NO_INVENTORY', 'PAUSED',
      ] as const)
      : 'IDLE',
    requestedCategory: includesDiscoveryState
      ? nullableSafeText(record['requestedCategory'], 80)
      : null,
    categoryAvailability: includesDiscoveryState
      ? literalValue(record['categoryAvailability'], ['UNKNOWN', 'AVAILABLE', 'UNAVAILABLE'] as const)
      : 'UNKNOWN',
    categoryInventoryCount: includesDiscoveryState
      ? nullableInteger(record['categoryInventoryCount'], 0)
      : null,
    clarificationsAsked: includesDiscoveryState
      ? integerValue(record['clarificationsAsked'], 0, 2)
      : 0,
    lastSearchOutcome: includesDiscoveryState && record['lastSearchOutcome'] !== null
      ? literalValue(record['lastSearchOutcome'], [
        'CATEGORY_UNAVAILABLE', 'FILTERS_TOO_STRICT', 'TEMPORARY_SEARCH_FAILURE',
        'SEARCH_UNAVAILABLE', 'RESULTS_AVAILABLE',
      ] as const)
      : null,
    activeGoal: includesControlledContext
      ? literalValue(record['activeGoal'], [
        'FIND_PRODUCT', 'RESOLVE_SUPPORT_ISSUE', 'LEARN_POLICY', 'NONE',
      ] as const)
      : 'NONE',
    activeCategory: includesControlledContext
      ? nullableSafeText(record['activeCategory'], 80)
      : null,
    workflowStatus: includesControlledContext
      ? literalValue(record['workflowStatus'], [
        'IDLE', 'PROBING', 'CLARIFYING', 'SEARCHING', 'PRESENTING', 'PAUSED', 'COMPLETE',
      ] as const)
      : 'IDLE',
    referencedListings: includesControlledContext
      ? arrayValue(record['referencedListings'], 5).map(ulidValue)
      : [],
    lastToolActions: toolActions,
    recentObservations,
  };
}

function parseSearchOutcome(value: unknown): DiscoverySearchOutcome {
  const record = exactRecord(value, [
    'mode', 'searchExecuted', 'category', 'totalActiveCategoryInventory',
    'exactMatchCount', 'appliedFilters', 'relaxableFilters', 'reason', 'retryable',
  ]);
  const filterValues = [
    'CATEGORY', 'CONDITION', 'MINIMUM_PRICE', 'MAXIMUM_PRICE', 'CITY',
    'COUNTY_OR_REGION',
  ] as const;
  const appliedFilters = arrayValue(record['appliedFilters'], 6)
    .map(item => literalValue(item, filterValues));
  const relaxableFilters = arrayValue(record['relaxableFilters'], 2)
    .map(item => literalValue(item, filterValues));
  const result: DiscoverySearchOutcome = {
    mode: literalValue(record['mode'], ['AVAILABILITY_PROBE', 'FULL_DISCOVERY_SEARCH'] as const),
    searchExecuted: booleanValue(record['searchExecuted']),
    category: safeText(record['category'], 80),
    totalActiveCategoryInventory: nullableInteger(record['totalActiveCategoryInventory'], 0),
    exactMatchCount: nullableInteger(record['exactMatchCount'], 0, 20),
    appliedFilters,
    relaxableFilters,
    reason: literalValue(record['reason'], [
      'CATEGORY_UNAVAILABLE', 'FILTERS_TOO_STRICT', 'TEMPORARY_SEARCH_FAILURE',
      'SEARCH_UNAVAILABLE', 'RESULTS_AVAILABLE',
    ] as const),
    retryable: booleanValue(record['retryable']),
  };
  if (
    new Set(appliedFilters).size !== appliedFilters.length
    || new Set(relaxableFilters).size !== relaxableFilters.length
    || relaxableFilters.some(item => !appliedFilters.includes(item))
    || (result.reason === 'CATEGORY_UNAVAILABLE'
      && (result.totalActiveCategoryInventory !== 0 || result.retryable))
    || (result.reason === 'FILTERS_TOO_STRICT'
      && (result.mode !== 'FULL_DISCOVERY_SEARCH'
        || result.totalActiveCategoryInventory === null
        || result.totalActiveCategoryInventory < 1
        || result.exactMatchCount !== 0
        || relaxableFilters.length < 1
        || result.retryable))
    || (['TEMPORARY_SEARCH_FAILURE', 'SEARCH_UNAVAILABLE'].includes(result.reason)
      && (!result.searchExecuted || !result.retryable))
    || (result.reason === 'RESULTS_AVAILABLE'
      && ((result.totalActiveCategoryInventory === null
          || result.totalActiveCategoryInventory < 1)
        && (result.exactMatchCount === null || result.exactMatchCount < 1)
        || result.retryable))
  ) {
    throw new DiscoveryContractError();
  }
  return result;
}

function parseRecommendation(value: unknown): DiscoveryRecommendation {
  const record = exactRecord(value, [
    'listingId', 'title', 'categoryId', 'categoryName', 'condition',
    'priceAmount', 'currency', 'publicCity', 'publicRegion', 'matchReason',
    'thumbnailUrl', 'sellerType', 'constraintCoverage', 'provenance',
  ]);
  const listingId = ulidValue(record['listingId']);
  const provenance = parseProvenance(record['provenance']);
  if (provenance.listingId !== listingId) {
    throw new DiscoveryContractError();
  }
  return {
    listingId,
    title: safeText(record['title'], 180),
    categoryId: ulidValue(record['categoryId']),
    categoryName: safeText(record['categoryName'], 180),
    condition: literalValue(record['condition'], CONDITIONS),
    priceAmount: decimalString(record['priceAmount']),
    currency: patternString(record['currency'], CURRENCY_PATTERN),
    publicCity: nullableSafeText(record['publicCity'], 100),
    publicRegion: nullableSafeText(record['publicRegion'], 100),
    thumbnailUrl: record['thumbnailUrl'] === null
      ? null
      : patternString(record['thumbnailUrl'], PUBLIC_LISTING_MEDIA_PATTERN),
    sellerType: literalValue(record['sellerType'], ['INDIVIDUAL'] as const),
    matchReason: safeText(record['matchReason'], 300),
    constraintCoverage: uniqueConstraintCoverage(record['constraintCoverage']),
    provenance,
  };
}

function uniqueConstraintCoverage(value: unknown): DiscoveryConstraintCoverage[] {
  const coverage = arrayValue(value, CONSTRAINT_COVERAGE.length)
    .map(item => literalValue(item, CONSTRAINT_COVERAGE));
  if (new Set(coverage).size !== coverage.length) {
    throw new DiscoveryContractError();
  }
  return coverage;
}

function resolutionForOutcome(outcome: DiscoveryOutcome): DiscoveryResolution {
  switch (outcome) {
    case 'ANSWER':
    case 'ACTION_REQUIRED':
      return 'ANSWERED';
    case 'CLARIFY':
    case 'ASK_CLARIFY':
      return 'CLARIFY';
    case 'SEARCH':
    case 'RECOMMEND':
      return 'RECOMMEND';
    case 'COMPARE':
    case 'DETAIL':
      return 'ANSWERED';
    case 'NO_RESULTS':
      return 'NO_RESULTS';
    case 'REFUSE':
    case 'REFUSED':
      return 'REFUSED';
    case 'HANDOFF':
      return 'HANDOFF';
  }
}

function parseDiscoveryExclusion(value: unknown): DiscoveryExclusion {
  const record = exactRecord(value, ['listingId', 'reasonCode', 'excludedAt']);
  return {
    listingId: ulidValue(record['listingId']),
    reasonCode: nullableExclusionReason(record['reasonCode']),
    excludedAt: timestampValue(record['excludedAt']),
  };
}

function nullableExclusionReason(value: unknown): DiscoveryExclusionReason | null {
  return value === null ? null : literalValue(value, EXCLUSION_REASONS);
}

function parseProvenance(value: unknown): DiscoveryProvenance {
  const record = exactRecord(value, ['listingId', 'checkedAt', 'responseHash']);
  return {
    listingId: ulidValue(record['listingId']),
    checkedAt: timestampValue(record['checkedAt']),
    responseHash: patternString(record['responseHash'], HASH_PATTERN),
  };
}

function exactRecord(value: unknown, keys: readonly string[]): Record<string, unknown> {
  const record = objectValue(value);
  const actual = Object.keys(record).sort();
  const expected = [...keys].sort();
  if (
    actual.length !== expected.length
    || actual.some((key, index) => key !== expected[index])
  ) {
    throw new DiscoveryContractError();
  }
  return record;
}

function objectValue(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new DiscoveryContractError();
  }
  return value as Record<string, unknown>;
}

function arrayValue(value: unknown, maximum: number): unknown[] {
  if (!Array.isArray(value) || value.length > maximum) {
    throw new DiscoveryContractError();
  }
  return value;
}

function safeText(value: unknown, maximum: number): string {
  if (
    typeof value !== 'string'
    || value.length < 1
    || value.length > maximum
    || CONTROL_PATTERN.test(value)
  ) {
    throw new DiscoveryContractError();
  }
  return value;
}

/** Accepts the same newline/tab subset as the guarded Agent answer contract. */
function multilineText(value: unknown, maximum: number): string {
  if (
    typeof value !== 'string'
    || value.length < 1
    || value.length > maximum
    || /[\u0000-\u0008\u000b-\u001f\u007f]/.test(value)
  ) {
    throw new DiscoveryContractError();
  }
  return value;
}

function boundedString(value: string, minimum: number, maximum: number): string {
  if (
    typeof value !== 'string'
    || value.length < minimum
    || value.length > maximum
    || CONTROL_PATTERN.test(value)
  ) {
    throw new DiscoveryContractError();
  }
  return value;
}

function nullableSafeText(value: unknown, maximum: number): string | null {
  return value === null ? null : safeText(value, maximum);
}

function ulidValue(value: unknown): string {
  return patternString(value, ULID_PATTERN);
}

function trustedUlid(value: string): string {
  return ulidValue(value);
}

function nullableUlid(value: unknown): string | null {
  return value === null ? null : ulidValue(value);
}

function patternString(value: unknown, pattern: RegExp): string {
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new DiscoveryContractError();
  }
  return value;
}

function nullableBoundedString(value: unknown, maximum: number): string | null {
  return value === null ? null : safeText(value, maximum);
}

function integerValue(value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new DiscoveryContractError();
  }
  return value as number;
}

function nullableInteger(
  value: unknown,
  minimum: number,
  maximum = Number.MAX_SAFE_INTEGER,
): number | null {
  return value === null ? null : integerValue(value, minimum, maximum);
}

function booleanValue(value: unknown): boolean {
  if (typeof value !== 'boolean') {
    throw new DiscoveryContractError();
  }
  return value;
}

function timestampValue(value: unknown): string {
  const text = safeText(value, 64);
  if (
    !/(?:Z|[+-]\d{2}:\d{2})$/.test(text)
    || Number.isNaN(Date.parse(text))
  ) {
    throw new DiscoveryContractError();
  }
  return text;
}

function decimalString(value: unknown): string {
  if (
    typeof value !== 'string'
    || !/^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/.test(value)
  ) {
    throw new DiscoveryContractError();
  }
  return value;
}

function nullableDecimalString(value: unknown): string | null {
  return value === null ? null : decimalString(value);
}

function literalValue<const T extends readonly string[]>(
  value: unknown,
  allowed: T,
): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new DiscoveryContractError();
  }
  return value as T[number];
}

/** Incrementally decodes strict two-line SSE frames without exposing loose event data. */
export class DiscoverySseParser {
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private buffer = '';
  private expectedSequence = 1;

  push(chunk: Uint8Array): DiscoveryStreamEvent[] {
    this.buffer += this.decoder.decode(chunk, { stream: true });
    return this.readFrames(false);
  }

  finish(): DiscoveryStreamEvent[] {
    this.buffer += this.decoder.decode();
    return this.readFrames(true);
  }

  private readFrames(final: boolean): DiscoveryStreamEvent[] {
    if (this.buffer.length > 65_536) {
      throw new DiscoveryContractError('BUFFER_LIMIT');
    }
    const events: DiscoveryStreamEvent[] = [];
    while (true) {
      const separator = /\r?\n\r?\n/.exec(this.buffer);
      if (!separator || separator.index === undefined) {
        break;
      }
      const frame = this.buffer.slice(0, separator.index);
      this.buffer = this.buffer.slice(separator.index + separator[0].length);
      if (!this.isCommentFrame(frame)) {
        events.push(this.parseFrame(frame));
      }
    }
    if (final && this.buffer.length !== 0) {
      throw new DiscoveryContractError('INCOMPLETE_FRAME');
    }
    return events;
  }

  /** Ignores only standards-valid SSE comment blocks without relaxing event fields. */
  private isCommentFrame(frame: string): boolean {
    return frame.length > 0
      && frame.split(/\r?\n/).every(line => line.startsWith(':'));
  }

  private parseFrame(frame: string): DiscoveryStreamEvent {
    const lines = frame.split(/\r?\n/);
    if (
      lines.length !== 2
      || !lines[0].startsWith('event: ')
      || !lines[1].startsWith('data: ')
    ) {
      throw new DiscoveryContractError('FRAME_SHAPE');
    }
    const eventName = lines[0].slice(7);
    let value: unknown;
    try {
      value = JSON.parse(lines[1].slice(6));
    } catch {
      throw new DiscoveryContractError('FRAME_JSON');
    }
    const base = objectValue(value);
    if (base['schemaVersion'] !== STREAM_SCHEMA_VERSION) {
      throw new DiscoveryContractError('SCHEMA_VERSION');
    }
    if (base['sequence'] !== this.expectedSequence) {
      throw new DiscoveryContractError('SEQUENCE');
    }
    if (base['type'] !== eventName) {
      throw new DiscoveryContractError('EVENT_TYPE');
    }
    this.expectedSequence += 1;
    const sequence = integerValue(base['sequence'], 1);
    switch (eventName) {
      case 'activity': {
        const record = exactRecord(base, ['schemaVersion', 'sequence', 'type', 'stage', 'label']);
        const stage = literalValue(record['stage'], STREAM_STAGES) as DiscoveryProgressStage;
        const label = safeText(record['label'], 80);
        const expectedLabel: Record<DiscoveryProgressStage, string> = {
          MESSAGE_ACCEPTED: 'Request accepted',
          UNDERSTANDING: 'Understanding your request',
          CHECKING_AVAILABILITY: 'Checking current availability',
          SEARCHING: 'Searching current public listings',
          CHECKING: 'Checking price and availability',
          COMPOSING: 'Preparing your answer',
        };
        if (label !== expectedLabel[stage]) {
          throw new DiscoveryContractError();
        }
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'activity', stage, label,
        };
      }
      case 'text_delta': {
        const record = exactRecord(base, ['schemaVersion', 'sequence', 'type', 'delta']);
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'text_delta',
          delta: streamedText(record['delta'], 256),
        };
      }
      case 'recommendations': {
        const record = exactRecord(base, ['schemaVersion', 'sequence', 'type', 'items']);
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'recommendations',
          items: arrayValue(record['items'], 5).map(parseRecommendation),
        };
      }
      case 'metadata': {
        const record = exactRecord(base, [
          'schemaVersion', 'sequence', 'type', 'citations', 'provenance',
        ]);
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'metadata',
          citations: arrayValue(record['citations'], 5).map(item => safeText(item, 1_000)),
          provenance: arrayValue(record['provenance'], 5).map(parseProvenance),
        };
      }
      case 'done': {
        const record = exactRecord(base, [
          'schemaVersion', 'sequence', 'type', 'messageId', 'response',
        ]);
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'done',
          messageId: ulidValue(record['messageId']),
          response: parseSendDiscoveryMessageResponse(record['response']),
        };
      }
      case 'error': {
        const record = exactRecord(base, [
          'schemaVersion', 'sequence', 'type', 'code', 'message', 'retryable',
        ]);
        if (typeof record['retryable'] !== 'boolean') {
          throw new DiscoveryContractError();
        }
        return {
          schemaVersion: STREAM_SCHEMA_VERSION,
          sequence,
          type: 'error',
          code: literalValue(record['code'], STREAM_FAILURE_CODES),
          message: safeText(record['message'], 200),
          retryable: record['retryable'],
        };
      }
      default:
        throw new DiscoveryContractError();
    }
  }
}

function streamedText(value: unknown, maximumLength: number): string {
  if (
    typeof value !== 'string'
    || value.length < 1
    || value.length > maximumLength
    || /[\u0000-\u0008\u000b-\u001f\u007f]/.test(value)
  ) {
    throw new DiscoveryContractError();
  }
  return value;
}
