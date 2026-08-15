import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { NEVER, of, Subject } from 'rxjs';
import { AgentMarketplaceV2PageComponent } from './agent-marketplace-v2-page.component';
import { AgentMarketplaceV2Service } from './agent-marketplace-v2.service';

describe('AgentMarketplaceV2PageComponent results-first presentation', () => {
  it('renders a customer-facing embedded shell without evaluation-only labels', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-08-03T01:00:00Z', updatedAt: '2026-08-03T01:00:00Z',
    }));
    service.listMessages.and.returnValue(of({ data: [], hasMore: false }));

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.componentRef.setInput('embedded', true);
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Marketplace assistant');
    expect(fixture.nativeElement.textContent).not.toContain('Development evaluation');
    expect(fixture.nativeElement.textContent).not.toContain('Marketplace Agent V2');
    expect(fixture.nativeElement.querySelector('.evidence')).toBeNull();
  });

  it('restores one ordinary scope-boundary message without discovery UI', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-08-03T01:00:00Z', updatedAt: '2026-08-03T01:00:00Z',
    }));
    const boundary = "I'm focused on marketplace help, such as finding and comparing listings and helping with buyer or seller questions. I can't help with that request here.";
    service.listMessages.and.returnValue(of({ data: [{
      id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'ASSISTANT', body: boundary,
      clientMessageId: null, retryable: false, responseRetryUserMessageId: null,
      createdAt: '2026-08-03T01:00:02Z',
      message: {
        role: 'ASSISTANT', content: boundary, attachments: [], refinement: null,
        pendingInteraction: null, citations: [], toolActivity: [],
        inputTokens: 0, outputTokens: 0,
      },
    }], hasMore: false }));

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.componentRef.setInput('embedded', true);
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('article').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain(boundary);
    expect(fixture.nativeElement.querySelector('.attachments')).toBeNull();
    expect(fixture.nativeElement.querySelector('.follow-up')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Marketplace discovery');
    expect(fixture.nativeElement.textContent).not.toContain('Retry response');
  });

  it('renders validated cards before an optional refinement and never auto-sends it', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-07-30T01:00:00Z', updatedAt: '2026-07-30T01:00:00Z',
    }));
    service.listMessages.and.returnValue(of({ data: [{
      id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'ASSISTANT',
      body: 'I found several possible matches. The closest matches appear first.',
      clientMessageId: null, retryable: false, responseRetryUserMessageId: null,
      createdAt: '2026-07-30T01:00:02Z',
      message: {
        role: 'ASSISTANT',
        content: 'I found several possible matches. The closest matches appear first.', citations: [],
        inputTokens: 10, outputTokens: 8, toolActivity: [],
        attachments: [{
          type: 'LISTING', listingId: '01ARZ3NDEKTSV4RRFFQ69G5FAX', title: 'Oak chair',
          categoryName: 'Chair', condition: 'GOOD', priceAmount: '75.00', currency: 'USD',
          publicCity: 'Irvine', publicRegion: 'Orange County',
          thumbnailUrl: '/api/v1/public/listing-media/01ARZ3NDEKTSV4RRFFQ69G5FAZ',
          checkedAt: '2026-07-30T01:00:01Z', responseHash: 'a'.repeat(64),
          matchQuality: 'EXACT',
        }, {
          type: 'LISTING', listingId: '01ARZ3NDEKTSV4RRFFQ69G5FAY', title: 'Related chair',
          categoryName: 'Chair', condition: 'GOOD', priceAmount: '65.00', currency: 'USD',
          publicCity: 'Irvine', publicRegion: 'Orange County', thumbnailUrl: null,
          checkedAt: '2026-07-30T01:00:01Z', responseHash: 'b'.repeat(64),
          matchQuality: 'RELATED',
        }],
        refinement: {
          question: null,
          options: [
            { facet: 'MATCH_SCOPE', value: 'Exact matches only', count: 2 },
            { facet: 'MAXIMUM_PRICE', value: 'Under $20', count: 1 },
            { facet: 'CONDITION', value: 'New condition', count: 1 },
            { facet: 'LOCATION', value: 'Near Irvine', count: 4 },
          ],
        },
        pendingInteraction: null,
      },
    }], hasMore: false }));

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.detectChanges();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();

    const card = fixture.nativeElement.querySelector('.attachments');
    const refinement = fixture.nativeElement.querySelector('.follow-up');
    expect(card.compareDocumentPosition(refinement) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    const buttons = refinement.querySelectorAll('button');
    expect(buttons.length).toBe(4);
    expect(Array.from(
      buttons as NodeListOf<HTMLButtonElement>, item => item.textContent?.trim(),
    )).toEqual([
      'Exact matches only (2)', 'Under $20 (1)', 'New condition (1)', 'Near Irvine (4)',
    ]);
    expect(refinement.textContent).not.toContain('Dining Chair');
    expect(refinement.textContent).not.toContain('General');
    expect(fixture.nativeElement.querySelectorAll('.follow-up').length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('.follow-up > p').length).toBe(0);
    const prose = fixture.nativeElement.querySelector('.message-text').textContent;
    expect(prose).not.toContain('?');
    expect(prose).not.toContain('Oak chair');
    expect(prose).not.toContain('75.00');
    expect(prose).not.toContain('01ARZ3NDEKTSV4RRFFQ69G5FAX');
    expect(prose.toLocaleLowerCase()).not.toContain('retrieval confidence');
    expect(card.querySelectorAll('h3')[0].textContent).toContain('Closest matches');
    expect(card.querySelectorAll('h3')[1].textContent).toContain('Related alternatives');
    expect(Array.from(card.querySelectorAll('a'), (item: Element) => item.textContent?.trim()))
      .toEqual(['PhotoOak chair$75.00', 'PhotoRelated chair$65.00']);
    expect(card.textContent).not.toContain('01ARZ3NDEKTSV4RRFFQ69G5FAX');
    const images = card.querySelectorAll('img.listing-thumbnail');
    expect(images.length).toBe(1);
    expect(images[0].getAttribute('src'))
      .toContain('/api/v1/public/listing-media/01ARZ3NDEKTSV4RRFFQ69G5FAZ');
    expect(images[0].getAttribute('loading')).toBe('lazy');
    expect(images[0].getAttribute('decoding')).toBe('async');
    expect(images[0].getAttribute('alt')).toBe('');
    expect(card.querySelectorAll('.listing-thumbnail-placeholder').length).toBe(2);
    buttons[1].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.draft)
      .toBe('Show current results under $20.');
    expect(service.streamMessage).not.toHaveBeenCalled();
  });

  it('renders one pending follow-up area and sends one explicit yes turn', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-07-30T01:00:00Z', updatedAt: '2026-07-30T01:00:00Z',
    }));
    service.listMessages.and.returnValue(of({ data: [{
      id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'ASSISTANT',
      body: 'Would you like me to run that narrower search?', clientMessageId: null,
      retryable: false, responseRetryUserMessageId: null, createdAt: '2026-07-30T01:00:02Z',
      message: {
        role: 'ASSISTANT', content: 'Would you like me to run that narrower search?',
        attachments: [], citations: [], inputTokens: 10, outputTokens: 8, toolActivity: [],
        refinement: {
          question: null,
          options: [
            { facet: 'MAXIMUM_PRICE', value: 'Under $20', count: 2 },
            { facet: 'LOCATION', value: 'Near Irvine', count: 4 },
          ],
        },
        pendingInteraction: {
          id: '01ARZ3NDEKTSV4RRFFQ69G5FAX', type: 'CONFIRM_ACTION',
          action: 'RUN_REFINED_SEARCH', arguments: { query: 'lamp under 20', limit: 5 },
          workflowType: null, field: null, question: null,
          status: 'WAITING', createdAt: '2026-07-30T01:00:01Z',
        },
      },
    }], hasMore: false }));
    service.streamMessage.and.returnValue(NEVER);

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    const followUps = fixture.nativeElement.querySelectorAll('.follow-up');
    expect(followUps.length).toBe(1);
    const buttons = followUps[0].querySelectorAll('button');
    expect(Array.from(buttons, (item: Element) => item.textContent?.trim()))
      .toEqual(['Yes', 'No']);

    buttons[0].click();

    expect(service.streamMessage).toHaveBeenCalledTimes(1);
    expect(service.streamMessage.calls.mostRecent().args[2]).toBe('yes');
  });

  it('renders a seller field question as ordinary conversation without yes/no controls', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-08-03T01:00:00Z', updatedAt: '2026-08-03T01:00:00Z',
    }));
    service.listMessages.and.returnValue(of({ data: [{
      id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'ASSISTANT',
      body: 'Great. What are you selling?', clientMessageId: null,
      retryable: false, responseRetryUserMessageId: null, createdAt: '2026-08-03T01:00:02Z',
      message: {
        role: 'ASSISTANT', content: 'Great. What are you selling?',
        attachments: [], citations: [], inputTokens: 10, outputTokens: 8,
        toolActivity: [], refinement: null,
        pendingInteraction: {
          id: '01ARZ3NDEKTSV4RRFFQ69G5FAX', type: 'ANSWER_FIELD', action: null,
          workflowType: 'CREATE_LISTING', field: 'ITEM_TYPE',
          question: 'What are you selling?', arguments: {}, status: 'WAITING',
          createdAt: '2026-08-03T01:00:01Z',
        },
      },
    }], hasMore: false }));
    service.streamMessage.and.returnValue(NEVER);

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('What are you selling?');
    expect(fixture.nativeElement.querySelector('.follow-up')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.follow-up button').length).toBe(0);
  });

  it('restores the persisted terminal assistant and Retry response after a stream error', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    const sessionId = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
    const userId = '01ARZ3NDEKTSV4RRFFQ69G5FAW';
    const assistantId = '01ARZ3NDEKTSV4RRFFQ69G5FAX';
    const clientId = '01ARZ3NDEKTSV4RRFFQ69G5FAY';
    const stream = new Subject<any>();
    service.createSession.and.returnValue(of({
      sessionId, sessionType: 'MARKETPLACE_AGENT_V2', status: 'OPEN',
      createdAt: '2026-08-02T01:00:00Z', updatedAt: '2026-08-02T01:00:00Z',
    }));
    service.listMessages.and.returnValues(
      of({ data: [], hasMore: false }),
      of({ data: [{
        id: userId, role: 'USER', body: 'hi', clientMessageId: clientId,
        message: null, retryable: false, responseRetryUserMessageId: null,
        createdAt: '2026-08-02T01:00:01Z',
      }, {
        id: assistantId, role: 'ASSISTANT',
        body: "I couldn't complete this response. You can retry without sending your message again.",
        clientMessageId: null, retryable: true, responseRetryUserMessageId: userId,
        createdAt: '2026-08-02T01:00:02Z',
        message: {
          role: 'ASSISTANT',
          content: "I couldn't complete this response. You can retry without sending your message again.",
          attachments: [], refinement: null, pendingInteraction: null, citations: [],
          toolActivity: [], inputTokens: 0, outputTokens: 0,
        },
      }], hasMore: false }),
    );
    service.streamMessage.and.returnValue(stream);

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.componentInstance.draft = 'hi';
    await fixture.componentInstance.send();
    stream.next({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'message_started', userMessage: {
        id: userId, role: 'USER', body: 'hi', createdAt: '2026-08-02T01:00:01Z',
      },
    });
    stream.next({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 2,
      type: 'error', code: 'MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED',
      message: 'The Marketplace Agent V2 response was interrupted.', retryable: true,
    });
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(service.listMessages).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.messages().filter(item => item.role === 'USER').length).toBe(1);
    expect(fixture.componentInstance.messages().filter(item => item.role === 'ASSISTANT').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Retry response');
    expect(fixture.nativeElement.textContent).toContain("I couldn't complete this response");
  });

  it('keeps embedded history scrollable and opens at the newest message', async () => {
    const service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-08-03T01:00:00Z', updatedAt: '2026-08-03T01:00:00Z',
    }));
    service.listMessages.and.returnValue(of({ data: [{
      id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'ASSISTANT', body: 'Most recent response',
      clientMessageId: null, retryable: false, responseRetryUserMessageId: null,
      createdAt: '2026-08-03T01:00:02Z', message: {
        role: 'ASSISTANT', content: 'Most recent response', attachments: [], refinement: null,
        pendingInteraction: null, citations: [], toolActivity: [], inputTokens: 1, outputTokens: 2,
      },
    }], hasMore: false }));

    await TestBed.configureTestingModule({
      imports: [AgentMarketplaceV2PageComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentMarketplaceV2Service, useValue: service },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AgentMarketplaceV2PageComponent);
    fixture.componentRef.setInput('embedded', true);
    fixture.detectChanges();
    const conversation = fixture.nativeElement.querySelector('.conversation') as HTMLElement;
    Object.defineProperty(conversation, 'scrollHeight', { configurable: true, value: 720 });
    Object.defineProperty(conversation, 'clientHeight', { configurable: true, value: 240 });
    let assignedScrollTop = 0;
    Object.defineProperty(conversation, 'scrollTop', {
      configurable: true,
      get: () => assignedScrollTop,
      set: value => { assignedScrollTop = value; },
    });

    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(getComputedStyle(conversation).overflowY).toBe('auto');
    expect(getComputedStyle(conversation).overflowX).toBe('hidden');
    expect(getComputedStyle(conversation).scrollBehavior).toBe('smooth');
    expect(conversation.tabIndex).toBe(0);
    expect(assignedScrollTop).toBe(720);
    expect(fixture.nativeElement.textContent).toContain('Most recent response');
    const shell = fixture.nativeElement.querySelector('.v2-shell') as HTMLElement;
    const header = fixture.nativeElement.querySelector('header') as HTMLElement;
    const composer = fixture.nativeElement.querySelector('form') as HTMLElement;
    const messageText = fixture.nativeElement.querySelector('.message-text') as HTMLElement;
    expect(getComputedStyle(shell).display).toBe('flex');
    expect(getComputedStyle(shell).flexDirection).toBe('column');
    expect(getComputedStyle(header).flexShrink).toBe('0');
    expect(getComputedStyle(composer).flexShrink).toBe('0');
    expect(getComputedStyle(messageText).overflowWrap).toBe('anywhere');

    assignedScrollTop = 100;
    conversation.dispatchEvent(new Event('scroll'));
    (fixture.componentInstance as any).handle({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'text_delta', delta: 'New streamed text',
    }, false);
    fixture.detectChanges();
    expect(assignedScrollTop).toBe(100);
  });
});
