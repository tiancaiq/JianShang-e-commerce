import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminActiveListingUpdateRequest,
  AdminListingModerationCase,
  AdminListingModerationCaseDetail,
  AdminListingRemoveRequest,
  Category,
  CreateListingDraftRequest,
  ListingDraft,
  ListingImage,
  ListingMedia,
  ListingMediaConfirmRequest,
  ListingMediaUploadRequest,
  ListingModerationCaseFilter,
  ListingModerationDecisionRequest,
  ListingModerationDecisionResponse,
  PublicListing,
  UpdateListingImagesRequest,
} from '../models/listing.model';

@Injectable({ providedIn: 'root' })
export class ListingService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1`;

  constructor(private http: HttpClient) {}

  getCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${this.baseUrl}/categories`, {
      withCredentials: true,
    });
  }

  createDraft(request: CreateListingDraftRequest): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(`${this.baseUrl}/listings`, request, {
      withCredentials: true,
    });
  }

  getListing(listingId: string): Observable<ListingDraft> {
    return this.http.get<ListingDraft>(`${this.baseUrl}/listings/${listingId}`, {
      withCredentials: true,
    });
  }

  getPublicListing(listingId: string): Observable<PublicListing> {
    return this.http.get<PublicListing>(`${this.baseUrl}/public/listings/${listingId}`, {
      withCredentials: true,
    });
  }

  getPublicListings(): Observable<PublicListing[]> {
    return this.http.get<PublicListing[]>(`${this.baseUrl}/public/listings`, {
      withCredentials: true,
    });
  }

  getMyListings(): Observable<ListingDraft[]> {
    return this.http.get<ListingDraft[]>(`${this.baseUrl}/users/me/listings`, {
      withCredentials: true,
    });
  }

  getPendingModerationListings(): Observable<ListingDraft[]> {
    return this.http.get<ListingDraft[]>(`${this.baseUrl}/admin/listings/moderation`, {
      withCredentials: true,
    });
  }

  getAdminListing(listingId: string): Observable<ListingDraft> {
    return this.http.get<ListingDraft>(`${this.baseUrl}/admin/listings/${listingId}`, {
      withCredentials: true,
    });
  }

  updateActiveListingByAdmin(
    listingId: string,
    version: number,
    request: AdminActiveListingUpdateRequest,
  ): Observable<ListingDraft> {
    return this.http.patch<ListingDraft>(`${this.baseUrl}/admin/listings/${listingId}`, request, {
      headers: { 'If-Match': String(version) },
      withCredentials: true,
    });
  }

  removeActiveListingByAdmin(
    listingId: string,
    version: number,
    request: AdminListingRemoveRequest,
  ): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(`${this.baseUrl}/admin/listings/${listingId}/remove`, request, {
      headers: { 'If-Match': String(version) },
      withCredentials: true,
    });
  }

  getListingModerationCases(
    filter: ListingModerationCaseFilter = 'open',
    query = '',
  ): Observable<AdminListingModerationCase[]> {
    const normalizedQuery = query.trim();
    const params: Record<string, string> = { filter };
    if (normalizedQuery) {
      params['q'] = normalizedQuery;
    }
    return this.http.get<AdminListingModerationCase[]>(`${this.baseUrl}/admin/moderation/listing-cases`, {
      params,
      withCredentials: true,
    });
  }

  claimListingModerationCase(caseId: string, version: number): Observable<AdminListingModerationCase> {
    return this.http.post<AdminListingModerationCase>(
      `${this.baseUrl}/admin/moderation/listing-cases/${caseId}/claim`,
      null,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  releaseListingModerationCase(caseId: string, version: number): Observable<AdminListingModerationCase> {
    return this.http.post<AdminListingModerationCase>(
      `${this.baseUrl}/admin/moderation/listing-cases/${caseId}/release`,
      null,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  getListingModerationCaseDetail(caseId: string): Observable<AdminListingModerationCaseDetail> {
    return this.http.get<AdminListingModerationCaseDetail>(
      `${this.baseUrl}/admin/moderation/listing-cases/${caseId}`,
      {
        withCredentials: true,
      },
    );
  }

  resolveListingModerationCase(
    caseId: string,
    version: number,
    request: ListingModerationDecisionRequest,
  ): Observable<AdminListingModerationCaseDetail> {
    return this.http.post<AdminListingModerationCaseDetail>(
      `${this.baseUrl}/admin/moderation/listing-cases/${caseId}/resolve`,
      request,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  updateDraft(listingId: string, version: number, request: CreateListingDraftRequest): Observable<ListingDraft> {
    return this.http.patch<ListingDraft>(`${this.baseUrl}/listings/${listingId}`, request, {
      headers: { 'If-Match': String(version) },
      withCredentials: true,
    });
  }

  submitForReview(listingId: string, version: number): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(`${this.baseUrl}/listings/${listingId}/submit`, null, {
      headers: { 'If-Match': String(version) },
      withCredentials: true,
    });
  }

  closeListing(listingId: string, version: number): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(`${this.baseUrl}/listings/${listingId}/close`, null, {
      headers: { 'If-Match': String(version) },
      withCredentials: true,
    });
  }

  requestMediaUpload(listingId: string, request: ListingMediaUploadRequest): Observable<ListingMedia> {
    return this.http.post<ListingMedia>(`${this.baseUrl}/listings/${listingId}/media/upload-request`, request, {
      withCredentials: true,
    });
  }

  uploadMediaFile(uploadUrl: string, file: File): Observable<void> {
    if (uploadUrl.startsWith('local-demo://')) {
      return of(undefined);
    }
    const usesGateway = uploadUrl.startsWith('/api/');
    const targetUrl = usesGateway ? `${environment.apiGatewayUrl}${uploadUrl}` : uploadUrl;
    return this.http.put<void>(targetUrl, file, {
      headers: { 'Content-Type': file.type },
      withCredentials: usesGateway,
    });
  }

  confirmMediaUpload(listingId: string, mediaId: string, request: ListingMediaConfirmRequest): Observable<ListingMedia> {
    return this.http.post<ListingMedia>(`${this.baseUrl}/listings/${listingId}/media/${mediaId}/confirm`, request, {
      withCredentials: true,
    });
  }

  updateListingImages(listingId: string, request: UpdateListingImagesRequest): Observable<ListingImage[]> {
    return this.http.put<ListingImage[]>(`${this.baseUrl}/listings/${listingId}/images`, request, {
      withCredentials: true,
    });
  }

  decideListing(
    listingId: string,
    version: number,
    request: ListingModerationDecisionRequest,
  ): Observable<ListingModerationDecisionResponse> {
    return this.http.post<ListingModerationDecisionResponse>(
      `${this.baseUrl}/admin/listings/${listingId}/decision`,
      request,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  mediaUrl(url: string | null | undefined): string {
    if (!url) {
      return '';
    }
    if (url.startsWith('/api/')) {
      return `${environment.apiGatewayUrl}${url}`;
    }
    return url;
  }
}
