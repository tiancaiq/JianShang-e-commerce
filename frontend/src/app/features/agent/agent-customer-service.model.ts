export type AgentSessionStatus = 'OPEN' | 'READ_ONLY' | 'CLOSED';
export type AgentMessageRole = 'USER' | 'ASSISTANT';
export type AgentResolutionType = 'ANSWERED' | 'PARTIAL' | 'UNKNOWN' | 'CONTACT_SELLER' | 'REFUSED';
export type AgentSourceType =
  | 'LISTING'
  | 'MARKETPLACE_POLICY'
  | 'SAFETY_GUIDANCE'
  | 'MARKETPLACE_FAQ'
  | 'CATEGORY_GUIDANCE';
export type AgentActionType = 'MESSAGE_SELLER' | 'VIEW_LISTING' | 'BROWSE_MARKETPLACE';

export interface AgentSubjectListing {
  id: string;
  version: string;
  title: string;
  thumbnailUrl: string | null;
  transactionNotice: string;
}

export interface AgentSession {
  id: string;
  sessionType: 'LISTING_CUSTOMER_SERVICE';
  status: AgentSessionStatus;
  subjectListing: AgentSubjectListing;
  createdAt: string;
  updatedAt: string;
}

export interface AgentAnswerSource {
  sourceType: AgentSourceType;
  sourceId: string;
  sourceVersion: string;
  label: string;
}

export type AgentAnswerAction =
  | { type: 'MESSAGE_SELLER'; listingId: string }
  | { type: 'VIEW_LISTING'; listingId: string }
  | { type: 'BROWSE_MARKETPLACE' };

export interface AgentMessage {
  id: string;
  role: AgentMessageRole;
  body: string;
  resolutionType: AgentResolutionType | null;
  sources: AgentAnswerSource[];
  actions: AgentAnswerAction[];
  createdAt: string;
}

export interface AgentMessagePage {
  data: AgentMessage[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface SendAgentMessageResponse {
  userMessage: AgentMessage;
  assistantMessage: AgentMessage;
}

export interface CreateAgentSessionRequest {
  sessionType: 'LISTING_CUSTOMER_SERVICE';
  subject: {
    type: 'LISTING';
    id: string;
  };
}

export interface SendAgentMessageRequest {
  clientMessageId: string;
  body: string;
}

