import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { CurrentUser, UpdateCurrentUserRequest } from '../models/user.model';
import { unwrapData } from './api-response';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1/users/me`;

  constructor(
    private http: HttpClient,
    private authService: AuthService
  ) {}

  getMe(): Observable<CurrentUser> {
    return this.http.get<ApiDataResponse<CurrentUser>>(this.baseUrl, { withCredentials: true }).pipe(
      map(unwrapData),
      tap(user => this.authService.setCurrentUser(user))
    );
  }

  updateMe(request: UpdateCurrentUserRequest, version: number): Observable<CurrentUser> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.patch<ApiDataResponse<CurrentUser>>(this.baseUrl, request, {
      headers,
      withCredentials: true,
    }).pipe(
      map(unwrapData),
      tap(user => this.authService.setCurrentUser(user))
    );
  }
}
