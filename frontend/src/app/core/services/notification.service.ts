import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { NotificationItem, NotificationPage } from '../models/notification.model';
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
}

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
  const args = strictRecord(record['presentationArgs'], ['orderId']);
  return {
    id: ulid(record['id']),
    type: exact(record['type'], 'ORDER_CONFIRMED'),
    messageKey: exact(record['messageKey'], 'ORDER_CONFIRMED_V1'),
    presentationArgs: {
      orderId: ulid(args['orderId']),
    },
    safeRoute: exact(record['safeRoute'], '/account'),
    read: boolean(record['read']),
    readAt: nullable(record['readAt'], dateTime),
    createdAt: dateTime(record['createdAt']),
  };
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
