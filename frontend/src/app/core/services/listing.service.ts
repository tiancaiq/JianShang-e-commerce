import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment.development';
import {
  Category,
  CreateListingDraftRequest,
  ListingDraft,
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
}
