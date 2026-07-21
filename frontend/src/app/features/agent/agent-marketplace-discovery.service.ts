import { HttpClient } from '@angular/common/http';
import { Inject, Injectable } from '@angular/core';
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
  DiscoveryOutcome,
  DiscoveryPreferenceState,
  DiscoveryProvenance,
  DiscoveryRecommendation,
  DiscoveryResolution,
  DiscoverySession,
  DiscoveryTurnResult,
  SendDiscoveryMessageResponse,
} from './agent-marketplace-discovery.model';

const ULID_PATTERN = /^[0-9A-Z]{26}$/;
const HASH_PATTERN = /^[0-9a-f]{64}$/;
const CURRENCY_PATTERN = /^[A-Z]{3}$/;
const CONTROL_PATTERN = /[\u0000-\u001f\u007f]/;
const SESSION_STATUSES = ['OPEN', 'CLOSED'] as const;
const OUTCOMES = ['ASK_CLARIFY', 'RECOMMEND', 'NO_RESULTS', 'REFUSE', 'HANDOFF'] as const;
const RESOLUTIONS = ['CLARIFY', 'RECOMMEND', 'NO_RESULTS', 'REFUSED', 'HANDOFF'] as const;
const CONDITIONS = ['NEW', 'LIKE_NEW', 'GOOD', 'FAIR', 'POOR'] as const;
const CONSTRAINT_COVERAGE = [
  'QUERY',
  'CATEGORY',
  'CONDITION',
  'PRICE',
  'CITY',
  'COUNTY_OR_REGION',
] as const;

export class DiscoveryContractError extends Error {
  constructor() {
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

  constructor(
    private readonly http: HttpClient,
    private readonly authService: AuthService,
    @Inject(AGENT_DISCOVERY_ENABLED) private readonly enabled: boolean,
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
        { withCredentials: true },
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
        { withCredentials: true },
      )),
      map(parseSendDiscoveryMessageResponse),
      finalize(() => this.messageSends.delete(operationKey)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.messageSends.set(operationKey, { fingerprint, request });
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

  private featureDisabled<T>(): Observable<T> {
    return throwError(() => new DiscoveryFeatureDisabledError());
  }
}

export function parseDiscoverySession(value: unknown): DiscoverySession {
  const record = exactRecord(value, [
    'id', 'sessionType', 'status', 'preferenceState', 'preferenceVersion',
    'clarificationTurnCount', 'clarificationQuestionCount', 'createdAt', 'updatedAt',
  ]);
  return {
    id: ulidValue(record['id']),
    sessionType: literalValue(record['sessionType'], ['MARKETPLACE_DISCOVERY'] as const),
    status: literalValue(record['status'], SESSION_STATUSES),
    preferenceState: parsePreferenceState(record['preferenceState']),
    preferenceVersion: integerValue(record['preferenceVersion'], 0),
    clarificationTurnCount: integerValue(record['clarificationTurnCount'], 0, 3),
    clarificationQuestionCount: integerValue(record['clarificationQuestionCount'], 0, 5),
    createdAt: timestampValue(record['createdAt']),
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
      body: safeText(user['body'], 8_000),
      createdAt: timestampValue(user['createdAt']),
    },
    result: parseTurnResult(record['result']),
    preferenceVersion: integerValue(record['preferenceVersion'], 0),
  };
}

function parseHistoryMessage(value: unknown): DiscoveryHistoryMessage {
  const record = exactRecord(value, [
    'id', 'role', 'body', 'resolutionType', 'result', 'createdAt',
  ]);
  const role = literalValue(record['role'], ['USER', 'ASSISTANT'] as const);
  const resolution = record['resolutionType'] === null
    ? null
    : literalValue(record['resolutionType'], RESOLUTIONS);
  const result = record['result'] === null ? null : parseTurnResult(record['result']);
  const body = safeText(record['body'], 12_000);
  if (
    (role === 'USER' && (resolution !== null || result !== null))
    || (role === 'ASSISTANT' && (resolution === null || result === null))
    || (role === 'ASSISTANT' && (
      resolution !== resolutionForOutcome(result!.outcome)
      || body !== result!.message
    ))
  ) {
    throw new DiscoveryContractError();
  }
  return {
    id: ulidValue(record['id']),
    role,
    body,
    resolutionType: resolution,
    result,
    createdAt: timestampValue(record['createdAt']),
  };
}

function parseTurnResult(value: unknown): DiscoveryTurnResult {
  const record = exactRecord(value, [
    'outcome', 'message', 'questions', 'recommendations', 'preferenceState',
    'inputTokens', 'outputTokens', 'estimatedCost',
  ]);
  const outcome = literalValue(record['outcome'], OUTCOMES);
  const questions = arrayValue(record['questions'], 2)
    .map(question => safeText(question, 240));
  const recommendations = arrayValue(record['recommendations'], 5)
    .map(parseRecommendation);
  if (
    (outcome === 'ASK_CLARIFY' && (questions.length < 1 || recommendations.length > 0))
    || (outcome === 'RECOMMEND' && (recommendations.length < 3 || questions.length > 0))
    || (!['ASK_CLARIFY', 'RECOMMEND'].includes(outcome)
      && (questions.length > 0 || recommendations.length > 0))
  ) {
    throw new DiscoveryContractError();
  }
  if (new Set(recommendations.map(item => item.listingId)).size !== recommendations.length) {
    throw new DiscoveryContractError();
  }
  return {
    outcome,
    message: safeText(record['message'], 800),
    questions,
    recommendations,
    preferenceState: parsePreferenceState(record['preferenceState']),
    inputTokens: integerValue(record['inputTokens'], 0, 8_000),
    outputTokens: integerValue(record['outputTokens'], 0, 800),
    estimatedCost: decimalString(record['estimatedCost']),
  };
}

function parsePreferenceState(value: unknown): DiscoveryPreferenceState {
  const record = exactRecord(value, [
    'query', 'categoryId', 'condition', 'minPrice', 'maxPrice', 'city', 'county',
  ]);
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
  };
}

function parseRecommendation(value: unknown): DiscoveryRecommendation {
  const record = exactRecord(value, [
    'listingId', 'title', 'categoryId', 'categoryName', 'condition',
    'priceAmount', 'currency', 'publicCity', 'publicRegion', 'matchReason',
    'constraintCoverage', 'provenance',
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
    case 'ASK_CLARIFY':
      return 'CLARIFY';
    case 'RECOMMEND':
      return 'RECOMMEND';
    case 'NO_RESULTS':
      return 'NO_RESULTS';
    case 'REFUSE':
      return 'REFUSED';
    case 'HANDOFF':
      return 'HANDOFF';
  }
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
