export interface BusinessStore {
  id: string;
  businessId: string;
  slug: string;
  name: string;
  description: string | null;
  logoUrl: string | null;
  bannerUrl: string | null;
  supportEmail: string | null;
  supportPhone: string | null;
  publicCity: string;
  publicRegion: string;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessStoreUpdateRequest {
  name: string;
  slug: string;
  description: string | null;
  logoUrl: string | null;
  bannerUrl: string | null;
  supportEmail: string | null;
  supportPhone: string | null;
}

export interface BusinessStoreContext {
  businessId: string;
  businessLegalName: string;
  businessStatus: string;
  membershipRole: string;
  permissions: string[];
  store: BusinessStore;
}
