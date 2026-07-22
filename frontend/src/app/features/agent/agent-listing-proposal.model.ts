export type ListingProposalStatus = 'READY' | 'DISMISSED' | 'EXPIRED';
export type ListingProposalReviewChoice = 'KEEP' | 'EDIT' | 'DISCARD';

export type ListingProposalUnknownField =
  | 'TITLE'
  | 'DESCRIPTION'
  | 'CATEGORY'
  | 'SELLER_IDENTITY'
  | 'PRICE'
  | 'EXACT_LOCATION'
  | 'QUANTITY'
  | 'CONDITION'
  | 'NEGOTIABILITY'
  | 'POLICY_CLAIMS'
  | 'CONTACT_DATA'
  | 'BRAND'
  | 'MODEL'
  | 'AUTHENTICITY'
  | 'SAFETY';

export interface CreateListingProposalRequest {
  schemaVersion: 'LISTING_PROPOSAL_V1';
  listingId: string;
  expectedListingVersion: number;
  mediaIds: string[];
  clientRequestId: string;
}

export interface ListingProposalSourceMediaEvidence {
  mediaId: string;
  evidenceId: string;
  sha256: string;
  actualMime: 'image/jpeg' | 'image/png' | 'image/webp';
  byteSize: number;
}

export interface ListingProposalSuggestedText {
  value: string;
  confidence: number;
  evidenceIds: string[];
}

export interface ListingProposalCategoryCandidate {
  label: string;
  confidence: number;
  evidenceIds: string[];
}

export interface ListingProposalEvidence {
  evidenceId: string;
  mediaId: string;
  observation: string;
}

export interface ListingProposalContent {
  schemaVersion: 'ai-list-proposal-v1';
  suggestedTitle: ListingProposalSuggestedText | null;
  suggestedDescription: ListingProposalSuggestedText | null;
  categoryCandidates: ListingProposalCategoryCandidate[];
  evidence: ListingProposalEvidence[];
  unknownFields: ListingProposalUnknownField[];
  proposalOnly: true;
  requiresSellerConfirmation: true;
}

export interface ListingProposalResultMetadata {
  instructionVersion: string;
  schemaVersion: 'ai-list-proposal-v1';
  providerMode: 'FAKE' | 'LIVE';
  resultCode: string;
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
}

export interface ListingProposalResponse {
  proposalId: string;
  status: ListingProposalStatus;
  proposalVersion: number;
  schemaVersion: 'LISTING_PROPOSAL_V1';
  listingId: string;
  sourceListingVersion: number;
  sourceMediaEvidence: ListingProposalSourceMediaEvidence[] | null;
  proposal: ListingProposalContent | null;
  proposalOnly: true | null;
  requiresSellerConfirmation: true | null;
  createdAt: string;
  expiresAt: string;
  dismissedAt: string | null;
  contentPurgedAt: string | null;
  resultMetadata: ListingProposalResultMetadata | null;
}

export interface ListingProposalReviewMedia {
  mediaId: string;
  imageUrl: string;
  altText: string;
  eligible: boolean;
}

export type ListingProposalApplicationState =
  | 'IDLE'
  | 'APPLYING'
  | 'APPLIED'
  | 'CONFLICT'
  | 'FAILED';

export interface ListingProposalApplicationFields {
  title?: string;
  description?: string;
  categoryId?: string;
}

/** Carries only seller-confirmed final values into the ordinary Product listing update flow. */
export interface ListingProposalApplicationCommand {
  proposalId: string;
  listingId: string;
  sourceListingVersion: number;
  fields: ListingProposalApplicationFields;
}
