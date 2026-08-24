import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AdminAnalyticsService } from './admin-analytics.service';

describe('AdminAnalyticsService', () => {
  let service: AdminAnalyticsService;
  let http: HttpTestingController;
  const base = `${environment.apiGatewayUrl}/api/v1/admin/analytics`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminAnalyticsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends a bounded custom overview request with comparison', () => {
    service.overview({
      range: 'CUSTOM',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-08T00:00:00.000Z',
      timezone: 'UTC',
      compare: true,
    }).subscribe();

    const request = http.expectOne(req => req.url === `${base}/overview`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.params.get('from')).toBe('2026-08-01T00:00:00.000Z');
    expect(request.request.params.get('to')).toBe('2026-08-08T00:00:00.000Z');
    expect(request.request.params.get('timezone')).toBe('UTC');
    expect(request.request.params.get('compare')).toBe('true');
    expect(request.request.params.get('range')).toBe('CUSTOM');
    request.flush({});
  });

  it('supports the approved preset query without inventing dates', () => {
    service.overview({ range: 'LAST_30_DAYS', timezone: 'UTC', compare: false }).subscribe();

    const request = http.expectOne(req => req.url === `${base}/overview`);
    expect(request.request.params.get('range')).toBe('LAST_30_DAYS');
    expect(request.request.params.has('from')).toBeFalse();
    request.flush({});
  });

  it('uses a typed trend metric and bounded granularity', () => {
    service.trend({
      range: 'CUSTOM',
      metric: 'REPORTS_SUBMITTED',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-08T00:00:00.000Z',
      timezone: 'UTC',
      granularity: 'DAY',
    }).subscribe();

    const request = http.expectOne(req => req.url === `${base}/trends`);
    expect(request.request.params.get('metric')).toBe('REPORTS_SUBMITTED');
    expect(request.request.params.get('range')).toBe('CUSTOM');
    expect(request.request.params.get('granularity')).toBe('DAY');
    expect(request.request.params.get('timezone')).toBe('UTC');
    request.flush({});
  });
});
