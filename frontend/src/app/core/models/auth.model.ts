import { CurrentUser } from './user.model';
export type { CurrentUser } from './user.model';

export interface CsrfSummary {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface SessionUser {
  subject: string;
  email: string | null;
  displayName: string | null;
  roles: string[];
  expiresAt: string | null;
}

export interface SessionResponse {
  authenticated: boolean;
  user: SessionUser | null;
  csrf: CsrfSummary | null;
}

export interface ApiDataResponse<T> {
  data: T;
}

export interface AuthState {
  authenticated: boolean;
  user: CurrentUser | null;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
}
