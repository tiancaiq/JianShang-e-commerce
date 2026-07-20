import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import { publicListing, publicListingImage } from '../../testing/listing-test-fixtures';
import { PublicListingDetailComponent } from './public-listing-detail.component';

describe('PublicListingDetailComponent', () => {
  let fixture: ComponentFixture<PublicListingDetailComponent>;
  let component: PublicListingDetailComponent;
  let listingService: jasmine.SpyObj<ListingService>;
  let chatService: jasmine.SpyObj<ChatService>;
  let authService: { isAuthenticated: jasmine.Spy; login: jasmine.Spy };
  let router: Router;

  const listing = publicListing({
    sellerAvatarUrl: 'https://example.com/alex.png',
    visitCount: 4,
    likeCount: 2,
    images: [
      publicListingImage(),
      publicListingImage({
        id: '01I00000000000000000000002',
        displayOrder: 1,
        url: '/api/v1/public/listing-media/01I00000000000000000000002',
      }),
    ],
  });

  beforeEach(async () => {
    document.body.classList.remove('listing-chat-dialog-open');
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'getPublicListing',
      'mediaUrl',
      'recordListingVisit',
      'likeListing',
      'unlikeListing',
      'getMyListingEngagement',
    ]);
    listingService.getPublicListing.and.returnValue(of(listing));
    listingService.mediaUrl.and.callFake(url => url || '');
    listingService.recordListingVisit.and.returnValue(of({
      listingId: listing.id,
      visitCount: 5,
      likeCount: 2,
      visitedByMe: true,
      likedByMe: false,
    }));
    listingService.likeListing.and.returnValue(of({
      listingId: listing.id,
      visitCount: 5,
      likeCount: 3,
      visitedByMe: true,
      likedByMe: true,
    }));
    listingService.unlikeListing.and.returnValue(of({
      listingId: listing.id,
      visitCount: 5,
      likeCount: 2,
      visitedByMe: true,
      likedByMe: false,
    }));
    listingService.getMyListingEngagement.and.returnValue(of({
      listingId: listing.id,
      visitCount: 5,
      likeCount: 2,
      visitedByMe: true,
      likedByMe: false,
    }));
    chatService = jasmine.createSpyObj<ChatService>('ChatService', ['startListingConversation', 'getMessages', 'sendMessage']);
    chatService.startListingConversation.and.returnValue(of({
      id: '01C00000000000000000000001',
      conversationType: 'LISTING_BUYER_SELLER',
      status: 'OPEN',
      listing: {
        id: listing.id,
        title: listing.title,
        sellerType: 'INDIVIDUAL',
        publicCity: 'Irvine',
        publicRegion: 'CA',
        thumbnailUrl: null,
        transactionNotice: listing.transactionNotice,
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
          avatarUrl: null,
          initials: 'AS',
          roleInConversation: 'SELLER',
          currentUser: false,
        },
      ],
      completion: {
        id: null,
        listingId: listing.id,
        conversationId: '01C00000000000000000000001',
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
      createdAt: '2026-07-04T12:00:00Z',
      updatedAt: '2026-07-04T12:00:00Z',
    }));
    chatService.getMessages.and.returnValue(of({ items: [], nextCursor: null }));
    chatService.sendMessage.and.returnValue(of({
      id: '01M00000000000000000000001',
      conversationId: '01C00000000000000000000001',
      senderUserId: '01U00000000000000000000011',
      messageType: 'TEXT',
      body: 'Hello seller',
      moderationState: 'VISIBLE',
      currentUser: true,
      createdAt: '2026-07-04T12:01:00Z',
    }));
    authService = jasmine.createSpyObj('AuthService', ['isAuthenticated', 'login']);
    authService.isAuthenticated.and.returnValue(true);
    await TestBed.configureTestingModule({
      imports: [PublicListingDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ListingService, useValue: listingService },
        { provide: ChatService, useValue: chatService },
        { provide: AuthService, useValue: authService },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => listing.id } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PublicListingDetailComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    document.body.classList.remove('listing-chat-dialog-open');
  });

  it('loads public listing details', () => {
    fixture.detectChanges();

    expect(listingService.getPublicListing).toHaveBeenCalledOnceWith(listing.id);
    expect(component.listing()).toEqual(listing);
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).toContain('Individual seller');
    expect(fixture.nativeElement.textContent).toContain('Marketplace seller');
    expect(fixture.nativeElement.textContent).toContain('@alexseller');
    expect(fixture.nativeElement.textContent).not.toContain('Sell an Item');
    expect(fixture.nativeElement.textContent).toContain('Payment and delivery are arranged directly');
    expect(fixture.nativeElement.textContent).toContain('Message seller');
    expect(fixture.nativeElement.querySelector('.agent-help-launch')).toBeNull();
    expect(fixture.nativeElement.querySelector('.trade-controls')).toBeNull();
    expect(fixture.nativeElement.querySelector('.purchase-panel select')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('👁 5 views');
    expect(fixture.nativeElement.textContent).toContain('♡ 2 likes');
    expect(fixture.nativeElement.querySelectorAll('.like-button').length).toBe(1);
    expect(listingService.recordListingVisit).toHaveBeenCalledOnceWith(listing.id);
    expect(fixture.nativeElement.querySelector('.seller-profile img')?.getAttribute('src')).toBe('https://example.com/alex.png');
    expect(fixture.nativeElement.querySelector('app-listing-image-gallery img')?.getAttribute('src')).toBe('/api/v1/public/listing-media/01I00000000000000000000001');
  });

  it('toggles the signed-in user like state on the listing detail page', () => {
    fixture.detectChanges();
    listingService.recordListingVisit.calls.reset();

    fixture.nativeElement.querySelector('.engagement-row button').click();
    fixture.detectChanges();

    expect(listingService.likeListing).toHaveBeenCalledOnceWith(listing.id);
    expect(fixture.nativeElement.textContent).toContain('♡ 3 likes');
    expect(fixture.nativeElement.querySelector('.like-button')?.classList.contains('liked')).toBeTrue();

    fixture.nativeElement.querySelector('.engagement-row button').click();
    fixture.detectChanges();

    expect(listingService.unlikeListing).toHaveBeenCalledOnceWith(listing.id);
    expect(fixture.nativeElement.textContent).toContain('♡ 2 likes');
    expect(fixture.nativeElement.querySelector('.like-button')?.classList.contains('liked')).toBeFalse();
  });

  it('sends guests to sign in before liking a listing', () => {
    authService.isAuthenticated.and.returnValue(false);
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.engagement-row button').click();

    expect(authService.login).toHaveBeenCalledOnceWith('marketplace', router.url);
    expect(listingService.likeListing).not.toHaveBeenCalled();
    expect(listingService.recordListingVisit).not.toHaveBeenCalled();
  });

  it('opens a listing chat drawer for signed-in users', () => {
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.message-button').click();
    fixture.detectChanges();

    expect(chatService.startListingConversation).toHaveBeenCalledOnceWith(listing.id);
    expect(chatService.getMessages).toHaveBeenCalledOnceWith('01C00000000000000000000001');
    expect(fixture.nativeElement.querySelector('.chat-drawer')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Alex Seller');
    expect(fixture.nativeElement.textContent).toContain('No messages yet.');
    expect(document.body.classList.contains('listing-chat-dialog-open')).toBeTrue();
  });

  it('sends text from the listing chat drawer and clears the composer', async () => {
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.message-button').click();
    fixture.detectChanges();

    const textarea = fixture.nativeElement.querySelector('.chat-composer textarea') as HTMLTextAreaElement;
    textarea.value = 'Hello seller';
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.chat-compose-actions button').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(chatService.sendMessage).toHaveBeenCalledOnceWith('01C00000000000000000000001', 'Hello seller');
    expect(fixture.nativeElement.textContent).toContain('Hello seller');
    expect(component.messageDraft()).toBe('');
    expect((fixture.nativeElement.querySelector('.chat-composer textarea') as HTMLTextAreaElement).value).toBe('');
  });

  it('keeps the listing chat drawer above the floating chat launcher layer', () => {
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.message-button').click();
    fixture.detectChanges();

    const backdrop = fixture.nativeElement.querySelector('.chat-backdrop') as HTMLElement;
    const drawer = fixture.nativeElement.querySelector('.chat-drawer') as HTMLElement;

    expect(Number(getComputedStyle(backdrop).zIndex)).toBeGreaterThan(80);
    expect(Number(getComputedStyle(drawer).zIndex)).toBeGreaterThan(Number(getComputedStyle(backdrop).zIndex));
  });

  it('sends guests to sign in before messaging a seller', () => {
    authService.isAuthenticated.and.returnValue(false);
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.message-button').click();

    expect(authService.login).toHaveBeenCalledOnceWith('marketplace', router.url);
    expect(chatService.startListingConversation).not.toHaveBeenCalled();
  });

  it('launches enabled authenticated AI help with only display-safe public listing context', () => {
    const contextualListing = publicListing({
      id: listing.id,
      sellerType: 'INDIVIDUAL',
      title: '  Used\u0000\n bicycle  ',
      description: 'Private-looking description must not be routed',
      sellerDisplayName: 'Seller must not be routed',
    });
    listingService.getPublicListing.and.returnValue(of(contextualListing));
    Object.defineProperty(component, 'aiAssistantEnabled', { value: true });
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);

    fixture.detectChanges();

    const launch = fixture.nativeElement.querySelector('.agent-help-launch button') as HTMLButtonElement;
    expect(launch).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Nothing is sent to the seller');
    launch.click();

    expect(navigate).toHaveBeenCalledOnceWith(['/account/messages/agent'], {
      queryParams: {
        listingId: contextualListing.id,
        title: 'Used bicycle',
      },
    });
    const queryParams = navigate.calls.mostRecent().args[1]?.queryParams as Record<string, string>;
    expect(Object.keys(queryParams).sort()).toEqual(['listingId', 'title']);
    expect(JSON.stringify(queryParams)).not.toContain(contextualListing.description);
    expect(JSON.stringify(queryParams)).not.toContain(contextualListing.sellerDisplayName as string);
    expect(chatService.startListingConversation).not.toHaveBeenCalled();
  });

  it('keeps enabled AI listing help absent for guests', () => {
    Object.defineProperty(component, 'aiAssistantEnabled', { value: true });
    authService.isAuthenticated.and.returnValue(false);

    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.agent-help-launch')).toBeNull();
    expect(chatService.startListingConversation).not.toHaveBeenCalled();
  });

  it('hides the message action when backend rejects owner messaging', () => {
    chatService.startListingConversation.and.returnValue(throwError(() => ({
      status: 409,
      error: { error: { code: 'CHAT_SELF_CONVERSATION_NOT_ALLOWED' } },
    })));
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.message-button').click();
    fixture.detectChanges();

    expect(component.selfChatBlocked()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.message-button')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('This is your listing.');
  });

  it('shows unavailable state when public listing cannot be loaded', () => {
    listingService.getPublicListing.and.returnValue(throwError(() => ({ status: 404 })));

    fixture.detectChanges();

    expect(component.errorMsg()).toBe('This listing is not available.');
    expect(fixture.nativeElement.textContent).toContain('Listing unavailable');
  });

  it('shows business store identity and informational catalog quantity without cart actions', () => {
    listingService.getPublicListing.and.returnValue(of(publicListing({
      sellerType: 'BUSINESS',
      sellerDisplayName: 'Mochi Store',
      storeId: '01S00000000000000000000001',
      storeSlug: 'mochi-store',
      storeName: 'Mochi Store',
      businessVerified: true,
      title: 'Store plush',
      quantity: 13,
      transactionNotice: null,
    })));

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Mochi Store');
    expect(text).toContain('Verified business');
    expect(text).toContain('Quantity listed: 13');
    expect(text).toContain('Quantity shown is catalog information only.');
    expect(text).toContain('Open public store profile');
    expect(text).not.toContain('Add to cart');
    expect(text).not.toContain('13 available');
    expect(text).not.toContain('Message seller');
    expect(fixture.nativeElement.querySelector('.agent-help-launch')).toBeNull();
    expect(text).not.toContain('checkout');
    expect(text).not.toContain('payment');
    expect(text).not.toContain('shipping');
    expect(text).not.toContain('order');

    const storeLink = fixture.nativeElement.querySelector('a[href="/stores/mochi-store"]');
    expect(storeLink).not.toBeNull();
  });
});
