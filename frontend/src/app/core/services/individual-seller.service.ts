import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import {
  ActivateIndividualSellerRequest,
  IndividualSellerProfile,
} from '../models/individual-seller.model';

@Injectable({ providedIn: 'root' })
export class IndividualSellerService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/individual-seller`;

  constructor(private http: HttpClient) {}

  getMe(): Observable<ApiDataResponse<IndividualSellerProfile>> {
    return this.http.get<ApiDataResponse<IndividualSellerProfile>>(`${this.baseUrl}/me`, {
      withCredentials: true,
    });
  }

  activate(request: ActivateIndividualSellerRequest): Observable<ApiDataResponse<IndividualSellerProfile>> {
    return this.http.post<ApiDataResponse<IndividualSellerProfile>>(`${this.baseUrl}/activation`, request, {
      withCredentials: true,
    });
  }
}
