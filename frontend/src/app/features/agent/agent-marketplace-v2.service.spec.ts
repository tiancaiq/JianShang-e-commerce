import { firstValueFrom, toArray } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import {
  AgentMarketplaceV2Service,
  MarketplaceAgentV2ContractError,
  MarketplaceAgentV2Fetch,
  MarketplaceAgentV2SseParser,
} from './agent-marketplace-v2.service';

describe('MarketplaceAgentV2SseParser', () => {
  it('parses coalesced and fragmented UTF-8 frames in monotonic order', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const first = 'event: message_started\ndata: {"schemaVersion":"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1","sequence":1,"type":"message_started","userMessage":{"id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","role":"USER","body":"chair","createdAt":"2026-07-30T01:00:00Z"}}\n\n';
    const second = 'event: text_delta\ndata: {"schemaVersion":"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1","sequence":2,"type":"text_delta","delta":"café 🪑"}\n\n';
    const bytes = new TextEncoder().encode(first + second);
    const split = first.length + second.indexOf('🪑') + 1;

    const events = [
      ...parser.push(bytes.slice(0, split)),
      ...parser.push(bytes.slice(split)),
      ...parser.finish(),
    ];

    expect(events.map(event => event.type)).toEqual(['message_started', 'text_delta']);
    expect(events[1].type === 'text_delta' && events[1].delta).toBe('café 🪑');
  });

  it('fails closed on unknown fields without weakening event validation', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const frame = 'event: text_delta\ndata: {"schemaVersion":"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1","sequence":1,"type":"text_delta","delta":"safe","private":"no"}\n\n';

    expect(() => parser.push(new TextEncoder().encode(frame)))
      .toThrowError(MarketplaceAgentV2ContractError);
  });

  it('fails closed on malformed nested tool activity in a terminal response', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'chair',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Here is what I found.', attachments: [], refinement: null,
        pendingInteraction: null, citations: [],
        toolActivity: [{
          tool: 'search_listings', status: 'PRIVATE', reason: 'RESULTS_AVAILABLE',
          observedAt: '2026-07-30T01:00:01Z',
        }],
        inputTokens: 10, outputTokens: 5,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    expect(() => parser.push(new TextEncoder().encode(frame)))
      .toThrowError(MarketplaceAgentV2ContractError);
  });

  it('accepts the allowlisted availability activity without relaxing schemas', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const frame = 'event: activity\ndata: {"schemaVersion":"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1","sequence":1,"type":"activity","tool":"check_availability","label":"Checking current availability"}\n\n';

    expect(parser.push(new TextEncoder().encode(frame))).toEqual([jasmine.objectContaining({
      type: 'activity', tool: 'check_availability',
    })]);
  });

  it('accepts zero decisions only for a strict completed response shape', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'never mind',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Okay — I’ll stop here.', attachments: [],
        refinement: null, pendingInteraction: null, citations: [], toolActivity: [], inputTokens: 0, outputTokens: 0,
      },
      decisionCount: 0,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    expect(parser.push(new TextEncoder().encode(frame))[0].type).toBe('done');
  });

  it('accepts a bounded Product-facet refinement in the strict terminal message', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'chair',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Here are current chair listings.', attachments: [],
        refinement: {
          question: 'Would you like me to focus on one of these available types?',
          options: [
            { facet: 'SUBTYPE', value: 'Dining Chair', count: 12 },
            { facet: 'SUBTYPE', value: 'Gaming Chair', count: 8 },
          ],
        },
        pendingInteraction: null,
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    const event = parser.push(new TextEncoder().encode(frame))[0];

    expect(event.type).toBe('done');
    expect(event.type === 'done' && event.response.message.refinement?.options[0].value)
      .toBe('Dining Chair');
  });

  it('accepts customer-oriented match and price refinements', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'key organizer',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'I found five possible matches.', attachments: [],
        refinement: {
          question: 'Would you like exact matches or a lower price?',
          options: [
            { facet: 'MATCH_SCOPE', value: 'Exact matches only', count: 2 },
            { facet: 'MAXIMUM_PRICE', value: 'Under $20', count: 3 },
          ],
        },
        pendingInteraction: null,
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    const event = parser.push(new TextEncoder().encode(frame))[0];

    expect(event.type === 'done' && event.response.message.refinement?.options.map(item => item.value))
      .toEqual(['Exact matches only', 'Under $20']);
  });

  it('accepts a strict persisted single-use pending interaction', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'narrow it',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Would you like me to run that narrower search?',
        attachments: [], refinement: null,
        pendingInteraction: {
          id: '01ARZ3NDEKTSV4RRFFQ69G5FAY', type: 'CONFIRM_ACTION',
          action: 'RUN_REFINED_SEARCH', arguments: { query: 'lamp under 25', limit: 5 },
          workflowType: null, field: null, question: null,
          status: 'WAITING', createdAt: '2026-07-30T01:00:01Z',
        },
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    const event = parser.push(new TextEncoder().encode(frame))[0];

    expect(event.type === 'done' && event.response.message.pendingInteraction?.status)
      .toBe('WAITING');
  });

  it('accepts a strict seller field interaction without treating it as confirmation', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'I want to sell an item.',
        createdAt: '2026-08-03T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Great. What are you selling?',
        attachments: [], refinement: null,
        pendingInteraction: {
          id: '01ARZ3NDEKTSV4RRFFQ69G5FAY', type: 'ANSWER_FIELD', action: null,
          workflowType: 'CREATE_LISTING', field: 'ITEM_TYPE',
          question: 'What are you selling?', arguments: {}, status: 'WAITING',
          createdAt: '2026-08-03T01:00:01Z',
        },
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    const event = parser.push(new TextEncoder().encode(frame))[0];

    expect(event.type === 'done' && event.response.message.pendingInteraction?.field)
      .toBe('ITEM_TYPE');
  });

  it('accepts exact and related attachment quality while preserving legacy attachments', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const attachment = {
      type: 'LISTING', listingId: '01ARZ3NDEKTSV4RRFFQ69G5FAY', title: 'Key pouch',
      categoryName: 'Organizers', condition: 'GOOD', priceAmount: '15.00', currency: 'USD',
      publicCity: 'Irvine', publicRegion: 'Orange County', thumbnailUrl: null,
      checkedAt: '2026-07-30T01:00:01Z', responseHash: 'a'.repeat(64),
    };
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: { id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'key bag',
        createdAt: '2026-07-30T01:00:00Z' },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: { role: 'ASSISTANT', content: 'I found close matches.',
        attachments: [{ ...attachment, matchQuality: 'EXACT' }], refinement: null,
        pendingInteraction: null,
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6 },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    const event = parser.push(new TextEncoder().encode(frame))[0];

    expect(event.type === 'done' && event.response.message.attachments[0].matchQuality)
      .toBe('EXACT');
  });

  it('accepts only Product-owned public thumbnail paths on listing attachments', () => {
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: { id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'lamp',
        createdAt: '2026-07-30T01:00:00Z' },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: { role: 'ASSISTANT', content: 'I found current matches.',
        attachments: [{
          type: 'LISTING', listingId: '01ARZ3NDEKTSV4RRFFQ69G5FAY', title: 'Desk lamp',
          categoryName: 'Lamps', condition: 'GOOD', priceAmount: '25.80', currency: 'USD',
          publicCity: 'Irvine', publicRegion: 'Orange County',
          thumbnailUrl: '/api/v1/public/listing-media/01ARZ3NDEKTSV4RRFFQ69G5FAZ',
          checkedAt: '2026-07-30T01:00:01Z', responseHash: 'a'.repeat(64),
          matchQuality: 'EXACT',
        }], refinement: null, pendingInteraction: null,
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6 },
      decisionCount: 2,
    };
    const frame = (thumbnailUrl: string) => `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId,
      response: {
        ...response,
        message: {
          ...response.message,
          attachments: [{ ...response.message.attachments[0], thumbnailUrl }],
        },
      },
    })}\n\n`;

    const accepted = new MarketplaceAgentV2SseParser().push(
      new TextEncoder().encode(frame('/api/v1/public/listing-media/01ARZ3NDEKTSV4RRFFQ69G5FAZ')),
    )[0];
    expect(accepted.type === 'done'
      && accepted.response.message.attachments[0].thumbnailUrl)
      .toBe('/api/v1/public/listing-media/01ARZ3NDEKTSV4RRFFQ69G5FAZ');

    expect(() => new MarketplaceAgentV2SseParser().push(
      new TextEncoder().encode(frame('https://untrusted.example/listing.jpg')),
    )).toThrowError(MarketplaceAgentV2ContractError);
  });

  it('rejects an untyped or unbounded refinement option', () => {
    const parser = new MarketplaceAgentV2SseParser();
    const response = {
      sessionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
      userMessage: {
        id: '01ARZ3NDEKTSV4RRFFQ69G5FAW', role: 'USER', body: 'chair',
        createdAt: '2026-07-30T01:00:00Z',
      },
      assistantMessageId: '01ARZ3NDEKTSV4RRFFQ69G5FAX',
      message: {
        role: 'ASSISTANT', content: 'Here are current chair listings.', attachments: [],
        refinement: {
          question: 'Choose a type.',
          options: [
            { facet: 'BRAND', value: 'Private guess', count: 1 },
            { facet: 'SUBTYPE', value: 'Gaming Chair', count: 8 },
          ],
        },
        pendingInteraction: null,
        citations: [], toolActivity: [], inputTokens: 10, outputTokens: 6,
      },
      decisionCount: 2,
    };
    const frame = `event: done\ndata: ${JSON.stringify({
      schemaVersion: 'MARKETPLACE_AGENT_V2_STREAM_EVENT_V1', sequence: 1,
      type: 'done', messageId: response.assistantMessageId, response,
    })}\n\n`;

    expect(() => parser.push(new TextEncoder().encode(frame)))
      .toThrowError(MarketplaceAgentV2ContractError);
  });
});

