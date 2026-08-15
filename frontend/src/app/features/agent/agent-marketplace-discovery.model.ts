export type DiscoveryOutcome =
  | 'ANSWER'
  | 'CLARIFY'
  | 'SEARCH'
  | 'ACTION_REQUIRED'
  | 'ASK_CLARIFY'
  | 'RECOMMEND'
  | 'COMPARE'
  | 'DETAIL'
  | 'NO_RESULTS'
  | 'REFUSE'
  | 'REFUSED'
  | 'HANDOFF';

export type MarketplaceIntent =
  | 'GENERAL_CONVERSATION'
  | 'MARKETPLACE_DISCOVERY'
  | 'LISTING_QUESTION'
  | 'CUSTOMER_SUPPORT'
  | 'SELLER_SUPPORT'
  | 'CLARIFICATION'
  | 'HANDOFF'
  | 'REFUSED';

export type DiscoveryResolution =
  | 'ANSWERED'
  | 'CLARIFY'
  | 'RECOMMEND'
  | 'NO_RESULTS'
  | 'REFUSED'
  | 'PARTIAL'
  | 'HANDOFF';

export interface DiscoveryPreferenceState {
  query: string | null;
  categoryId: string | null;
  condition: 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS' | null;
  minPrice: string | null;
  maxPrice: string | null;
  city: string | null;
  county: string | null;
  selectedListingId: string | null;
  status?: 'IDLE' | 'CHECKING_AVAILABILITY' | 'COLLECTING_PREFERENCES' | 'SEARCHING'
    | 'PRESENTING_RESULTS' | 'NO_INVENTORY' | 'PAUSED';
  requestedCategory?: string | null;
  categoryAvailability?: 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE';
  categoryInventoryCount?: number | null;
  clarificationsAsked?: number;
  lastSearchOutcome?: DiscoverySearchReason | null;
  activeGoal?: 'FIND_PRODUCT' | 'RESOLVE_SUPPORT_ISSUE' | 'LEARN_POLICY' | 'NONE';
  activeCategory?: string | null;
  workflowStatus?: 'IDLE' | 'PROBING' | 'CLARIFYING' | 'SEARCHING'
    | 'PRESENTING' | 'PAUSED' | 'COMPLETE';
  referencedListings?: string[];
  lastToolActions?: Array<{
    action: 'CHECK_AVAILABILITY' | 'SEARCH_LISTINGS' | 'GET_LISTING_DETAILS';
    tool: 'CHECK_AVAILABILITY' | 'SEARCH_INDIVIDUAL' | 'GET_LISTING';
    result: 'AVAILABLE' | 'UNAVAILABLE' | 'RESULTS_AVAILABLE' | 'NO_RESULTS'
      | 'VERIFIED' | 'NOT_FOUND' | 'TEMPORARY_FAILURE';
  }>;
  recentObservations?: Array<{
    tool: 'CHECK_AVAILABILITY' | 'SEARCH_INDIVIDUAL' | 'GET_LISTING';
    normalizedQuery: string;
    filterCategories: DiscoveryFilterCategory[];
    observedAt: string;
    result: 'AVAILABLE' | 'UNAVAILABLE' | 'RESULTS_AVAILABLE' | 'NO_RESULTS'
      | 'VERIFIED' | 'NOT_FOUND' | 'TEMPORARY_FAILURE';
    freshness: 'FRESH' | 'POTENTIALLY_STALE';
  }>;
}

export type DiscoverySearchReason =
  | 'CATEGORY_UNAVAILABLE'
  | 'FILTERS_TOO_STRICT'
  | 'TEMPORARY_SEARCH_FAILURE'
  | 'SEARCH_UNAVAILABLE'
  | 'RESULTS_AVAILABLE';

export type DiscoveryFilterCategory =
  | 'CATEGORY'
  | 'CONDITION'
  | 'MINIMUM_PRICE'
  | 'MAXIMUM_PRICE'
  | 'CITY'
  | 'COUNTY_OR_REGION';

export interface DiscoverySearchOutcome {
  mode: 'AVAILABILITY_PROBE' | 'FULL_DISCOVERY_SEARCH';
  searchExecuted: boolean;
  category: string;
  totalActiveCategoryInventory: number | null;
  exactMatchCount: number | null;
  appliedFilters: DiscoveryFilterCategory[];
  relaxableFilters: DiscoveryFilterCategory[];
  reason: DiscoverySearchReason;
  retryable: boolean;
}

