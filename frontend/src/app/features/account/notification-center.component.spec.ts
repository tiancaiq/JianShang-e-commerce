import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { NotificationItem, NotificationPage } from '../../core/models/notification.model';
import { AuthService } from '../../core/services/auth.service';
import { NotificationContractError, NotificationService } from '../../core/services/notification.service';
import { NOTIFICATION_CENTER_ENABLED } from './notification-center.capability';
import { NotificationCenterComponent } from './notification-center.component';

describe('NotificationCenterComponent', () => {
  let fixture: ComponentFixture<NotificationCenterComponent>;
  let notificationService: jasmine.SpyObj<NotificationService>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: Router;

  const item = (id: string, read = false): NotificationItem => ({
    id,
    type: 'ORDER_CONFIRMED',
    messageKey: 'ORDER_CONFIRMED_V1',
    presentationArgs: {
      orderId: '01KBBBBBBBBBBBBBBBBBBBBBBB',
    },
    safeRoute: '/account',
    read,
    readAt: read ? '2026-07-21T00:05:00Z' : null,
    createdAt: '2026-07-21T00:00:00Z',
  });

  async function configure(options: {
    enabled?: boolean;
    listResult?: Observable<NotificationPage>;
    markReadResult?: Observable<void>;
    markAllResult?: Observable<void>;
  } = {}): Promise<void> {
    notificationService = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'list',
      'markRead',
      'markAllRead',
    ]);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['clearUser']);
    notificationService.list.and.returnValue(options.listResult ?? of({
      items: [item('01KAAAAAAAAAAAAAAAAAAAAAAA')],
      page: {
        nextCursor: 'v1.next',
        hasMore: true,
      },
    }));
    notificationService.markRead.and.returnValue(options.markReadResult ?? of(undefined));
    notificationService.markAllRead.and.returnValue(options.markAllResult ?? of(undefined));

    await TestBed.configureTestingModule({
      imports: [NotificationCenterComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: NOTIFICATION_CENTER_ENABLED, useValue: options.enabled ?? true },
        { provide: NotificationService, useValue: notificationService },
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(NotificationCenterComponent);
  }

  afterEach(() => TestBed.resetTestingModule());

  it('is network silent and shows no list while the capability is disabled', async () => {
    await configure({ enabled: false });
    fixture.detectChanges();

    expect(notificationService.list).not.toHaveBeenCalled();
    expect(notificationService.markRead).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Notifications are unavailable.');
    expect(fixture.nativeElement.querySelector('.notification-list')).toBeNull();
  });

  it('renders the accessible order-confirmed center without raw args or unsafe links', async () => {
    await configure();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';

    expect(notificationService.list).toHaveBeenCalledOnceWith(null, 20);
    expect(text).toContain('Notifications');
    expect(text).toContain('1 unread');
    expect(text).toContain('Order confirmed');
    expect(text).not.toContain('01KBBBBBBBBBBBBBBBBBBBBBBB');
    expect(host.querySelector('ol[aria-label="Notifications"]')).not.toBeNull();
    expect(host.querySelector('nav[aria-label="Notification pages"]')).not.toBeNull();
    expect(host.querySelector('.quiet-link')?.getAttribute('href')).toBe('/account');
  });

  it('loads the next cursor without modifying it or duplicating rows', async () => {
    await configure();
    fixture.detectChanges();
    notificationService.list.and.returnValue(of({
      items: [
        item('01KAAAAAAAAAAAAAAAAAAAAAAA'),
        item('01KCCCCCCCCCCCCCCCCCCCCCCC'),
      ],
      page: { nextCursor: null, hasMore: false },
    }));

    fixture.componentInstance.loadMore();
    fixture.detectChanges();

    expect(notificationService.list.calls.argsFor(1)).toEqual(['v1.next', 20]);
    expect(fixture.componentInstance.notifications().map(notification => notification.id)).toEqual([
      '01KAAAAAAAAAAAAAAAAAAAAAAA',
      '01KCCCCCCCCCCCCCCCCCCCCCCC',
    ]);
  });

  it('marks one and all as read without fabricating read timestamps', async () => {
    await configure();
    fixture.detectChanges();

    fixture.componentInstance.markRead(fixture.componentInstance.notifications()[0]);
    expect(notificationService.markRead).toHaveBeenCalledOnceWith('01KAAAAAAAAAAAAAAAAAAAAAAA');
    expect(fixture.componentInstance.notifications()[0].read).toBeTrue();
    expect(fixture.componentInstance.notifications()[0].readAt).toBeNull();

    notificationService.list.and.returnValue(of({
      items: [item('01KDDDDDDDDDDDDDDDDDDDDDDD'), item('01KEEEEEEEEEEEEEEEEEEEEEEE')],
      page: { nextCursor: null, hasMore: false },
    }));
    fixture.componentInstance.load();
    fixture.componentInstance.markAllRead();

    expect(notificationService.markAllRead).toHaveBeenCalled();
    expect(fixture.componentInstance.notifications().every(notification => notification.read)).toBeTrue();
  });

  it('redirects auth failures and shows retryable outage or corrupt projection states', async () => {
    await configure({
      listResult: throwError(() => new HttpErrorResponse({ status: 401 })),
    });
    fixture.detectChanges();
    expect(authService.clearUser).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledOnceWith(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: '/account/notifications',
      },
    });

    TestBed.resetTestingModule();
    await configure({
      listResult: throwError(() => new HttpErrorResponse({
        status: 503,
        error: { error: { code: 'NOTIFICATION_IDENTITY_UNAVAILABLE' }, rawEvent: 'private' },
      })),
    });
    fixture.detectChanges();
    let text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Notifications are temporarily unavailable.');
    expect(text).toContain('Retry');
    expect(text).not.toContain('private');

    TestBed.resetTestingModule();
    await configure({
      listResult: throwError(() => new NotificationContractError()),
    });
    fixture.detectChanges();
    text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Notifications are temporarily unavailable.');
  });

  it('does not automatically retry after an uncertain read command failure', async () => {
    await configure({
      markReadResult: throwError(() => new HttpErrorResponse({ status: 503 })),
    });
    fixture.detectChanges();

    fixture.componentInstance.markRead(fixture.componentInstance.notifications()[0]);
    fixture.detectChanges();

    expect(notificationService.markRead).toHaveBeenCalledTimes(1);
    expect(notificationService.list).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('Notification changes are temporarily unavailable.');
  });
});
