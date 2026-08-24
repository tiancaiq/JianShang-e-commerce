import { AnalyticsMetric } from '../../core/models/admin-analytics.model';

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1, signDisplay: 'always' });

export function formatAnalyticsValue(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return 'N/A';
  const normalized = unit.toUpperCase();
  if (normalized === 'PERCENT' || normalized === 'PERCENTAGE') {
    return `${number.format(value)}%`;
  }
  if (normalized === 'DURATION_SECONDS' || normalized === 'SECONDS') {
    return formatDuration(value);
  }
  const currency = currencyCode(normalized);
  if (currency) {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(value);
  }
  return number.format(value);
}

export function analyticsComparisonSummary(metric: AnalyticsMetric, compare: boolean): string {
  if (!compare) return '';
  if (metric.comparisonState === 'NEW') return 'New in this period';
  if (metric.comparisonState === 'NOT_APPLICABLE') return 'Comparison N/A';
  if (metric.percentageChange === null || !Number.isFinite(metric.percentageChange)) {
    return 'Comparison N/A';
  }
  const direction = metric.percentageChange > 0 ? 'Up' : metric.percentageChange < 0 ? 'Down' : 'No change';
  const value = metric.percentageChange === 0 ? '0%' : `${percent.format(metric.percentageChange)}%`;
  return `${direction} ${value} from the previous period`;
}

export function analyticsUnitLabel(unit: string): string {
  const normalized = unit.toUpperCase();
  if (normalized === 'COUNT' || normalized === 'NUMBER') return 'Count';
  if (normalized === 'PERCENT' || normalized === 'PERCENTAGE') return 'Percent';
  if (normalized === 'DURATION_SECONDS' || normalized === 'SECONDS') return 'Duration';
  return currencyCode(normalized) || unit.replaceAll('_', ' ').toLowerCase();
}

function currencyCode(unit: string): string | null {
  const explicit = /^CURRENCY[:_\-]([A-Z]{3})$/.exec(unit)?.[1];
  if (explicit) return explicit;
  return /^[A-Z]{3}$/.test(unit) && !['NEW', 'DAY'].includes(unit) ? unit : null;
}

function formatDuration(rawSeconds: number): string {
  const seconds = Math.max(0, Math.round(rawSeconds));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours < 24) return remainingMinutes ? `${hours}h ${remainingMinutes}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const remainingHours = hours % 24;
  return remainingHours ? `${days}d ${remainingHours}h` : `${days}d`;
}
