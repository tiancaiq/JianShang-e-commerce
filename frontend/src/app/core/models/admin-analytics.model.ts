export type AnalyticsRangePreset =
  | 'TODAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'LAST_90_DAYS'
  | 'CUSTOM';

export type AnalyticsSectionStatus = 'AVAILABLE' | 'DEGRADED' | 'UNAVAILABLE' | 'RESTRICTED';
export type AnalyticsComparisonState = 'VALUE' | 'NEW' | 'NOT_APPLICABLE';
export type AnalyticsGranularity = 'HOUR' | 'DAY' | 'WEEK';

export type AnalyticsTrendMetric =
  | 'REPORTS_SUBMITTED'
  | 'ORDERS_CREATED'
  | 'DISPUTES_OPENED'
  | 'REFUNDS_SUCCEEDED'
  | 'SUPPORT_TICKETS_CREATED'
  | 'LISTINGS_CREATED';

export type AnalyticsDrillDownKey =
  | 'UNASSIGNED_REPORTS'
  | 'READY_FOR_INVESTIGATION_REPORTS'
  | 'OPEN_CASES'
  | 'UNASSIGNED_CASES'
  | 'READY_FOR_ACTION_CASES'
  | 'PENDING_APPEALS'
  | 'OPEN_DISPUTES'
  | 'UNASSIGNED_DISPUTES'
  | 'FAILED_REFUNDS'
  | 'PROCESSING_REFUNDS'
  | 'OPEN_SUPPORT_TICKETS'
  | 'UNASSIGNED_SUPPORT_TICKETS'
  | 'WAITING_FOR_USER_SUPPORT_TICKETS'
  | 'FAILED_JOBS'
  | 'FAILED_OUTBOX_EVENTS'
  | 'DEAD_LETTER_OUTBOX_EVENTS'
  | 'RECONCILIATION_ISSUES'
  | 'INVENTORY_ISSUES'
  | 'SEARCH_INDEX_FAILURES'
  | 'PENDING_BUSINESS_APPLICATIONS'
  | 'UNASSIGNED_LISTING_CASES'
  | 'CANCELLED_ORDERS'
  | 'COMPLETED_ORDERS'
  | 'FAILED_PAYMENTS'
  | 'PENDING_GOVERNANCE_APPROVALS'
  | 'ACTIVE_TEMPORARY_ELEVATIONS';

export interface AnalyticsRange {
  preset?: AnalyticsRangePreset;
  from: string;
  to: string;
  timezone: string;
  comparisonFrom?: string | null;
  comparisonTo?: string | null;
}

export interface AnalyticsMetric {
  key: string;
  label: string;
  currentValue: number | null;
  previousValue: number | null;
  absoluteChange: number | null;
  percentageChange: number | null;
  comparisonState: AnalyticsComparisonState;
  unit: string;
  drillDownKey?: AnalyticsDrillDownKey | null;
}

export interface AnalyticsBreakdownItem {
  key: string;
  label: string;
  value: number;
  secondaryValue?: number | null;
  drillDownKey?: AnalyticsDrillDownKey | null;
}

export interface AnalyticsBreakdown {
  key: string;
  label: string;
  unit: string;
  primaryLabel: string;
  secondaryLabel: string | null;
  items: AnalyticsBreakdownItem[];
}

export interface AnalyticsSection {
  status: AnalyticsSectionStatus;
  safeMessage: string | null;
  dataAsOf: string | null;
  metrics: AnalyticsMetric[];
  breakdowns: AnalyticsBreakdown[];
}

export interface AnalyticsCapabilities {
  financialAmounts: boolean;
  operations: boolean;
  governance: boolean;
}

export interface AdminAnalyticsOverview {
  range: AnalyticsRange;
  capabilities: AnalyticsCapabilities;
  marketplace: AnalyticsSection;
  moderation: AnalyticsSection;
  trustAndSafety: AnalyticsSection;
  commerce: AnalyticsSection;
  support: AnalyticsSection;
  catalog: AnalyticsSection;
  operations: AnalyticsSection;
  governance: AnalyticsSection;
  generatedAt: string;
}

export interface AnalyticsTrendPoint {
  bucketStart: string;
  bucketEnd: string;
  value: number;
}

export interface AnalyticsTrend {
  metric: AnalyticsTrendMetric;
  label: string;
  unit: string;
  range: AnalyticsRange;
  granularity: AnalyticsGranularity;
  status: AnalyticsSectionStatus;
  safeMessage: string | null;
  points: AnalyticsTrendPoint[];
  generatedAt: string;
}

interface AdminAnalyticsQueryBase {
  timezone: string;
  compare: boolean;
}

export type AdminAnalyticsOverviewQuery = AdminAnalyticsQueryBase & (
  | { range: Exclude<AnalyticsRangePreset, 'CUSTOM'>; from?: never; to?: never }
  | { range: 'CUSTOM'; from: string; to: string }
);

export interface AdminAnalyticsTrendQuery {
  range: 'CUSTOM';
  metric: AnalyticsTrendMetric;
  from: string;
  to: string;
  timezone: string;
  granularity: AnalyticsGranularity;
}
