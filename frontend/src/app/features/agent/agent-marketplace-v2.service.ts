import { Inject, Injectable, InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/services/auth.service';
import {
  MarketplaceAgentV2HistoryPage,
  MarketplaceAgentV2ListingAttachment,
  MarketplaceAgentV2Session,
  MarketplaceAgentV2StreamEvent,
  SendMarketplaceAgentV2MessageResponse,
  StopMarketplaceAgentV2Response,
} from './agent-marketplace-v2.model';

const SCHEMA = 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1';
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const HASH = /^[0-9a-f]{64}$/;
const PUBLIC_LISTING_MEDIA = /^\/api\/v1\/public\/listing-media\/[0-9A-HJKMNP-TV-Z]{26}$/;
const TOOLS = [
  'check_availability', 'search_listings', 'get_listing', 'request_confirmation',
  'collect_listing_information',
] as const;
const TOOL_STATUSES = ['SUCCEEDED', 'REJECTED', 'FAILED'] as const;
const CONDITIONS = ['NEW', 'OPEN_BOX', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS'] as const;
const EVENT_TYPES = [
  'message_started', 'activity', 'tool_completed', 'text_delta',
  'attachments', 'error', 'done',
] as const;

export type MarketplaceAgentV2Fetch = (
  input: RequestInfo | URL, init?: RequestInit,
) => Promise<Response>;

export const MARKETPLACE_AGENT_V2_FETCH = new InjectionToken<MarketplaceAgentV2Fetch>(
  'MARKETPLACE_AGENT_V2_FETCH',
  { providedIn: 'root', factory: () => globalThis.fetch.bind(globalThis) },
);

export class MarketplaceAgentV2ContractError extends Error {
  constructor() {
    super('Marketplace Agent V2 returned an invalid response.');
    this.name = 'MarketplaceAgentV2ContractError';
  }
}

@Injectable({ providedIn: 'root' })
export class AgentMarketplaceV2Service {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/agent/marketplace-v2`;

  constructor(
    private readonly auth: AuthService,
    @Inject(MARKETPLACE_AGENT_V2_FETCH) private readonly fetchRequest: MarketplaceAgentV2Fetch,
  ) {}

  createSession(newConversation: boolean): Observable<MarketplaceAgentV2Session> {
    return this.jsonRequest(
      `${this.baseUrl}/sessions`, 'POST',
      { sessionType: 'MARKETPLACE_AGENT_V2', newConversation }, parseSession,
    );
  }

  listMessages(sessionId: string): Observable<MarketplaceAgentV2HistoryPage> {
    if (!ULID.test(sessionId)) throw new MarketplaceAgentV2ContractError();
    return this.jsonRequest(
      `${this.baseUrl}/sessions/${sessionId}/messages?limit=100`, 'GET', undefined,
      parseHistory,
    );
  }

  stopMessage(
    sessionId: string, clientMessageId: string,
  ): Observable<StopMarketplaceAgentV2Response> {
    if (!ULID.test(sessionId) || !ULID.test(clientMessageId)) {
      throw new MarketplaceAgentV2ContractError();
    }
    return this.jsonRequest(
      `${this.baseUrl}/sessions/${sessionId}/messages/${clientMessageId}/stop`,
      'POST', {}, parseStop,
    );
  }

  /** Streams one V2 turn without automatic resend or Authorization headers. */
  streamMessage(
    sessionId: string,
    clientMessageId: string,
    body: string,
  ): Observable<MarketplaceAgentV2StreamEvent> {
    if (!ULID.test(sessionId) || !ULID.test(clientMessageId) || !body.trim()) {
      throw new MarketplaceAgentV2ContractError();
    }
    return this.streamRequest(
      `${this.baseUrl}/sessions/${sessionId}/messages/stream`,
      { clientMessageId, body: body.trim() },
    );
  }

  retryResponse(
    sessionId: string, userMessageId: string, clientMessageId: string,
  ): Observable<MarketplaceAgentV2StreamEvent> {
    if (!ULID.test(sessionId) || !ULID.test(userMessageId) || !ULID.test(clientMessageId)) {
      throw new MarketplaceAgentV2ContractError();
    }
    return this.streamRequest(
      `${this.baseUrl}/sessions/${sessionId}/messages/${userMessageId}/response-retry/stream`,
      { clientMessageId },
    );
  }

  private streamRequest(
    url: string, payload: Record<string, unknown>,
  ): Observable<MarketplaceAgentV2StreamEvent> {
    return new Observable(subscriber => {
      const csrf = this.auth.csrf();
      if (!csrf) {
        subscriber.error(new MarketplaceAgentV2ContractError());
        return undefined;
      }
      const abort = new AbortController();
      void (async () => {
        try {
          const response = await this.fetchRequest(
            url,
            {
              method: 'POST', credentials: 'include', signal: abort.signal,
              headers: {
                'Accept': 'text/event-stream', 'Content-Type': 'application/json',
                [csrf.headerName]: csrf.token,
              },
              body: JSON.stringify(payload),
            },
          );
          if (!response.ok
              || !response.headers.get('Content-Type')?.toLowerCase().startsWith('text/event-stream')
              || !response.body) {
            throw new MarketplaceAgentV2ContractError();
          }
          const reader = response.body.getReader();
          const parser = new MarketplaceAgentV2SseParser();
          let terminal = false;
          while (true) {
            const chunk = await reader.read();
            const events = chunk.done ? parser.finish() : parser.push(chunk.value);
            for (const event of events) {
              if (terminal) throw new MarketplaceAgentV2ContractError();
              terminal = event.type === 'done' || event.type === 'error';
              subscriber.next(event);
            }
            if (chunk.done) break;
          }
          if (!terminal) throw new MarketplaceAgentV2ContractError();
          subscriber.complete();
        } catch (error) {
          if (!abort.signal.aborted) subscriber.error(error);
        }
      })();
      return () => abort.abort();
    });
  }

  private jsonRequest<T>(
    url: string,
    method: 'GET' | 'POST',
    payload: Record<string, unknown> | undefined,
    parser: (value: unknown) => T,
  ): Observable<T> {
    return new Observable(subscriber => {
      const csrf = this.auth.csrf();
      if (method === 'POST' && !csrf) {
        subscriber.error(new MarketplaceAgentV2ContractError());
        return undefined;
      }
      const abort = new AbortController();
      void (async () => {
        try {
          const headers: Record<string, string> = { 'Accept': 'application/json' };
          if (method === 'POST' && csrf) {
            headers['Content-Type'] = 'application/json';
            headers[csrf.headerName] = csrf.token;
          }
          const response = await this.fetchRequest(url, {
            method, credentials: 'include', signal: abort.signal, headers,
            body: payload === undefined ? undefined : JSON.stringify(payload),
          });
          if (!response.ok) throw new MarketplaceAgentV2ContractError();
          subscriber.next(parser(await response.json()));
          subscriber.complete();
        } catch (error) {
          if (!abort.signal.aborted) subscriber.error(error);
        }
      })();
      return () => abort.abort();
    });
  }
}

export class MarketplaceAgentV2SseParser {
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private buffer = '';
  private expectedSequence = 1;

  push(chunk: Uint8Array): MarketplaceAgentV2StreamEvent[] {
    try {
      this.buffer += this.decoder.decode(chunk, { stream: true });
      return this.drain(false);
    } catch {
      throw new MarketplaceAgentV2ContractError();
    }
  }

  finish(): MarketplaceAgentV2StreamEvent[] {
    try {
      this.buffer += this.decoder.decode();
    } catch {
      throw new MarketplaceAgentV2ContractError();
    }
    const events = this.drain(true);
    if (this.buffer.length) throw new MarketplaceAgentV2ContractError();
    return events;
  }

  private drain(final: boolean): MarketplaceAgentV2StreamEvent[] {
    const events: MarketplaceAgentV2StreamEvent[] = [];
    while (true) {
      const match = /\r?\n\r?\n/.exec(this.buffer);
      if (!match) break;
      const block = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      if (!block) continue;
      const lines = block.split(/\r?\n/);
      if (lines.length !== 2 || !lines[0].startsWith('event: ') || !lines[1].startsWith('data: ')) {
        throw new MarketplaceAgentV2ContractError();
      }
      const eventName = lines[0].slice(7);
      if (!(EVENT_TYPES as readonly string[]).includes(eventName)) {
        throw new MarketplaceAgentV2ContractError();
      }
      let value: unknown;
      try { value = JSON.parse(lines[1].slice(6)); } catch { throw new MarketplaceAgentV2ContractError(); }
      const event = parseEvent(value, eventName);
      if (event.sequence !== this.expectedSequence++) throw new MarketplaceAgentV2ContractError();
      events.push(event);
    }
    if (final && this.buffer.length) throw new MarketplaceAgentV2ContractError();
    return events;
  }
}

function parseEvent(value: unknown, eventName: string): MarketplaceAgentV2StreamEvent {
  const record = object(value);
  if (record['schemaVersion'] !== SCHEMA || record['type'] !== eventName
      || !Number.isInteger(record['sequence']) || Number(record['sequence']) < 1) {
    throw new MarketplaceAgentV2ContractError();
  }
  if (eventName === 'text_delta') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'delta']);
    if (typeof record['delta'] !== 'string' || !record['delta']) throw new MarketplaceAgentV2ContractError();
  } else if (eventName === 'activity') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'tool', 'label']);
    tool(record['tool']); string(record['label']);
  } else if (eventName === 'tool_completed') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'tool', 'status', 'reason', 'observedAt']);
    tool(record['tool']); string(record['status']); string(record['reason']); date(record['observedAt']);
  } else if (eventName === 'attachments') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'items']);
    if (!Array.isArray(record['items'])) throw new MarketplaceAgentV2ContractError();
    record['items'].forEach(parseAttachment);
  } else if (eventName === 'message_started') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'userMessage']);
    parseUserMessage(record['userMessage']);
  } else if (eventName === 'error') {
    exact(record, ['schemaVersion', 'sequence', 'type', 'code', 'message', 'retryable']);
    string(record['code']); string(record['message']);
    if (typeof record['retryable'] !== 'boolean') throw new MarketplaceAgentV2ContractError();
  } else {
    exact(record, ['schemaVersion', 'sequence', 'type', 'messageId', 'response']);
    if (typeof record['messageId'] !== 'string' || !ULID.test(record['messageId'])) throw new MarketplaceAgentV2ContractError();
    parseResponse(record['response']);
  }
  return record as unknown as MarketplaceAgentV2StreamEvent;
}

function parseResponse(value: unknown): SendMarketplaceAgentV2MessageResponse {
  const record = object(value);
  exact(record, ['sessionId', 'userMessage', 'assistantMessageId', 'message', 'decisionCount']);
  if (!ULID.test(String(record['sessionId'])) || !ULID.test(String(record['assistantMessageId']))
      || !Number.isInteger(record['decisionCount']) || Number(record['decisionCount']) < 0
      || Number(record['decisionCount']) > 5) throw new MarketplaceAgentV2ContractError();
  parseUserMessage(record['userMessage']);
  parseMessage(record['message']);
  return record as unknown as SendMarketplaceAgentV2MessageResponse;
}

function parseMessage(value: unknown): void {
  const message = object(value);
  exact(message, [
    'role', 'content', 'attachments', 'refinement', 'pendingInteraction',
    'citations', 'toolActivity', 'inputTokens', 'outputTokens',
  ]);
  if (message['role'] !== 'ASSISTANT') throw new MarketplaceAgentV2ContractError();
  string(message['content']);
  if (!Array.isArray(message['attachments']) || !Array.isArray(message['citations'])
      || !Array.isArray(message['toolActivity'])) throw new MarketplaceAgentV2ContractError();
  message['attachments'].forEach(parseAttachment);
  if (message['attachments'].length > 8) throw new MarketplaceAgentV2ContractError();
  if (new Set(message['attachments'].map(item => object(item)['listingId'])).size
      !== message['attachments'].length) throw new MarketplaceAgentV2ContractError();
  parseRefinement(message['refinement']);
  parsePendingInteraction(message['pendingInteraction']);
  message['citations'].forEach(string);
  message['toolActivity'].forEach(parseToolActivity);
  if (!Number.isInteger(message['inputTokens']) || Number(message['inputTokens']) < 0
      || !Number.isInteger(message['outputTokens']) || Number(message['outputTokens']) < 0) {
    throw new MarketplaceAgentV2ContractError();
  }
}

function parseRefinement(value: unknown): void {
  if (value === null) return;
  const record = object(value);
  exact(record, ['question', 'options']);
  nullableString(record['question']);
  if (!Array.isArray(record['options']) || record['options'].length < 2
      || record['options'].length > 4) throw new MarketplaceAgentV2ContractError();
  const seen = new Set<string>();
  for (const item of record['options']) {
    const option = object(item);
    exact(option, ['facet', 'value', 'count']);
    if (!['SUBTYPE', 'CONDITION', 'PRICE_BAND', 'LOCATION', 'MATCH_SCOPE', 'MAXIMUM_PRICE']
          .includes(String(option['facet']))
        || !Number.isInteger(option['count']) || Number(option['count']) < 1
        || Number(option['count']) > 80) throw new MarketplaceAgentV2ContractError();
    string(option['value']);
    const identity = `${option['facet']}:${String(option['value']).toLocaleLowerCase()}`;
    if (seen.has(identity)) throw new MarketplaceAgentV2ContractError();
    seen.add(identity);
  }
}

function parsePendingInteraction(value: unknown): void {
  if (value === null) return;
  const record = object(value);
  exact(record, [
    'id', 'type', 'action', 'workflowType', 'field', 'question', 'arguments',
    'status', 'createdAt',
  ]);
  const sellerField = record['type'] === 'ANSWER_FIELD';
  if (!ULID.test(String(record['id']))
      || !['CONFIRM_ACTION', 'SELECT_OPTION', 'ANSWER_FIELD'].includes(String(record['type']))
      || !['WAITING', 'CONSUMED', 'CANCELLED'].includes(String(record['status']))) {
    throw new MarketplaceAgentV2ContractError();
  }
  if (sellerField) {
    if (record['action'] !== null || record['workflowType'] !== 'CREATE_LISTING'
        || !['ITEM_TYPE', 'TITLE', 'CONDITION', 'PRICE', 'DESCRIPTION', 'LOCATION', 'FULFILLMENT']
          .includes(String(record['field']))) throw new MarketplaceAgentV2ContractError();
    string(record['question']);
  } else if (!['SHOW_DETAILS', 'COMPARE_LISTINGS', 'RUN_REFINED_SEARCH']
      .includes(String(record['action']))
      || record['workflowType'] !== null || record['field'] !== null
      || record['question'] !== null) {
    throw new MarketplaceAgentV2ContractError();
  }
  const argumentsRecord = object(record['arguments']);
  if (Object.keys(argumentsRecord).length > 12) throw new MarketplaceAgentV2ContractError();
  date(record['createdAt']);
}

function parseSession(value: unknown): MarketplaceAgentV2Session {
  const record = object(value);
  exact(record, ['sessionId', 'sessionType', 'status', 'createdAt', 'updatedAt']);
  if (!ULID.test(String(record['sessionId'])) || record['sessionType'] !== 'MARKETPLACE_AGENT_V2'
      || !['OPEN', 'READ_ONLY', 'CLOSED'].includes(String(record['status']))) {
    throw new MarketplaceAgentV2ContractError();
  }
  date(record['createdAt']); date(record['updatedAt']);
  return record as unknown as MarketplaceAgentV2Session;
}

function parseHistory(value: unknown): MarketplaceAgentV2HistoryPage {
  const record = object(value);
  exact(record, ['data', 'hasMore']);
  if (!Array.isArray(record['data']) || typeof record['hasMore'] !== 'boolean') {
    throw new MarketplaceAgentV2ContractError();
  }
  for (const entry of record['data']) {
    const message = object(entry);
    exact(message, [
      'id', 'role', 'body', 'clientMessageId', 'message', 'retryable',
      'responseRetryUserMessageId', 'createdAt',
    ]);
    if (!ULID.test(String(message['id'])) || !['USER', 'ASSISTANT'].includes(String(message['role']))
        || typeof message['retryable'] !== 'boolean') throw new MarketplaceAgentV2ContractError();
    string(message['body']); date(message['createdAt']);
    nullableUlid(message['clientMessageId']); nullableUlid(message['responseRetryUserMessageId']);
    if (message['message'] !== null) parseMessage(message['message']);
    if (message['role'] === 'USER' && message['message'] !== null) throw new MarketplaceAgentV2ContractError();
    if (message['role'] === 'ASSISTANT' && message['message'] === null) throw new MarketplaceAgentV2ContractError();
  }
  return record as unknown as MarketplaceAgentV2HistoryPage;
}

function parseStop(value: unknown): StopMarketplaceAgentV2Response {
  const record = object(value);
  exact(record, ['sessionId', 'clientMessageId', 'outcome']);
  if (!ULID.test(String(record['sessionId'])) || !ULID.test(String(record['clientMessageId']))
      || !['NOT_COMMITTED', 'STOPPED', 'COMPLETED', 'TERMINAL'].includes(String(record['outcome']))) {
    throw new MarketplaceAgentV2ContractError();
  }
  return record as unknown as StopMarketplaceAgentV2Response;
}

function parseAttachment(value: unknown): MarketplaceAgentV2ListingAttachment {
  const record = object(value);
  exactWithOptional(record, ['type', 'listingId', 'title', 'categoryName', 'condition', 'priceAmount', 'currency',
    'publicCity', 'publicRegion', 'thumbnailUrl', 'checkedAt', 'responseHash'], ['matchQuality']);
  if (record['type'] !== 'LISTING' || !ULID.test(String(record['listingId']))
      || !HASH.test(String(record['responseHash']))
      || !(CONDITIONS as readonly unknown[]).includes(record['condition'])) {
    throw new MarketplaceAgentV2ContractError();
  }
  string(record['title']); string(record['categoryName']); string(record['priceAmount']);
  string(record['currency']); date(record['checkedAt']);
  nullableString(record['publicCity']); nullableString(record['publicRegion']);
  if (record['thumbnailUrl'] !== null
      && (typeof record['thumbnailUrl'] !== 'string'
        || !PUBLIC_LISTING_MEDIA.test(record['thumbnailUrl']))) {
    throw new MarketplaceAgentV2ContractError();
  }
  if (record['matchQuality'] !== undefined && record['matchQuality'] !== null
      && !['EXACT', 'RELATED'].includes(String(record['matchQuality']))) {
    throw new MarketplaceAgentV2ContractError();
  }
  return record as unknown as MarketplaceAgentV2ListingAttachment;
}

function parseToolActivity(value: unknown): void {
  const record = object(value);
  exact(record, ['tool', 'status', 'reason', 'observedAt']);
  tool(record['tool']);
  if (!(TOOL_STATUSES as readonly unknown[]).includes(record['status'])) {
    throw new MarketplaceAgentV2ContractError();
  }
  string(record['reason']); date(record['observedAt']);
}

function parseUserMessage(value: unknown): void {
  const record = object(value);
  exact(record, ['id', 'role', 'body', 'createdAt']);
  if (!ULID.test(String(record['id'])) || record['role'] !== 'USER') throw new MarketplaceAgentV2ContractError();
  string(record['body']); date(record['createdAt']);
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new MarketplaceAgentV2ContractError();
  return value as Record<string, unknown>;
}
function exact(record: Record<string, unknown>, keys: string[]): void {
  if (Object.keys(record).sort().join('|') !== [...keys].sort().join('|')) throw new MarketplaceAgentV2ContractError();
}
function exactWithOptional(
  record: Record<string, unknown>,
  required: string[],
  optional: string[],
): void {
  const actual = Object.keys(record);
  if (required.some(key => !actual.includes(key))
      || actual.some(key => !required.includes(key) && !optional.includes(key))) {
    throw new MarketplaceAgentV2ContractError();
  }
}
function string(value: unknown): void {
  if (typeof value !== 'string' || !value) throw new MarketplaceAgentV2ContractError();
}
function nullableString(value: unknown): void {
  if (value !== null && typeof value !== 'string') throw new MarketplaceAgentV2ContractError();
}
function nullableUlid(value: unknown): void {
  if (value !== null && (typeof value !== 'string' || !ULID.test(value))) {
    throw new MarketplaceAgentV2ContractError();
  }
}
function date(value: unknown): void {
  string(value); if (Number.isNaN(Date.parse(String(value)))) throw new MarketplaceAgentV2ContractError();
}
function tool(value: unknown): void {
  if (!(TOOLS as readonly unknown[]).includes(value)) throw new MarketplaceAgentV2ContractError();
}
