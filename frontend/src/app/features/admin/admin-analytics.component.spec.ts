import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import {
  AdminAnalyticsOverview,
  AnalyticsMetric,
  AnalyticsSection,
  AnalyticsTrend,
} from '../../core/models/admin-analytics.model';
import { AdminAnalyticsService } from '../../core/services/admin-analytics.service';
import { AdminAnalyticsComponent } from './admin-analytics.component';
import { ADMIN_ANALYTICS_NOW } from './admin-analytics-range';

describe('AdminAnalyticsComponent', () => {
  let fixture: ComponentFixture<AdminAnalyticsComponent>;
  let api: jasmine.SpyObj<AdminAnalyticsService>;

  beforeEach(async () => {
    api = jasmine.createSpyObj<AdminAnalyticsService>('AdminAnalyticsService', ['overview', 'trend']);
    api.overview.and.returnValue(of(overview()));
    api.trend.and.returnValue(of(trend()));
    await TestBed.configureTestingModule({
      imports: [AdminAnalyticsComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminAnalyticsService, useValue: api },
        { provide: ADMIN_ANALYTICS_NOW, useValue: () => new Date('2026-08-23T13:45:00Z') },
      ],
    }).compileComponents();
  });

  it('renders permission-aware sections, comparisons, and a validated workflow link', () => {
    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const link = element.querySelector('a.metric') as HTMLAnchorElement;
    expect(element.textContent).toContain('Operational analytics');
    expect(element.textContent).toContain('Support analytics are delayed.');
    expect(element.textContent).toContain('This section is outside your current analytics visibility.');
    expect(element.textContent).toContain('New in this period');
    expect(link.getAttribute('href')).toBe('/admin/reports?assignment=UNASSIGNED&unresolved=true');
    expect(element.textContent).not.toContain('Succeeded refund amount');
    expect(api.overview).toHaveBeenCalledWith({
      range: 'CUSTOM',
      from: '2026-07-24T13:45:00.000Z',
      to: '2026-08-23T13:45:00.000Z',
      timezone: 'UTC',
      compare: true,
    });
  });

  it('renders a financial amount only when the backend includes the metric', () => {
    const value = overview();
    value.commerce.metrics.push(metric('refundAmountSucceeded', 'Succeeded refund amount', 125, null, 'USD'));
    api.overview.and.returnValue(of(value));

    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Succeeded refund amount');
    expect(fixture.nativeElement.textContent).toContain('$125.00');
  });

  it('renders a nullable undefined rate as N/A', () => {
    const value = overview();
    value.commerce.metrics.push(metric('paymentSuccessRate', 'Payment success rate', null, null, 'PERCENT'));
    api.overview.and.returnValue(of(value));

    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    const card = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.metric'))
      .find(element => element.textContent?.includes('Payment success rate'));
    expect(card?.querySelector('strong')?.textContent).toContain('N/A');
  });

  it('renders authoritative finalized appeal outcomes and the exact adjustment rate', () => {
    const value = overview();
    value.trustAndSafety = section('AVAILABLE', [
      metric('appealsFinalized', 'Finalized appeals', 4, null),
      metric('appealsUpheld', 'Upheld', 2, null),
      metric('appealsModified', 'Modified', 1, null),
      metric('appealsRevoked', 'Revoked', 1, null),
      metric('appealAdjustmentRate', 'Appeals changed/reversed', 50, null, 'PERCENT'),
    ]);
    api.overview.and.returnValue(of(value));

    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    const cards = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.metric'));
    const currentValue = (label: string) => cards
      .find(card => card.textContent?.includes(label))
      ?.querySelector('strong')?.textContent?.trim();
    expect(currentValue('Finalized appeals')).toBe('4');
    expect(currentValue('Upheld')).toBe('2');
    expect(currentValue('Modified')).toBe('1');
    expect(currentValue('Revoked')).toBe('1');
    expect(currentValue('Appeals changed/reversed')).toBe('50%');
    expect(fixture.nativeElement.textContent).not.toContain('Appeal outcome analytics are unavailable');
  });

  it('uses semantic breakdown headers and omits a nullable secondary column', () => {
    const value = overview();
    value.commerce.breakdowns = [
      {
        key: 'refundAmounts',
        label: 'Refund succeeded / failed amounts',
        unit: 'MONEY',
        primaryLabel: 'Succeeded amount',
        secondaryLabel: 'Failed amount',
        items: [{ key: 'USD', label: 'USD', value: 125, secondaryValue: 8 }],
      },
      {
        key: 'activeRefundAmounts',
        label: 'Pending and processing refund amounts',
        unit: 'MONEY',
        primaryLabel: 'Pending and processing amount',
        secondaryLabel: null,
        items: [{ key: 'USD', label: 'USD', value: 42 }],
      },
    ];
    api.overview.and.returnValue(of(value));

    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    const tables = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.breakdown-shell table'));
    const refundTable = tables.find(table => table.querySelector('caption')?.textContent?.includes('Refund succeeded'))!;
    const activeTable = tables.find(table => table.querySelector('caption')?.textContent?.includes('Pending and processing'))!;
    const headers = (table: Element) => Array.from(table.querySelectorAll('thead th'));

    expect(headers(refundTable).map(header => header.textContent?.trim())).toEqual([
      'Category', 'Succeeded amount', 'Failed amount',
    ]);
    expect(headers(refundTable).every(header => header.getAttribute('scope') === 'col')).toBeTrue();
    expect(headers(activeTable).map(header => header.textContent?.trim())).toEqual([
      'Category', 'Pending and processing amount',
    ]);
    expect(activeTable.querySelector('tbody tr')?.children.length).toBe(2);
    expect(activeTable.querySelector('a')).toBeNull();
  });

  it('sends every selector as an explicit CUSTOM half-open range and rejects invalid custom input', () => {
    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    api.overview.calls.reset();
    const expected = [
      ['TODAY', '2026-08-23T00:00:00.000Z'],
      ['LAST_7_DAYS', '2026-08-16T13:45:00.000Z'],
      ['LAST_30_DAYS', '2026-07-24T13:45:00.000Z'],
      ['LAST_90_DAYS', '2026-05-25T13:45:00.000Z'],
    ] as const;
    for (const [preset, from] of expected) {
      component.selectPreset(preset);
      expect(api.overview.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
        range: 'CUSTOM', from, to: '2026-08-23T13:45:00.000Z',
      }));
    }

    component.rangePreset = 'CUSTOM';
    component.customFrom = '2026-08-01';
    component.customThrough = '2026-08-07';
    component.refresh();
    expect(api.overview.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      range: 'CUSTOM', from: '2026-08-01T00:00:00.000Z', to: '2026-08-08T00:00:00.000Z',
    }));

    const calls = api.overview.calls.count();
    component.customFrom = '2026-08-20';
    component.customThrough = '2026-08-01';
    component.refresh();
    fixture.detectChanges();

    expect(api.overview.calls.count()).toBe(calls);
    expect(fixture.nativeElement.textContent).toContain('start date must be on or before');
  });

  it('keeps the overview usable when the separate trend request fails', () => {
    api.trend.and.returnValue(throwError(() => ({ status: 503 })));

    fixture = TestBed.createComponent(AdminAnalyticsComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Marketplace overview');
    expect(fixture.nativeElement.textContent).toContain('Trend data is temporarily unavailable');
    expect(fixture.nativeElement.textContent).not.toContain('Analytics snapshot unavailable');
  });
});

