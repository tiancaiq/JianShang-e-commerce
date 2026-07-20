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
import { AGENT_CUSTOMER_SERVICE_ENABLED } from './agent-customer-service.capability';
import {
  AgentAnswerAction,
  AgentAnswerSource,
  AgentMessage,
  AgentMessagePage,
  AgentSession,
  CreateAgentSessionRequest,
  SendAgentMessageResponse,
} from './agent-customer-service.model';

const SESSION_TYPES = ['LISTING_CUSTOMER_SERVICE'] as const;
const SESSION_STATUSES = ['OPEN', 'READ_ONLY', 'CLOSED'] as const;
const MESSAGE_ROLES = ['USER', 'ASSISTANT'] as const;
const RESOLUTION_TYPES = ['ANSWERED', 'PARTIAL', 'UNKNOWN', 'CONTACT_SELLER', 'REFUSED'] as const;
const SOURCE_TYPES = [
  'LISTING',
  'MARKETPLACE_POLICY',
  'SAFETY_GUIDANCE',
  'MARKETPLACE_FAQ',
  'CATEGORY_GUIDANCE',
] as const;
const ACTION_TYPES = ['MESSAGE_SELLER', 'VIEW_LISTING', 'BROWSE_MARKETPLACE'] as const;

export class AgentContractError extends Error {
  constructor() {
    super('Agent Service returned an invalid response.');
    this.name = 'AgentContractError';
  }
}

export class AgentAuthenticationRequiredError extends Error {
  constructor() {
    super('An authenticated marketplace session is required.');
    this.name = 'AgentAuthenticationRequiredError';
  }
}

export class AgentFeatureDisabledError extends Error {
  constructor() {
    super('The marketplace AI assistant is disabled.');
    this.name = 'AgentFeatureDisabledError';
  }
}

