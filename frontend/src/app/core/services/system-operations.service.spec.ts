import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { SystemOperationsService } from './system-operations.service';

describe('SystemOperationsService', () => {
  let service: SystemOperationsService;
  let http: HttpTestingController;
  const base = `${environment.apiGatewayUrl}/api/v1/admin/system`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SystemOperationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends server-side job filters without blank values', () => {
    service.jobs({ service: 'PRODUCT', status: '', retryable: true, page: 2 }).subscribe();

    const request = http.expectOne(req => req.url === `${base}/jobs`);
    expect(request.request.params.get('service')).toBe('PRODUCT');
    expect(request.request.params.has('status')).toBeFalse();
    expect(request.request.params.get('retryable')).toBe('true');
    expect(request.request.params.get('page')).toBe('2');
    request.flush({ items: [], page: 2, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('uses dry-run and idempotent command endpoints for outbox recovery', () => {
    service.previewOutbox('event/one', 'Retry after transport recovery').subscribe();
    const preview = http.expectOne(`${base}/outbox/event%2Fone/retry/dry-run`);
    expect(preview.request.method).toBe('POST');
    preview.flush({});

    service.retryOutbox('event/one', { reason: 'Retry after transport recovery' }, 'operation-key-123').subscribe();
    const command = http.expectOne(`${base}/outbox/event%2Fone/retry`);
    expect(command.request.headers.get('Idempotency-Key')).toBe('operation-key-123');
    expect(command.request.body).toEqual({ reason: 'Retry after transport recovery' });
    command.flush({});
  });

  it('keeps listing reindex bounded to one encoded listing identifier', () => {
    service.reindex('listing:1', { reason: 'Repair failed projection work' }, 'search-key-123').subscribe();
    const request = http.expectOne(`${base}/search/listings/listing%3A1/reindex`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('search-key-123');
    request.flush({});
  });
});
