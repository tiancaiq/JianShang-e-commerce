import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminBusinessService } from './admin-business.service';

describe('AdminBusinessService', () => {
  let service: AdminBusinessService; let http: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] }); service=TestBed.inject(AdminBusinessService); http=TestBed.inject(HttpTestingController); });
  afterEach(() => http.verify());

  it('sends business filters and stable pagination through the gateway', () => {
    service.search({q:'01BUSINESS',businessState:'ACTIVE',enforcementState:'RESTRICTED',scope:'BUSINESS_NEW_SALES',page:1,size:25,sort:'name,asc'}).subscribe();
    const request=http.expectOne(candidate=>candidate.url==='/api/v1/admin/businesses');
    expect(request.request.withCredentials).toBeTrue(); expect(request.request.params.get('businessState')).toBe('ACTIVE');
    expect(request.request.params.get('enforcementState')).toBe('RESTRICTED'); expect(request.request.params.get('scope')).toBe('BUSINESS_NEW_SALES');
    expect(request.request.params.get('page')).toBe('1'); expect(request.request.params.get('sort')).toBe('name,asc');
    request.flush({data:{items:[],page:1,size:25,totalElements:0,totalPages:0,sort:'name,asc'}});
  });

  it('uses business-scoped preview, confirm, and single-action revoke endpoints', () => {
    const create={actionType:'BAN' as const,scopes:['BUSINESS_LISTING_CREATION' as const,'BUSINESS_LISTING_PUBLICATION' as const,'BUSINESS_NEW_SALES' as const],reasonCode:'POLICY',reason:'Repeated marketplace abuse',effectiveAt:null,expiresAt:null,expectedBusinessVersion:2,idempotencyKey:null,safeMetadata:{}};
    service.previewCreate('01BUSINESS',create).subscribe(); const preview=http.expectOne('/api/v1/admin/businesses/01BUSINESS/enforcements/dry-run'); expect(preview.request.method).toBe('POST'); preview.flush({data:{}});
    service.create('01BUSINESS',{...create,idempotencyKey:'one-attempt'}).subscribe(); const confirm=http.expectOne('/api/v1/admin/businesses/01BUSINESS/enforcements'); expect(confirm.request.body.idempotencyKey).toBe('one-attempt'); confirm.flush({data:{}});
    service.previewRevoke('01BUSINESS','01ACTION',{expectedEnforcementVersion:1,reasonCode:'ADMIN_REINSTATEMENT',reason:'Resolved',idempotencyKey:null,safeMetadata:{}}).subscribe(); const revoke=http.expectOne('/api/v1/admin/businesses/01BUSINESS/enforcements/01ACTION/revoke/dry-run'); expect(revoke.request.method).toBe('POST'); revoke.flush({data:{}});
  });
});
