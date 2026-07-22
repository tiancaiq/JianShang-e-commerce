import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, Subject, throwError } from 'rxjs';
import { AuthState, CsrfSummary } from '../../core/models/auth.model';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import { AuthService } from '../../core/services/auth.service';
import { AGENT_CUSTOMER_SERVICE_ENABLED } from './agent-customer-service.capability';
import {
  AgentAuthenticationRequiredError,
  AgentContractError,
  AgentCustomerService,
  AgentFeatureDisabledError,
} from './agent-customer-service.service';

describe('AgentCustomerService', () => {
  const WALNUT_LISTING_ID = '01D00000000000000000000101';
  let service: AgentCustomerService;
  let http: HttpTestingController;
  let httpClient: HttpClient;
  let authenticated: boolean;
  let csrf: CsrfSummary | null;
  let authService: {
    isAuthenticated: () => boolean;
    csrf: () => CsrfSummary | null;
    ensureSession: jasmine.Spy<() => ReturnType<AuthService['ensureSession']>>;
    refreshSession: jasmine.Spy<() => ReturnType<AuthService['refreshSession']>>;
  };

  const session = {
    id: '01A00000000000000000000001',
    sessionType: 'LISTING_CUSTOMER_SERVICE',
    status: 'OPEN',
    subjectListing: {
      id: WALNUT_LISTING_ID,
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
    authenticated = true;
    csrf = {
      headerName: 'X-CSRF-TOKEN',
      parameterName: '_csrf',
      token: 'agent-csrf-token',
    };
    authService = {
      isAuthenticated: () => authenticated,
      csrf: () => csrf,
      ensureSession: jasmine.createSpy('ensureSession').and.returnValue(of({ authenticated: true, user: null })),
      refreshSession: jasmine.createSpy('refreshSession').and.returnValue(of({ authenticated: true, user: null })),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: true },
      ],
    });
    service = TestBed.inject(AgentCustomerService);
    httpClient = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a listing session without browser-supplied actor fields', () => {
    let result: unknown;
    service.createOrResumeSession(WALNUT_LISTING_ID).subscribe(value => result = value);

    const request = http.expectOne('/api/v1/agent/sessions');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('agent-csrf-token');
    expect(request.request.body).toEqual({
      sessionType: 'LISTING_CUSTOMER_SERVICE',
      subject: {
        type: 'LISTING',
        id: WALNUT_LISTING_ID,
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
    expect(send.request.headers.get('X-CSRF-TOKEN')).toBe('agent-csrf-token');
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

  it('waits for one coalesced authenticated session warmup before creating once', async () => {
    authenticated = false;
    csrf = null;
    const warmup = new Subject<AuthState>();
    authService.ensureSession.and.returnValue(warmup);

    const first = firstValueFrom(service.createOrResumeSession(session.subjectListing.id));
    const second = firstValueFrom(service.createOrResumeSession(session.subjectListing.id));

    http.expectNone('/api/v1/agent/sessions');
    expect(authService.ensureSession).toHaveBeenCalledTimes(1);

    authenticated = true;
    csrf = {
      headerName: 'X-CSRF-TOKEN',
      parameterName: '_csrf',
      token: 'fresh-agent-csrf-token',
    };
    warmup.next({ authenticated: true, user: null });
    warmup.complete();

    const request = http.expectOne('/api/v1/agent/sessions');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('fresh-agent-csrf-token');
    request.flush(session);

    expect((await first).id).toBe(session.id);
    expect((await second).id).toBe(session.id);
  });

  it('uses an already-ready session without a redundant Auth request', () => {
    service.createOrResumeSession(session.subjectListing.id).subscribe();

    expect(authService.ensureSession).not.toHaveBeenCalled();
    expect(authService.refreshSession).not.toHaveBeenCalled();
    http.expectOne('/api/v1/agent/sessions').flush(session);
  });

  it('refreshes a signed-in session that is missing CSRF before the Agent POST', () => {
    csrf = null;
    authService.ensureSession.and.returnValue(of({ authenticated: true, user: null }));
    authService.refreshSession.and.callFake(() => {
      csrf = {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'refreshed-agent-csrf-token',
      };
      return of({ authenticated: true, user: null });
    });

    service.createOrResumeSession(session.subjectListing.id).subscribe();

    expect(authService.ensureSession).toHaveBeenCalledTimes(1);
    expect(authService.refreshSession).toHaveBeenCalledTimes(1);
    const request = http.expectOne('/api/v1/agent/sessions');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('refreshed-agent-csrf-token');
    request.flush(session);
  });

  it('maps unauthenticated preflight to auth-required with zero Agent calls', async () => {
    authenticated = false;
    csrf = null;
    authService.ensureSession.and.returnValue(of({ authenticated: false, user: null }));

    await expectAsync(firstValueFrom(
      service.createOrResumeSession(session.subjectListing.id),
    )).toBeRejectedWith(jasmine.any(AgentAuthenticationRequiredError));
    http.expectNone('/api/v1/agent/sessions');
  });

  it('preserves Auth 401, 403, 5xx and timeout failures without an Agent POST', async () => {
    for (const error of [
      new HttpErrorResponse({ status: 401 }),
      new HttpErrorResponse({ status: 403 }),
      new HttpErrorResponse({ status: 503 }),
      new HttpErrorResponse({ status: 0, statusText: 'Timeout' }),
    ]) {
      authenticated = false;
      csrf = null;
      authService.ensureSession.and.returnValue(throwError(() => error));

      await expectAsync(firstValueFrom(
        service.createOrResumeSession(session.subjectListing.id),
      )).toBeRejectedWith(error);
      http.expectNone('/api/v1/agent/sessions');
    }
  });

  it('coalesces the same message key and never automatically retries an ambiguous timeout', async () => {
    const first = firstValueFrom(service.sendMessage(
      session.id,
      '01C00000000000000000000001',
      'Is the bicycle available?',
    ));
    const second = firstValueFrom(service.sendMessage(
      session.id,
      '01C00000000000000000000001',
      'Is the bicycle available?',
    ));

    const request = http.expectOne(`/api/v1/agent/sessions/${session.id}/messages`);
    request.flush({ userMessage, assistantMessage });
    expect((await first).userMessage.id).toBe(userMessage.id);
    expect((await first).assistantMessage.id).toBe(assistantMessage.id);
    expect((await second).userMessage.id).toBe(userMessage.id);
    expect((await second).assistantMessage.id).toBe(assistantMessage.id);

    const timedOut = firstValueFrom(service.sendMessage(
      session.id,
      '01C00000000000000000000002',
      'Can I collect it today?',
    ));
    http.expectOne(`/api/v1/agent/sessions/${session.id}/messages`).error(
      new ProgressEvent('timeout'),
    );
    await expectAsync(timedOut).toBeRejected();
    http.expectNone(`/api/v1/agent/sessions/${session.id}/messages`);

    service.sendMessage(
      session.id,
      '01C00000000000000000000002',
      'Can I collect it today?',
    ).subscribe();
    http.expectOne(`/api/v1/agent/sessions/${session.id}/messages`)
      .flush({ userMessage, assistantMessage });
  });

  it('does not refresh or replay an Agent POST rejected with 403', async () => {
    const result = firstValueFrom(service.createOrResumeSession(session.subjectListing.id));
    http.expectOne('/api/v1/agent/sessions').flush(
      { error: { code: 'FORBIDDEN' } },
      { status: 403, statusText: 'Forbidden' },
    );

    await expectAsync(result).toBeRejected();
    expect(authService.ensureSession).not.toHaveBeenCalled();
    expect(authService.refreshSession).not.toHaveBeenCalled();
    http.expectNone('/api/v1/agent/sessions');
  });

  it('makes zero Auth and Agent calls when the build capability is disabled', async () => {
    const disabled = new AgentCustomerService(
      httpClient,
      authService as unknown as AuthService,
      false,
    );

    await expectAsync(firstValueFrom(
      disabled.createOrResumeSession(session.subjectListing.id),
    )).toBeRejectedWith(jasmine.any(AgentFeatureDisabledError));
    await expectAsync(firstValueFrom(
      disabled.sendMessage(session.id, '01C00000000000000000000001', 'Question'),
    )).toBeRejectedWith(jasmine.any(AgentFeatureDisabledError));
    expect(authService.ensureSession).not.toHaveBeenCalled();
    expect(authService.refreshSession).not.toHaveBeenCalled();
    http.expectNone(request => request.url.startsWith('/api/v1/agent/'));
  });
});
