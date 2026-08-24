import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SupportService } from './support.service';

describe('SupportService', () => {
  let service: SupportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(SupportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates requester tickets without accepting an actor identity and adds idempotency', () => {
    service.create({category: 'ACCOUNT_HELP', subject: 'Account help', description: 'I cannot update my profile.'}).subscribe();

    const request = http.expectOne('/api/v1/support/tickets');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('Idempotency-Key')).toContain('support-create:');
    expect(request.request.body.requesterUserId).toBeUndefined();
    request.flush({data: {}});
  });

  it('sends support inbox filters to the server', () => {
    service.search({
      q: 'refund', category: 'REFUND_HELP', status: 'UNDER_REVIEW', priority: 'HIGH',
      assignment: 'ASSIGNED_TO_ME', linkedOrderId: '01ORDER', page: 2, size: 25, sort: 'updatedAt,desc'
    }).subscribe();

    const request = http.expectOne(candidate => candidate.url === '/api/v1/admin/support/tickets');
    expect(request.request.params.get('q')).toBe('refund');
    expect(request.request.params.get('assignment')).toBe('ASSIGNED_TO_ME');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('linkedOrderId')).toBe('01ORDER');
    request.flush({data: {items: [], page: 2, size: 25, totalElements: 0, totalPages: 0, sort: 'updatedAt,desc'}});
  });

  it('carries expected versions and idempotency on requester-visible admin mutations', () => {
    service.requestInformation('01TICKET', 'Please provide the receipt.', 7).subscribe();

    const request = http.expectOne('/api/v1/admin/support/tickets/01TICKET/request-information');
    expect(request.request.body).toEqual({body: 'Please provide the receipt.', expectedVersion: 7});
    expect(request.request.headers.get('Idempotency-Key')).toContain('support-request-info:');
    request.flush({data: {}});
  });
});
