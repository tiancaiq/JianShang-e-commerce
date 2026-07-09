import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { ConversationPage, ConversationSummary } from '../../core/models/chat.model';
import { ChatService } from '../../core/services/chat.service';
import { ConversationShellComponent } from './conversation-shell.component';

describe('ConversationShellComponent', () => {
  let fixture: ComponentFixture<ConversationShellComponent>;
  let component: ConversationShellComponent;
  let chatService: jasmine.SpyObj<ChatService>;
  let paramMap: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
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
    chatService = jasmine.createSpyObj<ChatService>(
      'ChatService',
      ['getConversations', 'getConversation', 'getMessages', 'sendMessage', 'markRead', 'markDone', 'confirmCompletion'],
    );
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
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Still available.');
    expect(fixture.nativeElement.querySelector('.conversation-row img')?.getAttribute('src'))
      .toBe('http://localhost:9000/api/v1/public/user-avatars/01U00000000000000000000022?v=4');
    expect(fixture.nativeElement.textContent).toContain('Marketplace agent');
    expect(fixture.nativeElement.textContent).toContain('Coming soon');
    expect(fixture.nativeElement.textContent).toContain('Safety note: Payment and delivery are arranged directly between users. Meet in public.');
    expect(fixture.nativeElement.querySelector('.listing-header a')?.getAttribute('href'))
      .toBe('/listings/01L00000000000000000000001');
    expect(component.conversations()[0].unread).toBeFalse();
  });

  it('shows an empty inbox state', () => {
    chatService.getConversations.and.returnValue(of({ items: [], nextCursor: null }));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No conversations yet.');
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

    expect(chatService.confirmCompletion).toHaveBeenCalledOnceWith(conversationId);
    expect(component.selectedConversation()?.completion?.status).toBe('BUYER_CONFIRMED');
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