export interface DiscoverySession {
  id: string;
  sessionType: 'MARKETPLACE_DISCOVERY';
  status: 'OPEN' | 'CLOSED';
  preferenceState: DiscoveryPreferenceState;
  preferenceVersion: number;
  clarificationTurnCount: number;
  clarificationQuestionCount: number;
  exclusions: DiscoveryExclusion[];
  createdAt: string;
  updatedAt: string;
}

export type DiscoveryExclusionReason =
  | 'NOT_RELEVANT'
  | 'TOO_EXPENSIVE'
  | 'TOO_FAR'
  | 'WRONG_CONDITION'
  | 'ALREADY_HAVE'
  | 'OTHER';

export interface DiscoveryExclusion {
  listingId: string;
  reasonCode: DiscoveryExclusionReason | null;
  excludedAt: string;
}

export interface DiscoveryExclusionResponse {
  sessionId: string;
  listingId: string;
  reasonCode: DiscoveryExclusionReason | null;
  outcome: 'EXCLUDED' | 'ALREADY_EXCLUDED';
  preferenceVersion: number;
  excludedCount: number;
  updatedAt: string;
}

export interface DiscoveryProvenance {
  listingId: string;
  checkedAt: string;
  responseHash: string;
}

export type DiscoveryConstraintCoverage =
  | 'QUERY'
  | 'CATEGORY'
  | 'CONDITION'
  | 'PRICE'
  | 'CITY'
  | 'COUNTY_OR_REGION';

export interface DiscoveryRecommendation {
  listingId: string;
  title: string;
  categoryId: string;
  categoryName: string;
  condition: 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS';
  priceAmount: string;
  currency: string;
  publicCity: string | null;
  publicRegion: string | null;
  thumbnailUrl: string | null;
  sellerType: 'INDIVIDUAL';
  matchReason: string;
  constraintCoverage: DiscoveryConstraintCoverage[];
  provenance: DiscoveryProvenance;
}

export interface DiscoveryTurnResult {
  outcome: DiscoveryOutcome;
  intent?: MarketplaceIntent | null;
  message: string;
  questions: string[];
  recommendations: DiscoveryRecommendation[];
  preferenceState: DiscoveryPreferenceState;
  searchOutcome?: DiscoverySearchOutcome | null;
  inputTokens: number;
  outputTokens: number;
  estimatedCost: string;
}

export interface DiscoveryHistoryMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  clientMessageId: string | null;
  resolutionType: DiscoveryResolution | null;
  result: DiscoveryTurnResult | null;
  responseFailure: DiscoveryTurnResult | null;
  responseRetry: {
    invocationId: string;
    userMessageId: string;
  } | null;
  createdAt: string;
}

export interface DiscoveryHistoryPage {
  data: DiscoveryHistoryMessage[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface SendDiscoveryMessageResponse {
  userMessage: {
    id: string;
    role: 'USER';
    body: string;
    createdAt: string;
  };
  result: DiscoveryTurnResult;
  preferenceVersion: number;
}

export interface StopDiscoveryResponse {
  sessionId: string;
  clientMessageId: string;
  outcome: 'NOT_COMMITTED' | 'STOPPED' | 'COMPLETED' | 'TERMINAL';
}

export type DiscoveryProgressStage =
  | 'MESSAGE_ACCEPTED'
  | 'UNDERSTANDING'
  | 'CHECKING_AVAILABILITY'
  | 'SEARCHING'
  | 'CHECKING'
  | 'COMPOSING';

interface DiscoveryStreamEventBase {
  schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2';
  sequence: number;
}

type DiscoveryStreamEventV2 =
  | (DiscoveryStreamEventBase & {
      type: 'activity';
      stage: DiscoveryProgressStage;
      label?: string;
    })
  | (DiscoveryStreamEventBase & {
      type: 'text_delta';
      delta: string;
    })
  | (DiscoveryStreamEventBase & {
      type: 'recommendations';
      items: readonly DiscoveryRecommendation[];
    })
  | (DiscoveryStreamEventBase & {
      type: 'metadata';
      citations: readonly string[];
      provenance: readonly DiscoveryProvenance[];
    })
  | (DiscoveryStreamEventBase & {
      type: 'done';
      messageId?: string;
      response: SendDiscoveryMessageResponse;
    })
  | (DiscoveryStreamEventBase & {
      type: 'error';
      code: string;
      message: string;
      retryable: boolean;
    });

export type DiscoveryStreamEvent = DiscoveryStreamEventV2;
