import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError, toArray } from 'rxjs';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import { CsrfSummary } from '../../core/models/auth.model';
import { AuthService } from '../../core/services/auth.service';
import { AGENT_DISCOVERY_ENABLED } from './agent-customer-service.capability';
import {
  AgentMarketplaceDiscoveryService,
  AGENT_DISCOVERY_FETCH,
  DiscoveryFetch,
  DiscoveryAuthenticationRequiredError,
  DiscoveryContractError,
  DiscoveryFeatureDisabledError,
  DiscoverySseParser,
  DiscoveryStreamFailureError,
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
  let fetchSpy: jasmine.Spy<DiscoveryFetch>;
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
    selectedListingId: null,
  };
  const session = {
    id: sessionId,
    sessionType: 'MARKETPLACE_DISCOVERY',
    status: 'OPEN',
    preferenceState: preferences,
    preferenceVersion: 2,
    clarificationTurnCount: 1,
    clarificationQuestionCount: 1,
    exclusions: [],
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
      thumbnailUrl: index === 0
        ? '/api/v1/public/listing-media/01I00000000000000000000001'
        : null,
      sellerType: 'INDIVIDUAL',
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
    fetchSpy = jasmine.createSpy<DiscoveryFetch>('discoveryFetch');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
        { provide: AGENT_DISCOVERY_ENABLED, useValue: true },
        { provide: AGENT_DISCOVERY_FETCH, useValue: fetchSpy },
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

  it('strictly accepts a conversational intent and direct answer outcome', async () => {
    const response = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000004',
      2,
      'Hi',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000004',
        role: 'USER',
        body: 'Hi',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result: {
        outcome: 'ANSWER',
        intent: 'GENERAL_CONVERSATION',
        message: 'Hi! How can I help with the marketplace today?',
        questions: [],
        recommendations: [],
        preferenceState: {
          ...preferences,
          status: 'COLLECTING_PREFERENCES',
          requestedCategory: 'chair',
          categoryAvailability: 'AVAILABLE',
          categoryInventoryCount: 5,
          clarificationsAsked: 1,
          lastSearchOutcome: 'RESULTS_AVAILABLE',
          activeGoal: 'FIND_PRODUCT',
          activeCategory: 'chair',
          workflowStatus: 'CLARIFYING',
          referencedListings: [],
          lastToolActions: [{
            action: 'CHECK_AVAILABILITY',
            tool: 'CHECK_AVAILABILITY',
            result: 'AVAILABLE',
          }],
        },
        inputTokens: 0,
        outputTokens: 0,
        estimatedCost: '0',
      },
      preferenceVersion: 3,
    });

    const parsed = await response;
    expect(parsed.result.outcome).toBe('ANSWER');
    expect(parsed.result.intent).toBe('GENERAL_CONVERSATION');
    expect(parsed.result.recommendations).toEqual([]);
    expect(parsed.result.preferenceState.activeGoal).toBe('FIND_PRODUCT');
    expect(parsed.result.preferenceState.lastToolActions?.[0].action)
      .toBe('CHECK_AVAILABILITY');
  });

  it('accepts explicit unavailable inventory without treating it as an error or cards', async () => {
    const response = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000014',
      2,
      'I need a laptop',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000014', role: 'USER', body: 'I need a laptop',
        createdAt: '2026-07-20T02:02:00Z',
      },
      result: {
        outcome: 'NO_RESULTS', intent: 'MARKETPLACE_DISCOVERY',
        message: "I couldn't find any laptop listings in the marketplace right now.",
        questions: [], recommendations: [],
        preferenceState: {
          ...preferences,
          query: 'laptop', status: 'NO_INVENTORY', requestedCategory: 'laptop',
          categoryAvailability: 'UNAVAILABLE', categoryInventoryCount: 0,
          clarificationsAsked: 0, lastSearchOutcome: 'CATEGORY_UNAVAILABLE',
        },
        searchOutcome: {
          mode: 'AVAILABILITY_PROBE', searchExecuted: true, category: 'laptop',
          totalActiveCategoryInventory: 0, exactMatchCount: null,
          appliedFilters: [], relaxableFilters: [],
          reason: 'CATEGORY_UNAVAILABLE', retryable: false,
        },
        inputTokens: 0, outputTokens: 0, estimatedCost: '0',
      },
      preferenceVersion: 3,
    });

    const parsed = await response;
    expect(parsed.result.searchOutcome?.reason).toBe('CATEGORY_UNAVAILABLE');
    expect(parsed.result.preferenceState.categoryAvailability).toBe('UNAVAILABLE');
    expect(parsed.result.recommendations).toEqual([]);
  });

  it('uses a strict authoritative stop command without reposting the USER body', async () => {
    const clientMessageId = '01M00000000000000000000001';
    const stopped = firstValueFrom(service.stopMessage(sessionId, clientMessageId));
    const request = http.expectOne(
      `/api/v1/agent/discovery/sessions/${sessionId}/messages/${clientMessageId}/stop`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('discovery-csrf');
    expect(request.request.body).toEqual({});
    request.flush({ sessionId, clientMessageId, outcome: 'STOPPED' });

    expect((await stopped).outcome).toBe('STOPPED');
  });

  it('accepts strict comparison and exclusion session contracts', async () => {
    const storedSession = firstValueFrom(service.getSession(sessionId));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}`).flush({
      ...session,
      exclusions: [{
        listingId: listingIds[2],
        reasonCode: 'TOO_FAR',
        excludedAt: '2026-07-20T02:04:00Z',
      }],
    });
    expect((await storedSession).exclusions).toEqual([{
      listingId: listingIds[2],
      reasonCode: 'TOO_FAR',
      excludedAt: '2026-07-20T02:04:00Z',
    }]);

    const comparison = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000003',
      2,
      'Compare the first two',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000003',
        role: 'USER',
        body: 'Compare the first two',
        createdAt: '2026-07-20T02:04:00Z',
      },
      result: {
        ...result,
        outcome: 'COMPARE',
        message: 'Here is a current comparison of the first two listings.',
        recommendations: result.recommendations.slice(0, 2),
      },
      preferenceVersion: 3,
    });
    expect((await comparison).result.outcome).toBe('COMPARE');
  });

  it('keeps canonical live and stored comparison results at exact schema parity', async () => {
    const comparisonResult = {
      ...result,
      outcome: 'COMPARE',
      message: 'Here is a current comparison of the first two listings.',
      recommendations: result.recommendations.slice(0, 2),
    };
    const history = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [
        {
          id: '01U00000000000000000000005',
          role: 'USER',
          body: 'Compare the first two',
          clientMessageId: '01M00000000000000000000005',
          resolutionType: null,
          result: null,
          responseFailure: null,
          responseRetry: null,
          createdAt: '2026-07-20T02:04:00Z',
        },
        {
          id: '01A00000000000000000000005',
          role: 'ASSISTANT',
          body: comparisonResult.message,
          clientMessageId: null,
          resolutionType: 'ANSWERED',
          result: comparisonResult,
          responseFailure: null,
          responseRetry: null,
          createdAt: '2026-07-20T02:04:01Z',
        },
      ],
      nextCursor: null,
      hasMore: false,
    });

    const parsed = await history;

    expect(parsed.data[0].clientMessageId).toBe('01M00000000000000000000005');
    expect(parsed.data[1].clientMessageId).toBeNull();
    expect(parsed.data[1].result?.outcome).toBe('COMPARE');
    expect(parsed.data[1].result?.recommendations.length).toBe(2);

    const sparseResult = JSON.parse(JSON.stringify(comparisonResult)) as {
      recommendations: Array<Record<string, unknown>>;
    };
    delete sparseResult.recommendations[0]['thumbnailUrl'];
    const sparseHistory = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [{
        id: '01A00000000000000000000006',
        role: 'ASSISTANT',
        body: comparisonResult.message,
        clientMessageId: null,
        resolutionType: 'ANSWERED',
        result: sparseResult,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:05:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    });
    await expectAsync(sparseHistory)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));
  });

  it('accepts canonical multiline completion and guarded PARTIAL history', async () => {
    const multilineResult = {
      ...result,
      message: 'Three current matches were verified.\n\nCompare the details below.',
    };
    const partialResult = {
      ...result,
      outcome: 'HANDOFF',
      message: 'The verified response was interrupted.\nRetry when ready.',
      recommendations: [],
    };
    const history = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [
        {
          id: '01A00000000000000000000021', role: 'ASSISTANT',
          body: multilineResult.message, clientMessageId: null,
          resolutionType: 'RECOMMEND', result: multilineResult,
          responseFailure: null, responseRetry: null,
          createdAt: '2026-07-20T02:06:00Z',
        },
        {
          id: '01A00000000000000000000022', role: 'ASSISTANT',
          body: partialResult.message, clientMessageId: null,
          resolutionType: 'PARTIAL', result: partialResult,
          responseFailure: null,
          responseRetry: {
            invocationId: '01I00000000000000000000021',
            userMessageId: '01U00000000000000000000021',
          },
          createdAt: '2026-07-20T02:07:00Z',
        },
      ],
      nextCursor: null,
      hasMore: false,
    });

    const parsed = await history;
    expect(parsed.data[0].result?.message).toBe(multilineResult.message);
    expect(parsed.data[1].resolutionType).toBe('PARTIAL');
    expect(parsed.data[1].result?.outcome).toBe('HANDOFF');
    expect(parsed.data[1].responseRetry?.userMessageId)
      .toBe('01U00000000000000000000021');
  });

  it('rejects history without strict role-specific client message correlation', async () => {
    const missingUserCorrelation = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [{
        id: '01U00000000000000000000007',
        role: 'USER',
        body: 'Find a desk chair',
        clientMessageId: null,
        resolutionType: null,
        result: null,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:06:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    });
    await expectAsync(missingUserCorrelation)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const assistantCorrelation = firstValueFrom(service.getMessages(sessionId));
    http.expectOne(request => request.url.includes('/messages')).flush({
      data: [{
        id: '01A00000000000000000000007',
        role: 'ASSISTANT',
        body: result.message,
        clientMessageId: '01M00000000000000000000007',
        resolutionType: 'RECOMMEND',
        result,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:06:01Z',
      }],
      nextCursor: null,
      hasMore: false,
    });
    await expectAsync(assistantCorrelation)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));
  });

  it('sends the exact exclusion command and coalesces only an identical retry', async () => {
    const first = firstValueFrom(service.excludeListing(
      sessionId,
      '01X00000000000000000000001',
      2,
      listingIds[0],
      'NOT_RELEVANT',
    ));
    const replay = firstValueFrom(service.excludeListing(
      sessionId,
      '01X00000000000000000000001',
      2,
      listingIds[0],
      'NOT_RELEVANT',
    ));
    await expectAsync(firstValueFrom(service.excludeListing(
      sessionId,
      '01X00000000000000000000001',
      2,
      listingIds[1],
      'NOT_RELEVANT',
    ))).toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const request = http.expectOne(
      `/api/v1/agent/discovery/sessions/${sessionId}/exclusions`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('discovery-csrf');
    expect(request.request.headers.get('Idempotency-Key'))
      .toBe('01X00000000000000000000001');
    expect(request.request.body).toEqual({
      expectedPreferenceVersion: 2,
      listingId: listingIds[0],
      reasonCode: 'NOT_RELEVANT',
    });
    request.flush({
      sessionId,
      listingId: listingIds[0],
      reasonCode: 'NOT_RELEVANT',
      outcome: 'EXCLUDED',
      preferenceVersion: 3,
      excludedCount: 1,
      updatedAt: '2026-07-20T02:04:00Z',
    });
    expect((await first).outcome).toBe('EXCLUDED');
    expect((await replay).preferenceVersion).toBe(3);
  });

  it('fails closed on malformed comparison, exclusion input, and response identity', async () => {
    const malformedComparison = firstValueFrom(service.sendMessage(
      sessionId,
      '01M00000000000000000000004',
      2,
      'Compare the first two',
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/messages`).flush({
      userMessage: {
        id: '01U00000000000000000000004',
        role: 'USER',
        body: 'Compare the first two',
        createdAt: '2026-07-20T02:04:00Z',
      },
      result: {
        ...result,
        outcome: 'COMPARE',
        recommendations: result.recommendations.slice(0, 1),
      },
      preferenceVersion: 3,
    });
    await expectAsync(malformedComparison)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));

    await expectAsync(firstValueFrom(service.excludeListing(
      sessionId,
      'short-key',
      2,
      listingIds[0],
      'NOT_RELEVANT',
    ))).toBeRejectedWith(jasmine.any(DiscoveryContractError));
    await expectAsync(firstValueFrom(service.excludeListing(
      sessionId,
      '01X00000000000000000000002',
      2,
      listingIds[0],
      'UNAPPROVED' as never,
    ))).toBeRejectedWith(jasmine.any(DiscoveryContractError));

    const mismatchedResponse = firstValueFrom(service.excludeListing(
      sessionId,
      '01X00000000000000000000003',
      2,
      listingIds[0],
      null,
    ));
    http.expectOne(`/api/v1/agent/discovery/sessions/${sessionId}/exclusions`).flush({
      sessionId,
      listingId: listingIds[1],
      reasonCode: null,
      outcome: 'EXCLUDED',
      preferenceVersion: 3,
      excludedCount: 1,
      updatedAt: '2026-07-20T02:04:00Z',
    });
    await expectAsync(mismatchedResponse)
      .toBeRejectedWith(jasmine.any(DiscoveryContractError));
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
        clientMessageId: null,
        resolutionType: 'NO_RESULTS',
        result,
        responseFailure: null,
        responseRetry: null,
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

  it('parses fragmented UTF-8 SSE frames with strict monotonic ordering', () => {
    const parser = new DiscoverySseParser();
    const encoded = new TextEncoder().encode(
      'event: activity\r\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":1,"type":"activity","stage":"SEARCHING","label":"Searching current public listings"}\r\n\r\n'
      + 'event: text_delta\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":2,"type":"text_delta","delta":"Bike 🚲"}\n\n',
    );
    const events = [
      ...parser.push(encoded.slice(0, 17)),
      ...parser.push(encoded.slice(17, encoded.length - 3)),
      ...parser.push(encoded.slice(encoded.length - 3)),
      ...parser.finish(),
    ];

    expect(events.map(event => event.type)).toEqual(['activity', 'text_delta']);
    expect(events[1].type === 'text_delta' ? events[1].delta : '').toBe('Bike 🚲');
  });

  it('fails closed for unknown, malformed, or out-of-order SSE events', () => {
    const unknown = new DiscoverySseParser();
    try {
      unknown.push(new TextEncoder().encode(
        'event: reasoning\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":1,"type":"reasoning"}\n\n',
      ));
      fail('unknown event must be rejected');
    } catch (error) {
      expect(error).toEqual(jasmine.any(DiscoveryContractError));
      expect((error as DiscoveryContractError).kind).toBe('CONTRACT');
    }

    const outOfOrder = new DiscoverySseParser();
    try {
      outOfOrder.push(new TextEncoder().encode(
        'event: activity\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":2,"type":"activity","stage":"SEARCHING","label":"Searching current public listings"}\n\n',
      ));
      fail('out-of-order event must be rejected');
    } catch (error) {
      expect((error as DiscoveryContractError).kind).toBe('SEQUENCE');
    }

    const incomplete = new DiscoverySseParser();
    incomplete.push(new TextEncoder().encode('event: activity\ndata: {}'));
    try {
      incomplete.finish();
      fail('incomplete frame must be rejected');
    } catch (error) {
      expect((error as DiscoveryContractError).kind).toBe('INCOMPLETE_FRAME');
    }
  });

  it('ignores the exact deployed opening comment without weakening event validation', () => {
    const parser = new DiscoverySseParser();
    const deployedOpening = `: stream-open${' '.repeat(4083)}\n\n`;
    const accepted = 'event: activity\n'
      + 'data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2",'
      + '"sequence":1,"type":"activity","stage":"MESSAGE_ACCEPTED",'
      + '"label":"Request accepted"}\n\n';

    const events = [
      ...parser.push(new TextEncoder().encode(deployedOpening + accepted)),
      ...parser.finish(),
    ];

    expect(new TextEncoder().encode(deployedOpening).byteLength).toBe(4098);
    expect(events.length).toBe(1);
    expect(events[0].type).toBe('activity');
    expect(events[0].sequence).toBe(1);

    const unsupportedField = new DiscoverySseParser();
    expect(() => unsupportedField.push(new TextEncoder().encode(
      'retry: 1000\n\n',
    ))).toThrowError(DiscoveryContractError);
  });

  it('uses credentialed CSRF fetch, validates exact completion, and never sends Authorization', async () => {
    const streamedResult = {
      ...result,
      message: 'Three current matches were verified.\n\nCompare the details below.',
    };
    const completedResponse = {
      userMessage: {
        id: '01M00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        createdAt: '2026-07-20T02:03:00Z',
      },
      result: streamedResult,
      preferenceVersion: 3,
    };
    const frames = [
      ['activity', { stage: 'MESSAGE_ACCEPTED', label: 'Request accepted' }],
      ['text_delta', { delta: streamedResult.message }],
      ['recommendations', { items: streamedResult.recommendations }],
      ['metadata', { citations: [], provenance: streamedResult.recommendations.map(item => item.provenance) }],
      ['done', { messageId: '01A00000000000000000000009', response: completedResponse }],
    ].map(([type, payload], index) => (
      `event: ${type}\ndata: ${JSON.stringify({
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: index + 1,
        type,
        ...(payload as object),
      })}\n\n`
    )).join('');
    const bytes = new TextEncoder().encode(frames);
    fetchSpy.and.resolveTo(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(bytes.slice(0, 31));
        controller.enqueue(bytes.slice(31));
        controller.close();
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } }));

    const events = await firstValueFrom(service.streamMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ).pipe(toArray()));

    expect(events.map(event => event.type)).toEqual([
      'activity', 'text_delta', 'recommendations', 'metadata', 'done',
    ]);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.calls.mostRecent().args;
    expect(String(url)).toContain(`/sessions/${sessionId}/messages/stream`);
    expect(init?.credentials).toBe('include');
    expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('discovery-csrf');
    expect(new Headers(init?.headers).has('Authorization')).toBeFalse();
    const done = events.at(-1);
    expect(done?.type).toBe('done');
    if (done?.type === 'done') {
      expect(done.response.result.message).toBe(streamedResult.message);
    }
  });

  it('retries only the response for an existing USER message', async () => {
    const userMessageId = '01U00000000000000000000011';
    const completedResponse = {
      userMessage: {
        id: userMessageId,
        role: 'USER',
        body: 'Irvine, Orange County.',
        createdAt: '2026-07-20T02:03:00Z',
      },
      result,
      preferenceVersion: 3,
    };
    const frames = [
      ['activity', { stage: 'MESSAGE_ACCEPTED', label: 'Request accepted' }],
      ['text_delta', { delta: result.message }],
      ['recommendations', { items: result.recommendations }],
      ['metadata', { citations: [], provenance: result.recommendations.map(item => item.provenance) }],
      ['done', { messageId: '01A00000000000000000000009', response: completedResponse }],
    ].map(([type, payload], index) => (
      `event: ${type}\ndata: ${JSON.stringify({
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: index + 1,
        type,
        ...(payload as object),
      })}\n\n`
    )).join('');
    fetchSpy.and.resolveTo(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(frames));
        controller.close();
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));

    const events = await firstValueFrom(service.retryResponse(
      sessionId,
      userMessageId,
      2,
    ).pipe(toArray()));

    expect(events.at(-1)?.type).toBe('done');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.calls.mostRecent().args;
    expect(String(url)).toContain(
      `/messages/${userMessageId}/response-retry/stream`,
    );
    expect(JSON.parse(String(init?.body))).toEqual({ expectedPreferenceVersion: 2 });
    expect(String(init?.body)).not.toContain('Irvine');
    expect(new Headers(init?.headers).has('Authorization')).toBeFalse();
  });

  it('treats disconnect and safe failed events as terminal without fallback or resend', async () => {
    const disconnected = new TextEncoder().encode(
      'event: activity\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":1,"type":"activity","stage":"MESSAGE_ACCEPTED","label":"Request accepted"}\n\n',
    );
    fetchSpy.and.resolveTo(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(disconnected);
        controller.close();
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));

    await expectAsync(firstValueFrom(service.streamMessage(
      sessionId,
      '01M00000000000000000000001',
      2,
      'Find a desk chair',
    ).pipe(toArray()))).toBeRejectedWith(jasmine.any(DiscoveryContractError));
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    fetchSpy.calls.reset();
    const failed = new TextEncoder().encode(
      'event: error\ndata: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":1,"type":"error","code":"AGENT_DISCOVERY_UNAVAILABLE","message":"Marketplace discovery is temporarily unavailable.","retryable":false}\n\n',
    );
    fetchSpy.and.resolveTo(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(failed);
        controller.close();
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    await expectAsync(firstValueFrom(service.streamMessage(
      sessionId,
      '01M00000000000000000000002',
      2,
      'Find another desk chair',
    ).pipe(toArray()))).toBeRejectedWith(jasmine.any(DiscoveryStreamFailureError));
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('aborts the credentialed fetch when the only stream subscriber stops', async () => {
    fetchSpy.and.callFake(() => new Promise<Response>(() => undefined));
    const subscription = service.streamMessage(
      sessionId,
      '01M00000000000000000000003',
      2,
      'Find a chair',
    ).subscribe();
    await Promise.resolve();

    subscription.unsubscribe();

    const requestSignal = fetchSpy.calls.mostRecent().args[1]?.signal;
    expect(requestSignal?.aborted).toBeTrue();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('keeps the disabled service network and Auth silent', async () => {
    const disabled = new AgentMarketplaceDiscoveryService(
      TestBed.inject(HttpClient),
      authService as unknown as AuthService,
      false,
      fetchSpy,
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
