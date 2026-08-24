import { signal } from '@angular/core';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { AdminService } from '../../core/services/admin.service';
import { SystemOperationsService } from '../../core/services/system-operations.service';
import { AdminSystemComponent } from './admin-system.component';

describe('AdminSystemComponent', () => {
  let fixture: ComponentFixture<AdminSystemComponent>;
  let api: jasmine.SpyObj<SystemOperationsService>;
  let queryParams: BehaviorSubject<ParamMap>;
  let route: {snapshot:{data:Record<string,string>;paramMap:{get:(key:string)=>string|null}};queryParamMap:BehaviorSubject<ParamMap>};

  beforeEach(async () => {
    api = jasmine.createSpyObj<SystemOperationsService>('SystemOperationsService', [
      'summary', 'health', 'jobs', 'job', 'outbox', 'outboxEvent', 'reconciliation',
      'inventory', 'search', 'features', 'previewJob', 'retryJob', 'previewOutbox',
      'retryOutbox', 'previewReindex', 'reindex',
    ]);
    queryParams=new BehaviorSubject(convertToParamMap({}));
    route={snapshot:{data:{systemSection:'summary'},paramMap:{get:()=>null}},queryParamMap:queryParams};
    api.summary.and.returnValue(of({
      generatedAt: '2026-08-20T00:00:00Z',
      serviceHealth: { primary: 7, secondary: 1 },
      jobs: { primary: 2, secondary: 1 },
      outbox: { primary: 3, secondary: 0 },
      reconciliationRequiresAttention: 1,
      inventoryPotentiallyStuck: 4,
      failedIndexOperations: 2,
      featureWarnings: 1,
      sourceWarnings: ['Payment signals are temporarily unavailable.'],
      recentOperations: [],
    }));
    api.health.and.returnValue(of([{
      serviceKey: 'AUTH', displayName: 'Auth', status: 'HEALTHY',
      checkedAt: '2026-08-20T00:00:00Z', responseLatencyMs: 12,
      safeSummary: 'Health endpoint responded normally.',
    }, {
      serviceKey: 'PAYMENT', displayName: 'Payment', status: 'UNAVAILABLE',
      checkedAt: '2026-08-20T00:00:00Z', responseLatencyMs: 2000,
      safeSummary: 'Health endpoint could not be reached within the bounded check.',
    }]));
    const admin = jasmine.createSpyObj<AdminService>('AdminService', [], {
      currentAdmin: signal({
        userId: 'admin-1', role: 'PLATFORM_ADMIN', roles: ['SUPER_ADMIN'], accountState: 'ACTIVE',
        permissions: ['admin.system.read', 'admin.system.retry', 'admin.search.maintenance', 'admin.feature.read'],
      }),
    });

    await TestBed.configureTestingModule({
      imports: [AdminSystemComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminService, useValue: admin },
        { provide: SystemOperationsService, useValue: api },
        { provide: ActivatedRoute, useValue: route },
      ],
    }).compileComponents();
  });

  it('renders safe partial-health and operational summary signals', () => {
    fixture = TestBed.createComponent(AdminSystemComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Runtime attention required');
    expect(text).toContain('Auth');
    expect(text).toContain('Payment');
    expect(text).toContain('2 failed');
    expect(text).toContain('3 failed');
    expect(text).not.toContain('jdbc:');
    expect(text).not.toContain('password');
  });

  it('keeps the page available when the summary request fails', () => {
    api.summary.and.returnValue(throwError(() => ({ status: 503 })));
    fixture = TestBed.createComponent(AdminSystemComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Operations data unavailable');
  });

  it('hydrates aggregate and terminal durable-job statuses and resets query navigation', () => {
    route.snapshot.data['systemSection']='jobs';
    api.jobs.and.returnValue(of({items:[],page:0,size:50,totalElements:0,totalPages:0,sort:'createdAt,desc'}));
    queryParams.next(convertToParamMap({status:'FAILURE'}));
    fixture=TestBed.createComponent(AdminSystemComponent);fixture.detectChanges();
    expect(api.jobs.calls.mostRecent().args[0]).toEqual({service:'',status:'FAILURE'});
    const failure = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('option'))
      .find(option => (option as HTMLOptionElement).value === 'FAILURE');
    expect(failure?.textContent).toContain('failed, dead-letter, terminal');
    queryParams.next(convertToParamMap({status:'TERMINAL'}));
    expect(api.jobs.calls.mostRecent().args[0]).toEqual({service:'',status:'TERMINAL'});
    queryParams.next(convertToParamMap({}));
    expect(api.jobs.calls.mostRecent().args[0]).toEqual({service:'',status:''});
  });

  it('hydrates and resets the aggregate outbox failure status from query navigation', () => {
    route.snapshot.data['systemSection']='outbox';
    api.outbox.and.returnValue(of({items:[],page:0,size:50,totalElements:0,totalPages:0,sort:'createdAt,desc'}));
    queryParams.next(convertToParamMap({status:'FAILURE'}));
    fixture=TestBed.createComponent(AdminSystemComponent);fixture.detectChanges();
    expect(api.outbox.calls.mostRecent().args[0]).toEqual({service:'',status:'FAILURE'});
    const failure = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('option'))
      .find(option => (option as HTMLOptionElement).value === 'FAILURE');
    expect(failure?.textContent).toContain('failed or dead-letter');
    queryParams.next(convertToParamMap({}));
    expect(api.outbox.calls.mostRecent().args[0]).toEqual({service:'',status:''});
  });
});
