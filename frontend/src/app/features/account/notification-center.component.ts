import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NotificationItem } from '../../core/models/notification.model';
import { AuthService } from '../../core/services/auth.service';
import { NotificationContractError, NotificationService } from '../../core/services/notification.service';
import { NOTIFICATION_CENTER_ENABLED } from './notification-center.capability';

@Component({
  selector: 'app-notification-center',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <section class="notification-page">
      <header class="page-header">
        <div>
          <a routerLink="/account" class="back-link">Back to account</a>
          <p class="eyebrow">Marketplace account</p>
          <h1>Notifications</h1>
          <p class="subheading">{{ unreadCount() }} unread</p>
        </div>
        <button
          type="button"
          class="secondary-button"
          (click)="markAllRead()"
          [disabled]="loading() || commandInFlight() !== null || unreadCount() === 0"
        >
          Mark all read
        </button>
      </header>

      @if (!featureEnabled) {
        <section class="state-panel" role="status">
          <h2>Notifications are unavailable.</h2>
        </section>
      } @else {
        @if (pageError()) {
          <div class="message error-message" role="alert">
            <span>{{ pageError() }}</span>
            @if (canRetryLoad()) {
              <button type="button" class="quiet-button" (click)="load()" [disabled]="loading()">Retry</button>
            }
          </div>
        }

        @if (loading() && notifications().length === 0) {
          <div class="loading-list" aria-label="Loading notifications">
            <span></span>
            <span></span>
            <span></span>
          </div>
        } @else if (notifications().length === 0 && !pageError()) {
          <section class="state-panel empty-state">
            <span class="state-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24"><path d="M12 22a2.4 2.4 0 0 0 2.3-1.8H9.7A2.4 2.4 0 0 0 12 22zm7-6V11a7 7 0 0 0-5.4-6.8V3a1.6 1.6 0 0 0-3.2 0v1.2A7 7 0 0 0 5 11v5l-2 2v1h18v-1l-2-2z"/></svg>
            </span>
            <h2>No notifications yet</h2>
          </section>
        } @else {
          <ol class="notification-list" aria-label="Notifications">
            @for (notification of notifications(); track notification.id) {
              <li>
                <article class="notification-card" [class.unread]="!notification.read">
                  <span class="notification-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d="M19 4H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4l3 3 3-3h4a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-7 11.2-4.2-4.2 1.4-1.4 2.8 2.8 5.3-5.3 1.4 1.4-6.7 6.7z"/></svg>
                  </span>
                  <div class="notification-copy">
                    <div class="notification-heading">
                      <h2>{{ title(notification) }}</h2>
                      <span [class.read-pill]="notification.read" [class.unread-pill]="!notification.read">
                        {{ notification.read ? 'Read' : 'Unread' }}
                      </span>
                    </div>
                    <p>{{ body(notification) }}</p>
                    <time [dateTime]="notification.createdAt">{{ notification.createdAt | date:'medium' }}</time>
                  </div>
                  <div class="notification-actions">
                    <a [routerLink]="safeRoute(notification)" class="quiet-link">Open</a>
                    @if (!notification.read) {
                      <button
                        type="button"
                        class="quiet-button"
                        (click)="markRead(notification)"
                        [disabled]="commandInFlight() === notification.id"
                      >
                        Mark read
                      </button>
                    }
                  </div>
                </article>
              </li>
            }
          </ol>

          <nav class="pagination" aria-label="Notification pages">
            <button
              type="button"
              class="secondary-button"
              (click)="loadMore()"
              [disabled]="loading() || !nextCursor()"
            >
              {{ loading() ? 'Loading' : nextCursor() ? 'Load more' : 'All caught up' }}
            </button>
          </nav>
        }
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      color: var(--notification-ink, #352348);
    }

    .notification-page {
      width: min(100%, 940px);
      margin: 0 auto;
      display: grid;
      gap: 18px;
    }

    .page-header,
    .message,
    .notification-card,
    .notification-heading,
    .notification-actions,
    .pagination {
      display: flex;
      align-items: center;
    }

    .page-header {
      justify-content: space-between;
      gap: 24px;
      padding: 4px 0 12px;
      border-bottom: 1px solid rgba(150, 91, 173, 0.22);
    }

    .page-header > div,
    .notification-copy {
      min-width: 0;
    }

    .back-link {
      display: inline-block;
      margin-bottom: 16px;
      color: #716080;
      font-weight: 750;
      text-decoration: none;
    }

    .eyebrow,
    h1,
    h2,
    p {
      margin: 0;
    }

    .eyebrow {
      color: #c73588;
      font-size: 0.75rem;
      font-weight: 850;
      text-transform: uppercase;
    }

    h1 {
      margin-top: 5px;
      font-size: 2rem;
      line-height: 1.08;
    }

    .subheading {
      margin-top: 7px;
      color: #716080;
      font-size: 0.9rem;
      font-weight: 700;
    }

    button,
    .quiet-link {
      min-height: 40px;
      border-radius: 8px;
      font: inherit;
      font-weight: 800;
    }

    button {
      cursor: pointer;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.58;
    }

    button:focus-visible,
    a:focus-visible {
      outline: 3px solid rgba(199, 53, 136, 0.24);
      outline-offset: 2px;
    }

    .secondary-button {
      padding: 0 16px;
      border: 1px solid rgba(150, 91, 173, 0.22);
      color: #352348;
      background: #fff;
    }

    .quiet-button,
    .quiet-link {
      padding: 0 13px;
      border: 1px solid rgba(150, 91, 173, 0.22);
      color: #352348;
      background: #fff;
      text-decoration: none;
    }

    .quiet-link {
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    .message {
      justify-content: space-between;
      gap: 12px;
    }

    .error-message {
      padding: 11px 13px;
      border: 1px solid rgba(190, 48, 76, 0.35);
      border-radius: 6px;
      color: #9f263f;
      background: #fff4f6;
      font-size: 0.88rem;
      font-weight: 750;
    }

    .state-panel {
      min-height: 260px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 14px;
      border: 1px dashed rgba(150, 91, 173, 0.34);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.66);
      text-align: center;
    }

    .state-icon,
    .notification-icon {
      display: grid;
      place-items: center;
      border-radius: 10px;
      color: #fff;
      background: linear-gradient(135deg, #2f9d83, #8b6fe8);
    }

    .state-icon {
      width: 54px;
      aspect-ratio: 1;
      font-size: 1.5rem;
    }

    svg {
      width: 1em;
      height: 1em;
      fill: currentColor;
      flex: 0 0 auto;
    }

    .loading-list {
      display: grid;
      gap: 12px;
    }

    .loading-list span {
      min-height: 112px;
      border: 1px solid rgba(150, 91, 173, 0.18);
      border-radius: 8px;
      background: linear-gradient(100deg, #fff 20%, #f6f0fb 45%, #fff 70%);
      background-size: 220% 100%;
      animation: notification-loading 1.2s linear infinite;
    }

    @keyframes notification-loading {
      to { background-position: -220% 0; }
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-list span { animation: none; }
    }

    .notification-list {
      display: grid;
      gap: 12px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .notification-card {
      min-height: 112px;
      align-items: flex-start;
      gap: 16px;
      padding: 18px;
      border: 1px solid rgba(150, 91, 173, 0.2);
      border-left: 4px solid transparent;
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 10px 24px rgba(126, 77, 155, 0.06);
    }

    .notification-card.unread {
      border-left-color: #2f9d83;
      background: #fbfffd;
    }

    .notification-icon {
      width: 44px;
      aspect-ratio: 1;
      flex: 0 0 auto;
      font-size: 1.1rem;
    }

    .notification-heading {
      flex-wrap: wrap;
      gap: 10px;
    }

    .notification-heading h2 {
      font-size: 1.05rem;
    }

    .notification-copy p {
      margin-top: 8px;
      color: #665571;
      line-height: 1.45;
      font-weight: 650;
    }

    time {
      display: block;
      margin-top: 9px;
      color: #86768f;
      font-size: 0.8rem;
      font-weight: 700;
    }

    .read-pill,
    .unread-pill {
      display: inline-flex;
      align-items: center;
      min-height: 24px;
      padding: 0 9px;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 850;
    }

    .read-pill {
      color: #62536a;
      background: #f0edf2;
    }

    .unread-pill {
      color: #16725d;
      background: #e2f6ef;
    }

    .notification-actions {
      align-self: center;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 8px;
      margin-left: auto;
    }

    .pagination {
      justify-content: center;
      padding-top: 4px;
    }

    @media (max-width: 720px) {
      .page-header,
      .notification-card {
        align-items: stretch;
        flex-direction: column;
      }

      .notification-actions {
        justify-content: flex-start;
        margin-left: 0;
      }

      .page-header > .secondary-button,
      .notification-actions > * {
        width: 100%;
      }
    }
  `],
})
export class NotificationCenterComponent implements OnInit {
  readonly featureEnabled = inject(NOTIFICATION_CENTER_ENABLED);
  private readonly notificationService = inject(NotificationService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly notifications = signal<NotificationItem[]>([]);
  readonly loading = signal(false);
  readonly pageError = signal<string | null>(null);
  readonly nextCursor = signal<string | null>(null);
  readonly commandInFlight = signal<string | null>(null);
  readonly canRetryLoad = signal(false);
  readonly unreadCount = computed(() => this.notifications().filter(notification => !notification.read).length);

  ngOnInit(): void {
    if (!this.featureEnabled) {
      return;
    }
    this.load();
  }

  load(): void {
    if (!this.featureEnabled || this.loading()) {
      return;
    }
    this.loading.set(true);
    this.pageError.set(null);
    this.canRetryLoad.set(false);
    this.notificationService.list(null, 20).subscribe({
      next: page => {
        this.notifications.set(page.items);
        this.nextCursor.set(page.page.nextCursor);
        this.loading.set(false);
      },
      error: error => this.handleLoadError(error),
    });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!this.featureEnabled || this.loading() || !cursor) {
      return;
    }
    this.loading.set(true);
    this.pageError.set(null);
    this.notificationService.list(cursor, 20).subscribe({
      next: page => {
        this.notifications.update(items => this.merge(items, page.items));
        this.nextCursor.set(page.page.nextCursor);
        this.loading.set(false);
      },
      error: error => this.handleLoadError(error),
    });
  }

  markRead(notification: NotificationItem): void {
    if (notification.read || this.commandInFlight()) {
      return;
    }
    this.commandInFlight.set(notification.id);
    this.pageError.set(null);
    this.canRetryLoad.set(false);
    this.notificationService.markRead(notification.id).subscribe({
      next: () => {
        this.notifications.update(items => items.map(item =>
          item.id === notification.id ? { ...item, read: true } : item));
        this.commandInFlight.set(null);
      },
      error: error => this.handleCommandError(error),
    });
  }

  markAllRead(): void {
    if (this.unreadCount() === 0 || this.commandInFlight()) {
      return;
    }
    this.commandInFlight.set('all');
    this.pageError.set(null);
    this.canRetryLoad.set(false);
    this.notificationService.markAllRead().subscribe({
      next: () => {
        this.notifications.update(items => items.map(item => ({ ...item, read: true })));
        this.commandInFlight.set(null);
      },
      error: error => this.handleCommandError(error),
    });
  }

  title(notification: NotificationItem): string {
    return ({
      BUYER_ORDER_CONFIRMED_V1: 'Order confirmed',
      BUYER_ORDER_CANCELLED_V1: 'Cancellation completed',
      BUYER_REFUND_COMPLETED_V1: 'Demo refund completed',
      BUYER_ORDER_ACCEPTED_V1: 'Seller accepted your order',
      BUYER_ORDER_PROCESSING_V1: 'Order processing',
      BUYER_ORDER_SHIPPED_V1: 'Order shipped',
      BUYER_ORDER_DELIVERED_V1: 'Order delivered',
      ORDER_CONFIRMED_V1: 'Order confirmed',
      SELLER_NEW_ORDER_V1: 'New paid order',
      SELLER_ORDER_CANCELLED_V1: 'Order cancelled',
      BUYER_RETURN_AUTHORIZED_V1: 'Return authorized',
      BUYER_RETURN_RECEIVED_V1: 'Return received',
      BUYER_RETURN_REFUND_COMPLETED_V1: 'Return refund completed',
      SELLER_RETURN_REQUESTED_V1: 'Return requested',
    } as Record<string, string>)[notification.messageKey] || 'Notification';
  }

  body(notification: NotificationItem): string {
    const store = notification.presentationArgs.storeDisplayName;
    const messages: Record<string, string> = {
      BUYER_ORDER_CONFIRMED_V1: 'Your order has been confirmed.',
      BUYER_ORDER_CANCELLED_V1: 'Your cancellation has completed.',
      BUYER_REFUND_COMPLETED_V1: 'Your demo refund has completed.',
      BUYER_ORDER_ACCEPTED_V1: `${store || 'The seller'} accepted your order.`,
      BUYER_ORDER_PROCESSING_V1: `${store || 'The seller'} is processing your order.`,
      BUYER_ORDER_SHIPPED_V1: `${store || 'The seller'} shipped your order.`,
      BUYER_ORDER_DELIVERED_V1: 'Your order was marked delivered in the local demo.',
      ORDER_CONFIRMED_V1: 'Your order has been confirmed.',
      SELLER_NEW_ORDER_V1: 'You have a new order waiting for acceptance.',
      SELLER_ORDER_CANCELLED_V1: 'The buyer cancellation has completed for this order.',
      BUYER_RETURN_AUTHORIZED_V1: `${store || 'The seller'} authorized your return.`,
      BUYER_RETURN_RECEIVED_V1: `${store || 'The seller'} received your return.`,
      BUYER_RETURN_REFUND_COMPLETED_V1: 'Your demo return refund has completed.',
      SELLER_RETURN_REQUESTED_V1: 'A buyer requested a return for this order.',
    };
    return messages[notification.messageKey] || 'Open the order for details.';
  }

  safeRoute(notification: NotificationItem): string {
    return notification.safeRoute;
  }

  private handleLoadError(error: unknown): void {
    this.loading.set(false);
    if (this.isUnauthorized(error)) {
      this.redirectToLogin();
      return;
    }
    this.pageError.set(this.errorMessage(error));
    this.canRetryLoad.set(true);
  }

  private handleCommandError(error: unknown): void {
    this.commandInFlight.set(null);
    if (this.isUnauthorized(error)) {
      this.redirectToLogin();
      return;
    }
    this.pageError.set('Notification changes are temporarily unavailable.');
    this.canRetryLoad.set(false);
  }

  private errorMessage(error: unknown): string {
    if (error instanceof NotificationContractError) {
      return 'Notifications are temporarily unavailable.';
    }
    const status = error instanceof HttpErrorResponse ? error.status : (error as { status?: number })?.status;
    if (status === 401) {
      return 'Sign in to view notifications.';
    }
    if (status === 403) {
      return 'Notification access is unavailable for this account.';
    }
    return 'Notifications are temporarily unavailable.';
  }

  private isUnauthorized(error: unknown): boolean {
    return error instanceof HttpErrorResponse
      ? error.status === 401
      : (error as { status?: number })?.status === 401;
  }

  private redirectToLogin(): void {
    this.authService.clearUser();
    void this.router.navigate(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: '/account/notifications',
      },
    });
  }

  private merge(existing: NotificationItem[], incoming: NotificationItem[]): NotificationItem[] {
    const seen = new Set(existing.map(item => item.id));
    return [
      ...existing,
      ...incoming.filter(item => !seen.has(item.id)),
    ];
  }
}
