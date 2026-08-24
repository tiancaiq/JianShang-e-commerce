import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ReportService } from './report.service';

describe('ReportService', () => {
  let service: ReportService; let http: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({providers:[provideZonelessChangeDetection(),provideHttpClient(),provideHttpClientTesting()]});service=TestBed.inject(ReportService);http=TestBed.inject(HttpTestingController); });
  afterEach(() => http.verify());

  it('submits only caller-selectable report fields', () => {
    service.submit('LISTING','01L00000000000000000000001','MISLEADING_LISTING','Wrong title').subscribe();
    const request=http.expectOne('/api/v1/reports');
    expect(request.request.method).toBe('POST');expect(request.request.withCredentials).toBeTrue();
    expect(request.request.body).toEqual({targetType:'LISTING',targetId:'01L00000000000000000000001',reasonCode:'MISLEADING_LISTING',description:'Wrong title'});
    expect(request.request.body.reporterUserId).toBeUndefined();expect(request.request.body.targetSnapshot).toBeUndefined();
    request.flush({data:{reportId:'01R',status:'SUBMITTED',createdAt:'2026-08-15T00:00:00Z',supportReference:'RPT-1'}});
  });

  it('sends server-side inbox filters and expected versions', () => {
    service.search({assignment:'ASSIGNED_TO_ME',status:'UNDER_TRIAGE',targetType:'BUSINESS',unresolved:true,page:2,size:25,sort:'createdAt,desc'}).subscribe();
    const search=http.expectOne(candidate=>candidate.url==='/api/v1/admin/reports');
    expect(search.request.params.get('assignment')).toBe('ASSIGNED_TO_ME');expect(search.request.params.get('unresolved')).toBe('true');expect(search.request.params.get('page')).toBe('2');search.flush({data:{items:[],page:2,size:25,totalElements:0,totalPages:0,sort:'createdAt,desc'}});
    service.dismiss('01R',4,'NO_VIOLATION_FOUND','No violation').subscribe();
    const dismiss=http.expectOne('/api/v1/admin/reports/01R/dismiss');expect(dismiss.request.body.expectedVersion).toBe(4);dismiss.flush({data:{}});
  });
});
