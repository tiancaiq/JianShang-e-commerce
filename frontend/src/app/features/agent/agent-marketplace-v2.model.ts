export type MarketplaceAgentV2Tool =
  'check_availability' | 'search_listings' | 'get_listing' | 'request_confirmation'
  | 'collect_listing_information';

export interface MarketplaceAgentV2PendingInteraction {
  id: string;
  type: 'CONFIRM_ACTION' | 'SELECT_OPTION' | 'ANSWER_FIELD';
  action: 'SHOW_DETAILS' | 'COMPARE_LISTINGS' | 'RUN_REFINED_SEARCH' | null;
  workflowType: 'CREATE_LISTING' | null;
  field: 'ITEM_TYPE' | 'TITLE' | 'CONDITION' | 'PRICE' | 'DESCRIPTION'
    | 'LOCATION' | 'FULFILLMENT' | null;
  question: string | null;
  arguments: Record<string, unknown>;
  status: 'WAITING' | 'CONSUMED' | 'CANCELLED';
  createdAt: string;
}

export interface MarketplaceAgentV2ListingAttachment {
  type: 'LISTING';
  listingId: string;
  title: string;
  categoryName: string;
  condition: 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS';
  priceAmount: string;
  currency: string;
  publicCity: string | null;
  publicRegion: string | null;
  thumbnailUrl: string | null;
  checkedAt: string;
  responseHash: string;
  matchQuality?: 'EXACT' | 'RELATED' | null;
}

export interface MarketplaceAgentV2Message {
  role: 'ASSISTANT';
  content: string;
  attachments: MarketplaceAgentV2ListingAttachment[];
  refinement: {
    question: string | null;
    options: Array<{
      facet: 'SUBTYPE' | 'CONDITION' | 'PRICE_BAND' | 'LOCATION'
        | 'MATCH_SCOPE' | 'MAXIMUM_PRICE';
      value: string;
      count: number;
    }>;
  } | null;
  pendingInteraction: MarketplaceAgentV2PendingInteraction | null;
  citations: string[];
  toolActivity: Array<{
    tool: MarketplaceAgentV2Tool;
    status: 'SUCCEEDED' | 'REJECTED' | 'FAILED';
    reason: string;
    observedAt: string;
  }>;
  inputTokens: number;
  outputTokens: number;
}

export interface SendMarketplaceAgentV2MessageResponse {
  sessionId: string;
  userMessage: { id: string; role: 'USER'; body: string; createdAt: string };
  assistantMessageId: string;
  message: MarketplaceAgentV2Message;
  decisionCount: number;
}

export interface MarketplaceAgentV2Session {
  sessionId: string;
  sessionType: 'MARKETPLACE_AGENT_V2';
  status: 'OPEN' | 'READ_ONLY' | 'CLOSED';
  createdAt: string;
  updatedAt: string;
}

export interface MarketplaceAgentV2HistoryMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  clientMessageId: string | null;
  message: MarketplaceAgentV2Message | null;
  retryable: boolean;
  responseRetryUserMessageId: string | null;
  createdAt: string;
}

export interface MarketplaceAgentV2HistoryPage {
  data: MarketplaceAgentV2HistoryMessage[];
  hasMore: boolean;
}

export interface StopMarketplaceAgentV2Response {
  sessionId: string;
  clientMessageId: string;
  outcome: 'NOT_COMMITTED' | 'STOPPED' | 'COMPLETED' | 'TERMINAL';
}

interface StreamBase {
  schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1';
  sequence: number;
}

export type MarketplaceAgentV2StreamEvent =
  | (StreamBase & { type: 'message_started'; userMessage: SendMarketplaceAgentV2MessageResponse['userMessage'] })
  | (StreamBase & { type: 'activity'; tool: MarketplaceAgentV2Tool; label: string })
  | (StreamBase & { type: 'tool_completed'; tool: MarketplaceAgentV2Tool; status: string; reason: string; observedAt: string })
  | (StreamBase & { type: 'text_delta'; delta: string })
  | (StreamBase & { type: 'attachments'; items: MarketplaceAgentV2ListingAttachment[] })
  | (StreamBase & { type: 'error'; code: string; message: string; retryable: boolean })
  | (StreamBase & { type: 'done'; messageId: string; response: SendMarketplaceAgentV2MessageResponse });
