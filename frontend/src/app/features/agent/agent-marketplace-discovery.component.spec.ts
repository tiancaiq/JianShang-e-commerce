import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { concat, of, Subject, switchMap, throwError } from 'rxjs';
import { AGENT_CLIENT_MESSAGE_ID_FACTORY } from './agent-customer-service.capability';
import {
  AgentMarketplaceDiscoveryComponent,
} from './agent-marketplace-discovery.component';
import {
  DiscoverySession,
  DiscoveryStreamEvent,
  DiscoveryTurnResult,
} from './agent-marketplace-discovery.model';
import {
  AgentMarketplaceDiscoveryService,
  DiscoveryContractError,
  DiscoveryStreamFailureError,
} from './agent-marketplace-discovery.service';

describe('AgentMarketplaceDiscoveryComponent', () => {
  const sessionId = '01A00000000000000000000001';
  const session: DiscoverySession = {
    id: sessionId,
    sessionType: 'MARKETPLACE_DISCOVERY',
    status: 'OPEN',
    preferenceState: {
      query: null,
      categoryId: null,
      condition: null,
      minPrice: null,
      maxPrice: null,
      city: null,
      county: null,
      selectedListingId: null,
    },
    preferenceVersion: 0,
    clarificationTurnCount: 0,
    clarificationQuestionCount: 0,
    exclusions: [],
    createdAt: '2026-07-20T02:00:00Z',
    updatedAt: '2026-07-20T02:00:00Z',
  };
  let fixture: ComponentFixture<AgentMarketplaceDiscoveryComponent>;
  let component: AgentMarketplaceDiscoveryComponent;
  let service: jasmine.SpyObj<AgentMarketplaceDiscoveryService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      [
        'createOrResumeSession',
        'getSession',
        'getMessages',
        'sendMessage',
        'streamMessage',
        'stopMessage',
        'retryResponse',
        'excludeListing',
      ],
    );
    service.createOrResumeSession.and.returnValue(of(session));
    service.getSession.and.returnValue(of(session));
    service.getMessages.and.returnValue(of({
      data: [],
      nextCursor: null,
      hasMore: false,
    }));
    service.sendMessage.and.returnValue(of(sendResponse(recommendationResult())));
    service.streamMessage.and.callFake((
      selectedSessionId,
      clientMessageId,
      expectedPreferenceVersion,
      body,
    ) => service.sendMessage(
      selectedSessionId,
      clientMessageId,
      expectedPreferenceVersion,
      body,
    ).pipe(switchMap(response => of(
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 1,
        type: 'activity',
        stage: 'MESSAGE_ACCEPTED',
        label: 'Request accepted',
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 2,
        type: 'activity',
        stage: 'UNDERSTANDING',
        label: 'Understanding your request',
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 3,
        type: 'text_delta',
        delta: response.result.message,
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 4,
        type: 'recommendations',
        items: response.result.recommendations,
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 5,
        type: 'metadata',
        citations: [],
        provenance: response.result.recommendations.map(item => item.provenance),
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 6,
        type: 'done',
        messageId: '01A00000000000000000000009',
        response,
      } as const,
    ))));
    service.retryResponse.and.returnValue(of());
    service.stopMessage.and.callFake((selectedSessionId, clientMessageId) => of({
      sessionId: selectedSessionId,
      clientMessageId,
      outcome: 'STOPPED',
    }));
    service.excludeListing.and.returnValue(of({
      sessionId,
      listingId: '01L00000000000000000000001',
      reasonCode: 'NOT_RELEVANT',
      outcome: 'EXCLUDED',
      preferenceVersion: 2,
      excludedCount: 1,
      updatedAt: '2026-07-20T02:03:00Z',
    }));

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceDiscoveryComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AgentMarketplaceDiscoveryService, useValue: service },
        {
          provide: AGENT_CLIENT_MESSAGE_ID_FACTORY,
          useValue: () => '01M00000000000000000000001',
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentMarketplaceDiscoveryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads history before rendering an authoritative Ready state', () => {
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('How can I help?');
    expect(root.querySelector('label[for="discovery-prompt"]')).not.toBeNull();
    expect(root.querySelector('[aria-live="polite"]')).not.toBeNull();
    expect(root.textContent).toContain('Ask a marketplace question');
    expect(service.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(service.getSession).not.toHaveBeenCalled();
    expect(service.getMessages).toHaveBeenCalledOnceWith(sessionId, null, 50);
    expect(service.sendMessage).not.toHaveBeenCalled();

    root.querySelector<HTMLButtonElement>('.examples button')?.click();
    fixture.detectChanges();
    expect(component.prompt()).toBe('Used bicycle near Irvine');
    expect(service.createOrResumeSession).toHaveBeenCalledTimes(1);
  });

  it('renders resumed history before the composer becomes ready', () => {
    fixture.destroy();
    service.createOrResumeSession.calls.reset();
    service.getMessages.calls.reset();
    service.getMessages.and.returnValue(of({
      data: [{
        id: '01U00000000000000000000008',
        role: 'USER',
        body: 'Find a desk chair under $100.',
        clientMessageId: '01M00000000000000000000008',
        resolutionType: null,
        result: null,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:01:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    }));

    fixture = TestBed.createComponent(AgentMarketplaceDiscoveryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Find a desk chair under $100.');
    expect(root.textContent).not.toContain('What can I help you find?');
    expect(component.historyReady()).toBeTrue();
  });

  it('shows Retry response and reuses the committed USER message', () => {
    const userMessageId = '01U00000000000000000000012';
    const failure: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'HANDOFF',
      message: 'I could not finish checking current listings. Retry this response.',
      questions: [],
      recommendations: [],
    };
    service.getMessages.and.returnValue(of({
      data: [{
        id: userMessageId,
        role: 'USER',
        body: 'Irvine, Orange County.',
        clientMessageId: '01M00000000000000000000012',
        resolutionType: null,
        result: null,
        responseFailure: failure,
        responseRetry: {
          invocationId: '01I00000000000000000000012',
          userMessageId,
        },
        createdAt: '2026-07-20T02:01:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    }));
    component.refreshHistory();
    fixture.detectChanges();
    const success = sendResponse(recommendationResult());
    success.userMessage.id = userMessageId;
    success.userMessage.body = 'Irvine, Orange County.';
    service.retryResponse.and.returnValue(of(
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 1,
        type: 'activity',
        stage: 'MESSAGE_ACCEPTED',
        label: 'Request accepted',
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 2,
        type: 'text_delta',
        delta: success.result.message,
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 3,
        type: 'recommendations',
        items: success.result.recommendations,
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 4,
        type: 'metadata', citations: [],
        provenance: success.result.recommendations.map(item => item.provenance),
      } as const,
      {
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 5,
        type: 'done', messageId: '01A00000000000000000000009',
        response: success,
      } as const,
    ));

    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.retry-response')?.click();
    fixture.detectChanges();

    expect(service.retryResponse).toHaveBeenCalledOnceWith(
      sessionId,
      userMessageId,
      0,
    );
    expect(service.streamMessage).not.toHaveBeenCalled();
    expect(root.querySelectorAll('.user-message').length).toBe(1);
    expect(root.textContent).toContain(recommendationResult().message);
  });

  it('reloads a stopped zero-text response with Retry response', () => {
    const userMessageId = '01U00000000000000000000013';
    const stopped: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'HANDOFF',
      message: 'Response stopped before the answer began.',
      questions: [],
      recommendations: [],
    };
    service.getMessages.and.returnValue(of({
      data: [
        {
          id: userMessageId,
          role: 'USER',
          body: 'Find a used bicycle under $500.',
          clientMessageId: '01M00000000000000000000013',
          resolutionType: null,
          result: null,
          responseFailure: null,
          responseRetry: null,
          createdAt: '2026-07-20T02:01:00Z',
        },
        {
          id: '01A00000000000000000000013',
          role: 'ASSISTANT',
          body: stopped.message,
          clientMessageId: null,
          resolutionType: 'PARTIAL',
          result: stopped,
          responseFailure: null,
          responseRetry: {
            invocationId: '01I00000000000000000000013',
            userMessageId,
          },
          createdAt: '2026-07-20T02:01:01Z',
        },
      ],
      nextCursor: null,
      hasMore: false,
    }));

    component.refreshHistory();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Response stopped before the answer began.');
    expect(root.querySelector<HTMLButtonElement>('.retry-response')).not.toBeNull();
    expect(root.textContent).not.toContain('What can I help you find?');
  });

  it('creates/resumes, loads history and sends one exact first turn', () => {
    component.prompt.set('I need a beginner camera under $500');
    component.submitPrompt();
    fixture.detectChanges();

    expect(service.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(service.getMessages).toHaveBeenCalledOnceWith(sessionId, null, 50);
    expect(service.sendMessage).toHaveBeenCalledOnceWith(
      sessionId,
      '01M00000000000000000000001',
      0,
      'I need a beginner camera under $500',
    );
    expect(service.streamMessage).toHaveBeenCalledOnceWith(
      sessionId,
      '01M00000000000000000000001',
      0,
      'I need a beginner camera under $500',
    );
    expect(component.session()?.preferenceVersion).toBe(1);
    expect(component.prompt()).toBe('');
  });

  it('renders three safe responsive cards with provenance and only listing actions', () => {
    component.prompt.set('A desk chair under $100 in Irvine');
    component.submitPrompt();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const cards = root.querySelectorAll('.listing-card');
    expect(cards.length).toBe(3);
    expect(root.querySelectorAll('.listing-card a').length).toBe(3);
    expect(root.textContent).toContain('Detail checked');
    expect(root.textContent).toContain('View listing');
    expect(root.textContent).toContain('Compare');
    expect(root.textContent).not.toContain('Message seller');
    expect(root.textContent).not.toContain('Buy');
    expect(root.textContent).toContain('Not interested');
    expect(cards[2].textContent).not.toContain('Public area');
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('.listing-card a'));
    expect(links.every(link => link.getAttribute('href')?.startsWith('/listings/'))).toBeTrue();
  });

  it('renders each V2 network delta immediately and gates cards until done', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const result = recommendationResult();
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('A desk chair under $100');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    fixture.detectChanges();
    expect(component.prompt()).toBe('');
    expect((fixture.nativeElement as HTMLElement).querySelector('.pending-user-message')?.textContent)
      .toContain('A desk chair under $100');
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'text_delta', delta: 'These current ',
    });
    fixture.detectChanges();
    expect(component.streamedAnswer()).toBe('These current ');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('These current');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.listing-card').length).toBe(0);
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'text_delta', delta: 'listings match your selected constraints.',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 4,
      type: 'recommendations', items: result.recommendations,
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 5,
      type: 'metadata', citations: [],
      provenance: result.recommendations.map(item => item.provenance),
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 6,
      type: 'done', messageId: '01A00000000000000000000009',
      response: sendResponse(result),
    });
    fixture.detectChanges();
    expect(component.messages().at(-1)?.body).toBe(result.message);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.listing-card').length).toBe(3);
  });

  it('collapses reached activity stages into a summary only after successful done', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const result = recommendationResult();
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('A desk chair under $100');
    component.submitPrompt();
    const stages = [
      ['MESSAGE_ACCEPTED', 'Request accepted'],
      ['UNDERSTANDING', 'Understanding your request'],
      ['SEARCHING', 'Searching current public listings'],
      ['CHECKING', 'Checking price and availability'],
      ['COMPOSING', 'Preparing your answer'],
    ] as const;
    stages.forEach(([stage, label], index) => stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: index + 1, type: 'activity', stage, label,
    }));
    stream.next({ schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 6,
      type: 'text_delta', delta: result.message });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .not.toContain('Searched, verified, and compared current listings');
    stream.next({ schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 7,
      type: 'recommendations', items: result.recommendations });
    stream.next({ schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 8,
      type: 'metadata', citations: [],
      provenance: result.recommendations.map(item => item.provenance) });
    stream.next({ schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 9,
      type: 'done', messageId: '01A00000000000000000000009', response: sendResponse(result) });
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.streaming-message')).toBeNull();
    expect(root.querySelector('.completed-activity-summary')?.textContent)
      .toContain('Searched, verified, and compared current listings');
  });

  it('stops the stream subscription and preserves visible partial text', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('Find a chair');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'text_delta', delta: 'A verified partial answer',
    });

    component.stopGeneration();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'text_delta', delta: ' must not append',
    });

    expect(component.streamedAnswer()).toBe('A verified partial answer');
    expect(component.streamInterrupted()).toBeTrue();
    expect(component.sending()).toBeFalse();
  });

  it('keeps the draft when stopped before durable acceptance', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    service.streamMessage.and.returnValue(stream);
    service.stopMessage.and.callFake((selectedSessionId, clientMessageId) => of({
      sessionId: selectedSessionId,
      clientMessageId,
      outcome: 'NOT_COMMITTED',
    }));
    component.prompt.set('Find a bicycle under $500');
    component.submitPrompt();

    component.stopGeneration();
    fixture.detectChanges();

    expect(component.prompt()).toBe('Find a bicycle under $500');
    expect(component.streamInterrupted()).toBeFalse();
    expect(component.liveStatus()).toContain('No message was saved');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('partial answer');
  });

  it('shows a saved zero-text stopped state after durable acceptance', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('Find a bicycle under $500');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });

    component.stopGeneration();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(component.prompt()).toBe('');
    expect(component.streamInterrupted()).toBeTrue();
    expect(text).toContain('Response stopped');
    expect(text).toContain('no answer text was generated');
    expect(text).not.toContain('partial answer is preserved');
  });

  it('reconciles a stopped committed user immediately without a pending duplicate', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const clientMessageId = '01M00000000000000000000001';
    const userMessageId = '01U00000000000000000000014';
    const stopped: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'HANDOFF',
      message: 'Response stopped before the answer began.',
      questions: [],
      recommendations: [],
    };
    service.streamMessage.and.returnValue(stream);
    service.getMessages.and.returnValue(of({
      data: [
        {
          id: userMessageId,
          role: 'USER',
          body: 'Find a bicycle under $500',
          clientMessageId,
          resolutionType: null,
          result: null,
          responseFailure: null,
          responseRetry: null,
          createdAt: '2026-07-20T02:01:00Z',
        },
        {
          id: '01A00000000000000000000014',
          role: 'ASSISTANT',
          body: stopped.message,
          clientMessageId: null,
          resolutionType: 'PARTIAL',
          result: stopped,
          responseFailure: null,
          responseRetry: {
            invocationId: '01I00000000000000000000014',
            userMessageId,
          },
          createdAt: '2026-07-20T02:01:01Z',
        },
      ],
      nextCursor: null,
      hasMore: false,
    }));
    component.prompt.set('Find a bicycle under $500');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });

    component.stopGeneration();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.user-message').length).toBe(1);
    expect(root.querySelector('.pending-user-message')).toBeNull();
    expect(root.textContent).toContain('Response stopped before the answer began.');
    expect(root.querySelector<HTMLButtonElement>('.retry-response')).not.toBeNull();
    expect(component.prompt()).toBe('');
  });

  it('accepts coalesced network events without scheduling a client reveal', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    service.streamMessage.and.returnValue(stream);
    component.session.set(session);
    component.prompt.set('A desk chair under $100 in Irvine');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 1,
      type: 'activity',
      stage: 'MESSAGE_ACCEPTED',
      label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 2,
      type: 'activity',
      stage: 'SEARCHING',
      label: 'Searching current public listings',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 3,
      type: 'text_delta',
      delta: 'These current listings match ',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 4,
      type: 'text_delta',
      delta: 'your selected constraints.',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 5,
      type: 'recommendations',
      items: recommendationResult().recommendations,
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 6,
      type: 'metadata', citations: [],
      provenance: recommendationResult().recommendations.map(item => item.provenance),
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 7,
      type: 'done', messageId: '01A00000000000000000000009',
      response: sendResponse(recommendationResult()),
    });
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('These current listings match');
    expect(root.querySelectorAll('.listing-card').length).toBe(3);
    expect(component.liveStatus()).toBe('Recommendations');
    expect(root.textContent).not.toContain('the model thinks');

    expect(component.streamedAnswer().length).toBeGreaterThan(0);
    expect(component.streamedAnswer()).toBe(recommendationResult().message);
    expect(root.querySelectorAll('.listing-card').length).toBe(3);

    expect(component.messages().at(-1)?.body).toBe(recommendationResult().message);
    expect(root.querySelectorAll('.listing-card').length).toBe(3);
    expect(root.querySelector('.streaming-message')).toBeNull();
    expect(document.head.textContent).toContain('prefers-reduced-motion');
  });

  it('streams a direct conversational answer without search activity or cards', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const direct: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'ANSWER',
      intent: 'GENERAL_CONVERSATION',
      message: 'Hi! How can I help with the marketplace today?',
      questions: [],
      recommendations: [],
    };
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('Hi');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'text_delta', delta: direct.message,
    });
    fixture.detectChanges();

    let root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain(direct.message);
    expect(root.querySelector('.activity-details')).toBeNull();
    expect(root.querySelector('.current-activity')).toBeNull();
    expect(root.querySelector('.recommendation-grid')).toBeNull();

    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'recommendations', items: [],
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 4,
      type: 'metadata', citations: [], provenance: [],
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 5,
      type: 'done', messageId: '01A00000000000000000000015',
      response: sendResponse(direct),
    });
    fixture.detectChanges();

    root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain(direct.message);
    expect(root.querySelector('.answer-heading small')).toBeNull();
    expect(component.liveStatus()).toBe('Response ready.');
    expect(root.querySelector('.recommendation-grid')).toBeNull();
    expect(root.querySelector('.completed-activity-summary')).toBeNull();
  });

  it('renders category unavailable as a normal answer with probe-only activity and no cards', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const unavailable: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'NO_RESULTS',
      intent: 'MARKETPLACE_DISCOVERY',
      message: "I couldn't find any laptop listings in the marketplace right now.",
      questions: [],
      recommendations: [],
      preferenceState: {
        ...recommendationResult().preferenceState,
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
    };
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('I need a laptop');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'activity', stage: 'UNDERSTANDING', label: 'Understanding your request',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'activity', stage: 'CHECKING_AVAILABILITY', label: 'Checking current availability',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 4,
      type: 'text_delta', delta: unavailable.message,
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 5,
      type: 'recommendations', items: [],
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 6,
      type: 'metadata', citations: [], provenance: [],
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 7,
      type: 'done', messageId: '01A00000000000000000000018',
      response: sendResponse(unavailable),
    });
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('No current listings');
    expect(root.querySelector('.availability-label')).not.toBeNull();
    expect(root.querySelector('.recommendation-grid')).toBeNull();
    expect(root.querySelector('.completed-activity-summary')).toBeNull();
    expect(root.textContent).not.toContain('compared current listings');
  });

  it('preserves Unicode exactly without a client-side reveal scheduler', () => {
    const result = recommendationResult();
    result.message = 'Café chair 👩🏽‍💻 — verified.';
    service.sendMessage.and.returnValue(of(sendResponse(result)));

    component.prompt.set('Find a chair');
    component.submitPrompt();
    fixture.detectChanges();

    expect(component.messages().at(-1)?.body).toBe(result.message);
  });

  it('preserves partial text after a stream failure and never appends stale text', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    service.streamMessage.and.returnValue(stream);
    component.prompt.set('Find a chair');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 1,
      type: 'activity',
      stage: 'MESSAGE_ACCEPTED',
      label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
      sequence: 2,
      type: 'text_delta',
      delta: 'This text must not appear after failure.',
    });
    stream.error(new DiscoveryContractError());

    fixture.detectChanges();

    expect(component.streamedAnswer()).toBe('This text must not appear after failure.');
    expect(component.messages()).toEqual([]);
    expect(component.errorGuidance()).toContain('not automatically sent again');
    expect((fixture.nativeElement as HTMLElement).textContent)
      .not.toContain('Searched, verified, and compared current listings');
  });

  it('reconciles a terminal send failure without a stale stopped activity bubble', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const userMessageId = '01U00000000000000000000021';
    const failure: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'HANDOFF',
      message: 'A marketplace service was temporarily unavailable. Retry this response.',
      questions: [],
      recommendations: [],
    };
    service.streamMessage.and.returnValue(stream);
    service.getMessages.and.returnValue(of({
      data: [{
        id: userMessageId,
        role: 'USER',
        body: 'Search broadly for related items',
        clientMessageId: '01M00000000000000000000001',
        resolutionType: null,
        result: null,
        responseFailure: failure,
        responseRetry: {
          invocationId: '01I00000000000000000000021',
          userMessageId,
        },
        createdAt: '2026-07-30T02:39:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    }));
    component.prompt.set('Search broadly for related items');
    component.submitPrompt();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'activity', stage: 'SEARCHING', label: 'Searching current public listings',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'error', code: 'AGENT_DISCOVERY_UNAVAILABLE',
      message: failure.message, retryable: true,
    });
    stream.error(new DiscoveryStreamFailureError(
      'AGENT_DISCOVERY_UNAVAILABLE', failure.message, true,
    ));
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.streaming-message')).toBeNull();
    expect(root.querySelectorAll('.user-message').length).toBe(1);
    expect(root.textContent).toContain(failure.message);
    expect(root.querySelector('.retry-response')).not.toBeNull();
    expect(root.textContent).not.toContain('Response stopped');
    expect(root.textContent).not.toContain('Working...');
  });

  it('retires failed response-retry activity after authoritative history loads', () => {
    const stream = new Subject<DiscoveryStreamEvent>();
    const userMessageId = '01U00000000000000000000022';
    const failure: DiscoveryTurnResult = {
      ...recommendationResult(),
      outcome: 'HANDOFF',
      message: 'A marketplace service was temporarily unavailable. Retry this response.',
      questions: [],
      recommendations: [],
    };
    const history = {
      data: [{
        id: userMessageId,
        role: 'USER' as const,
        body: 'Search broadly for related items',
        clientMessageId: '01M00000000000000000000022',
        resolutionType: null,
        result: null,
        responseFailure: failure,
        responseRetry: {
          invocationId: '01I00000000000000000000022',
          userMessageId,
        },
        createdAt: '2026-07-30T02:39:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    };
    service.getMessages.and.returnValue(of(history));
    component.refreshHistory();
    fixture.detectChanges();
    service.retryResponse.and.returnValue(stream);

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.retry-response')?.click();
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 1,
      type: 'activity', stage: 'MESSAGE_ACCEPTED', label: 'Request accepted',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 2,
      type: 'activity', stage: 'SEARCHING', label: 'Searching current public listings',
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2', sequence: 3,
      type: 'error', code: 'AGENT_DISCOVERY_UNAVAILABLE',
      message: failure.message, retryable: true,
    });
    stream.error(new DiscoveryStreamFailureError(
      'AGENT_DISCOVERY_UNAVAILABLE', failure.message, true,
    ));
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.streaming-message')).toBeNull();
    expect(root.querySelectorAll('.user-message').length).toBe(1);
    expect(root.querySelectorAll('.assistant-message').length).toBe(1);
    expect(root.querySelector('.retry-response')).not.toBeNull();
    expect(root.textContent).not.toContain('Response stopped');
    expect(root.textContent).not.toContain('Working...');
  });

  it('keeps Ask about this in the same conversation and sends a trusted title reference', () => {
    component.prompt.set('A desk chair under $100 in Irvine');
    component.submitPrompt();
    fixture.detectChanges();
    service.sendMessage.calls.reset();

    (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLButtonElement>('.ask-button')[1].click();
    fixture.detectChanges();

    expect(component.selectedListing()?.listingId)
      .toBe('01L00000000000000000000002');
    expect(fixture.nativeElement.textContent).toContain('Asking about');
    component.prompt.set('Is this suitable for a small room?');
    component.submitPrompt();

    expect(service.sendMessage).toHaveBeenCalledOnceWith(
      sessionId,
      '01M00000000000000000000001',
      1,
      'About "Desk chair 2": Is this suitable for a small room?',
    );
  });

  it('keeps Compare in the same conversation with stable title context', () => {
    component.prompt.set('I need a beginner camera under $500');
    component.submitPrompt();
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLButtonElement>('.compare-button')[0].click();
    fixture.detectChanges();

    expect(component.selectedListing()?.listingId)
      .toBe('01L00000000000000000000001');
    expect(component.prompt()).toBe('Compare "Desk chair 1" with the other current recommendations.');
    expect(fixture.nativeElement.textContent).toContain('Comparing');
    expect(fixture.nativeElement.textContent).not.toContain('Asking about');
    expect(service.streamMessage).toHaveBeenCalledTimes(1);
  });

  it('preserves canonical line breaks as safe text and formats USD with two decimals', () => {
    const result = recommendationResult();
    result.message = 'Verified matches:\n- First option\n- Second option';
    result.recommendations[0] = {
      ...result.recommendations[0],
      priceAmount: '25.8',
    };
    service.sendMessage.and.returnValue(of(sendResponse(result)));
    component.prompt.set('Find a desk chair');
    component.submitPrompt();
    fixture.detectChanges();

    const answer = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('.canonical-answer');
    expect(answer?.textContent).toBe(result.message);
    expect(getComputedStyle(answer!).whiteSpace).toBe('pre-wrap');
    expect((fixture.nativeElement as HTMLElement).querySelector('.listing-card strong')?.textContent)
      .toContain('$25.80');
  });

  it('renders a typed two-item comparison with the same authoritative cards', () => {
    const recommendation = recommendationResult();
    service.sendMessage.and.returnValue(of(sendResponse({
      ...recommendation,
      outcome: 'COMPARE',
      message: 'Here is a current comparison of the first two listings.',
      recommendations: recommendation.recommendations.slice(0, 2),
    })));
    component.session.set(session);
    component.prompt.set('Compare the first two');
    component.submitPrompt();
    fixture.detectChanges();

    const comparison = fixture.nativeElement.querySelector(
      '[data-outcome="COMPARE"]',
    ) as HTMLElement;
    expect(comparison.textContent).toContain('Comparison');
    expect(comparison.querySelectorAll('.listing-card').length).toBe(2);
    expect(comparison.querySelector('[aria-label="Compared listings"]')).not.toBeNull();
  });

  it('excludes one recommendation with the exact fixed-reason command', () => {
    component.prompt.set('A desk chair under $100 in Irvine');
    component.submitPrompt();
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.exclude-button')?.click();
    fixture.detectChanges();

    expect(service.excludeListing).toHaveBeenCalledOnceWith(
      sessionId,
      '01M00000000000000000000001',
      1,
      '01L00000000000000000000001',
      'NOT_RELEVANT',
    );
    expect(component.session()?.preferenceVersion).toBe(2);
    expect(component.session()?.exclusions).toEqual([{
      listingId: '01L00000000000000000000001',
      reasonCode: 'NOT_RELEVANT',
      excludedAt: '2026-07-20T02:03:00Z',
    }]);
    expect(fixture.nativeElement.textContent).toContain('Excluded from this search');
  });

  it('refreshes a conflicted exclusion and never replays it automatically', () => {
    component.session.set({ ...session, preferenceVersion: 3 });
    service.excludeListing.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 409, statusText: 'Conflict' }),
    ));

    component.excludeRecommendation(
      '01L00000000000000000000001',
      'NOT_RELEVANT',
    );
    fixture.detectChanges();

    expect(service.excludeListing).toHaveBeenCalledTimes(1);
    expect(service.getSession).toHaveBeenCalledOnceWith(sessionId);
    expect(service.getMessages).toHaveBeenCalledTimes(2);
    expect(component.errorGuidance()).toContain('Select Not interested again');
  });

  it('does not replay an uncertain exclusion request', () => {
    component.session.set(session);
    service.excludeListing.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 0, statusText: 'Timeout' }),
    ));

    component.excludeRecommendation(
      '01L00000000000000000000001',
      'NOT_RELEVANT',
    );
    fixture.detectChanges();

    expect(service.excludeListing).toHaveBeenCalledTimes(1);
    expect(service.getSession).not.toHaveBeenCalled();
    expect(component.errorGuidance()).toContain('not automatically sent again');
  });


  it('uses clarification questions only to fill the same composer', () => {
    service.sendMessage.and.returnValue(of(sendResponse({
      outcome: 'ASK_CLARIFY',
      message: 'I need one location preference.',
      questions: ['Which city or county should I search?'],
      recommendations: [],
      preferenceState: session.preferenceState,
      inputTokens: 0,
      outputTokens: 0,
      estimatedCost: '0',
    })));
    component.prompt.set('Find a desk chair');
    component.submitPrompt();
    fixture.detectChanges();

    const question = fixture.nativeElement.querySelector(
      '.clarification button',
    ) as HTMLButtonElement;
    question.click();
    fixture.detectChanges();

    expect(component.prompt()).toBe('Which city or county should I search?');
    expect(service.streamMessage).toHaveBeenCalledTimes(1);
  });

  it('renders no-results, refusal and handoff as distinct safe terminal states', () => {
    const terminalOutcomes = [
      ['NO_RESULTS', 'No current matches'],
      ['REFUSE', 'Cannot assist'],
      ['HANDOFF', 'Support needed'],
    ] as const;

    component.session.set(session);
    for (const [outcome, label] of terminalOutcomes) {
      service.sendMessage.and.returnValue(of(sendResponse({
        outcome,
        message: `Safe ${outcome.toLowerCase()} guidance.`,
        questions: [],
        recommendations: [],
        preferenceState: session.preferenceState,
        inputTokens: 0,
        outputTokens: 0,
        estimatedCost: '0',
      })));
      component.messages.set([]);
      component.prompt.set('Find another option');
      component.submitPrompt();
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector(
        `[data-outcome="${outcome}"]`,
      )?.textContent).toContain(label);
      expect(fixture.nativeElement.textContent).not.toContain('tool trace');
    }
  });

  it('requires confirmation before replacing history with a new search', () => {
    component.prompt.set('Find a desk chair');
    component.submitPrompt();
    fixture.detectChanges();
    service.createOrResumeSession.calls.reset();

    component.requestNewSearch();
    fixture.detectChanges();

    expect(component.confirmNewSearch()).toBeTrue();
    expect(service.createOrResumeSession).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Start a new conversation?');

    component.startNewSearch();
    fixture.detectChanges();
    expect(service.createOrResumeSession).toHaveBeenCalledOnceWith(true);
    expect(component.messages()).toEqual([]);
  });

  it('refreshes on conflict but preserves text and never replays the POST', () => {
    component.session.set({ ...session, preferenceVersion: 3 });
    component.prompt.set('Only chairs under $80');
    service.sendMessage.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 409, statusText: 'Conflict' }),
    ));

    component.submitPrompt();
    fixture.detectChanges();

    expect(service.sendMessage).toHaveBeenCalledTimes(1);
    expect(service.getSession).toHaveBeenCalledOnceWith(sessionId);
    expect(service.getMessages).toHaveBeenCalledTimes(2);
    expect(component.prompt()).toBe('Only chairs under $80');
    expect(component.errorMessage()).toContain('changed in another request');
  });

  it('keeps uncertain text and requires an explicit retry after timeout', () => {
    component.session.set(session);
    component.prompt.set('Only chairs under $80');
    service.sendMessage.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 0, statusText: 'Timeout' }),
    ));

    component.submitPrompt();
    fixture.detectChanges();

    expect(service.sendMessage).toHaveBeenCalledTimes(1);
    expect(component.prompt()).toBe('Only chairs under $80');
    expect(component.errorMessage()).toContain('uncertain');
    expect(component.errorGuidance()).toContain('not automatically sent again');
  });

  it('labels a history-load failure without claiming listing search failed', () => {
    fixture.destroy();
    service.createOrResumeSession.calls.reset();
    service.getMessages.calls.reset();
    service.sendMessage.calls.reset();
    service.getMessages.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 503, statusText: 'Unavailable' }),
    ));
    fixture = TestBed.createComponent(AgentMarketplaceDiscoveryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.errorMessage()).toContain('conversation history');
    expect(component.errorGuidance()).toContain('Listing search was not attempted');
    expect(component.canRetryRead()).toBeTrue();
    expect(service.sendMessage).not.toHaveBeenCalled();
  });

  it('labels a disabled discovery route during session creation without blaming history', () => {
    fixture.destroy();
    service.createOrResumeSession.calls.reset();
    service.getMessages.calls.reset();
    service.sendMessage.calls.reset();
    service.createOrResumeSession.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 404, statusText: 'Not Found' }),
    ));
    fixture = TestBed.createComponent(AgentMarketplaceDiscoveryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(service.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(service.getMessages).not.toHaveBeenCalled();
    expect(service.sendMessage).not.toHaveBeenCalled();
    expect(component.errorMessage()).toContain('not available in this environment');
    expect(component.errorGuidance()).toContain('Gateway Discovery route is disabled');
    expect(component.errorGuidance()).toContain('Listing search was not attempted');
    expect(component.errorMessage()).not.toContain('history');
    expect(component.canRetryRead()).toBeFalse();
    expect(component.historyReady()).toBeFalse();
  });

  it('labels a temporary session-create failure separately from history loading', () => {
    fixture.destroy();
    service.createOrResumeSession.calls.reset();
    service.getMessages.calls.reset();
    service.sendMessage.calls.reset();
    service.createOrResumeSession.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 503, statusText: 'Unavailable' }),
    ));
    fixture = TestBed.createComponent(AgentMarketplaceDiscoveryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(service.getMessages).not.toHaveBeenCalled();
    expect(service.sendMessage).not.toHaveBeenCalled();
    expect(component.errorMessage()).toContain('discovery is temporarily unavailable');
    expect(component.errorGuidance()).toContain('session could not be opened');
    expect(component.errorMessage()).not.toContain('history');
    expect(component.canRetryRead()).toBeFalse();
    expect(component.historyReady()).toBeFalse();
  });

  it('labels a listing-search failure with explicit read-only reconciliation', () => {
    component.session.set(session);
    component.prompt.set('Find a desk chair');
    service.sendMessage.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 503, statusText: 'Unavailable' }),
    ));

    component.submitPrompt();
    fixture.detectChanges();

    expect(component.errorMessage()).toContain('uncertain');
    expect(component.errorGuidance()).toContain('not automatically sent again');
    expect(component.canRetryRead()).toBeFalse();
    expect(component.prompt()).toBe('Find a desk chair');
  });

  it('clears uncertain draft only when refreshed history contains the exact client message', () => {
    component.session.set(session);
    component.prompt.set('Find a desk chair');
    service.streamMessage.and.returnValue(concat(
      of({
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 1,
        type: 'activity',
        stage: 'MESSAGE_ACCEPTED',
        label: 'Request accepted',
      } as const),
      throwError(() => new DiscoveryContractError()),
    ));
    component.submitPrompt();
    service.getMessages.and.returnValue(of({
      data: [{
        id: '01U00000000000000000000001',
        role: 'USER',
        body: 'Find a desk chair',
        clientMessageId: '01M00000000000000000000001',
        resolutionType: null,
        result: null,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:02:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    }));

    component.refreshHistory();
    fixture.detectChanges();

    expect(service.streamMessage).toHaveBeenCalledTimes(1);
    expect(component.errorMessage()).toBe('');
    expect(component.prompt()).toBe('');
    expect(component.liveStatus()).toContain('confirmed');
  });

  it('keeps uncertain draft when refreshed history lacks the exact client message', () => {
    component.session.set(session);
    component.prompt.set('Find a desk chair');
    service.streamMessage.and.returnValue(concat(
      of({
        schemaVersion: 'MARKETPLACE_DISCOVERY_STREAM_EVENT_V2',
        sequence: 1,
        type: 'activity',
        stage: 'MESSAGE_ACCEPTED',
        label: 'Request accepted',
      } as const),
      throwError(() => new DiscoveryContractError()),
    ));
    component.submitPrompt();
    service.getMessages.and.returnValue(of({
      data: [{
        id: '01U00000000000000000000002',
        role: 'USER',
        body: 'Older prompt',
        clientMessageId: '01M00000000000000000000009',
        resolutionType: null,
        result: null,
        responseFailure: null,
        responseRetry: null,
        createdAt: '2026-07-20T02:01:00Z',
      }],
      nextCursor: null,
      hasMore: false,
    }));

    component.refreshHistory();
    fixture.detectChanges();

    expect(service.streamMessage).toHaveBeenCalledTimes(1);
    expect(component.errorMessage()).toContain('still uncertain');
    expect(component.canRetryRead()).toBeTrue();
    expect(component.prompt()).toBe('Find a desk chair');
  });
});

function sendResponse(result: DiscoveryTurnResult) {
  return {
    userMessage: {
      id: '01U00000000000000000000001',
      role: 'USER' as const,
      body: 'A desk chair under $100 in Irvine',
      createdAt: '2026-07-20T02:02:00Z',
    },
    result,
    preferenceVersion: 1,
  };
}

function recommendationResult(): DiscoveryTurnResult {
  const listingIds = [
    '01L00000000000000000000001',
    '01L00000000000000000000002',
    '01L00000000000000000000003',
  ];
  return {
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
    preferenceState: {
      query: 'desk chair',
      categoryId: null,
      condition: null,
      minPrice: null,
      maxPrice: '100',
      city: 'Irvine',
      county: null,
      selectedListingId: null,
    },
    inputTokens: 0,
    outputTokens: 0,
    estimatedCost: '0',
  };
}