describe('AgentMarketplaceV2Service commands', () => {
  const sessionId = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
  const userMessageId = '01ARZ3NDEKTSV4RRFFQ69G5FAW';
  const clientMessageId = '01ARZ3NDEKTSV4RRFFQ69G5FAX';
  const auth = { csrf: () => ({ headerName: 'X-CSRF-TOKEN', token: 'opaque' }) } as AuthService;

  it('stops by committed client identity without posting message content', async () => {
    const fetchRequest = jasmine.createSpy<MarketplaceAgentV2Fetch>().and.resolveTo(new Response(
      JSON.stringify({ sessionId, clientMessageId, outcome: 'STOPPED' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));
    const service = new AgentMarketplaceV2Service(auth, fetchRequest);

    const result = await firstValueFrom(service.stopMessage(sessionId, clientMessageId));

    expect(result.outcome).toBe('STOPPED');
    expect(String(fetchRequest.calls.mostRecent().args[0])).toContain(`/${clientMessageId}/stop`);
    expect(fetchRequest.calls.mostRecent().args[1]?.body).toBe('{}');
  });

  it('retries only the response using the committed user and client identities', async () => {
    const frame = 'event: error\ndata: {"schemaVersion":"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1","sequence":1,"type":"error","code":"MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED","message":"Interrupted.","retryable":true}\n\n';
    const fetchRequest = jasmine.createSpy<MarketplaceAgentV2Fetch>().and.resolveTo(new Response(
      new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode(frame)); controller.close();
      } }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    ));
    const service = new AgentMarketplaceV2Service(auth, fetchRequest);

    const events = await firstValueFrom(service.retryResponse(
      sessionId, userMessageId, clientMessageId,
    ).pipe(toArray()));

    expect(events.map(event => event.type)).toEqual(['error']);
    expect(String(fetchRequest.calls.mostRecent().args[0])).toContain(`/${userMessageId}/response-retry/stream`);
    expect(fetchRequest.calls.mostRecent().args[1]?.body)
      .toBe(JSON.stringify({ clientMessageId }));
  });
});
