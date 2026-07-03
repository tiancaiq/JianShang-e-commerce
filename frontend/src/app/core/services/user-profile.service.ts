import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, map, switchMap, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { AvatarUploadTarget, CurrentUser, UpdateCurrentUserRequest } from '../models/user.model';
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

  uploadAvatar(file: File, version: number): Observable<CurrentUser> {
    return this.requestAvatarUpload(file).pipe(
      switchMap(target => this.uploadAvatarBytes(target.uploadUrl, file).pipe(map(() => target))),
      switchMap(target => this.confirmAvatarUpload(target, version)),
      tap(user => this.authService.setCurrentUser(user))
    );
  }

  private requestAvatarUpload(file: File): Observable<AvatarUploadTarget> {
    return this.http.post<ApiDataResponse<AvatarUploadTarget>>(`${this.baseUrl}/avatar/upload-request`, {
      contentType: file.type,
      fileName: file.name,
      sizeBytes: file.size,
    }, {
      withCredentials: true,
    }).pipe(map(unwrapData));
  }

  private uploadAvatarBytes(uploadUrl: string, file: File): Observable<void> {
    const usesGateway = uploadUrl.startsWith('/api/');
    const targetUrl = usesGateway ? `${environment.apiGatewayUrl}${uploadUrl}` : uploadUrl;
    return this.http.put<void>(targetUrl, file, {
      headers: { 'Content-Type': file.type },
      withCredentials: usesGateway,
    });
  }

  private confirmAvatarUpload(target: AvatarUploadTarget, version: number): Observable<CurrentUser> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.post<ApiDataResponse<CurrentUser>>(`${this.baseUrl}/avatar/confirm`, {
      objectKey: target.objectKey,
      contentType: target.contentType,
      sizeBytes: target.sizeBytes,
    }, {
      headers,
      withCredentials: true,
    }).pipe(
      map(unwrapData)
    );
  }

  deleteAvatar(version: number): Observable<CurrentUser> {
    const headers = new HttpHeaders({ 'If-Match': String(version) });
    return this.http.delete<ApiDataResponse<CurrentUser>>(`${this.baseUrl}/avatar`, {
      headers,
      withCredentials: true,
    }).pipe(
      map(unwrapData),
      tap(user => this.authService.setCurrentUser(user))
    );
  }
}
