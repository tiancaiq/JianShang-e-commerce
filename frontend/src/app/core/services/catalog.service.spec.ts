import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CatalogService } from './catalog.service';
import { environment } from '../../../environments/environment';

describe('CatalogService', () => {
  let service: CatalogService;
  let http: HttpTestingController;
  const base = `${environment.apiGatewayUrl}/api/v1/admin/catalog/categories`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('passes trimmed catalog filters to the hierarchy overview', () => {
    service.overview({ q: '  bikes ', status: 'ACTIVE', sellerEligibility: 'BOTH' }).subscribe();

    const request = http.expectOne(req => req.url === base);
    expect(request.request.params.get('q')).toBe('bikes');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    expect(request.request.params.get('sellerEligibility')).toBe('BOTH');
    expect(request.request.withCredentials).toBeTrue();
    request.flush({});
  });

  it('uses a dry-run endpoint before category status changes', () => {
    service.statusPreview('category-1', { status: 'DISABLED' }).subscribe();

    const request = http.expectOne(`${base}/category-1/status/dry-run`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ status: 'DISABLED' });
    request.flush({});
  });

  it('adds an idempotency key to category creation', () => {
    service.create({ name: 'Bikes' }).subscribe();

    const request = http.expectOne(base);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^catalog-/);
    request.flush({});
  });
});
