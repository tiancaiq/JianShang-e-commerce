import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Category,
  CreateListingDraftRequest,
  ListingDraft,
  ListingMedia,
  ListingMediaConfirmRequest,
  ListingMediaUploadRequest,
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
}
