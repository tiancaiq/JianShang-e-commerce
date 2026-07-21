export type NotificationType = 'ORDER_CONFIRMED';
export type NotificationMessageKey = 'ORDER_CONFIRMED_V1';

export interface NotificationItem {
  id: string;
  type: NotificationType;
  messageKey: NotificationMessageKey;
  presentationArgs: {
    orderId: string;
  };
  safeRoute: '/account';
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface NotificationPage {
  items: NotificationItem[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}
