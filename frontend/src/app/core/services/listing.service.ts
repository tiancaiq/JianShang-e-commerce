import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Category,
  CreateListingDraftRequest,
  ListingDraft,
  ListingImage,
  ListingMedia,
  ListingMediaConfirmRequest,
  ListingMediaUploadRequest,
  ListingModerationDecisionRequest,
  ListingModerationDecisionResponse,
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

  requestMediaUpload(listingId: string, request: ListingMediaUploadRequest): Observable<ListingMedia> {
    return this.http.post<ListingMedia>(`${this.baseUrl}/listings/${listingId}/media/upload-request`, request, {
      withCredentials: true,
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
}
