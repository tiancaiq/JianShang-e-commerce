import { AnalyticsMetric } from '../../core/models/admin-analytics.model';
import { analyticsComparisonSummary, formatAnalyticsValue } from './admin-analytics-format';

describe('admin analytics presentation formatting', () => {
  it('uses neutral directional language, including a zero change', () => {
    expect(analyticsComparisonSummary(metric('VALUE', 12.5), true))
      .toBe('Up +12.5% from the previous period');
    expect(analyticsComparisonSummary(metric('VALUE', -8), true))
      .toBe('Down -8% from the previous period');
    expect(analyticsComparisonSummary(metric('VALUE', 0), true))
      .toBe('No change 0% from the previous period');
  });

  it('does not invent infinity when a comparison is new or unavailable', () => {
    expect(analyticsComparisonSummary(metric('NEW', null), true)).toBe('New in this period');
    expect(analyticsComparisonSummary(metric('NOT_APPLICABLE', null), true)).toBe('Comparison N/A');
    expect(analyticsComparisonSummary(metric('VALUE', null), true)).toBe('Comparison N/A');
  });

  it('formats typed currency and server-computed duration values', () => {
    expect(formatAnalyticsValue(125, 'USD')).toContain('125.00');
    expect(formatAnalyticsValue(3_900, 'DURATION_SECONDS')).toBe('1h 5m');
  });

  it('renders an undefined rate as N/A instead of coercing it to zero', () => {
    expect(formatAnalyticsValue(null, 'PERCENT')).toBe('N/A');
  });
});

function metric(
  comparisonState: AnalyticsMetric['comparisonState'],
  percentageChange: number | null,
): AnalyticsMetric {
  return {
    key: 'value', label: 'Value', currentValue: 1, previousValue: 1,
    absoluteChange: 0, percentageChange, comparisonState, unit: 'COUNT',
  };
}
