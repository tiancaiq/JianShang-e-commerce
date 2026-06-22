export interface IndividualSellerProfile {
  id: string;
  userId: string;
  publicCity: string;
  publicRegion: string;
  status: string;
  completedSalesCount: number;
  termsVersion: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ActivateIndividualSellerRequest {
  publicCity: string;
  publicRegion: string;
  termsVersion: string;
}
