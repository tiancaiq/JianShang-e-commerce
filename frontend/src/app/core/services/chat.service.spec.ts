import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { ConversationPage, ConversationSummary } from '../models/chat.model';
import { ChatService } from './chat.service';

describe('ChatService', () => {
  let service: ChatService;
  let httpMock: HttpTestingController;
  let originalGatewayUrl: string;

  const conversationId = '01C00000000000000000000001';
  const listingId = '01L00000000000000000000001';
  const sellerId = '01U00000000000000000000022';

  const conversation: ConversationSummary = {
    id: conversationId,
    conversationType: 'LISTING_BUYER_SELLER',
    status: 'OPEN',
    listing: {
      id: listingId,
      title: 'Used bicycle',
      sellerType: 'INDIVIDUAL',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      thumbnailUrl: '/api/v1/public/listing-media/01I00000000000000000000001',
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
        participantId: sellerId,
        displayName: 'Alex Seller',
        avatarUrl: `/api/v1/public/user-avatars/${sellerId}?v=4`,
        initials: 'AS',
        roleInConversation: 'SELLER',
        currentUser: false,
      },
    ],
    completion: {
      id: null,
      listingId,
      conversationId,
      sellerUserId: sellerId,
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

  beforeEach(() => {
    originalGatewayUrl = environment.apiGatewayUrl;
    environment.apiGatewayUrl = 'http://localhost:9000';

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        ChatService,
      ],
    });
    service = TestBed.inject(ChatService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    environment.apiGatewayUrl = originalGatewayUrl;
  });

  it('normalizes relative listing and avatar URLs on conversation detail', () => {
    let result: ConversationSummary | undefined;

    service.getConversation(conversationId).subscribe(response => {
      result = response;
    });

    const request = httpMock.expectOne(`http://localhost:9000/api/v1/conversations/${conversationId}`);
    expect(request.request.withCredentials).toBeTrue();
    request.flush(conversation);

    expect(result?.listing.thumbnailUrl).toBe('http://localhost:9000/api/v1/public/listing-media/01I00000000000000000000001');
    expect(result?.participants[1].avatarUrl).toBe(`http://localhost:9000/api/v1/public/user-avatars/${sellerId}?v=4`);
  });

  it('normalizes relative listing and avatar URLs on conversation list items', () => {
    let result: ConversationPage | undefined;

    service.getConversations(null, 20).subscribe(response => {
      result = response;
    });

    const request = httpMock.expectOne('http://localhost:9000/api/v1/conversations?limit=20');
    request.flush({
      items: [{
        id: conversationId,
        conversationType: 'LISTING_BUYER_SELLER',
        status: 'OPEN',
        listing: conversation.listing,
        otherParticipant: conversation.participants[1],
        lastMessage: null,
        unread: false,
        lastMessageAt: null,
        createdAt: conversation.createdAt,
        updatedAt: conversation.updatedAt,
      }],
      nextCursor: null,
    });

    expect(result?.items[0].listing.thumbnailUrl).toBe('http://localhost:9000/api/v1/public/listing-media/01I00000000000000000000001');
    expect(result?.items[0].otherParticipant.avatarUrl).toBe(`http://localhost:9000/api/v1/public/user-avatars/${sellerId}?v=4`);
  });

  it('posts seller mark-done requests', () => {
    service.markDone(conversationId, 2).subscribe();

    const request = httpMock.expectOne(`http://localhost:9000/api/v1/conversations/${conversationId}/completion/mark-done`);
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.body).toEqual({ quantitySold: 2 });
    request.flush({
      ...conversation.completion,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'SELLER_MARKED_DONE',
      sellerMarkedDoneAt: '2026-07-06T12:04:00Z',
    });
  });

  it('posts buyer completion confirmations', () => {
    service.confirmCompletion(conversationId).subscribe();

    const request = httpMock.expectOne(`http://localhost:9000/api/v1/conversations/${conversationId}/completion/confirm`);
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    request.flush({
      ...conversation.completion,
      id: '01T00000000000000000000001',
      quantitySold: 2,
      status: 'BUYER_CONFIRMED',
      sellerMarkedDoneAt: '2026-07-06T12:04:00Z',
      buyerConfirmedAt: '2026-07-06T12:05:00Z',
    });
  });
});
