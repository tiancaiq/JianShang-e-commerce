import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ConversationSummary } from '../../core/models/chat.model';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import { publicListing } from '../../testing/listing-test-fixtures';
import {
  AGENT_CLIENT_MESSAGE_ID_FACTORY,
  AGENT_CUSTOMER_SERVICE_ENABLED,
} from './agent-customer-service.capability';
import {
  AgentMessage,
  AgentSession,
  SendAgentMessageResponse,
} from './agent-customer-service.model';
import { AgentCustomerService } from './agent-customer-service.service';
import { AgentCustomerServiceThreadComponent } from './agent-customer-service-thread.component';

describe('AgentCustomerServiceThreadComponent', () => {
  let fixture: ComponentFixture<AgentCustomerServiceThreadComponent>;
  let component: AgentCustomerServiceThreadComponent;
  let agentService: jasmine.SpyObj<AgentCustomerService>;
  let chatService: jasmine.SpyObj<ChatService>;
  let listingService: jasmine.SpyObj<ListingService>;
  let router: Router;

  const listingId = '01L00000000000000000000001';
  const session: AgentSession = {
    id: '01A00000000000000000000001',
    sessionType: 'LISTING_CUSTOMER_SERVICE',
    status: 'OPEN',
    subjectListing: {
      id: listingId,
      version: '12',
      title: 'Used bicycle',
      thumbnailUrl: null,
      transactionNotice: 'Payment and delivery are arranged directly by participants.',
    },
    createdAt: '2026-07-20T02:00:00Z',
    updatedAt: '2026-07-20T02:00:00Z',
  };
  const userMessage: AgentMessage = {
    id: '01M00000000000000000000001',
    role: 'USER',
    body: 'Can I collect it today?',
    resolutionType: null,
    sources: [],
    actions: [],
    createdAt: '2026-07-20T02:01:00Z',
  };
  const assistantMessage: AgentMessage = {
    id: '01M00000000000000000000002',
    role: 'ASSISTANT',
    body: 'Only the seller can confirm a collection time.',
    resolutionType: 'CONTACT_SELLER',
    sources: [{
      sourceType: 'LISTING',
      sourceId: listingId,
      sourceVersion: '12',
      label: 'Current listing',
    }],
    actions: [{ type: 'MESSAGE_SELLER', listingId }],
    createdAt: '2026-07-20T02:01:01Z',
  };
  const response: SendAgentMessageResponse = { userMessage, assistantMessage };

  beforeEach(async () => {
    agentService = jasmine.createSpyObj<AgentCustomerService>(
      'AgentCustomerService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage'],
    );
    chatService = jasmine.createSpyObj<ChatService>('ChatService', ['startListingConversation']);
    listingService = jasmine.createSpyObj<ListingService>(
      'ListingService',
      ['searchMarketplaceListings', 'mediaUrl'],
    );
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [publicListing({
        id: listingId,
        sellerType: 'INDIVIDUAL',
        title: 'Used bicycle',
        images: [],
      })],
      page: { nextCursor: null, hasMore: false },
    }));
    listingService.mediaUrl.and.callFake(url => url || '');
    agentService.createOrResumeSession.and.returnValue(of(session));
    agentService.getSession.and.returnValue(of(session));
    agentService.getMessages.and.returnValue(of({ data: [], nextCursor: null, hasMore: false }));
    agentService.sendMessage.and.returnValue(of(response));
    chatService.startListingConversation.and.returnValue(of({
      id: '01C00000000000000000000001',
    } as ConversationSummary));

    await TestBed.configureTestingModule({
      imports: [AgentCustomerServiceThreadComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: true },
        {
          provide: AGENT_CLIENT_MESSAGE_ID_FACTORY,
          useValue: () => '01C00000000000000000000001',
        },
        { provide: AgentCustomerService, useValue: agentService },
        { provide: ChatService, useValue: chatService },
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentCustomerServiceThreadComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
  });

  it('loads the public selector without starting an Agent session or model request', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Choose a listing');
    expect(listingService.searchMarketplaceListings).toHaveBeenCalledOnceWith({
      q: null,
      sort: 'newest',
      limit: 8,
    });
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
    expect(agentService.getSession).not.toHaveBeenCalled();
    expect(agentService.getMessages).not.toHaveBeenCalled();
    expect(agentService.sendMessage).not.toHaveBeenCalled();
  });

  it('renders no picker and makes no listing or Agent request when the capability is disabled', () => {
    Object.defineProperty(component, 'enabled', { value: false });

    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-agent-listing-context-picker')).toBeNull();
    expect(listingService.searchMarketplaceListings).not.toHaveBeenCalled();
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
    expect(agentService.getSession).not.toHaveBeenCalled();
  });

  it('restores an authorized direct agent session route without using buyer-seller message APIs', () => {
    component.sessionId = session.id;
    fixture.detectChanges();

    expect(agentService.getSession).toHaveBeenCalledOnceWith(session.id);
    expect(agentService.getMessages).toHaveBeenCalledOnceWith(session.id, null, 50);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
  });

  it('shows loading while it creates a listing-bound session without making a model request', () => {
    const created = new Subject<AgentSession>();
    agentService.createOrResumeSession.and.returnValue(created);
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.listing-result').click();
    fixture.detectChanges();

    expect(component.loading()).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Opening listing help');
    expect(agentService.createOrResumeSession).toHaveBeenCalledOnceWith(listingId);
    expect(agentService.sendMessage).not.toHaveBeenCalled();

    created.next(session);
    created.complete();
    fixture.detectChanges();

    expect(agentService.getMessages).toHaveBeenCalledOnceWith(session.id, null, 50);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
  });

  it('uses an explicit detail launch context without sending title or actor fields to the API', () => {
    component.initialListing = {
      listingId: listingId.toLowerCase(),
      title: '  Used\u0000\n bicycle  ',
    };

    fixture.detectChanges();

    expect(agentService.createOrResumeSession).toHaveBeenCalledOnceWith(listingId);
    expect(component.selectedListing()).toEqual({ listingId, title: 'Used bicycle' });
    expect(agentService.sendMessage).not.toHaveBeenCalled();
    expect(chatService.startListingConversation).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.trade-card')).toBeNull();
  });

  it('returns to the public selector when a selected listing has been removed', () => {
    agentService.createOrResumeSession.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.listing-result').click();
    fixture.detectChanges();

    expect(component.listingUnavailable()).toBeTrue();
    expect(component.session()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('This listing is unavailable');
    expect(fixture.nativeElement.textContent).toContain(
      'This listing is not available for AI assistance.',
    );
    expect(fixture.nativeElement.querySelector('app-agent-listing-context-picker')).not.toBeNull();
    expect(agentService.sendMessage).not.toHaveBeenCalled();
    expect(chatService.startListingConversation).not.toHaveBeenCalled();
  });

  it('renders grounded provenance and the explicit seller handoff without trade controls', () => {
    fixture.detectChanges();
    component.session.set(session);
    component.messages.set([userMessage, assistantMessage]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Only the seller can confirm');
    expect(fixture.nativeElement.textContent).toContain('Current listing');
    expect(fixture.nativeElement.textContent).toContain(`LISTING · ${listingId} · v12`);
    expect(fixture.nativeElement.textContent).toContain('Nothing has been sent automatically');
    expect(fixture.nativeElement.textContent).not.toContain('Trade completion');
    expect(fixture.nativeElement.querySelector('.trade-card')).toBeNull();
  });

  it('shows the pending question and appends the typed response', () => {
    const sent = new Subject<SendAgentMessageResponse>();
    agentService.sendMessage.and.returnValue(sent);
    fixture.detectChanges();
    component.session.set(session);
    component.draft.set('Can I collect it today?');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.composer button').click();
    fixture.detectChanges();

    expect(agentService.sendMessage).toHaveBeenCalledOnceWith(
      session.id,
      '01C00000000000000000000001',
      'Can I collect it today?',
    );
    expect(fixture.nativeElement.textContent).toContain('Checking approved listing sources');

    sent.next(response);
    sent.complete();
    fixture.detectChanges();

    expect(component.messages()).toEqual([userMessage, assistantMessage]);
    expect(component.pendingQuestion()).toBeNull();
  });

  it('maps outages to a safe retry that reuses the same client message id', () => {
    const outage = new HttpErrorResponse({ status: 503 });
    agentService.sendMessage.and.returnValue(throwError(() => outage));
    fixture.detectChanges();
    component.session.set(session);
    component.draft.set('Can I collect it today?');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.composer button').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Listing help is temporarily unavailable');
    expect(fixture.nativeElement.textContent).toContain('marketplace and seller messages still work');

    agentService.sendMessage.and.returnValue(of(response));
    fixture.nativeElement.querySelector('.outage-state button').click();
    fixture.detectChanges();

    expect(agentService.sendMessage.calls.count()).toBe(2);
    expect(agentService.sendMessage.calls.allArgs()).toEqual([
      [session.id, '01C00000000000000000000001', 'Can I collect it today?'],
      [session.id, '01C00000000000000000000001', 'Can I collect it today?'],
    ]);
  });

  it('keeps an in-progress idempotent message retryable without making the session read-only', () => {
    const inProgress = new HttpErrorResponse({
      status: 409,
      error: {
        error: {
          code: 'AGENT_MESSAGE_IN_PROGRESS',
          message: 'This message is already being processed.',
          details: [],
          correlationId: 'agent-ui-conflict-1',
        },
      },
    });
    agentService.sendMessage.and.returnValue(throwError(() => inProgress));
    fixture.detectChanges();
    component.session.set(session);
    component.draft.set('Can I collect it today?');

    component.sendQuestion();
    fixture.detectChanges();

    expect(component.session()?.status).toBe('OPEN');
    expect(component.listingUnavailable()).toBeFalse();
    expect(component.pendingQuestion()?.clientMessageId).toBe('01C00000000000000000000001');
    expect(fixture.nativeElement.textContent).toContain('still being processed');
    expect(fixture.nativeElement.querySelector('.outage-state button')).not.toBeNull();
  });

  it('stops offering an invalid retry key after a terminal idempotency conflict', () => {
    const exhausted = new HttpErrorResponse({
      status: 409,
      error: {
        error: {
          code: 'AGENT_RETRY_EXHAUSTED',
          message: 'The retry limit has been reached.',
          details: [],
          correlationId: 'agent-ui-conflict-2',
        },
      },
    });
    agentService.sendMessage.and.returnValue(throwError(() => exhausted));
    fixture.detectChanges();
    component.session.set(session);
    component.draft.set('Can I collect it today?');

    component.sendQuestion();
    fixture.detectChanges();

    expect(component.session()?.status).toBe('OPEN');
    expect(component.pendingQuestion()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('cannot be retried');
    expect(fixture.nativeElement.querySelector('.outage-state button')).toBeNull();
  });

  it('opens the existing seller conversation only after the user chooses Message seller', () => {
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
    component.session.set(session);
    component.messages.set([assistantMessage]);
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.answer-actions button').click();
    fixture.detectChanges();

    expect(chatService.startListingConversation).toHaveBeenCalledOnceWith(listingId);
    expect(navigate).toHaveBeenCalledWith(['/account/messages', '01C00000000000000000000001']);
  });

  it('makes an unavailable session read-only and preserves marketplace navigation', () => {
    fixture.detectChanges();
    component.session.set({ ...session, status: 'READ_ONLY' });
    component.listingUnavailable.set(true);
    component.errorMessage.set('The listing is no longer available.');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.composer')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('This assistant thread is read-only');
    expect(fixture.nativeElement.querySelector('.read-only a')?.getAttribute('href')).toBe('/marketplace');
  });
});
