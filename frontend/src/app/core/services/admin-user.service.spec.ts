import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminUserService } from './admin-user.service';

describe('AdminUserService', () => {
  let service: AdminUserService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminUserService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends server-side user filters and stable pagination through the gateway', () => {
    service.search({
      q: '01USER', enforcementState: 'SUSPENDED', scope: 'USER_BUYING',
      page: 2, size: 25, sort: 'createdAt,desc',
    }).subscribe();

    const request = http.expectOne(candidate => candidate.url === '/api/v1/admin/users');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.params.get('q')).toBe('01USER');
    expect(request.request.params.get('enforcementState')).toBe('SUSPENDED');
    expect(request.request.params.get('scope')).toBe('USER_BUYING');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('25');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    request.flush({ data: { items: [], page: 2, size: 25, totalElements: 0, totalPages: 0, sort: 'createdAt,desc' } });
  });

  it('uses user-scoped dry-run, confirm, and revoke endpoints', () => {
    const create = {
      actionType: 'RESTRICT' as const,
      scopes: ['USER_SELLING' as const],
      reasonCode: 'POLICY', reason: 'Test reason', effectiveAt: null, expiresAt: null,
      expectedUserVersion: 3, idempotencyKey: null, safeMetadata: {},
    };
    service.previewCreate('01USER', create).subscribe();
    const preview = http.expectOne('/api/v1/admin/users/01USER/enforcements/dry-run');
    expect(preview.request.method).toBe('POST');
    expect(preview.request.body.idempotencyKey).toBeNull();
    preview.flush({ data: {} });

    service.create('01USER', { ...create, idempotencyKey: 'same-attempt-key' }).subscribe();
    const confirm = http.expectOne('/api/v1/admin/users/01USER/enforcements');
    expect(confirm.request.body.idempotencyKey).toBe('same-attempt-key');
    confirm.flush({ data: {} });

    service.previewRevoke('01USER', '01ACTION', {
      expectedEnforcementVersion: 1, reasonCode: 'REINSTATE', reason: 'Resolved',
      idempotencyKey: null, safeMetadata: {},
    }).subscribe();
    const revoke = http.expectOne('/api/v1/admin/users/01USER/enforcements/01ACTION/revoke/dry-run');
    expect(revoke.request.method).toBe('POST');
    revoke.flush({ data: {} });
  });
});
