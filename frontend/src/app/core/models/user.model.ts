export interface CurrentUser {
  id: string;
  keycloakSub: string;
  email: string | null;
  emailVerified: boolean;
  displayName: string | null;
  publicHandle?: string | null;
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

export interface AvatarUploadRequest {
  contentType: string;
  fileName: string;
  sizeBytes: number;
}

export interface AvatarUploadTarget {
  objectBucket: string;
  objectKey: string;
  contentType: string;
  sizeBytes: number;
  uploadMethod: string;
  uploadUrl: string;
}

export interface AvatarUploadConfirmRequest {
  objectKey: string;
  contentType: string;
  sizeBytes: number;
}
