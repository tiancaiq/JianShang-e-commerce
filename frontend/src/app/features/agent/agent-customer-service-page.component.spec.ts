import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import {
  AGENT_CUSTOMER_SERVICE_ENABLED,
  AGENT_DISCOVERY_ENABLED,
} from './agent-customer-service.capability';
import { AgentMarketplaceDiscoveryService } from './agent-marketplace-discovery.service';
import { AgentCustomerService } from './agent-customer-service.service';
import { AgentCustomerServicePageComponent } from './agent-customer-service-page.component';

describe('AgentCustomerServicePageComponent', () => {
  let fixture: ComponentFixture<AgentCustomerServicePageComponent>;
  let agentService: jasmine.SpyObj<AgentCustomerService>;
  let discoveryService: jasmine.SpyObj<AgentMarketplaceDiscoveryService>;
  let navigate: jasmine.Spy;

  const listingId = '01L00000000000000000000001';

  beforeEach(async () => {
    agentService = jasmine.createSpyObj<AgentCustomerService>(
      'AgentCustomerService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage'],
    );
    discoveryService = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage', 'streamMessage', 'retryResponse'],
    );
    discoveryService.createOrResumeSession.and.returnValue(of(discoverySession()));
    discoveryService.getMessages.and.returnValue(of({ data: [], nextCursor: null, hasMore: false }));
    agentService.createOrResumeSession.and.returnValue(of({
      id: '01A00000000000000000000001',
      sessionType: 'LISTING_CUSTOMER_SERVICE',
      status: 'OPEN',
      subjectListing: {
        id: listingId,
        version: '1',
        title: 'Used bicycle',
        thumbnailUrl: null,
        transactionNotice: 'Arrange payment and delivery directly.',
      },
      createdAt: '2026-07-20T02:00:00Z',
      updatedAt: '2026-07-20T02:00:00Z',
    }));
    agentService.getMessages.and.returnValue(of({ data: [], nextCursor: null, hasMore: false }));
    const listingService = jasmine.createSpyObj<ListingService>(
      'ListingService',
      ['searchMarketplaceListings', 'mediaUrl'],
    );
    listingService.searchMarketplaceListings.and.returnValue(of({
      data: [],
      page: { nextCursor: null, hasMore: false },
    }));
    const chatService = jasmine.createSpyObj<ChatService>('ChatService', ['startListingConversation']);
    const authService = jasmine.createSpyObj<AuthService>('AuthService', ['login']);

    await TestBed.configureTestingModule({
      imports: [AgentCustomerServicePageComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: true },
        { provide: AgentCustomerService, useValue: agentService },
        { provide: AgentMarketplaceDiscoveryService, useValue: discoveryService },
        { provide: AuthService, useValue: authService },
        { provide: ListingService, useValue: listingService },
        { provide: ChatService, useValue: chatService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: () => null },
              queryParamMap: {
                get: (name: string) => name === 'listingId'
                  ? listingId.toLowerCase()
                  : '  Used\u0000\n bicycle  ',
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentCustomerServicePageComponent);
    navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
  });

  it('defaults to the discovery assistant without requiring the listing route context', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1')?.textContent)
      .toContain('Marketplace assistant');
    expect(fixture.nativeElement.querySelector(
      'app-agent-marketplace-discovery',
    )).not.toBeNull();
    expect(fixture.nativeElement.querySelector(
      'app-agent-customer-service-thread',
    )).toBeNull();
    expect(fixture.nativeElement.querySelector('#discovery-prompt')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('How can I help?');
    expect(fixture.nativeElement.textContent).not.toContain('Choose a listing');
    expect(fixture.nativeElement.textContent).not.toContain('\u0000');
    expect(agentService.createOrResumeSession).not.toHaveBeenCalled();
    expect(agentService.getMessages).not.toHaveBeenCalled();
    expect(agentService.sendMessage).not.toHaveBeenCalled();
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.getMessages).toHaveBeenCalledOnceWith(
      discoverySession().id,
      null,
      50,
    );
    expect(discoveryService.sendMessage).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledOnceWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: { listingId: null, title: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });
});

describe('AgentCustomerServicePageComponent discovery landing', () => {
  it('uses the existing Agent destination and loads history without sending', async () => {
    const discoveryService = jasmine.createSpyObj<AgentMarketplaceDiscoveryService>(
      'AgentMarketplaceDiscoveryService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage', 'streamMessage', 'retryResponse'],
    );
    discoveryService.createOrResumeSession.and.returnValue(of(discoverySession()));
    discoveryService.getMessages.and.returnValue(of({ data: [], nextCursor: null, hasMore: false }));

    await TestBed.configureTestingModule({
      imports: [AgentCustomerServicePageComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AGENT_DISCOVERY_ENABLED, useValue: true },
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: true },
        { provide: AgentMarketplaceDiscoveryService, useValue: discoveryService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: () => null },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    const discoveryFixture = TestBed.createComponent(AgentCustomerServicePageComponent);
    discoveryFixture.detectChanges();

    expect(discoveryFixture.nativeElement.querySelector('h1')?.textContent)
      .toContain('Marketplace assistant');
    expect(discoveryFixture.nativeElement.querySelector(
      'app-agent-marketplace-discovery',
    )).not.toBeNull();
    expect(discoveryFixture.nativeElement.querySelector(
      'app-agent-customer-service-thread',
    )).toBeNull();
    expect(discoveryFixture.nativeElement.querySelector('.listing-picker')).toBeNull();
    expect(discoveryFixture.nativeElement.querySelector('.listing-grid')).toBeNull();
    expect(discoveryFixture.nativeElement.textContent).not.toContain('Already viewing an item?');
    expect(discoveryFixture.nativeElement.textContent).not.toContain('Ask about a listing');
    expect(discoveryService.createOrResumeSession).toHaveBeenCalledOnceWith(false);
    expect(discoveryService.getSession).not.toHaveBeenCalled();
    expect(discoveryService.getMessages).toHaveBeenCalledOnceWith(
      discoverySession().id,
      null,
      50,
    );
    expect(discoveryService.sendMessage).not.toHaveBeenCalled();
  });
});

function discoverySession() {
  return {
    id: '01A00000000000000000000002',
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
}
