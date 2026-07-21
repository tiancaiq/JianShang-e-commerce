export type DiscoveryOutcome =
  | 'ASK_CLARIFY'
  | 'RECOMMEND'
  | 'NO_RESULTS'
  | 'REFUSE'
  | 'HANDOFF';

export type DiscoveryResolution =
  | 'CLARIFY'
  | 'RECOMMEND'
  | 'NO_RESULTS'
  | 'REFUSED'
  | 'HANDOFF';

export interface DiscoveryPreferenceState {
  query: string | null;
  categoryId: string | null;
  condition: 'NEW' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'POOR' | null;
  minPrice: string | null;
  maxPrice: string | null;
  city: string | null;
  county: string | null;
}

export interface DiscoverySession {
  id: string;
  sessionType: 'MARKETPLACE_DISCOVERY';
  status: 'OPEN' | 'CLOSED';
  preferenceState: DiscoveryPreferenceState;
  preferenceVersion: number;
  clarificationTurnCount: number;
  clarificationQuestionCount: number;
  createdAt: string;
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
  condition: 'NEW' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'POOR';
  priceAmount: string;
  currency: string;
  publicCity: string | null;
  publicRegion: string | null;
  matchReason: string;
  constraintCoverage: DiscoveryConstraintCoverage[];
  provenance: DiscoveryProvenance;
}

export interface DiscoveryTurnResult {
  outcome: DiscoveryOutcome;
  message: string;
  questions: string[];
  recommendations: DiscoveryRecommendation[];
  preferenceState: DiscoveryPreferenceState;
  inputTokens: number;
  outputTokens: number;
  estimatedCost: string;
}

export interface DiscoveryHistoryMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  resolutionType: DiscoveryResolution | null;
  result: DiscoveryTurnResult | null;
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
