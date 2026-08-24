import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AnalyticsTrend } from '../../core/models/admin-analytics.model';
import { AdminAnalyticsTrendComponent } from './admin-analytics-trend.component';

describe('AdminAnalyticsTrendComponent', () => {
  let fixture: ComponentFixture<AdminAnalyticsTrendComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminAnalyticsTrendComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminAnalyticsTrendComponent);
  });

  it('renders a decorative SVG and a complete accessible data table', () => {
    fixture.componentRef.setInput('trend', trend());
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(element.querySelector('caption')?.textContent).toContain('complete accessible data');
    expect(element.querySelectorAll('tbody tr').length).toBe(2);
    expect(element.textContent).toContain('Total in range');
    expect(element.textContent).toContain('5');
  });

  it('explains an empty trend without relying on a blank plot', () => {
    fixture.componentRef.setInput('trend', { ...trend(), points: [] });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No activity was recorded');
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
  });

  it('does not misreport an unavailable source as zero activity', () => {
    fixture.componentRef.setInput('trend', {
      ...trend(), status: 'UNAVAILABLE', safeMessage: 'The source timed out.', points: [],
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Trend values are unavailable');
    expect(fixture.nativeElement.textContent).not.toContain('No activity was recorded');
  });
});

function trend(): AnalyticsTrend {
  return {
    metric: 'REPORTS_SUBMITTED', label: 'Reports submitted', unit: 'COUNT',
    range: { from: '2026-08-01T00:00:00Z', to: '2026-08-03T00:00:00Z', timezone: 'UTC' },
    granularity: 'DAY', status: 'AVAILABLE', safeMessage: null, generatedAt: '2026-08-03T00:01:00Z',
    points: [
      { bucketStart: '2026-08-01T00:00:00Z', bucketEnd: '2026-08-02T00:00:00Z', value: 2 },
      { bucketStart: '2026-08-02T00:00:00Z', bucketEnd: '2026-08-03T00:00:00Z', value: 3 },
    ],
  };
}
