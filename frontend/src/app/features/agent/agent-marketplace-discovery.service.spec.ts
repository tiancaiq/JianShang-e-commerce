import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import { CsrfSummary } from '../../core/models/auth.model';
import { AuthService } from '../../core/services/auth.service';
import { AGENT_DISCOVERY_ENABLED } from './agent-customer-service.capability';
import {
  AgentMarketplaceDiscoveryService,
  DiscoveryAuthenticationRequiredError,
  DiscoveryContractError,
  DiscoveryFeatureDisabledError,
} from './agent-marketplace-discovery.service';

describe('AgentMarketplaceDiscoveryService', () => {
  const sessionId = '01A00000000000000000000001';
  const listingIds = [
    '01L00000000000000000000001',
    '01L00000000000000000000002',
    '01L00000000000000000000003',
  ];
  let service: AgentMarketplaceDiscoveryService;
  let http: HttpTestingController;
  let authenticated: boolean;
  let csrf: CsrfSummary | null;
  let authService: {
    isAuthenticated: () => boolean;
    csrf: () => CsrfSummary | null;
    ensureSession: jasmine.Spy;
    refreshSession: jasmine.Spy;
  };

  const preferences = {
    query: 'desk chair',
    categoryId: null,
    condition: null,
    minPrice: null,
    maxPrice: '100',
    city: 'Irvine',
    county: null,
  };
  const session = {
    id: sessionId,
    sessionType: 'MARKETPLACE_DISCOVERY',
    status: 'OPEN',
    preferenceState: preferences,
    preferenceVersion: 2,
    clarificationTurnCount: 1,
    clarificationQuestionCount: 1,
    createdAt: '2026-07-20T02:00:00Z',
    updatedAt: '2026-07-20T02:01:00Z',
  };
  const result = {
    outcome: 'RECOMMEND',
    message: 'These current listings match your selected constraints.',
    questions: [],
    recommendations: listingIds.map((listingId, index) => ({
      listingId,
      title: `Desk chair ${index + 1}`,
      categoryId: '01C00000000000000000000001',
      categoryName: 'Furniture',
      condition: 'GOOD',
      priceAmount: `${70 + index}`,
      currency: 'USD',
      publicCity: index === 2 ? null : 'Irvine',
      publicRegion: index === 2 ? null : 'Orange',
      matchReason: 'Within the selected public area and budget.',
      constraintCoverage: ['QUERY', 'PRICE', 'CITY'],
      provenance: {
        listingId,
        checkedAt: '2026-07-20T02:02:00Z',
        responseHash: `${index + 1}`.padStart(64, '0'),
      },
    })),
    preferenceState: preferences,
    inputTokens: 0,
    outputTokens: 0,
    estimatedCost: '0',
  };

  beforeEach(() => {
    authenticated = true;
    csrf = {
      headerName: 'X-CSRF-TOKEN',
      parameterName: '_csrf',
      token: 'discovery-csrf',
    };
    authService = {
      isAuthenticated: () => authenticated,
      csrf: () => csrf,
      ensureSession: jasmine.createSpy('ensureSession')
        .and.returnValue(of({ authenticated: true, user: null })),
      refreshSession: jasmine.createSpy('refreshSession')
        .and.returnValue(of({ authenticated: true, user: null })),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
        { provide: AGENT_DISCOVERY_ENABLED, useValue: true },
      ],
    });
    service = TestBed.inject(AgentMarketplaceDiscoveryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates one discovery session only after auth and CSRF readiness', () => {
    service.createOrResumeSession(false).subscribe();

    expect(authService.ensureSession).not.toHaveBeenCalled();
    const request = http.expectOne('/api/v1/agent/discovery/sessions');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('discovery-csrf');
    expect(request.request.body).toEqual({
      sessionType: 'MARKETPLACE_DISCOVERY',
      newSearch: false,
    });
    expect(request.request.body['actorUserId']).toBeUndefined();
    request.flush(session);
  });

  it('uses exact history and idempotent message contracts', () => {
    service.getMessages(sessionId, 'opaque-cursor', 25).subscribe();
    const history = http.expectOne(request =>
      request.url === `/api/v1/agent/discovery/sessions/${sessionId}/messages`
      && request.params.get('cursor') === 'opaque-cursor'
      && request.params.get('limit') === '25');
    history.flush({ data: [], nextCursor: null, hasMore: false });

    service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ).subscribe();
    const send = http.expectOne(
      `/api/v1/agent/discovery/sessions/${sessionId}/messages`,
    );
    expect(send.request.method).toBe('POST');
    expect(send.request.headers.get('X-CSRF-TOKEN')).toBe('discovery-csrf');
    expect(send.request.body).toEqual({
      clientMessageId: '01M00000000000000000000001',
      expectedPreferenceVersion: 2,
      body: 'Find a desk chair',
    });
    send.flush({
      userMessage: {
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result,
      preferenceVersion: 3,
    });
  });

  it('rejects extra fields, unsafe provenance and arbitrary identifier routes', async () => {
    const expanded = firstValueFrom(service.getSession(sessionId));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}`).flush({
      ...session,
      actorUserId: 'must-not-cross',
    });
    await expectAsync(expanded).toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const badProvenance = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result: {
        ...result,
        recommendations: result.recommendations.map((item, index) => index ? item : {
          ...item,
          provenance: { ...item.provenance, responseHash: 'unsafe' },
        }),
      },
      preferenceVersion: 3,
    });
    await expectAsync(badProvenance).toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const mismatchedProvenance = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000002',
      2,
      'Find another desk chair',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000002',
        role: 'USER',
        body: 'Find another desk chair',
        createdAt: '2026-07-20T02:03:00Z',
      },
      result: {
        ...result,
        recommendations: result.recommendations.map((item, index) => index ? item : {
          ...item,
          provenance: { ...item.provenance, listingId: listingIds[1] },
        }),
      },
      preferenceVersion: 3,
    });
    await expectAsync(mismatchedProvenance)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));

    expect(() => service.getSession('../outside'))
      .toThrowError(DiscoveryContractError);
    http.expectNone(request => request.url.includes('../outside'));
  });

  it('coalesces session warmup and same message sends', async () => {
    authenticated = false;
    csrf = null;
    authService.ensureSession.and.callFake(() => {
      authenticated = true;
      csrf = {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'fresh-discovery-csrf',
      };
      return of({ authenticated: true, user: null });
    });

    const first = firstValueFrom(service.createOrResumeSession(false));
    const second = firstValueFrom(service.createOrResumeSession(false));
    expect(authService.ensureSession).toHaveBeenCalledTimes(1);
    const request = http.expectOne('/api/v1/agent/discovery/sessions');
    request.flush(session);
    expect((await first).id).toBe(sessionId);
    expect((await second).id).toBe(sessionId);

    const firstSend = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    const sameSend = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    const send = http.expectOne(
      `/api/v1/agent/discovery/sessions/${sessionId}/messages`,
    );
    send.flush({
      userMessage: {
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result,
      preferenceVersion: 3,
    });
    expect((await firstSend).preferenceVersion).toBe(3);
    expect((await sameSend).preferenceVersion).toBe(3);
  });

  it('rejects conflicting concurrent create and message idempotency semantics', async () => {
    const resume = firstValueFrom(service.createOrResumeSession(false));
    await expectAsync(firstValueFrom(service.createOrResumeSession(true)))
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));
    const create = http.expectOne('/api/v1/agent/discovery/sessions');
    create.flush(session);
    await resume;

    const first = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    await expectAsync(firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      3,
      'Find a different chair',
    ))).toBeRejectedWith(jasmine.any(DiscoveryContractError));
    const send = http.expectOne(
      `/api/v1/agent/discovery/sessions/${sessionId}/messages`,
    );
    send.flush({
      userMessage: {
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result,
      preferenceVersion: 3,
    });
    await first;
    http.expectNone(request => request.url.includes('/messages') && request !== send.request);
  });

  it('rejects unknown coverage and inconsistent stored assistant results', async () => {
    const badCoverage = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result: {
        ...result,
        recommendations: result.recommendations.map((item, index) => index ? item : {
          ...item,
          constraintCoverage: ['QUERY', 'PRIVATE_SIGNAL'],
        }),
      },
      preferenceVersion: 3,
    });
    await expectAsync(badCoverage)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const history = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [{
        id: '01A00000000000000000000002',
        role: 'ASSISTANT',
        body: 'This body does not match the structured result.',
        resolutionType: 'NO_RESULTS',
        result,
        createdAt: '2026-07-20T02:03:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    });
    await expectAsync(history)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));
  });

  it('maps unauthenticated warmup to auth-required with zero discovery calls', async () => {
    authenticated = false;
    csrf = null;
    authService.ensureSession.and.returnValue(
      of({ authenticated: false, user: null }),
    );

    await expectAsync(firstValueFrom(
      service.createOrResumeSession(false),
    )).toBeRejectedWith(jasmine.any(DiscoveryAuthenticationRequiredError));
    http.expectNone(request => request.url.includes('/agent/discovery/'));
  });

  it('does not retry an uncertain or conflicting POST automatically', async () => {
    const timeout = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`)
      .error(new ProgressEvent('timeout'), { status: 0, statusText: 'Timeout' });
    await expectAsync(timeout).toBeRejectedWith(jasmine.any(HttpErrorResponse));
    http.expectNone(`/api/v1/agent/discovery/sessions/${sessionId}/messages`);

    const conflict = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000002',
      2,
      'Find a desk chair',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`)
      .flush(
        { error: { code: 'AGENT_DISCOVERY_PREFERENCE_VERSION_CONFLICT' } },
        { status: 409, statusText: 'Conflict' },
      );
    await expectAsync(conflict).toBeRejectedWith(jasmine.any(HttpErrorResponse));
    http.expectNone(`/api/v1/agent/discovery/sessions/${sessionId}/messages`);
  });

  it('keeps the disabled service network and Auth silent', async () => {
    const disabled = new AgentMarketplaceDiscoveryService(
      TestBed.inject(HttpClient),
      authService as unknown as AuthService,
      false,
    );

    await expectAsync(firstValueFrom(
      disabled.createOrResumeSession(false),
    )).toBeRejectedWith(jasmine.any(DiscoveryFeatureDisabledError));
    expect(authService.ensureSession).not.toHaveBeenCalled();
    http.expectNone(request => request.url.includes('/agent/discovery/'));
  });

  it('preserves raw 401/403/dependency failures without creating a discovery POST', async () => {
    for (const status of [401, 403, 503, 0]) {
      authenticated = false;
      csrf = null;
      authService.ensureSession.and.returnValue(throwError(() =>
        new HttpErrorResponse({ status }),
      ));
      await expectAsync(firstValueFrom(
        service.createOrResumeSession(false),
      )).toBeRejected();
      http.expectNone('/api/v1/agent/discovery/sessions');
    }
  });
});
