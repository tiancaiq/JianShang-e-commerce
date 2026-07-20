export interface BuyerAddress {
  id: string;
  label: string | null;
  recipientName: string;
  phone: string;
  line1: string;
  line2: string | null;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
  isDefault: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AddressCreateRequest {
  label?: string | null;
  recipientName: string;
  phone: string;
  line1: string;
  line2?: string | null;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
}

export type AddressPatchRequest = Partial<AddressCreateRequest>;
