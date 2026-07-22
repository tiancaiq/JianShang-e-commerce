import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AGENT_CLIENT_MESSAGE_ID_FACTORY } from './agent-customer-service.capability';
import { AgentMarketplaceDiscoveryComponent } from './agent-marketplace-discovery.component';
import { DiscoverySession, DiscoveryTurnResult } from './agent-marketplace-discovery.model';
import { AgentMarketplaceDiscoveryService } from './agent-marketplace-discovery.service';

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
    },
    preferenceVersion: 0,
    clarificationTurnCount: 0,
    clarificationQuestionCount: 0,
    createdAt: '2026-07-20T02:00:00Z',
    updatedAt: '2026-07-20T02:00:00Z',
  };
  let fixture: ComponentFixture<AgentMarketplaceDiscoveryComponent>;
  let component: AgentMarketplaceDiscoveryComponent;
  let service: jasmine.SpyObj<AgentMarketplaceDiscoveryService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage'],
    );
    service.createOrResumeSession.and.returnValue(of(session));
    service.getSession.and.returnValue(of(session));
    service.getMessages.and.returnValue(of({
      data: [],
      nextCursor: null,
      hasMore: false,
    }));
    service.sendMessage.and.returnValue(of(sendResponse(recommendationResult())));

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

  it('renders a query-first accessible landing with zero requests before submit', () => {
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('h2')?.textContent).toContain('Describe what you are trying to find');
    expect(root.querySelector('label[for="discovery-prompt"]')).not.toBeNull();
    expect(root.querySelector('[aria-live="polite"]')).not.toBeNull();
    expect(root.textContent).toContain('cannot buy, message, reserve, or verify');
    expect(service.createOrResumeSession).not.toHaveBeenCalled();
    expect(service.getSession).not.toHaveBeenCalled();
    expect(service.getMessages).not.toHaveBeenCalled();
    expect(service.sendMessage).not.toHaveBeenCalled();

    root.querySelector<HTMLButtonElement>('.examples button')?.click();
    fixture.detectChanges();
    expect(component.prompt()).toContain('desk chair');
    expect(service.createOrResumeSession).not.toHaveBeenCalled();
  });

  it('creates/resumes, loads history and sends one exact first turn', () => {
    component.prompt.set('A desk chair under $100 in Irvine');
    component.submitPrompt();
    fixture.detectChanges();

    expect(service.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(service.getMessages).toHaveBeenCalledOnceWith(sessionId, null, 50);
    expect(service.sendMessage).toHaveBeenCalledOnceWith(
      sessionId,
      '01M00000000000000000000001',
      0,
      'A desk chair under $100 in Irvine',
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
    expect(root.textContent).not.toContain('Message seller');
    expect(root.textContent).not.toContain('Buy');
    expect(cards[2].textContent).not.toContain('Public area');
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('.listing-card a'));
    expect(links.every(link => link.getAttribute('href')?.startsWith('/listings/'))).toBeTrue();
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
    expect(service.sendMessage).toHaveBeenCalledTimes(1);
  });

  it('renders no-results, refusal and handoff as distinct safe terminal states', () => {
    const terminalOutcomes = [
      ['NO_RESULTS', 'No current matches'],
      ['REFUSE', 'Cannot assist'],
      ['HANDOFF', 'Search unavailable'],
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
    expect(fixture.nativeElement.textContent).toContain('Start a new search?');

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
    expect(service.getMessages).toHaveBeenCalledOnceWith(sessionId, null, 50);
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
    },
    inputTokens: 0,
    outputTokens: 0,
    estimatedCost: '0',
  };
}
