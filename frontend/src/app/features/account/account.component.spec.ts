import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ConversationListItem } from '../../core/models/chat.model';
import { IndividualSellerProfile } from '../../core/models/individual-seller.model';
import { ListingDraft } from '../../core/models/listing.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { ListingService } from '../../core/services/listing.service';
import { UserProfileService } from '../../core/services/user-profile.service';
import { AccountComponent } from './account.component';

describe('AccountComponent', () => {
  let fixture: ComponentFixture<AccountComponent>;
  let authService: jasmine.SpyObj<AuthService>;
  let listingService: jasmine.SpyObj<ListingService>;
  let chatService: jasmine.SpyObj<ChatService>;
  let individualSellerService: jasmine.SpyObj<IndividualSellerService>;
  let userProfileService: jasmine.SpyObj<UserProfileService>;

  const sellerProfile: IndividualSellerProfile = {
    id: 'SELLER1',
    userId: '01USER',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    completedSalesCount: 0,
    termsVersion: '2026-01',
    version: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  const activeListing = (id: string, title: string, condition: ListingDraft['condition'], priceAmount: number): ListingDraft => ({
    id,
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: '01USER',
    businessId: null,
    storeId: null,
    categoryId: 'CAT1',
    title,
    description: 'A real listing from the current account.',
    condition,
    conditionNotes: null,
    priceAmount,
    currency: 'USD',
    negotiable: true,
    sku: null,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    moderationStatus: 'APPROVED',
    publicationSource: 'ADMIN_REVIEW',
    publishedAt: '2026-01-02T00:00:00Z',
    version: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  });

  const conversation = (id: string, displayName: string, body: string, unread: boolean): ConversationListItem => ({
    id,
    conversationType: 'LISTING_BUYER_SELLER',
    status: 'OPEN',
    listing: {
      id: 'LISTING1',
      title: 'Current account listing',
      sellerType: 'INDIVIDUAL',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      thumbnailUrl: null,
      transactionNotice: 'Payment and delivery are arranged directly between users.',
    },
    otherParticipant: {
      participantId: `${id}-USER`,
      displayName,
      avatarUrl: null,
      initials: displayName.slice(0, 2).toUpperCase(),
      roleInConversation: 'BUYER',
      currentUser: false,
    },
    lastMessage: {
      id: `${id}-MESSAGE`,
      conversationId: id,
      senderUserId: `${id}-USER`,
      messageType: 'TEXT',
      body,
      moderationState: 'VISIBLE',
      currentUser: false,
      createdAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    },
    unread,
    lastMessageAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
  });

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['user', 'logout']);
    authService.user.and.returnValue({
      id: '01USER',
      keycloakSub: 'keycloak-sub',
      email: 'alex@example.com',
      emailVerified: true,
      displayName: 'Alex Buyer',
      phone: null,
      phoneVerified: false,
      avatarUrl: null,
      status: 'ACTIVE',
      version: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getMyListings', 'mediaUrl']);
    listingService.getMyListings.and.returnValue(of([
      activeListing('LISTING1', 'Current account listing', 'NEW', 45),
      activeListing('LISTING2', 'Second account listing', 'LIKE_NEW', 38),
    ]));
    listingService.mediaUrl.and.callFake(url => url || '');
    chatService = jasmine.createSpyObj<ChatService>('ChatService', ['getConversations']);
    chatService.getConversations.and.returnValue(of({
      items: [
        conversation('CONV1', 'Recent Buyer', "Hi! I'm interested in your listing.", true),
        conversation('CONV2', 'Returning Buyer', 'Thanks again! Let me know when you ship it out.', false),
      ],
      nextCursor: null,
    }));
    individualSellerService = jasmine.createSpyObj<IndividualSellerService>('IndividualSellerService', ['getMe']);
    individualSellerService.getMe.and.returnValue(of(sellerProfile));
    userProfileService = jasmine.createSpyObj<UserProfileService>('UserProfileService', ['getMe']);
    userProfileService.getMe.and.returnValue(of({
      id: '01USER',
      keycloakSub: 'keycloak-sub',
      email: 'alex@example.com',
      emailVerified: true,
      displayName: 'Alex Buyer',
      phone: null,
      phoneVerified: false,
      avatarUrl: '/api/v1/public/user-avatars/01USER?v=4',
      status: 'ACTIVE',
      version: 4,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }));

    await TestBed.configureTestingModule({
      imports: [AccountComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: ListingService, useValue: listingService },
        { provide: ChatService, useValue: chatService },
        { provide: IndividualSellerService, useValue: individualSellerService },
        { provide: UserProfileService, useValue: userProfileService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccountComponent);
    fixture.detectChanges();
  });

  it('renders the marketplace account dashboard shortcuts', () => {
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => ({
      text: link.textContent?.trim(),
      href: link.getAttribute('href'),
    }));

    expect(text).toContain('Welcome, Alex Buyer');
    expect(text).toContain('Seller profile active');
    expect(text).toContain('2 active listings');
    expect(text).toContain('1 unread messages');
    expect(text).toContain('Active Listings');
    expect(text).toContain('Current account listing');
    expect(text).toContain('$45');
    expect(text).toContain('Recent Buyer');
    expect(text).toContain('Trade Overview');
    expect(text).toContain('Marketplace seller');
    expect(text).toContain('Rating');
    expect(text).not.toContain('Addresses');
    expect(text).toContain('Liked Listings');
    expect(links).toContain(jasmine.objectContaining({ href: '/account/profile' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/seller-profile' }));
    expect(links).not.toContain(jasmine.objectContaining({ href: '/account/addresses' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/liked' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/messages' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/listings' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/listings/new' }));
    expect(host.querySelector('.avatar-ring img')?.getAttribute('src')).toBe('/api/v1/public/user-avatars/01USER?v=4');
    expect(userProfileService.getMe).toHaveBeenCalled();
    expect(listingService.getMyListings).toHaveBeenCalled();
    expect(chatService.getConversations).toHaveBeenCalledWith(null, 20);
    expect(individualSellerService.getMe).toHaveBeenCalled();
  });

  it('keeps V2 account areas out of the marketplace dashboard', () => {
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href') || '');

    expect(text).toContain('Messages');
    expect(links.some(href => href.includes('notifications'))).toBeFalse();
    expect(links.some(href => href.includes('orders'))).toBeFalse();
    expect(links.some(href => href.includes('cart'))).toBeFalse();
    expect(links.some(href => href.includes('addresses'))).toBeFalse();
    expect(links.some(href => href.includes('reviews'))).toBeFalse();
  });
});
