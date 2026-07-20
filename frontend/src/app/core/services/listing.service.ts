import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminActiveListingUpdateRequest,
  AdminListingModerationCase,
  AdminListingModerationCaseDetail,
  AdminListingRemoveRequest,
  BusinessStoreListingSearchPage,
  BusinessStoreListingSearchParams,
  BusinessStoreItemSearchPage,
  BusinessStoreItemSearchParams,
  Category,
  CreateListingDraftRequest,
  ListingEngagement,
  ListingDraft,
  ListingImage,
  ListingMedia,
  ListingMediaConfirmRequest,
  ListingMediaUploadRequest,
  ListingModerationCaseFilter,
  ListingModerationDecisionRequest,
  ListingModerationDecisionResponse,
  MarketplaceListingSearchPage,
  MarketplaceListingSearchParams,
  PublicListing,
  PublicListingSearchParams,
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

  recordListingVisit(listingId: string): Observable<ListingEngagement> {
    return this.http.post<ListingEngagement>(`${this.baseUrl}/listings/${listingId}/visit`, null, {
      withCredentials: true,
    });
  }

  likeListing(listingId: string): Observable<ListingEngagement> {
    return this.http.post<ListingEngagement>(`${this.baseUrl}/listings/${listingId}/like`, null, {
      withCredentials: true,
    });
  }

  unlikeListing(listingId: string): Observable<ListingEngagement> {
    return this.http.delete<ListingEngagement>(`${this.baseUrl}/listings/${listingId}/like`, {
      withCredentials: true,
    });
  }

  getMyListingEngagement(listingId: string): Observable<ListingEngagement> {
    return this.http.get<ListingEngagement>(`${this.baseUrl}/listings/${listingId}/engagement/me`, {
      withCredentials: true,
    });
  }

  getMyLikedListings(): Observable<PublicListing[]> {
    return this.http.get<PublicListing[]>(`${this.baseUrl}/users/me/liked-listings`, {
      withCredentials: true,
    });
  }

  searchMarketplaceListings(params: MarketplaceListingSearchParams = {}): Observable<MarketplaceListingSearchPage> {
    return this.http.get<MarketplaceListingSearchPage>(`${this.baseUrl}/public/marketplace/listings/search`, {
      params: this.listingSearchHttpParams(params),
      withCredentials: true,
    });
  }

  searchBusinessStoreListings(params: BusinessStoreListingSearchParams = {}): Observable<BusinessStoreListingSearchPage> {
    return this.http.get<BusinessStoreListingSearchPage>(`${this.baseUrl}/public/stores/listings/search`, {
      params: this.listingSearchHttpParams(params),
      withCredentials: true,
    });
  }

  getMyListings(): Observable<ListingDraft[]> {
    return this.http.get<ListingDraft[]>(`${this.baseUrl}/users/me/listings`, {
      withCredentials: true,
    });
  }

  getBusinessStoreItems(businessId: string): Observable<ListingDraft[]> {
    return this.http.get<ListingDraft[]>(`${this.baseUrl}/businesses/${businessId}/store/items`, {
      withCredentials: true,
    });
  }

  searchBusinessStoreItems(
    businessId: string,
    params: BusinessStoreItemSearchParams = {},
  ): Observable<BusinessStoreItemSearchPage> {
    let httpParams = new HttpParams();
    if (params.q?.trim()) {
      httpParams = httpParams.set('q', params.q.trim());
    }
    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }
    if (params.cursor?.trim()) {
      httpParams = httpParams.set('cursor', params.cursor.trim());
    }
    if (params.limit !== null && params.limit !== undefined) {
      httpParams = httpParams.set('limit', String(params.limit));
    }
    return this.http.get<BusinessStoreItemSearchPage>(
      `${this.baseUrl}/businesses/${businessId}/store/items/search`,
      {
        params: httpParams,
        withCredentials: true,
      },
    );
  }

  createBusinessStoreItem(businessId: string, request: CreateListingDraftRequest): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(`${this.baseUrl}/businesses/${businessId}/store/items`, request, {
      withCredentials: true,
    });
  }

  getBusinessStoreItem(businessId: string, listingId: string): Observable<ListingDraft> {
    return this.http.get<ListingDraft>(`${this.baseUrl}/businesses/${businessId}/store/items/${listingId}`, {
      withCredentials: true,
    });
  }

  updateBusinessStoreItem(
    businessId: string,
    listingId: string,
    version: number,
    request: CreateListingDraftRequest,
  ): Observable<ListingDraft> {
    return this.http.patch<ListingDraft>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}`,
      request,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  publishBusinessStoreItem(businessId: string, listingId: string, version: number): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/publish`,
      null,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  pauseBusinessStoreItem(businessId: string, listingId: string, version: number): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/pause`,
      null,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
  }

  relistBusinessStoreItem(businessId: string, listingId: string, version: number): Observable<ListingDraft> {
    return this.http.post<ListingDraft>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/relist`,
      null,
      {
        headers: { 'If-Match': String(version) },
        withCredentials: true,
      },
    );
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

  requestBusinessStoreItemMediaUpload(
    businessId: string,
    listingId: string,
    request: ListingMediaUploadRequest,
  ): Observable<ListingMedia> {
    return this.http.post<ListingMedia>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/media/upload-request`,
      request,
      {
        withCredentials: true,
      },
    );
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

  confirmBusinessStoreItemMediaUpload(
    businessId: string,
    listingId: string,
    mediaId: string,
    request: ListingMediaConfirmRequest,
  ): Observable<ListingMedia> {
    return this.http.post<ListingMedia>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/media/${mediaId}/confirm`,
      request,
      {
        withCredentials: true,
      },
    );
  }

  updateListingImages(listingId: string, request: UpdateListingImagesRequest): Observable<ListingImage[]> {
    return this.http.put<ListingImage[]>(`${this.baseUrl}/listings/${listingId}/images`, request, {
      withCredentials: true,
    });
  }

  updateBusinessStoreItemImages(
    businessId: string,
    listingId: string,
    request: UpdateListingImagesRequest,
  ): Observable<ListingImage[]> {
    return this.http.put<ListingImage[]>(
      `${this.baseUrl}/businesses/${businessId}/store/items/${listingId}/images`,
      request,
      {
        withCredentials: true,
      },
    );
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

  // Builds the shared query string for the split marketplace and business-store public search endpoints.
  private listingSearchHttpParams(params: PublicListingSearchParams): HttpParams {
    let httpParams = new HttpParams();
    const append = (name: string, value: string | number | null | undefined): void => {
      if (value === null || value === undefined) {
        return;
      }
      const text = String(value).trim();
      if (!text) {
        return;
      }
      httpParams = httpParams.set(name, text);
    };

    append('q', params.q);
    append('categoryId', params.categoryId);
    append('condition', params.condition);
    append('minPrice', params.minPrice);
    append('maxPrice', params.maxPrice);
    append('city', params.city);
    append('county', params.county);
    append('sort', params.sort === 'none' ? null : params.sort);
    append('cursor', params.cursor);
    append('limit', params.limit);
    return httpParams;
  }
}
