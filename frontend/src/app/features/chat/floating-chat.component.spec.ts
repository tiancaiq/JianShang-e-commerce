import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ConversationPage, ConversationSummary } from '../../core/models/chat.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import {
  AGENT_CUSTOMER_SERVICE_ENABLED,
  AGENT_DISCOVERY_ENABLED,
  AGENT_MARKETPLACE_V2_ENABLED,
} from '../agent/agent-customer-service.capability';
import { AgentCustomerService } from '../agent/agent-customer-service.service';
import { AgentMarketplaceDiscoveryService } from '../agent/agent-marketplace-discovery.service';
import { AgentMarketplaceV2Service } from '../agent/agent-marketplace-v2.service';
import { FloatingChatComponent } from './floating-chat.component';

describe('FloatingChatComponent', () => {
  let fixture: ComponentFixture<FloatingChatComponent>;
  let component: FloatingChatComponent;
  let authenticated = true;
  let chatService: jasmine.SpyObj<ChatService>;
  let agentService: jasmine.SpyObj<AgentCustomerService>;
  let discoveryService: jasmine.SpyObj<AgentMarketplaceDiscoveryService>;
  let v2Service: jasmine.SpyObj<AgentMarketplaceV2Service>;
  let listingService: jasmine.SpyObj<ListingService>;
  let conversationRead: Subject<string>;

  const conversationId = '01C00000000000000000000001';

  const conversation: ConversationSummary = {
    id: conversationId,
    conversationType: 'LISTING_BUYER_SELLER',
    status: 'OPEN',
    listing: {
      id: '01L00000000000000000000001',
      title: 'Used bicycle',
      sellerType: 'INDIVIDUAL',
      quantity: 1,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      thumbnailUrl: null,
      transactionNotice: 'Payment and delivery are arranged directly by participants.',
    },
    participants: [
      {
        participantId: '01U00000000000000000000011',
        displayName: 'You',
        avatarUrl: null,
        initials: 'Y',
        roleInConversation: 'BUYER',
        currentUser: true,
      },
      {
        participantId: '01U00000000000000000000022',
        displayName: 'Alex Seller',
        publicHandle: 'alex-sells',
        avatarUrl: 'http://localhost:9000/api/v1/public/user-avatars/01U00000000000000000000022?v=4',
        initials: 'AS',
        roleInConversation: 'SELLER',
        currentUser: false,
      },
    ],
    completion: {
      id: null,
      listingId: '01L00000000000000000000001',
      conversationId,
      sellerUserId: '01U00000000000000000000022',
      buyerUserId: '01U00000000000000000000011',
      quantitySold: null,
      status: 'NOT_STARTED',
      sellerMarkedDoneAt: null,
      buyerConfirmedAt: null,
      cancelledAt: null,
      currentUserCanMarkDone: false,
      currentUserCanConfirm: false,
    },
    createdAt: '2026-07-06T12:00:00Z',
    updatedAt: '2026-07-06T12:01:00Z',
  };

  const conversationPage: ConversationPage = {
    items: [
      {
        id: conversationId,
        conversationType: 'LISTING_BUYER_SELLER',
        status: 'OPEN',
        listing: conversation.listing,
        otherParticipant: conversation.participants[1],
        lastMessage: {
          id: '01M00000000000000000000001',
          conversationId,
          senderUserId: '01U00000000000000000000022',
          messageType: 'TEXT',
          body: 'Still available.',
          moderationState: 'VISIBLE',
          currentUser: false,
          createdAt: '2026-07-06T12:01:00Z',
        },
        unread: true,
        lastMessageAt: '2026-07-06T12:01:00Z',
        createdAt: '2026-07-06T12:00:00Z',
        updatedAt: '2026-07-06T12:01:00Z',
      },
    ],
    nextCursor: null,
  };

  beforeEach(async () => {
    document.body.classList.remove('listing-chat-dialog-open');
    conversationRead = new Subject<string>();
    agentService = jasmine.createSpyObj<AgentCustomerService>(
      'AgentCustomerService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage'],
    );
    discoveryService = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      [
        'createOrResumeSession', 'getSession', 'getMessages', 'sendMessage',
        'streamMessage', 'retryResponse', 'excludeListing',
      ],
    );
    v2Service = jasmine.createSpyObj<AgentMarketplaceV2Service>(
      'AgentMarketplaceV2Service',
      ['createSession', 'listMessages', 'streamMessage', 'retryResponse', 'stopMessage'],
    );
    v2Service.createSession.and.returnValue(of({
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', sessionType: 'MARKETPLACE_AGENT_V2',
      status: 'OPEN', createdAt: '2026-08-03T01:00:00Z', updatedAt: '2026-08-03T01:00:00Z',
    }));
    v2Service.listMessages.and.returnValue(of({ data: [], hasMore: false }));
    const discoverySession = {
      id: '01D00000000000000000000001',
      sessionType: 'MARKETPLACE_DISCOVERY' as const,
      status: 'OPEN' as const,
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
    discoveryService.createOrResumeSession.and.returnValue(of(discoverySession));
    discoveryService.getSession.and.returnValue(of(discoverySession));
    discoveryService.getMessages.and.returnValue(of({
      data: [],
      nextCursor: null,
      hasMore: false,
    }));
    chatService = jasmine.createSpyObj<ChatService>(
      'ChatService',
      ['getConversations', 'getConversation', 'getMessages', 'sendMessage', 'markRead', 'markDone', 'confirmCompletion', 'notifyConversationRead'],
    );
    Object.defineProperty(chatService, 'conversationRead$', {
      value: conversationRead.asObservable(),
    });
    chatService.getConversations.and.returnValue(of(conversationPage));
    chatService.getConversation.and.returnValue(of(conversation));
    chatService.getMessages.and.returnValue(of({ items: [conversationPage.items[0].lastMessage!], nextCursor: null }));
    chatService.markRead.and.returnValue(of({
      conversationId,
      lastReadMessageId: '01M00000000000000000000001',
      lastReadAt: '2026-07-06T12:02:00Z',
      unread: false,
    }));
    chatService.sendMessage.and.returnValue(of({
      id: '01M00000000000000000000002',
      conversationId,
      senderUserId: '01U00000000000000000000011',
      messageType: 'TEXT',
      body: 'I can meet today.',
      moderationState: 'VISIBLE',
      currentUser: true,
      createdAt: '2026-07-06T12:03:00Z',
    }));
    chatService.markDone.and.returnValue(of({
      ...conversation.completion!,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'SELLER_MARKED_DONE',
      sellerMarkedDoneAt: '2026-07-06T12:04:00Z',
      currentUserCanMarkDone: false,
      currentUserCanConfirm: false,
    }));
    chatService.confirmCompletion.and.returnValue(of({
      ...conversation.completion!,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'BUYER_CONFIRMED',
      sellerMarkedDoneAt: '2026-07-06T12:04:00Z',
      buyerConfirmedAt: '2026-07-06T12:05:00Z',
      currentUserCanMarkDone: false,
      currentUserCanConfirm: false,
    }));
    listingService = jasmine.createSpyObj<ListingService>(
      'ListingService',
      ['searchMarketplaceListings', 'mediaUrl'],
    );
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [],
      page: { nextCursor: null, hasMore: false },
    }));
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [FloatingChatComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ChatService, useValue: chatService },
        { provide: AgentCustomerService, useValue: agentService },
        { provide: AgentMarketplaceDiscoveryService, useValue: discoveryService },
        { provide: AgentMarketplaceV2Service, useValue: v2Service },
        { provide: ListingService, useValue: listingService },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
          },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    document.body.classList.remove('listing-chat-dialog-open');
  });

  function createComponent(): void {
    fixture = TestBed.createComponent(FloatingChatComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('hides the launcher for guests', () => {
    authenticated = false;

    createComponent();

    expect(fixture.nativeElement.querySelector('.chat-launcher')).toBeNull();
    expect(chatService.getConversations).not.toHaveBeenCalled();
  });

  it('shows unread count for signed-in users', () => {
    authenticated = true;

    createComponent();

    expect(fixture.nativeElement.querySelector('.chat-launcher')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('1');
  });

  it('clears the launcher badge when another chat surface marks the conversation read', () => {
    authenticated = true;
    createComponent();

    expect(component.unreadCount()).toBe(1);

    conversationRead.next(conversationId);
    fixture.detectChanges();

    expect(component.unreadCount()).toBe(0);
    expect(fixture.nativeElement.querySelector('.launcher-badge')).toBeNull();
  });

  it('hides the launcher while the listing chat dialog is open', () => {
    authenticated = true;
    document.body.classList.add('listing-chat-dialog-open');

    createComponent();

    expect(getComputedStyle(fixture.nativeElement.querySelector('.floating-chat')).display).toBe('none');
  });

  it('opens recent conversations from the launcher', () => {
    authenticated = true;
    createComponent();

    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.chat-panel')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Marketplace agent');
    expect(fixture.nativeElement.querySelector('.agent-row')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Still available.');
    expect(fixture.nativeElement.querySelector('.conversation-row:not(.agent-row) img')?.getAttribute('src'))
      .toBe('http://localhost:9000/api/v1/public/user-avatars/01U00000000000000000000022?v=4');
  });

  it('keeps the deferred agent option hidden when there are no buyer/seller chats', () => {
    authenticated = true;
    chatService.getConversations.and.returnValue(of({ items: [], nextCursor: null }));
    createComponent();

    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Marketplace agent');
    expect(fixture.nativeElement.querySelector('.agent-row')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('No buyer/seller chats yet.');
    expect(fixture.nativeElement.textContent).toContain('Ready when a chat starts');
  });

  it('shows a retry state when recent conversations fail to load', () => {
    authenticated = true;
    chatService.getConversations.and.returnValue(throwError(() => new Error('network')));
    createComponent();

    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    const callsBeforeRetry = chatService.getConversations.calls.count();

    expect(fixture.nativeElement.textContent).toContain('Could not load chats.');

    fixture.nativeElement.querySelector('.error-state button').click();
    fixture.detectChanges();

    expect(chatService.getConversations.calls.count()).toBe(callsBeforeRetry + 1);
  });

  it('does not activate the agent placeholder while the capability is disabled', () => {
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();

    component.showAgent();
    fixture.detectChanges();

    expect(component.agentSelected()).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('Marketplace agent is coming soon');
    expect(chatService.getConversation).not.toHaveBeenCalled();
    expect(chatService.getMessages).not.toHaveBeenCalled();
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
  });

  it('opens discovery-first agent chat under the legacy assistant flag without listing selection', async () => {
    TestBed.overrideProvider(AGENT_CUSTOMER_SERVICE_ENABLED, { useValue: true });
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const discovery = fixture.nativeElement.querySelector(
      'app-agent-marketplace-discovery',
    ) as HTMLElement;
    expect(discovery).not.toBeNull();
    expect(discovery.textContent).toContain('How can I help?');
    expect(discovery.querySelector<HTMLTextAreaElement>('#discovery-prompt')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Ask what you need');
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(fixture.nativeElement.querySelector('.listing-picker')).toBeNull();
    expect(chatService.getConversation).not.toHaveBeenCalled();
    expect(chatService.getMessages).not.toHaveBeenCalled();
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
    expect(agentService.sendMessage).not.toHaveBeenCalled();
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.getMessages)
      .toHaveBeenCalledOnceWith('01D00000000000000000000001', null, 50);
    expect(discoveryService.sendMessage).not.toHaveBeenCalled();
  });

  it('opens query-first discovery with no listing wall or request when discovery is enabled', async () => {
    TestBed.overrideProvider(AGENT_CUSTOMER_SERVICE_ENABLED, { useValue: true });
    TestBed.overrideProvider(AGENT_DISCOVERY_ENABLED, { useValue: true });
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();
    listingService.searchMarketplaceListings.calls.reset();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const discovery = fixture.nativeElement.querySelector(
      'app-agent-marketplace-discovery',
    ) as HTMLElement;
    const prompt = discovery.querySelector<HTMLTextAreaElement>('#discovery-prompt');
    expect(discovery.querySelector('h2')?.textContent)
      .toContain('Marketplace assistant');
    expect(discovery.textContent).toContain('How can I help?');
    expect(fixture.nativeElement.textContent).toContain('Ask what you need');
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(fixture.nativeElement.querySelector('.listing-picker')).toBeNull();
    expect(document.activeElement).toBe(prompt);
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.getSession).not.toHaveBeenCalled();
    expect(discoveryService.getMessages)
      .toHaveBeenCalledOnceWith('01D00000000000000000000001', null, 50);
    expect(discoveryService.sendMessage).not.toHaveBeenCalled();
    expect(listingService.searchMarketplaceListings).not.toHaveBeenCalled();
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
  });

  it('cuts the floating Marketplace assistant over to V2 when its build flag is enabled', async () => {
    TestBed.overrideProvider(AGENT_MARKETPLACE_V2_ENABLED, { useValue: true });
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-agent-marketplace-v2-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).toBeNull();
    expect(fixture.nativeElement.querySelector('.v2-shell.compact')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Marketplace assistant');
    expect(fixture.nativeElement.textContent).toContain('Customer service and listing help');
    expect(fixture.nativeElement.textContent).not.toContain('Development evaluation');
    expect(fixture.nativeElement.textContent).not.toContain('Latest safe evaluation evidence');
    expect(v2Service.createSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.createOrResumeSession).not.toHaveBeenCalled();
  });

  it('keeps the Agent row in one discovery conversation with no legacy mode switch', () => {
    TestBed.overrideProvider(AGENT_CUSTOMER_SERVICE_ENABLED, { useValue: true });
    TestBed.overrideProvider(AGENT_DISCOVERY_ENABLED, { useValue: true });
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-agent-customer-service-thread')).toBeNull();
    expect(fixture.nativeElement.querySelector('.agent-mode-switch')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
  });

  it('opens a thread and marks it read', () => {
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.conversation-row:not(.agent-row)').click();
    fixture.detectChanges();

    expect(chatService.getConversation).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.getMessages).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.markRead).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.notifyConversationRead).toHaveBeenCalledWith(conversationId);
    expect(fixture.nativeElement.querySelector('.thread-listing a')?.getAttribute('href'))
      .toBe('/listings/01L00000000000000000000001');
    expect(fixture.nativeElement.textContent).toContain('Safety note: Payment and delivery are arranged directly between users. Meet in public.');
    expect(fixture.nativeElement.textContent).toContain('@alex-sells');
    expect(fixture.nativeElement.textContent).toContain('Awaiting buyer');
    expect(fixture.nativeElement.textContent).toContain('Qty 1');
    expect(component.conversations()[0].unread).toBeFalse();
  });

  it('sends a message in the floating thread', () => {
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.conversation-row:not(.agent-row)').click();
    component.draft.set('I can meet today.');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.composer button').click();
    fixture.detectChanges();

    expect(chatService.sendMessage).toHaveBeenCalledOnceWith(conversationId, 'I can meet today.');
    expect(component.messages().some(message => message.body === 'I can meet today.')).toBeTrue();
    expect(component.conversations()[0].unread).toBeFalse();
  });

  it('lets a seller mark the selected conversation done', () => {
    authenticated = true;
    chatService.getConversation.and.returnValue(of({
      ...conversation,
      completion: {
        ...conversation.completion!,
        currentUserCanMarkDone: true,
      },
    }));
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.conversation-row:not(.agent-row)').click();
    fixture.detectChanges();

    component.completionQuantity.set(2);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.trade-card button').click();
    fixture.detectChanges();

    expect(chatService.markDone).toHaveBeenCalledOnceWith(conversationId, 2);
    expect(component.activeConversation()?.completion?.status).toBe('SELLER_MARKED_DONE');
  });

  it('reviews buyer confirmation and makes the completed thread read-only', () => {
    authenticated = true;
    chatService.getConversation.and.returnValue(of({
      ...conversation,
      completion: {
        ...conversation.completion!,
        id: '01T00000000000000000000001',
        quantitySold: 1,
        status: 'SELLER_MARKED_DONE',
        sellerMarkedDoneAt: '2026-07-06T12:04:00Z',
        currentUserCanConfirm: true,
      },
    }));
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.conversation-row:not(.agent-row)').click();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.completion-status button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Confirm this handoff');
    expect(fixture.nativeElement.textContent).toContain('does not verify or protect that payment');

    fixture.nativeElement.querySelector('.confirmation-review .review-actions button:last-child').click();
    fixture.detectChanges();

    expect(chatService.confirmCompletion).toHaveBeenCalledOnceWith(conversationId);
    expect(fixture.nativeElement.querySelector('.composer')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Read-only');
  });
});
