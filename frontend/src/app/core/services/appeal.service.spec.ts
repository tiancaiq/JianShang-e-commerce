import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AppealService } from './appeal.service';

describe('AppealService',()=>{
  let service:AppealService;let http:HttpTestingController;
  beforeEach(()=>{TestBed.configureTestingModule({providers:[provideZonelessChangeDetection(),provideHttpClient(),provideHttpClientTesting()]});service=TestBed.inject(AppealService);http=TestBed.inject(HttpTestingController)});afterEach(()=>http.verify());
  it('submits only the enforcement id and appellant-authored fields',()=>{service.submit('01ACTION','OTHER','Please reconsider').subscribe();const request=http.expectOne('/api/v1/enforcements/01ACTION/appeals');expect(request.request.body).toEqual({reasonCode:'OTHER',explanation:'Please reconsider',safeEvidenceReferences:[]});expect(request.request.body.targetId).toBeUndefined();request.flush({data:{}})});
  it('sends assignment filters and optimistic versions',()=>{service.search({assignment:'ASSIGNED_TO_ME',status:'UNDER_REVIEW',page:0,size:25,sort:'submittedAt,desc'}).subscribe();const search=http.expectOne(v=>v.url==='/api/v1/admin/appeals');expect(search.request.params.get('assignment')).toBe('ASSIGNED_TO_ME');search.flush({data:{items:[],page:0,size:25,totalElements:0,totalPages:0,sort:'submittedAt,desc'}});service.start('01APPEAL',4).subscribe();const start=http.expectOne('/api/v1/admin/appeals/01APPEAL/start-review');expect(start.request.body).toEqual({expectedVersion:4});start.flush({data:{}})});
  it('stores a modify recommendation without calling an enforcement endpoint',()=>{service.review('01APPEAL',5,'MODIFY_RECOMMENDED','POLICY_REVIEW','Narrow the scope',{actionType:'RESTRICT',scopes:['USER_SELLING'],expiresAt:null,reasonCode:'APPEAL_MODIFICATION',reason:'Temporary',expectedTargetVersion:2}).subscribe();const request=http.expectOne('/api/v1/admin/appeals/01APPEAL/review');expect(request.request.body.outcome).toBe('MODIFY_RECOMMENDED');request.flush({data:{}})});
  it('uses separate resolution preview and confirmed idempotent execution endpoints',()=>{
    const versions={expectedAppealVersion:5,expectedEnforcementVersion:2,expectedTargetVersion:9};
    service.previewResolution('01APPEAL',versions).subscribe();
    const preview=http.expectOne('/api/v1/admin/appeals/01APPEAL/resolution/dry-run');
    expect(preview.request.method).toBe('POST');expect(preview.request.body).toEqual(versions);
    expect(preview.request.headers.has('Idempotency-Key')).toBeFalse();preview.flush({data:{}});

    service.resolve('01APPEAL',{...versions,previewToken:'preview-token',idempotencyKey:'resolution-key',confirmed:true}).subscribe();
    const execute=http.expectOne('/api/v1/admin/appeals/01APPEAL/resolution');
    expect(execute.request.method).toBe('POST');expect(execute.request.headers.get('Idempotency-Key')).toBe('resolution-key');
    expect(execute.request.body).toEqual({...versions,previewToken:'preview-token',idempotencyKey:'resolution-key',confirmed:true});
    execute.flush({data:{}});
  });
});
