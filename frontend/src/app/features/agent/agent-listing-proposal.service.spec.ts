import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/services/auth.service';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import {
  AgentListingProposalService,
  ListingProposalContractError,
} from './agent-listing-proposal.service';

describe('AgentListingProposalService', () => {
  let service: AgentListingProposalService;
  let http: HttpTestingController;

  const listingId = '01H00000000000000000000001';
  const mediaId = '01J00000000000000000000001';
  const proposalId = '01K00000000000000000000001';
  const readyResponse = {
    proposalId,
    status: 'READY',
    proposalVersion: 1,
    schemaVersion: 'LISTING_PROPOSAL_V1',
    listingId,
    sourceListingVersion: 12,
    sourceMediaEvidence: [{
      mediaId,
      evidenceId: 'E1',
      sha256: 'a'.repeat(64),
      actualMime: 'image/png',
      byteSize: 2048,
    }],
    proposal: {
      schema_version: 'ai-list-proposal-v1',
      suggested_title: {
        value: 'City bicycle',
        confidence: 0.91,
        evidence_ids: ['E1'],
      },
      suggested_description: {
        value: 'Blue city bicycle with a rear rack.',
        confidence: 0.82,
        evidence_ids: ['E1'],
      },
      category_candidates: [{
        label: 'Bicycles',
        confidence: 0.88,
        evidence_ids: ['E1'],
      }],
      evidence: [{
        evidence_id: 'E1',
        media_id: mediaId,
        observation: 'A blue bicycle with a rear rack is visible.',
      }],
      unknown_fields: [
        'SELLER_IDENTITY',
        'PRICE',
        'EXACT_LOCATION',
        'QUANTITY',
        'CONDITION',
        'NEGOTIABILITY',
        'POLICY_CLAIMS',
        'CONTACT_DATA',
        'AUTHENTICITY',
        'SAFETY',
      ],
      proposal_only: true,
      requires_seller_confirmation: true,
    },
    proposalOnly: true,
    requiresSellerConfirmation: true,
    createdAt: '2026-07-20T10:00:00Z',
    expiresAt: '2026-07-21T10:00:00Z',
    dismissedAt: null,
    contentPurgedAt: null,
    resultMetadata: {
      instructionVersion: 'ai-list-instructions-v1',
      schemaVersion: 'ai-list-proposal-v1',
      providerMode: 'FAKE',
      resultCode: 'PROPOSAL_READY',
      latencyMs: 12,
      inputTokens: 0,
      outputTokens: 0,
    },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            csrf: () => ({
              headerName: 'X-CSRF-TOKEN',
              parameterName: '_csrf',
              token: 'proposal-csrf',
            }),
          },
        },
      ],
    });
    service = TestBed.inject(AgentListingProposalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the exact create contract and the existing BFF CSRF convention without actor fields', () => {
    let result: unknown;
    service.createOrResume({
      schemaVersion: 'LISTING_PROPOSAL_V1',
      listingId,
      expectedListingVersion: 12,
      mediaIds: [mediaId],
      clientRequestId: 'proposal-review-0001',
    }).subscribe(value => result = value);

    const request = http.expectOne('/api/v1/agent/listing-proposals');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('proposal-csrf');
    expect(request.request.body).toEqual({
      schemaVersion: 'LISTING_PROPOSAL_V1',
      listingId,
      expectedListingVersion: 12,
      mediaIds: [mediaId],
      clientRequestId: 'proposal-review-0001',
    });
    expect(request.request.body['actorUserId']).toBeUndefined();
    request.flush(readyResponse);

    expect(result).toEqual(jasmine.objectContaining({
      proposalId,
      status: 'READY',
      proposal: jasmine.objectContaining({
        suggestedTitle: jasmine.objectContaining({ value: 'City bicycle' }),
      }),
    }));
  });

  it('gets and dismisses through the shipped routes with one replay-safe dismiss key', () => {
    service.get(proposalId).subscribe();
    const get = http.expectOne(`/api/v1/agent/listing-proposals/${proposalId}`);
    expect(get.request.method).toBe('GET');
    expect(get.request.headers.has('X-CSRF-TOKEN')).toBeFalse();
    get.flush(readyResponse);

    service.dismiss(proposalId, 'dismiss-review-0001').subscribe();
    const dismiss = http.expectOne(`/api/v1/agent/listing-proposals/${proposalId}/dismiss`);
    expect(dismiss.request.method).toBe('POST');
    expect(dismiss.request.headers.get('Idempotency-Key')).toBe('dismiss-review-0001');
    expect(dismiss.request.headers.get('X-CSRF-TOKEN')).toBe('proposal-csrf');
    dismiss.flush({
      proposalId,
      status: 'DISMISSED',
      proposalVersion: 2,
      schemaVersion: 'LISTING_PROPOSAL_V1',
      listingId,
      sourceListingVersion: 12,
      sourceMediaEvidence: null,
      proposal: null,
      proposalOnly: null,
      requiresSellerConfirmation: null,
      createdAt: '2026-07-20T10:00:00Z',
      expiresAt: '2026-07-21T10:00:00Z',
      dismissedAt: '2026-07-20T10:05:00Z',
      contentPurgedAt: '2026-07-20T10:05:00Z',
      resultMetadata: null,
    });
  });

  it('rejects expanded, ungrounded, or content-bearing terminal responses', () => {
    const errors: unknown[] = [];

    service.get(proposalId).subscribe({ error: error => errors.push(error) });
    http.expectOne(`/api/v1/agent/listing-proposals/${proposalId}`).flush({
      ...readyResponse,
      actorUserId: 'must-not-cross-the-boundary',
    });

    service.get(proposalId).subscribe({ error: error => errors.push(error) });
    http.expectOne(`/api/v1/agent/listing-proposals/${proposalId}`).flush({
      ...readyResponse,
      proposal: {
        ...readyResponse.proposal,
        suggested_title: {
          ...readyResponse.proposal.suggested_title,
          evidence_ids: ['E4'],
        },
      },
    });

    service.get(proposalId).subscribe({ error: error => errors.push(error) });
    http.expectOne(`/api/v1/agent/listing-proposals/${proposalId}`).flush({
      ...readyResponse,
      status: 'DISMISSED',
    });

    expect(errors).toEqual([
      jasmine.any(ListingProposalContractError),
      jasmine.any(ListingProposalContractError),
      jasmine.any(ListingProposalContractError),
    ]);
  });
});
