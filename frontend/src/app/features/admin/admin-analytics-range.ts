import { InjectionToken } from '@angular/core';
import { AnalyticsGranularity, AnalyticsRangePreset } from '../../core/models/admin-analytics.model';

const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_CUSTOM_DAYS = 90;

export const ADMIN_ANALYTICS_NOW = new InjectionToken<() => Date>('ADMIN_ANALYTICS_NOW', {
  providedIn: 'root',
  factory: () => () => new Date(),
});

export interface ResolvedAnalyticsRange {
  preset: AnalyticsRangePreset;
  from: string;
  to: string;
  timezone: 'UTC';
}

export interface AnalyticsRangeResolution {
  range: ResolvedAnalyticsRange | null;
  error: string;
}

export function resolveAnalyticsRange(
  preset: AnalyticsRangePreset,
  customFrom: string,
  customThrough: string,
  now: Date,
): AnalyticsRangeResolution {
  const current = now.getTime();
  const todayStart = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  if (preset !== 'CUSTOM') {
    const days = preset === 'LAST_7_DAYS' ? 7
      : preset === 'LAST_30_DAYS' ? 30
      : 90;
    const from = preset === 'TODAY' ? todayStart : current - days * DAY_MS;
    return {
      range: {
        preset,
        from: new Date(from).toISOString(),
        to: now.toISOString(),
        timezone: 'UTC',
      },
      error: '',
    };
  }

  const from = parseUtcDate(customFrom);
  const through = parseUtcDate(customThrough);
  if (from === null || through === null) {
    return { range: null, error: 'Choose both custom dates.' };
  }
  if (from > through) {
    return { range: null, error: 'The start date must be on or before the through date.' };
  }
  if (from > todayStart || through > todayStart) {
    return { range: null, error: 'Custom ranges cannot include a future UTC date.' };
  }
  const exclusiveEnd = Math.min(through + DAY_MS, current);
  if (from >= exclusiveEnd) {
    return { range: null, error: 'The custom range must contain elapsed time.' };
  }
  if ((exclusiveEnd - from) / DAY_MS > MAX_CUSTOM_DAYS) {
    return { range: null, error: 'Custom ranges can include at most 90 UTC calendar days.' };
  }
  return {
    range: {
      preset,
      from: new Date(from).toISOString(),
      to: new Date(exclusiveEnd).toISOString(),
      timezone: 'UTC',
    },
    error: '',
  };
}

export function defaultAnalyticsCustomDates(now: Date): { from: string; through: string } {
  const todayStart = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  return {
    from: dateInput(new Date(todayStart - 29 * DAY_MS)),
    through: dateInput(new Date(todayStart)),
  };
}

export function analyticsGranularity(range: ResolvedAnalyticsRange): AnalyticsGranularity {
  return Date.parse(range.to) - Date.parse(range.from) <= 2 * DAY_MS ? 'HOUR' : 'DAY';
}

function parseUtcDate(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const timestamp = Date.UTC(year, month - 1, day);
  const parsed = new Date(timestamp);
  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day ? timestamp : null;
}

function dateInput(value: Date): string {
  return value.toISOString().slice(0, 10);
}
