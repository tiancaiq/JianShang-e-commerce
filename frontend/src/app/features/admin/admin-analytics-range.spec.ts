import { analyticsGranularity, defaultAnalyticsCustomDates, resolveAnalyticsRange } from './admin-analytics-range';

describe('admin analytics UTC ranges', () => {
  const now = new Date('2026-08-23T13:45:00Z');

  it('resolves presets to elapsed UTC windows with an exclusive current-time end', () => {
    expect(resolveAnalyticsRange('TODAY', '', '', now).range).toEqual({
      preset: 'TODAY',
      from: '2026-08-23T00:00:00.000Z',
      to: '2026-08-23T13:45:00.000Z',
      timezone: 'UTC',
    });
    expect(resolveAnalyticsRange('LAST_7_DAYS', '', '', now).range?.from)
      .toBe('2026-08-16T13:45:00.000Z');
    expect(resolveAnalyticsRange('LAST_30_DAYS', '', '', now).range?.from)
      .toBe('2026-07-24T13:45:00.000Z');
    expect(resolveAnalyticsRange('LAST_90_DAYS', '', '', now).range?.from)
      .toBe('2026-05-25T13:45:00.000Z');
  });

  it('includes the custom through date by advancing the exclusive boundary', () => {
    const result = resolveAnalyticsRange('CUSTOM', '2026-08-01', '2026-08-07', now);

    expect(result.error).toBe('');
    expect(result.range).toEqual({
      preset: 'CUSTOM',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-08T00:00:00.000Z',
      timezone: 'UTC',
    });
  });

  it('ends a custom range through today at the current instant and rejects future dates', () => {
    expect(resolveAnalyticsRange('CUSTOM', '2026-08-22', '2026-08-23', now).range?.to)
      .toBe('2026-08-23T13:45:00.000Z');
    expect(resolveAnalyticsRange('CUSTOM', '2026-08-23', '2026-08-24', now).error)
      .toContain('future');
  });

  it('rejects inverted, invalid, and overlong custom ranges', () => {
    expect(resolveAnalyticsRange('CUSTOM', '2026-08-08', '2026-08-01', now).range).toBeNull();
    expect(resolveAnalyticsRange('CUSTOM', '2026-02-30', '2026-03-01', now).range).toBeNull();
    expect(resolveAnalyticsRange('CUSTOM', '2026-01-01', '2026-08-01', now).error).toContain('at most 90');
  });

  it('uses hourly points only for ranges no longer than two days', () => {
    const today = resolveAnalyticsRange('TODAY', '', '', now).range!;
    const week = resolveAnalyticsRange('LAST_7_DAYS', '', '', now).range!;

    expect(analyticsGranularity(today)).toBe('HOUR');
    expect(analyticsGranularity(week)).toBe('DAY');
    expect(defaultAnalyticsCustomDates(now)).toEqual({ from: '2026-07-25', through: '2026-08-23' });
  });
});
