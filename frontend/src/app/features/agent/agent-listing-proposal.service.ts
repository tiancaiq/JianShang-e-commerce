import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateListingProposalRequest,
  ListingProposalCategoryCandidate,
  ListingProposalContent,
  ListingProposalEvidence,
  ListingProposalResponse,
  ListingProposalResultMetadata,
  ListingProposalSourceMediaEvidence,
  ListingProposalSuggestedText,
  ListingProposalUnknownField,
} from './agent-listing-proposal.model';

const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const EVIDENCE_ID_PATTERN = /^E[1-4]$/;
const RESULT_CODE_PATTERN = /^[A-Z][A-Z0-9_]*$/;
const STATUSES = ['READY', 'DISMISSED', 'EXPIRED'] as const;
const MIMES = ['image/jpeg', 'image/png', 'image/webp'] as const;
const PROVIDER_MODES = ['FAKE', 'LIVE'] as const;
const UNKNOWN_FIELDS = [
  'TITLE',
  'DESCRIPTION',
  'CATEGORY',
  'SELLER_IDENTITY',
  'PRICE',
  'EXACT_LOCATION',
  'QUANTITY',
  'CONDITION',
  'NEGOTIABILITY',
  'POLICY_CLAIMS',
  'CONTACT_DATA',
  'BRAND',
  'MODEL',
  'AUTHENTICITY',
  'SAFETY',
] as const;
const ALWAYS_UNKNOWN: readonly ListingProposalUnknownField[] = [
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
];

export class ListingProposalContractError extends Error {
  constructor() {
    super('Agent Service returned an invalid listing proposal.');
    this.name = 'ListingProposalContractError';
  }
}

