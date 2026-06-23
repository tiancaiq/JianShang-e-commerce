import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { CurrentUser, UpdateCurrentUserRequest } from '../models/user.model';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/users/me`;

  constructor(
    private http: HttpClient,
    private authService: AuthService
  ) {}

  getMe(): Observable<ApiDataResponse<CurrentUser>> {
    return this.http.get<ApiDataResponse<CurrentUser>>(this.baseUrl, { withCredentials: true }).pipe(
      tap(response => this.authService.setCurrentUser(response.data))
    );
  }

  updateMe(request: UpdateCurrentUserRequest, version: number): Observable<ApiDataResponse<CurrentUser>> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.patch<ApiDataResponse<CurrentUser>>(this.baseUrl, request, {
      headers,
      withCredentials: true,
    }).pipe(
      tap(response => this.authService.setCurrentUser(response.data))
    );
  }
}
