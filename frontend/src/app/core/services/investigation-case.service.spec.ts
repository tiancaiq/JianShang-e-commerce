import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { InvestigationCaseService } from './investigation-case.service';

describe('InvestigationCaseService', () => {
  let service: InvestigationCaseService; let http: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({providers:[provideZonelessChangeDetection(),provideHttpClient(),provideHttpClientTesting()]});service=TestBed.inject(InvestigationCaseService);http=TestBed.inject(HttpTestingController); });
  afterEach(() => http.verify());

  it('sends server-side filters and case/report versions', () => {
    service.search({assignment:'ASSIGNED_TO_ME',status:'UNDER_INVESTIGATION',targetType:'LISTING',page:1,size:25,sort:'updatedAt,desc'}).subscribe();
    const search=http.expectOne(candidate=>candidate.url==='/api/v1/admin/cases');
    expect(search.request.params.get('assignment')).toBe('ASSIGNED_TO_ME');expect(search.request.params.get('page')).toBe('1');search.flush({data:{items:[],page:1,size:25,totalElements:0,totalPages:0,sort:'updatedAt,desc'}});
    service.linkReport('01C','01R',4,7).subscribe();
    const link=http.expectOne('/api/v1/admin/cases/01C/reports');
    expect(link.request.body).toEqual({reportId:'01R',expectedCaseVersion:4,expectedReportVersion:7});link.flush({data:{}});
  });

  it('uses an idempotency key for append-only notes', () => {
    service.addNote('01C',3,'Internal finding','note-retry-1').subscribe();
    const request=http.expectOne('/api/v1/admin/cases/01C/notes');
    expect(request.request.body).toEqual({expectedVersion:3,body:'Internal finding',idempotencyKey:'note-retry-1'});
    request.flush({data:{}});
  });

  it('sends only versions and the stable key when executing a validated proposal',()=>{
    service.executeProposal('01C','01P',8,3,'case-exec-01P').subscribe();
    const request=http.expectOne('/api/v1/admin/cases/01C/enforcement-proposals/01P/execute');
    expect(request.request.body).toEqual({expectedCaseVersion:8,expectedProposalVersion:3,idempotencyKey:'case-exec-01P'});
    request.flush({data:{}});
  });
});
