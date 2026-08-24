import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { InvestigationCaseService } from '../../core/services/investigation-case.service';
import { AdminInvestigationCaseInboxComponent } from './admin-investigation-case-inbox.component';

describe('AdminInvestigationCaseInboxComponent', () => {
  let fixture: ComponentFixture<AdminInvestigationCaseInboxComponent>;
  let cases: jasmine.SpyObj<InvestigationCaseService>;
  let queryParams: BehaviorSubject<ParamMap>;
  beforeEach(async () => {
    cases=jasmine.createSpyObj<InvestigationCaseService>('InvestigationCaseService',['search']);
    cases.search.and.returnValue(of({items:[],page:0,size:25,totalElements:0,totalPages:0,sort:'updatedAt,desc'}));
    queryParams=new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({imports:[AdminInvestigationCaseInboxComponent],providers:[
      provideZonelessChangeDetection(),provideRouter([]),{provide:InvestigationCaseService,useValue:cases},
      {provide:ActivatedRoute,useValue:{queryParamMap:queryParams.asObservable()}},
    ]}).compileComponents();
    fixture=TestBed.createComponent(AdminInvestigationCaseInboxComponent);fixture.detectChanges();
  });

  it('announces the selected inbox view with aria-pressed', () => {
    const buttons=Array.from(fixture.nativeElement.querySelectorAll('.tabs button')) as HTMLButtonElement[];
    expect(buttons.find(button=>button.textContent?.trim()==='Open')?.getAttribute('aria-pressed')).toBe('true');
    expect(buttons.find(button=>button.textContent?.trim()==='Unassigned')?.getAttribute('aria-pressed')).toBe('false');
  });

  it('hydrates and resets the exact ready-for-action drill-down across query navigation', () => {
    queryParams.next(convertToParamMap({status:'READY_FOR_ACTION',assignment:'ALL'}));
    expect(cases.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      status:'READY_FOR_ACTION',assignment:'ALL',
    }));
    queryParams.next(convertToParamMap({}));
    expect(cases.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:'OPEN',assignment:'ALL'}));
  });
});
