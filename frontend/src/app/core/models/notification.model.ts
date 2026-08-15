export type NotificationType =
  | 'BUYER_ORDER_CONFIRMED' | 'BUYER_ORDER_CANCELLED' | 'BUYER_REFUND_COMPLETED'
  | 'BUYER_ORDER_ACCEPTED' | 'BUYER_ORDER_PROCESSING' | 'BUYER_ORDER_SHIPPED'
  | 'BUYER_ORDER_DELIVERED' | 'SELLER_NEW_ORDER' | 'SELLER_ORDER_CANCELLED'
  | 'BUYER_RETURN_AUTHORIZED' | 'BUYER_RETURN_RECEIVED' | 'BUYER_RETURN_REFUND_COMPLETED'
  | 'SELLER_RETURN_REQUESTED'
  | 'ORDER_CONFIRMED';
export type NotificationMessageKey = `${NotificationType}_V1`;

export interface NotificationItem {
  id: string;
  type: NotificationType;
  messageKey: NotificationMessageKey;
  presentationArgs: {
    orderId: string;
    businessOrderId?: string;
    storeDisplayName?: string;
  };
  safeRoute: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface NotificationCount { unreadCount: number; }

export interface NotificationPage {
  items: NotificationItem[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}
