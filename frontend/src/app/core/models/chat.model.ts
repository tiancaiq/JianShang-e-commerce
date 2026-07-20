export interface ChatListingSummary {
  id: string;
  title: string;
  sellerType: 'INDIVIDUAL' | 'BUSINESS';
  quantity?: number | null;
  priceAmount?: number | null;
  currency?: string | null;
  publicCity: string | null;
  publicRegion: string | null;
  thumbnailUrl: string | null;
  transactionNotice: string | null;
}

export interface ChatParticipantSummary {
  participantId: string;
  displayName: string;
  publicHandle?: string | null;
  avatarUrl: string | null;
  initials: string;
  roleInConversation: 'BUYER' | 'SELLER';
  currentUser: boolean;
}

export type ConversationCompletionStatus = 'NOT_STARTED' | 'SELLER_MARKED_DONE' | 'BUYER_CONFIRMED' | 'CANCELLED';

export interface ConversationCompletion {
  id: string | null;
  listingId: string;
  conversationId: string;
  sellerUserId: string;
  buyerUserId: string;
  quantitySold: number | null;
  status: ConversationCompletionStatus;
  sellerMarkedDoneAt: string | null;
  buyerConfirmedAt: string | null;
  cancelledAt: string | null;
  currentUserCanMarkDone: boolean;
  currentUserCanConfirm: boolean;
}

export interface ConversationSummary {
  id: string;
  conversationType: 'LISTING_BUYER_SELLER';
  status: 'OPEN' | 'ARCHIVED' | 'LOCKED';
  listing: ChatListingSummary;
  participants: ChatParticipantSummary[];
  completion: ConversationCompletion | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: string;
  conversationId: string;
  senderUserId: string;
  messageType: 'TEXT';
  body: string;
  moderationState: 'VISIBLE';
  currentUser: boolean;
  createdAt: string;
}

export interface ChatMessagePage {
  items: ChatMessage[];
  nextCursor: string | null;
}

export interface ConversationListItem {
  id: string;
  conversationType: 'LISTING_BUYER_SELLER';
  status: 'OPEN' | 'ARCHIVED' | 'LOCKED';
  listing: ChatListingSummary;
  otherParticipant: ChatParticipantSummary;
  lastMessage: ChatMessage | null;
  unread: boolean;
  lastMessageAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationPage {
  items: ConversationListItem[];
  nextCursor: string | null;
}

export interface MarkConversationReadResponse {
  conversationId: string;
  lastReadMessageId: string | null;
  lastReadAt: string | null;
  unread: boolean;
}
