import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AgentContractError, AgentCustomerService } from './agent-customer-service.service';

describe('AgentCustomerService', () => {
  let service: AgentCustomerService;
  let http: HttpTestingController;

  const session = {
    id: '01A00000000000000000000001',
    sessionType: 'LISTING_CUSTOMER_SERVICE',
    status: 'OPEN',
    subjectListing: {
      id: '01L00000000000000000000001',
      version: '12',
      title: 'Used bicycle',
      thumbnailUrl: '/api/v1/public/listing-media/01I00000000000000000000001',
      transactionNotice: 'Payment and delivery are arranged directly by participants.',
    },
    createdAt: '2026-07-20T02:00:00Z',
    updatedAt: '2026-07-20T02:00:00Z',
  };

  const userMessage = {
    id: '01M00000000000000000000001',
    role: 'USER',
    body: 'Is the bicycle available?',
    resolutionType: null,
    sources: [],
    actions: [],
    createdAt: '2026-07-20T02:01:00Z',
  };

  const assistantMessage = {
    id: '01M00000000000000000000002',
    role: 'ASSISTANT',
    body: 'The current listing is active.',
    resolutionType: 'ANSWERED',
    sources: [{
      sourceType: 'LISTING',
      sourceId: '01L00000000000000000000001',
      sourceVersion: '12',
      label: 'Current listing',
    }],
    actions: [{
      type: 'VIEW_LISTING',
      listingId: '01L00000000000000000000001',
    }],
    createdAt: '2026-07-20T02:01:01Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(AgentCustomerService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a listing session without browser-supplied actor fields', () => {
    let result: unknown;
    service.createOrResumeSession('01L00000000000000000000001').subscribe(value => result = value);

    const request = http.expectOne('/api/v1/agent/sessions');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.body).toEqual({
      sessionType: 'LISTING_CUSTOMER_SERVICE',
      subject: {
        type: 'LISTING',
        id: '01L00000000000000000000001',
      },
    });
    expect(request.request.body['actorUserId']).toBeUndefined();
    request.flush(session);

    expect(result).toEqual(session);
  });

  it('uses the exact session/message routes and preserves the idempotency key in the request body', () => {
    service.getSession(session.id).subscribe();
    http.expectOne(`/api/v1/agent/sessions/${session.id}`).flush(session);

    service.getMessages(session.id, 'opaque-cursor', 25).subscribe();
    const messages = http.expectOne(request =>
      request.url === `/api/v1/agent/sessions/${session.id}/messages`
      && request.params.get('cursor') === 'opaque-cursor'
      && request.params.get('limit') === '25');
    messages.flush({ data: [userMessage], nextCursor: null, hasMore: false });

    service.sendMessage(session.id, '01C00000000000000000000001', 'Is the bicycle available?').subscribe();
    const send = http.expectOne(`/api/v1/agent/sessions/${session.id}/messages`);
    expect(send.request.method).toBe('POST');
    expect(send.request.withCredentials).toBeTrue();
    expect(send.request.body).toEqual({
      clientMessageId: '01C00000000000000000000001',
      body: 'Is the bicycle available?',
    });
    send.flush({ userMessage, assistantMessage });
  });

  it('rejects malformed or expanded response contracts instead of trusting server-shaped unknown data', () => {
    let contractError: unknown;
    service.getSession(session.id).subscribe({
      error: error => contractError = error,
    });
    http.expectOne(`/api/v1/agent/sessions/${session.id}`).flush({
      ...session,
      actorUserId: 'must-not-cross-the-boundary',
    });

    expect(contractError).toEqual(jasmine.any(AgentContractError));
  });

  it('rejects arbitrary action targets and inconsistent pagination', () => {
    let actionError: unknown;
    service.sendMessage(session.id, '01C00000000000000000000001', 'Question').subscribe({
      error: error => actionError = error,
    });
    http.expectOne(`/api/v1/agent/sessions/${session.id}/messages`).flush({
      userMessage,
      assistantMessage: {
        ...assistantMessage,
        actions: [{ type: 'OPEN_URL', url: 'https://example.invalid' }],
      },
    });
    expect(actionError).toEqual(jasmine.any(AgentContractError));

    let pageError: unknown;
    service.getMessages(session.id).subscribe({ error: error => pageError = error });
    http.expectOne(request => request.url === `/api/v1/agent/sessions/${session.id}/messages`)
      .flush({ data: [], nextCursor: null, hasMore: true });
    expect(pageError).toEqual(jasmine.any(AgentContractError));
  });
});
