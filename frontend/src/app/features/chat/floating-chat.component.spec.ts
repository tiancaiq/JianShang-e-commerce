import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ConversationPage, ConversationSummary } from '../../core/models/chat.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { FloatingChatComponent } from './floating-chat.component';

describe('FloatingChatComponent', () => {
  let fixture: ComponentFixture<FloatingChatComponent>;
  let component: FloatingChatComponent;
  let authenticated = true;
  let chatService: jasmine.SpyObj<ChatService>;

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
    chatService = jasmine.createSpyObj<ChatService>(
      'ChatService',
      ['getConversations', 'getConversation', 'getMessages', 'sendMessage', 'markRead', 'markDone', 'confirmCompletion'],
    );
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

    await TestBed.configureTestingModule({
      imports: [FloatingChatComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ChatService, useValue: chatService },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
          },
        },
      ],
    }).compileComponents();
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

  it('opens recent conversations from the launcher', () => {
    authenticated = true;
    createComponent();

    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.chat-panel')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Marketplace agent');
    expect(fixture.nativeElement.textContent).toContain('Coming soon');
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Still available.');
    expect(fixture.nativeElement.querySelector('.conversation-row:not(.agent-row) img')?.getAttribute('src'))
      .toBe('http://localhost:9000/api/v1/public/user-avatars/01U00000000000000000000022?v=4');
  });

  it('keeps the agent option visible when there are no buyer/seller chats', () => {
    authenticated = true;
    chatService.getConversations.and.returnValue(of({ items: [], nextCursor: null }));
    createComponent();

    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Marketplace agent');
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

  it('shows an agent placeholder without loading a conversation', () => {
    authenticated = true;
    createComponent();
    fixture.nativeElement.querySelector('.chat-launcher').click();
    fixture.detectChanges();
    chatService.getConversation.calls.reset();
    chatService.getMessages.calls.reset();

    fixture.nativeElement.querySelector('.agent-row').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Marketplace agent is coming soon');
    expect(fixture.nativeElement.querySelector('.composer')).toBeNull();
    expect(chatService.getConversation).not.toHaveBeenCalled();
    expect(chatService.getMessages).not.toHaveBeenCalled();
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
    expect(fixture.nativeElement.querySelector('.thread-listing a')?.getAttribute('href'))
      .toBe('/listings/01L00000000000000000000001');
    expect(fixture.nativeElement.textContent).toContain('Safety note: Payment and delivery are arranged directly between users. Meet in public.');
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
});
