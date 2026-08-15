import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { NotificationCount, NotificationItem, NotificationMessageKey, NotificationPage, NotificationType } from '../models/notification.model';
import { unwrapData } from './api-response';

const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;

export class NotificationContractError extends Error {
  constructor() {
    super('Notification Service returned an invalid notification projection.');
    this.name = 'NotificationContractError';
  }
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/notifications`;
  private readonly unread = signal(0);
  readonly unreadCount = this.unread.asReadonly();

  constructor(private readonly http: HttpClient) {}

  // Reads only the authenticated recipient's notification projection; actor IDs never come from the browser.
  list(cursor: string | null = null, limit = 20): Observable<NotificationPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<ApiDataResponse<unknown>>(this.baseUrl, {
      params,
      withCredentials: true,
    }).pipe(
      map(unwrapData),
      map(parseNotificationPage),
    );
  }

  count(): Observable<NotificationCount> {
    return this.http.get<ApiDataResponse<unknown>>(`${this.baseUrl}/unread-count`, {
      withCredentials: true,
    }).pipe(map(unwrapData), map(parseCount), tap(value => this.unread.set(value.unreadCount)));
  }

  businessList(businessId: string, cursor: string | null = null, limit = 20): Observable<NotificationPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<ApiDataResponse<unknown>>(
      `${environment.apiGatewayUrl}/api/v1/businesses/${encodeURIComponent(businessId)}/notifications`,
      { params, withCredentials: true },
    ).pipe(map(unwrapData), map(parseNotificationPage));
  }

  businessCount(businessId: string): Observable<NotificationCount> {
    return this.http.get<ApiDataResponse<unknown>>(
      `${environment.apiGatewayUrl}/api/v1/businesses/${encodeURIComponent(businessId)}/notifications/unread-count`,
      { withCredentials: true },
    ).pipe(map(unwrapData), map(parseCount));
  }

  // Marks a single owned notification as read with an empty POST body and no automatic retry.
  markRead(notificationId: string): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/${encodeURIComponent(notificationId)}/read`,
      null,
      { withCredentials: true },
    );
  }

  // Marks all owned unread notifications as read with an empty POST body and no count leak.
  markAllRead(): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/read-all`,
      null,
      { withCredentials: true },
    );
  }

  businessMarkRead(businessId: string, notificationId: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiGatewayUrl}/api/v1/businesses/${encodeURIComponent(businessId)}/notifications/${encodeURIComponent(notificationId)}/read`,
      null, { withCredentials: true });
  }

  businessMarkAllRead(businessId: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiGatewayUrl}/api/v1/businesses/${encodeURIComponent(businessId)}/notifications/read-all`,
      null, { withCredentials: true });
  }
}

const NOTIFICATION_TYPES = new Set<NotificationType>([
  'BUYER_ORDER_CONFIRMED', 'BUYER_ORDER_CANCELLED', 'BUYER_REFUND_COMPLETED',
  'BUYER_ORDER_ACCEPTED', 'BUYER_ORDER_PROCESSING', 'BUYER_ORDER_SHIPPED',
  'BUYER_ORDER_DELIVERED', 'SELLER_NEW_ORDER', 'SELLER_ORDER_CANCELLED', 'ORDER_CONFIRMED',
  'BUYER_RETURN_AUTHORIZED', 'BUYER_RETURN_RECEIVED', 'BUYER_RETURN_REFUND_COMPLETED',
  'SELLER_RETURN_REQUESTED',
]);

export function parseNotificationPage(value: unknown): NotificationPage {
  const record = strictRecord(value, ['items', 'page']);
  const page = strictRecord(record['page'], ['nextCursor', 'hasMore']);
  return {
    items: array(record['items'], parseNotificationItem, 0, 50),
    page: {
      nextCursor: nullable(page['nextCursor'], cursor),
      hasMore: boolean(page['hasMore']),
    },
  };
}

function parseNotificationItem(value: unknown): NotificationItem {
  const record = strictRecord(value, [
    'id',
    'type',
    'messageKey',
    'presentationArgs',
    'safeRoute',
    'read',
    'readAt',
    'createdAt',
  ]);
  const args = flexibleArgs(record['presentationArgs']);
  const type = notificationType(record['type']);
  const key = `${type}_V1` as NotificationMessageKey;
  if (record['messageKey'] !== key) throw new NotificationContractError();
  const route = safeRoute(record['safeRoute']);
  return {
    id: ulid(record['id']),
    type,
    messageKey: key,
    presentationArgs: {
      orderId: ulid(args['orderId']),
      ...(args['businessOrderId'] === undefined ? {} : { businessOrderId: ulid(args['businessOrderId']) }),
      ...(args['storeDisplayName'] === undefined ? {} : { storeDisplayName: boundedText(args['storeDisplayName']) }),
    },
    safeRoute: route,
    read: boolean(record['read']),
    readAt: nullable(record['readAt'], dateTime),
    createdAt: dateTime(record['createdAt']),
  };
}

function flexibleArgs(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new NotificationContractError();
  const record = value as Record<string, unknown>;
  const allowed = ['orderId', 'businessOrderId', 'storeDisplayName'];
  if (!('orderId' in record) || Object.keys(record).some(key => !allowed.includes(key))) {
    throw new NotificationContractError();
  }
  return record;
}

function notificationType(value: unknown): NotificationType {
  if (typeof value !== 'string' || !NOTIFICATION_TYPES.has(value as NotificationType)) {
    throw new NotificationContractError();
  }
  return value as NotificationType;
}

function safeRoute(value: unknown): string {
  if (value === '/account') return value;
  if (typeof value === 'string' && (/^\/account\/orders\/[0-9A-HJKMNP-TV-Z]{26}$/.test(value)
      || /^\/seller\/orders\/[0-9A-HJKMNP-TV-Z]{26}$/.test(value))) return value;
  throw new NotificationContractError();
}

function boundedText(value: unknown): string {
  if (typeof value !== 'string' || value.trim().length < 1 || value.length > 120) {
    throw new NotificationContractError();
  }
  return value;
}

function parseCount(value: unknown): NotificationCount {
  const record = strictRecord(value, ['unreadCount']);
  const count = record['unreadCount'];
  if (!Number.isInteger(count) || (count as number) < 0) throw new NotificationContractError();
  return { unreadCount: count as number };
}

function strictRecord(value: unknown, required: readonly string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new NotificationContractError();
  }
  const record = value as Record<string, unknown>;
  if (required.some(key => !(key in record)) || Object.keys(record).some(key => !required.includes(key))) {
    throw new NotificationContractError();
  }
  return record;
}

function array<T>(value: unknown, parser: (item: unknown) => T, minimum: number, maximum: number): T[] {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
    throw new NotificationContractError();
  }
  return value.map(parser);
}

function nullable<T>(value: unknown, parser: (item: unknown) => T): T | null {
  return value === null || value === undefined ? null : parser(value);
}

function cursor(value: unknown): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > 512) {
    throw new NotificationContractError();
  }
  return value;
}

function ulid(value: unknown): string {
  if (typeof value !== 'string' || !ULID_PATTERN.test(value)) {
    throw new NotificationContractError();
  }
  return value;
}

function dateTime(value: unknown): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > 64 || !Number.isFinite(Date.parse(value))) {
    throw new NotificationContractError();
  }
  return value;
}

function exact<const T extends string>(value: unknown, expected: T): T {
  if (value !== expected) {
    throw new NotificationContractError();
  }
  return expected;
}

function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') {
    throw new NotificationContractError();
  }
  return value;
}
