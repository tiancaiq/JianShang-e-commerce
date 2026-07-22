import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NotificationItem } from '../models/notification.model';
import {
  NotificationContractError,
  NotificationService,
  parseNotificationPage,
} from './notification.service';

describe('NotificationService', () => {
  let service: NotificationService;
  let httpMock: HttpTestingController;

  const notification: NotificationItem = {
    id: '01KAAAAAAAAAAAAAAAAAAAAAAA',
    type: 'ORDER_CONFIRMED',
    messageKey: 'ORDER_CONFIRMED_V1',
    presentationArgs: {
      orderId: '01KBBBBBBBBBBBBBBBBBBBBBBB',
    },
    safeRoute: '/account',
    read: false,
    readAt: null,
    createdAt: '2026-07-21T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(NotificationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists notifications through the gateway without browser actor identity', () => {
    service.list('v1.cursor', 10).subscribe(page => {
      expect(page.items).toEqual([notification]);
      expect(page.page.nextCursor).toBeNull();
    });

    const request = httpMock.expectOne(req =>
      req.url === '/api/v1/notifications'
      && req.params.get('cursor') === 'v1.cursor'
      && req.params.get('limit') === '10');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('X-User-Id')).toBeFalse();
    expect(request.request.headers.has('X-Actor-User-Id')).toBeFalse();
    request.flush({
      data: {
        items: [notification],
        page: {
          nextCursor: null,
          hasMore: false,
        },
      },
    });
  });

  it('sends empty read commands and relies on the interceptor for CSRF', () => {
    service.markRead(notification.id).subscribe();
    const one = httpMock.expectOne(`/api/v1/notifications/${notification.id}/read`);
    expect(one.request.method).toBe('POST');
    expect(one.request.body).toBeNull();
    expect(one.request.withCredentials).toBeTrue();
    one.flush(null);

    service.markAllRead().subscribe();
    const all = httpMock.expectOne('/api/v1/notifications/read-all');
    expect(all.request.method).toBe('POST');
    expect(all.request.body).toBeNull();
    expect(all.request.withCredentials).toBeTrue();
    all.flush(null);
  });

  it('rejects unsafe routes, unsupported messages, corrupt args, and leaked fields', () => {
    expect(() => parseNotificationPage({
      items: [{ ...notification, safeRoute: '/admin' }],
      page: { nextCursor: null, hasMore: false },
    })).toThrowError(NotificationContractError);

    expect(() => parseNotificationPage({
      items: [{ ...notification, messageKey: 'RAW_HTML' }],
      page: { nextCursor: null, hasMore: false },
    })).toThrowError(NotificationContractError);

    expect(() => parseNotificationPage({
      items: [{ ...notification, presentationArgs: { orderId: notification.presentationArgs.orderId, html: '<b>x</b>' } }],
      page: { nextCursor: null, hasMore: false },
    })).toThrowError(NotificationContractError);

    expect(() => parseNotificationPage({
      items: [{ ...notification, sourceEventId: '01KCCCCCCCCCCCCCCCCCCCCCCC' }],
      page: { nextCursor: null, hasMore: false },
    })).toThrowError(NotificationContractError);
  });
});
