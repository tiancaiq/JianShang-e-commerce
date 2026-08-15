import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, Subject, of } from 'rxjs';
import { ConversationPage, ConversationSummary } from '../../core/models/chat.model';
import { ChatService } from '../../core/services/chat.service';
import { AgentMarketplaceDiscoveryService } from '../agent/agent-marketplace-discovery.service';
import { AgentMarketplaceV2Service } from '../agent/agent-marketplace-v2.service';
import { ConversationShellComponent } from './conversation-shell.component';

describe('ConversationShellComponent', () => {
  let fixture: ComponentFixture<ConversationShellComponent>;
  let component: ConversationShellComponent;
  let chatService: jasmine.SpyObj<ChatService>;
  let discoveryService: jasmine.SpyObj<AgentMarketplaceDiscoveryService>;
  let v2Service: jasmine.SpyObj<AgentMarketplaceV2Service>;
  let paramMap: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let conversationRead: Subject<string>;
  let router: Router;

  const conversationId = '01C00000000000000000000001';

  const conversation: ConversationSummary = {
    id: conversationId,
    conversationType: 'LISTING_BUYER_SELLER',
    status: 'OPEN',
    listing: {
      id: '01L00000000000000000000001',
      title: 'Used bicycle',
      sellerType: 'INDIVIDUAL',
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
    createdAt: '2026-07-05T12:00:00Z',
    updatedAt: '2026-07-05T12:01:00Z',
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
          createdAt: '2026-07-05T12:01:00Z',
        },
        unread: true,
        lastMessageAt: '2026-07-05T12:01:00Z',
        createdAt: '2026-07-05T12:00:00Z',
        updatedAt: '2026-07-05T12:01:00Z',
      },
    ],
    nextCursor: null,
  };

  beforeEach(async () => {
    conversationRead = new Subject<string>();
    chatService = jasmine.createSpyObj<ChatService>(
      'ChatService',
      ['getConversations', 'getConversation', 'getMessages', 'sendMessage', 'markRead', 'markDone', 'confirmCompletion', 'notifyConversationRead'],
    );
    discoveryService = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage', 'excludeListing', 'streamMessage', 'retryResponse'],
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
    discoveryService.createOrResumeSession.and.returnValue(of({
      id: '01D00000000000000000000001',
      sessionType: 'MARKETPLACE_DISCOVERY',
      status: 'OPEN',
      preferenceState: {
        query: null, categoryId: null, condition: null, minPrice: null,
        maxPrice: null, city: null, county: null, selectedListingId: null,
      },
      preferenceVersion: 0,
      clarificationTurnCount: 0,
      clarificationQuestionCount: 0,
      exclusions: [],
      createdAt: '2026-07-20T02:00:00Z',
      updatedAt: '2026-07-20T02:00:00Z',
    }));
    discoveryService.getMessages.and.returnValue(of({
      data: [], nextCursor: null, hasMore: false,
    }));
    Object.defineProperty(chatService, 'conversationRead$', {
      value: conversationRead.asObservable(),
    });
    chatService.getConversations.and.returnValue(of(conversationPage));
    chatService.getConversation.and.returnValue(of(conversation));
    chatService.getMessages.and.returnValue(of({
      items: [conversationPage.items[0].lastMessage!],
      nextCursor: null,
    }));
    chatService.markRead.and.returnValue(of({
      conversationId,
      lastReadMessageId: '01M00000000000000000000001',
      lastReadAt: '2026-07-05T12:02:00Z',
      unread: false,
    }));
    chatService.sendMessage.and.returnValue(of({
      id: '01M00000000000000000000002',
      conversationId,
      senderUserId: '01U00000000000000000000011',
      messageType: 'TEXT',
      body: 'Yes, I am interested.',
      moderationState: 'VISIBLE',
      currentUser: true,
      createdAt: '2026-07-05T12:03:00Z',
    }));
    chatService.markDone.and.returnValue(of({
      ...conversation.completion!,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'SELLER_MARKED_DONE',
      sellerMarkedDoneAt: '2026-07-05T12:04:00Z',
      currentUserCanMarkDone: false,
      currentUserCanConfirm: false,
    }));
    chatService.confirmCompletion.and.returnValue(of({
      ...conversation.completion!,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'BUYER_CONFIRMED',
      sellerMarkedDoneAt: '2026-07-05T12:04:00Z',
      buyerConfirmedAt: '2026-07-05T12:05:00Z',
      currentUserCanMarkDone: false,
      currentUserCanConfirm: false,
    }));
    paramMap = new BehaviorSubject(convertToParamMap({}));

    await TestBed.configureTestingModule({
      imports: [ConversationShellComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ChatService, useValue: chatService },
        { provide: AgentMarketplaceDiscoveryService, useValue: discoveryService },
        { provide: AgentMarketplaceV2Service, useValue: v2Service },
        { provide: ActivatedRoute, useValue: { paramMap } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConversationShellComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  it('loads the inbox and opens the first conversation', () => {
    fixture.detectChanges();

    expect(chatService.getConversations).toHaveBeenCalled();
    expect(chatService.getConversation).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.getMessages).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.markRead).toHaveBeenCalledOnceWith(conversationId);
    expect(chatService.notifyConversationRead).toHaveBeenCalledWith(conversationId);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Still available.');
    expect(fixture.nativeElement.querySelector('.conversation-row img')?.getAttribute('src'))
      .toBe('http://localhost:9000/api/v1/public/user-avatars/01U00000000000000000000022?v=4');
    expect(fixture.nativeElement.textContent).not.toContain('Marketplace agent');
    expect(fixture.nativeElement.querySelector('.agent-row')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Safety note: Payment and delivery are arranged directly between users. Meet in public.');
    expect(fixture.nativeElement.textContent).toContain('@alex-sells');
    expect(fixture.nativeElement.textContent).toContain('Discussing');
    expect(fixture.nativeElement.textContent).toContain('Awaiting buyer');
    expect(fixture.nativeElement.querySelector('.listing-header a')?.getAttribute('href'))
      .toBe('/listings/01L00000000000000000000001');
    expect(component.conversations()[0].unread).toBeFalse();
  });

  it('clears unread state when the inbox auto-opens a conversation before mark-read returns', () => {
    const markReadResult = new Subject<{
      conversationId: string;
      lastReadMessageId: string;
      lastReadAt: string;
      unread: boolean;
    }>();
    chatService.markRead.and.returnValue(markReadResult.asObservable());

    fixture.detectChanges();

    expect(chatService.markRead).toHaveBeenCalledOnceWith(conversationId);
    expect(component.conversations()[0].unread).toBeFalse();
    expect(fixture.nativeElement.querySelector('.unread-dot')).toBeNull();

    markReadResult.next({
      conversationId,
      lastReadMessageId: '01M00000000000000000000001',
      lastReadAt: '2026-07-05T12:02:00Z',
      unread: false,
    });
    markReadResult.complete();
  });

  it('shows an empty inbox state', () => {
    chatService.getConversations.and.returnValue(of({ items: [], nextCursor: null }));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No conversations yet.');
  });

  it('opens discovery assistant in the messages chat without using ChatService as an agent client', () => {
    Object.defineProperty(component, 'aiAssistantEnabled', { value: true });
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();

    expect(router.navigate).not.toHaveBeenCalledWith(['/account/messages/agent']);
    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('How can I help?');
    expect(fixture.nativeElement.textContent).toContain('Message the marketplace assistant');
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(chatService.getConversation).not.toHaveBeenCalled();
    expect(chatService.getMessages).not.toHaveBeenCalled();
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledWith(false);
    expect(discoveryService.sendMessage).not.toHaveBeenCalled();
  });

  it('uses the same inline discovery assistant when discovery alone is enabled', () => {
    Object.defineProperty(component, 'aiDiscoveryEnabled', { value: true });
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();

    const row = fixture.nativeElement.querySelector('.agent-row') as HTMLButtonElement;
    expect(row).not.toBeNull();
    expect(row.textContent).toContain('Marketplace assistant');
    row.click();
    fixture.detectChanges();

    expect(router.navigate).not.toHaveBeenCalledWith(['/account/messages/agent']);
    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(chatService.getConversation).not.toHaveBeenCalled();
    expect(chatService.getMessages).not.toHaveBeenCalled();
  });

  it('cuts the Marketplace assistant pane over to V2 when its build flag is enabled', async () => {
    Object.defineProperty(component, 'agentV2Enabled', { value: true });
    Object.defineProperty(component, 'aiAssistantEnabled', { value: true });
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-agent-marketplace-v2-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Marketplace assistant');
    expect(fixture.nativeElement.textContent).not.toContain('Development evaluation');
    expect(fixture.nativeElement.textContent).not.toContain('Latest safe evaluation evidence');
    expect(v2Service.createSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.createOrResumeSession).not.toHaveBeenCalled();
  });

  it('treats the reserved agent route parameter as inline discovery, not a conversation id', () => {
    Object.defineProperty(component, 'aiAssistantEnabled', { value: true });
    paramMap.next(convertToParamMap({ conversationId: 'agent' }));

    fixture.detectChanges();

    expect(component.agentSelected()).toBeTrue();
    expect(fixture.nativeElement.querySelector('app-agent-marketplace-discovery')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('How can I help?');
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(chatService.getConversation).not.toHaveBeenCalledWith('agent');
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledWith(false);
  });

  it('opens a direct conversation route', () => {
    paramMap.next(convertToParamMap({ conversationId }));

    fixture.detectChanges();

    expect(component.selectedConversationId()).toBe(conversationId);
    expect(chatService.getConversation).toHaveBeenCalledWith(conversationId);
  });

  it('sends a message in the selected thread', () => {
    fixture.detectChanges();
    component.draft.set('Yes, I am interested.');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.composer button').click();
    fixture.detectChanges();

    expect(chatService.sendMessage).toHaveBeenCalledOnceWith(conversationId, 'Yes, I am interested.');
    expect(component.messages().some(message => message.body === 'Yes, I am interested.')).toBeTrue();
    expect(component.conversations()[0].unread).toBeFalse();
  });

  it('lets the buyer confirm a seller-marked completion', () => {
    chatService.getConversation.and.returnValue(of({
      ...conversation,
      completion: {
        ...conversation.completion!,
        id: '01T00000000000000000000001',
        status: 'SELLER_MARKED_DONE',
        sellerMarkedDoneAt: '2026-07-05T12:04:00Z',
        currentUserCanConfirm: true,
      },
    }));

    fixture.detectChanges();
    fixture.nativeElement.querySelector('.completion-status button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Confirm this handoff');
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Alex Seller');
    expect(fixture.nativeElement.textContent).toContain('does not verify or protect that payment');

    fixture.nativeElement.querySelector('.confirmation-review .review-actions button:last-child').click();
    fixture.detectChanges();
    expect(chatService.confirmCompletion).toHaveBeenCalledOnceWith(conversationId);
    expect(component.selectedConversation()?.completion?.status).toBe('BUYER_CONFIRMED');
    expect(fixture.nativeElement.querySelector('.composer')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('This conversation is now read-only.');
    expect(fixture.nativeElement.querySelector('.completed-footer a')).toBeNull();
  });

  it('lets a seller mark the selected conversation done with quantity', () => {
    chatService.getConversation.and.returnValue(of({
      ...conversation,
      completion: {
        ...conversation.completion!,
        currentUserCanMarkDone: true,
      },
    }));

    fixture.detectChanges();
    component.completionQuantity.set(2);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.trade-card button').click();
    fixture.detectChanges();

    expect(chatService.markDone).toHaveBeenCalledOnceWith(conversationId, 2);
    expect(component.selectedConversation()?.completion?.status).toBe('SELLER_MARKED_DONE');
  });
});
