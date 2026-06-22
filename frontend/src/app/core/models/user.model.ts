export interface CurrentUser {
  id: string;
  keycloakSub: string;
  email: string | null;
  emailVerified: boolean;
  displayName: string | null;
  phone: string | null;
  phoneVerified: boolean;
  avatarUrl: string | null;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateCurrentUserRequest {
  displayName: string | null;
  phone: string | null;
  avatarUrl: string | null;
}