@Injectable({ providedIn: 'root' })
export class AgentListingProposalService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/agent/listing-proposals`;

  constructor(private readonly http: HttpClient) {}

  /** Creates or replays one proposal without sending browser-owned actor identity. */
  createOrResume(request: CreateListingProposalRequest): Observable<ListingProposalResponse> {
    return this.http.post<unknown>(this.baseUrl, request, {
      withCredentials: true,
    }).pipe(map(parseListingProposalResponse));
  }

  get(proposalId: string): Observable<ListingProposalResponse> {
    return this.http.get<unknown>(`${this.baseUrl}/${proposalId}`, {
      withCredentials: true,
    }).pipe(map(parseListingProposalResponse));
  }

  /** Dismisses one proposal using a replay-safe key supplied by the UI workflow. */
  dismiss(proposalId: string, idempotencyKey: string): Observable<ListingProposalResponse> {
    return this.http.post<unknown>(
      `${this.baseUrl}/${proposalId}/dismiss`,
      null,
      {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
        withCredentials: true,
      },
    ).pipe(map(parseListingProposalResponse));
  }
}

function parseListingProposalResponse(value: unknown): ListingProposalResponse {
  const record = strictRecord(
    value,
    [
      'proposalId',
      'status',
      'proposalVersion',
      'schemaVersion',
      'listingId',
      'sourceListingVersion',
      'createdAt',
      'expiresAt',
    ],
    [
      'sourceMediaEvidence',
      'proposal',
      'proposalOnly',
      'requiresSellerConfirmation',
      'dismissedAt',
      'contentPurgedAt',
      'resultMetadata',
    ],
  );
  const status = literal(record['status'], STATUSES);
  const proposal = nullable(record['proposal'], parseProposal);
  const sourceMediaEvidence = nullable(
    record['sourceMediaEvidence'],
    item => array(item, parseSourceMediaEvidence, 1, 4),
  );
  const resultMetadata = nullable(record['resultMetadata'], parseResultMetadata);
  const proposalOnly = nullableTrue(record['proposalOnly']);
  const requiresSellerConfirmation = nullableTrue(record['requiresSellerConfirmation']);

  if (status === 'READY') {
    if (!proposal || !sourceMediaEvidence || !resultMetadata || proposalOnly !== true
      || requiresSellerConfirmation !== true) {
      throw new ListingProposalContractError();
    }
    const sourcePairs = new Set(
      sourceMediaEvidence.map(item => `${item.evidenceId}:${item.mediaId}`),
    );
    if (sourcePairs.size !== sourceMediaEvidence.length
      || proposal.evidence.some(
        item => !sourcePairs.has(`${item.evidenceId}:${item.mediaId}`),
      )) {
      throw new ListingProposalContractError();
    }
  } else if (proposal || sourceMediaEvidence || resultMetadata || proposalOnly || requiresSellerConfirmation) {
    throw new ListingProposalContractError();
  }

  return {
    proposalId: ulid(record['proposalId']),
    status,
    proposalVersion: integer(record['proposalVersion'], 1),
    schemaVersion: exactString(record['schemaVersion'], 'LISTING_PROPOSAL_V1'),
    listingId: ulid(record['listingId']),
    sourceListingVersion: integer(record['sourceListingVersion'], 1),
    sourceMediaEvidence,
    proposal,
    proposalOnly,
    requiresSellerConfirmation,
    createdAt: dateTime(record['createdAt']),
    expiresAt: dateTime(record['expiresAt']),
    dismissedAt: nullable(record['dismissedAt'], dateTime),
    contentPurgedAt: nullable(record['contentPurgedAt'], dateTime),
    resultMetadata,
  };
}

function parseSourceMediaEvidence(value: unknown): ListingProposalSourceMediaEvidence {
  const record = strictRecord(value, ['mediaId', 'evidenceId', 'sha256', 'actualMime', 'byteSize']);
  return {
    mediaId: ulid(record['mediaId']),
    evidenceId: patternedString(record['evidenceId'], EVIDENCE_ID_PATTERN, 2, 2),
    sha256: patternedString(record['sha256'], SHA256_PATTERN, 64, 64),
    actualMime: literal(record['actualMime'], MIMES),
    byteSize: integer(record['byteSize'], 1, 10 * 1024 * 1024),
  };
}

function parseProposal(value: unknown): ListingProposalContent {
  const record = strictRecord(value, [
    'schema_version',
    'suggested_title',
    'suggested_description',
    'category_candidates',
    'evidence',
    'unknown_fields',
    'proposal_only',
    'requires_seller_confirmation',
  ]);
  const evidence = array(record['evidence'], parseEvidence, 0, 4);
  const evidenceIds = new Set(evidence.map(item => item.evidenceId));
  if (evidenceIds.size !== evidence.length) {
    throw new ListingProposalContractError();
  }
  const suggestedTitle = nullable(record['suggested_title'], parseSuggestedText);
  const suggestedDescription = nullable(record['suggested_description'], parseSuggestedText);
  const categoryCandidates = array(record['category_candidates'], parseCategoryCandidate, 0, 3);
  const unknownFields = array(
    record['unknown_fields'],
    item => literal(item, UNKNOWN_FIELDS),
    0,
    UNKNOWN_FIELDS.length,
  );
  if (new Set(unknownFields).size !== unknownFields.length
    || ALWAYS_UNKNOWN.some(field => !unknownFields.includes(field))
    || Boolean(suggestedTitle) === unknownFields.includes('TITLE')
    || Boolean(suggestedDescription) === unknownFields.includes('DESCRIPTION')
    || (categoryCandidates.length > 0) === unknownFields.includes('CATEGORY')
    || !allEvidenceKnown(
      [suggestedTitle, suggestedDescription, ...categoryCandidates].filter(
        (item): item is ListingProposalSuggestedText | ListingProposalCategoryCandidate => item !== null,
      ),
      evidenceIds,
    )) {
    throw new ListingProposalContractError();
  }
  return {
    schemaVersion: exactString(record['schema_version'], 'ai-list-proposal-v1'),
    suggestedTitle,
    suggestedDescription,
    categoryCandidates,
    evidence,
    unknownFields,
    proposalOnly: exactTrue(record['proposal_only']),
    requiresSellerConfirmation: exactTrue(record['requires_seller_confirmation']),
  };
}

function parseSuggestedText(value: unknown): ListingProposalSuggestedText {
  const record = strictRecord(value, ['value', 'confidence', 'evidence_ids']);
  return {
    value: boundedString(record['value'], 1, 2_000),
    confidence: number(record['confidence'], 0, 1),
    evidenceIds: uniqueEvidenceIds(record['evidence_ids']),
  };
}

function parseCategoryCandidate(value: unknown): ListingProposalCategoryCandidate {
  const record = strictRecord(value, ['label', 'confidence', 'evidence_ids']);
  return {
    label: boundedString(record['label'], 1, 80),
    confidence: number(record['confidence'], 0, 1),
    evidenceIds: uniqueEvidenceIds(record['evidence_ids']),
  };
}

function parseEvidence(value: unknown): ListingProposalEvidence {
  const record = strictRecord(value, ['evidence_id', 'media_id', 'observation']);
  return {
    evidenceId: patternedString(record['evidence_id'], EVIDENCE_ID_PATTERN, 2, 2),
    mediaId: ulid(record['media_id']),
    observation: boundedString(record['observation'], 1, 240),
  };
}

function parseResultMetadata(value: unknown): ListingProposalResultMetadata {
  const record = strictRecord(value, [
    'instructionVersion',
    'schemaVersion',
    'providerMode',
    'resultCode',
    'latencyMs',
    'inputTokens',
    'outputTokens',
  ]);
  return {
    instructionVersion: boundedString(record['instructionVersion'], 1, 80),
    schemaVersion: exactString(record['schemaVersion'], 'ai-list-proposal-v1'),
    providerMode: literal(record['providerMode'], PROVIDER_MODES),
    resultCode: patternedString(record['resultCode'], RESULT_CODE_PATTERN, 1, 80),
    latencyMs: integer(record['latencyMs'], 0, 3_600_000),
    inputTokens: integer(record['inputTokens'], 0, 1_000_000),
    outputTokens: integer(record['outputTokens'], 0, 1_000_000),
  };
}

function strictRecord(
  value: unknown,
  required: readonly string[],
  optional: readonly string[] = [],
): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ListingProposalContractError();
  }
  const record = value as Record<string, unknown>;
  const allowed = new Set([...required, ...optional]);
  if (required.some(key => !(key in record)) || Object.keys(record).some(key => !allowed.has(key))) {
    throw new ListingProposalContractError();
  }
  return record;
}

function array<T>(
  value: unknown,
  parser: (item: unknown) => T,
  minimum: number,
  maximum: number,
): T[] {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
    throw new ListingProposalContractError();
  }
  return value.map(parser);
}

function uniqueEvidenceIds(value: unknown): string[] {
  const values = array(
    value,
    item => patternedString(item, EVIDENCE_ID_PATTERN, 2, 2),
    1,
    4,
  );
  if (new Set(values).size !== values.length) {
    throw new ListingProposalContractError();
  }
  return values;
}

function allEvidenceKnown(
  suggestions: readonly (ListingProposalSuggestedText | ListingProposalCategoryCandidate)[],
  evidenceIds: ReadonlySet<string>,
): boolean {
  return suggestions.every(item => item.evidenceIds.every(id => evidenceIds.has(id)));
}

function nullable<T>(value: unknown, parser: (item: unknown) => T): T | null {
  return value === null || value === undefined ? null : parser(value);
}

function nullableTrue(value: unknown): true | null {
  return value === null || value === undefined ? null : exactTrue(value);
}

function exactTrue(value: unknown): true {
  if (value !== true) {
    throw new ListingProposalContractError();
  }
  return true;
}

function ulid(value: unknown): string {
  return patternedString(value, ULID_PATTERN, 26, 26);
}

function dateTime(value: unknown): string {
  const text = boundedString(value, 1, 64);
  if (!Number.isFinite(Date.parse(text))) {
    throw new ListingProposalContractError();
  }
  return text;
}

function exactString<const T extends string>(value: unknown, expected: T): T {
  if (value !== expected) {
    throw new ListingProposalContractError();
  }
  return expected;
}

function boundedString(value: unknown, minimum: number, maximum: number): string {
  if (typeof value !== 'string' || value.length < minimum || value.length > maximum) {
    throw new ListingProposalContractError();
  }
  return value;
}

function patternedString(
  value: unknown,
  pattern: RegExp,
  minimum: number,
  maximum: number,
): string {
  const text = boundedString(value, minimum, maximum);
  if (!pattern.test(text)) {
    throw new ListingProposalContractError();
  }
  return text;
}

function integer(value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ListingProposalContractError();
  }
  return value as number;
}

function number(value: unknown, minimum: number, maximum: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum || value > maximum) {
    throw new ListingProposalContractError();
  }
  return value;
}

function literal<const T extends readonly string[]>(value: unknown, allowed: T): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new ListingProposalContractError();
  }
  return value as T[number];
}
