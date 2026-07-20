import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AddressCreateRequest, AddressPatchRequest, BuyerAddress } from '../models/address.model';
import { ApiDataResponse } from '../models/auth.model';
import { unwrapData } from './api-response';

@Injectable({ providedIn: 'root' })
export class AddressBookService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/users/me/addresses`;

  constructor(private readonly http: HttpClient) {}

  list(): Observable<BuyerAddress[]> {
    return this.http.get<ApiDataResponse<BuyerAddress[]>>(this.baseUrl, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  create(request: AddressCreateRequest): Observable<BuyerAddress> {
    return this.http.post<ApiDataResponse<BuyerAddress>>(this.baseUrl, request, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  patch(addressId: string, version: number, request: AddressPatchRequest): Observable<BuyerAddress> {
    return this.http.patch<ApiDataResponse<BuyerAddress>>(
      `${this.baseUrl}/${encodeURIComponent(addressId)}`,
      request,
      {
        headers: this.versionHeader(version),
        withCredentials: true,
      },
    ).pipe(map(unwrapData));
  }

  delete(addressId: string, version: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(addressId)}`, {
      headers: this.versionHeader(version),
      withCredentials: true,
    });
  }

  setDefault(addressId: string, version: number): Observable<BuyerAddress> {
    return this.http.post<ApiDataResponse<BuyerAddress>>(
      `${this.baseUrl}/${encodeURIComponent(addressId)}/default`,
      null,
      {
        headers: this.versionHeader(version),
        withCredentials: true,
      },
    ).pipe(map(unwrapData));
  }

  private versionHeader(version: number): HttpHeaders {
    return new HttpHeaders({ 'If-Match': String(version) });
  }
}