@Injectable({ providedIn: 'root' })
export class AgentCustomerService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/agent`;
  private sessionWarmup: Observable<void> | null = null;
  private readonly sessionCreates = new Map<string, Observable<AgentSession>>();
  private readonly messageSends = new Map<string, Observable<SendAgentMessageResponse>>();

  constructor(
    private readonly http: HttpClient,
    private readonly authService: AuthService,
    @Inject(AGENT_CUSTOMER_SERVICE_ENABLED) private readonly enabled: boolean,
  ) {}

  /** Creates or resumes a listing-bound session without sending browser actor fields. */
  createOrResumeSession(listingId: string): Observable<AgentSession> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const existing = this.sessionCreates.get(listingId);
    if (existing) {
      return existing;
    }
    const body: CreateAgentSessionRequest = {
      sessionType: 'LISTING_CUSTOMER_SERVICE',
      subject: { type: 'LISTING', id: listingId },
    };
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(`${this.baseUrl}/sessions`, body, {
        withCredentials: true,
      })),
      map(parseSession),
      finalize(() => this.sessionCreates.delete(listingId)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.sessionCreates.set(listingId, request);
    return request;
  }

  getSession(sessionId: string): Observable<AgentSession> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    return this.http.get<unknown>(`${this.baseUrl}/sessions/${sessionId}`, {
      withCredentials: true,
    }).pipe(map(parseSession));
  }

  getMessages(sessionId: string, cursor?: string | null, limit = 50): Observable<AgentMessagePage> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const params: Record<string, string> = { limit: String(limit) };
    if (cursor) {
      params['cursor'] = cursor;
    }
    return this.http.get<unknown>(`${this.baseUrl}/sessions/${sessionId}/messages`, {
      params,
      withCredentials: true,
    }).pipe(map(parseMessagePage));
  }

  /** Sends one idempotent question; retries reuse the caller-generated clientMessageId. */
  sendMessage(
    sessionId: string,
    clientMessageId: string,
    body: string,
  ): Observable<SendAgentMessageResponse> {
    if (!this.enabled) {
      return this.featureDisabled();
    }
    const operationKey = `${sessionId}:${clientMessageId}`;
    const existing = this.messageSends.get(operationKey);
    if (existing) {
      return existing;
    }
    const request = this.requireAuthenticatedSession().pipe(
      switchMap(() => this.http.post<unknown>(
        `${this.baseUrl}/sessions/${sessionId}/messages`,
        { clientMessageId, body },
        { withCredentials: true },
      )),
      map(parseSendResponse),
      finalize(() => this.messageSends.delete(operationKey)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.messageSends.set(operationKey, request);
    return request;
  }

  /** Waits for the BFF-owned authenticated session and CSRF state before Agent writes. */
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
            throw new AgentAuthenticationRequiredError();
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

  /** Refreshes only the inconsistent signed-in-without-CSRF state through AuthService. */
  private refreshMissingCsrf(state: AuthState): Observable<AuthState> {
    if (state.authenticated && !this.authService.csrf()) {
      return this.authService.refreshSession().pipe(take(1));
    }
    return of(state);
  }

  private featureDisabled<T>(): Observable<T> {
    return throwError(() => new AgentFeatureDisabledError());
  }
}

function parseSession(value: unknown): AgentSession {
  const record = exactRecord(value, [
    'id', 'sessionType', 'status', 'subjectListing', 'createdAt', 'updatedAt',
  ]);
  const listing = exactRecord(record['subjectListing'], [
    'id', 'version', 'title', 'thumbnailUrl', 'transactionNotice',
  ]);
  return {
    id: stringValue(record['id']),
    sessionType: literalValue(record['sessionType'], SESSION_TYPES),
    status: literalValue(record['status'], SESSION_STATUSES),
    subjectListing: {
      id: stringValue(listing['id']),
      version: stringValue(listing['version']),
      title: stringValue(listing['title']),
      thumbnailUrl: nullableString(listing['thumbnailUrl']),
      transactionNotice: stringValue(listing['transactionNotice']),
    },
    createdAt: stringValue(record['createdAt']),
    updatedAt: stringValue(record['updatedAt']),
  };
}

function parseMessagePage(value: unknown): AgentMessagePage {
  const record = exactRecord(value, ['data', 'nextCursor', 'hasMore']);
  const items = arrayValue(record['data']).map(parseMessage);
  const hasMore = booleanValue(record['hasMore']);
  const nextCursor = nullableString(record['nextCursor']);
  if (hasMore !== (nextCursor !== null)) {
    throw new AgentContractError();
  }
  return { data: items, nextCursor, hasMore };
}

function parseSendResponse(value: unknown): SendAgentMessageResponse {
  const record = exactRecord(value, ['userMessage', 'assistantMessage']);
  const userMessage = parseMessage(record['userMessage']);
  const assistantMessage = parseMessage(record['assistantMessage']);
  if (userMessage.role !== 'USER' || assistantMessage.role !== 'ASSISTANT') {
    throw new AgentContractError();
  }
  return { userMessage, assistantMessage };
}

function parseMessage(value: unknown): AgentMessage {
  const record = exactRecord(value, [
    'id', 'role', 'body', 'resolutionType', 'sources', 'actions', 'createdAt',
  ]);
  const role = literalValue(record['role'], MESSAGE_ROLES);
  const resolutionType = record['resolutionType'] === null
    ? null
    : literalValue(record['resolutionType'], RESOLUTION_TYPES);
  if ((role === 'USER') !== (resolutionType === null)) {
    throw new AgentContractError();
  }
  return {
    id: stringValue(record['id']),
    role,
    body: stringValue(record['body']),
    resolutionType,
    sources: arrayValue(record['sources']).map(parseSource),
    actions: arrayValue(record['actions']).map(parseAction),
    createdAt: stringValue(record['createdAt']),
  };
}

function parseSource(value: unknown): AgentAnswerSource {
  const record = exactRecord(value, ['sourceType', 'sourceId', 'sourceVersion', 'label']);
  return {
    sourceType: literalValue(record['sourceType'], SOURCE_TYPES),
    sourceId: stringValue(record['sourceId']),
    sourceVersion: stringValue(record['sourceVersion']),
    label: stringValue(record['label']),
  };
}

function parseAction(value: unknown): AgentAnswerAction {
  const base = objectValue(value);
  const type = literalValue(base['type'], ACTION_TYPES);
  if (type === 'BROWSE_MARKETPLACE') {
    exactKeys(base, ['type']);
    return { type };
  }
  exactKeys(base, ['type', 'listingId']);
  return { type, listingId: stringValue(base['listingId']) };
}

function exactRecord(value: unknown, keys: readonly string[]): Record<string, unknown> {
  const record = objectValue(value);
  exactKeys(record, keys);
  return record;
}

function exactKeys(record: Record<string, unknown>, keys: readonly string[]): void {
  const actual = Object.keys(record).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    throw new AgentContractError();
  }
}

function objectValue(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AgentContractError();
  }
  return value as Record<string, unknown>;
}

function arrayValue(value: unknown): unknown[] {
  if (!Array.isArray(value)) {
    throw new AgentContractError();
  }
  return value;
}

function stringValue(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new AgentContractError();
  }
  return value;
}

function nullableString(value: unknown): string | null {
  return value === null ? null : stringValue(value);
}

function booleanValue(value: unknown): boolean {
  if (typeof value !== 'boolean') {
    throw new AgentContractError();
  }
  return value;
}

function literalValue<const T extends readonly string[]>(value: unknown, allowed: T): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new AgentContractError();
  }
  return value as T[number];
}
