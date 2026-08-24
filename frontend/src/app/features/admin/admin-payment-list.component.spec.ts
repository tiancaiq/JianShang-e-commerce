import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { AdminFinanceService } from '../../core/services/admin-finance.service';
import { AdminPaymentListComponent } from './admin-payment-list.component';

describe('AdminPaymentListComponent analytics query filters', () => {
  it('hydrates failed payments and restores the all-status default on query removal', async () => {
    const api=jasmine.createSpyObj<AdminFinanceService>('AdminFinanceService',['payments']);
    api.payments.and.returnValue(of({content:[],page:0,size:25,totalElements:0,totalPages:0,sort:'createdAt,desc'}));
    const query=new BehaviorSubject<ParamMap>(convertToParamMap({status:'FAILED'}));
    await TestBed.configureTestingModule({imports:[AdminPaymentListComponent],providers:[
      provideZonelessChangeDetection(),provideRouter([]),{provide:AdminFinanceService,useValue:api},
      {provide:ActivatedRoute,useValue:{queryParamMap:query.asObservable()}},
    ]}).compileComponents();

    const fixture=TestBed.createComponent(AdminPaymentListComponent);fixture.detectChanges();
    expect(api.payments.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:'FAILED'}));
    query.next(convertToParamMap({}));
    expect(api.payments.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:''}));
  });
});
