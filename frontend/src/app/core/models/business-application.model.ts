export interface BusinessApplication {
  id: string;
  applicantUserId: string;
  legalName: string;
  businessType: string;
  country: string;
  contactEmail: string;
  contactPhone: string | null;
  publicCity: string;
  publicRegion: string;
  websiteUrl: string | null;
  description: string | null;
  status: string;
  submittedAt: string | null;
  reviewerUserId: string | null;
  approvedBusinessId: string | null;
  decisionReason: string | null;
  decidedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessApplicationDraftRequest {
  legalName: string;
  businessType: string;
  country: string;
  contactEmail: string;
  contactPhone: string | null;
  publicCity: string;
  publicRegion: string;
  websiteUrl: string | null;
  description: string | null;
}

export type BusinessApplicationDecision = 'APPROVE' | 'REJECT' | 'REQUEST_INFORMATION';

export interface BusinessApplicationDecisionRequest {
  decision: BusinessApplicationDecision;
  reason: string;
}
