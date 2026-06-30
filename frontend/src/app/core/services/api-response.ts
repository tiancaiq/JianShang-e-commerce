import { ApiDataResponse } from '../models/auth.model';

// Unwraps the standard backend data envelope so components receive domain objects.
export function unwrapData<T>(response: ApiDataResponse<T>): T {
  return response.data;
}