function overview(): AdminAnalyticsOverview {
  return {
    range: { from: '2026-07-25T00:00:00Z', to: '2026-08-24T00:00:00Z', timezone: 'UTC' },
    capabilities: { financialAmounts: false, operations: true, governance: false },
    marketplace: section(),
    moderation: section(),
    trustAndSafety: section('AVAILABLE', [
      metric('unassignedReports', 'Unassigned reports', 4, 0, 'COUNT', 'UNASSIGNED_REPORTS', 'NEW'),
    ]),
    commerce: section('AVAILABLE', [metric('refundsSucceeded', 'Succeeded refunds', 2, 1)]),
    support: section('DEGRADED', [], 'Support analytics are delayed.'),
    catalog: section(),
    operations: section(),
    governance: section('RESTRICTED'),
    generatedAt: '2026-08-23T13:46:00Z',
  };
}

function section(
  status: AnalyticsSection['status'] = 'AVAILABLE',
  metrics: AnalyticsMetric[] = [],
  safeMessage: string | null = null,
): AnalyticsSection {
  return { status, safeMessage, dataAsOf: '2026-08-23T13:45:00Z', metrics, breakdowns: [] };
}

function metric(
  key: string,
  label: string,
  currentValue: number | null,
  previousValue: number | null,
  unit = 'COUNT',
  drillDownKey?: AnalyticsMetric['drillDownKey'],
  comparisonState: AnalyticsMetric['comparisonState'] = 'VALUE',
): AnalyticsMetric {
  return {
    key, label, currentValue, previousValue,
    absoluteChange: currentValue === null || previousValue === null ? null : currentValue - previousValue,
    percentageChange: currentValue !== null && previousValue ? (currentValue - previousValue) / previousValue * 100 : null,
    comparisonState, unit, drillDownKey,
  };
}

function trend(): AnalyticsTrend {
  return {
    metric: 'REPORTS_SUBMITTED', label: 'Reports submitted', unit: 'COUNT',
    range: { from: '2026-07-25T00:00:00Z', to: '2026-08-24T00:00:00Z', timezone: 'UTC' },
    granularity: 'DAY', status: 'AVAILABLE', safeMessage: null,
    points: [{ bucketStart: '2026-08-22T00:00:00Z', bucketEnd: '2026-08-23T00:00:00Z', value: 2 }],
    generatedAt: '2026-08-23T13:46:00Z',
  };
}
