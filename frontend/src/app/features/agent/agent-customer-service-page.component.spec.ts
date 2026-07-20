import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import { AGENT_CUSTOMER_SERVICE_ENABLED } from './agent-customer-service.capability';
import { AgentCustomerService } from './agent-customer-service.service';
import { AgentCustomerServicePageComponent } from './agent-customer-service-page.component';

describe('AgentCustomerServicePageComponent', () => {
  let fixture: ComponentFixture<AgentCustomerServicePageComponent>;
  let agentService: jasmine.SpyObj<AgentCustomerService>;
  let navigate: jasmine.Spy;

  const listingId = '01L00000000000000000000001';

  beforeEach(async () => {
    agentService = jasmine.createSpyObj<AgentCustomerService>(
      'AgentCustomerService',
      ['createOrResumeSession', 'getSession', 'getMessages', 'sendMessage'],
    );
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

    await TestBed.configureTestingModule({
      imports: [AgentCustomerServicePageComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AGENT_CUSTOMER_SERVICE_ENABLED, useValue: true },
        { provide: AgentCustomerService, useValue: agentService },
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

  it('starts the existing listing-bound create flow from display-safe route context', () => {
    fixture.detectChanges();

    expect(agentService.createOrResumeSession).toHaveBeenCalledOnceWith(listingId);
    expect(agentService.getMessages).toHaveBeenCalledOnceWith(
      '01A00000000000000000000001',
      null,
      50,
    );
    expect(fixture.nativeElement.textContent).toContain('Used bicycle');
    expect(fixture.nativeElement.textContent).not.toContain('\u0000');
    expect(agentService.sendMessage).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledOnceWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: { listingId: null, title: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });
});
