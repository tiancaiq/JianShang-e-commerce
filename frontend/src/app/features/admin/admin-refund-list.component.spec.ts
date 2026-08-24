import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { AdminFinanceService } from '../../core/services/admin-finance.service';
import { AdminRefundListComponent } from './admin-refund-list.component';

describe('AdminRefundListComponent analytics query filters', () => {
  it('hydrates validated refund status and restores the existing all-status default', async () => {
    const api = jasmine.createSpyObj<AdminFinanceService>('AdminFinanceService', ['refunds']);
    api.refunds.and.returnValue(of({content:[],page:0,size:25,totalElements:0,totalPages:0,sort:'createdAt,desc'}));
    const query = new BehaviorSubject<ParamMap>(convertToParamMap({status:'FAILED'}));
    await TestBed.configureTestingModule({
      imports:[AdminRefundListComponent],
      providers:[provideZonelessChangeDetection(),provideRouter([]),
        {provide:AdminFinanceService,useValue:api},
        {provide:ActivatedRoute,useValue:{queryParamMap:query.asObservable()}},
      ],
    }).compileComponents();

    const fixture=TestBed.createComponent(AdminRefundListComponent);fixture.detectChanges();
    expect(api.refunds.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:'FAILED'}));
    query.next(convertToParamMap({status:'PROCESSING'}));
    expect(api.refunds.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:'PROCESSING'}));
    query.next(convertToParamMap({}));
    expect(api.refunds.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:''}));
  });
});
