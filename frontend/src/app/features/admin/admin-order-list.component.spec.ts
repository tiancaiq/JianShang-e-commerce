import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { AdminOrderPage } from '../../core/models/admin-order.model';
import { AdminOrderService } from '../../core/services/admin-order.service';
import { AdminOrderListComponent } from './admin-order-list.component';

describe('AdminOrderListComponent', () => {
  let fixture: ComponentFixture<AdminOrderListComponent>;
  let service: jasmine.SpyObj<AdminOrderService>;
  let queryParams: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    service=jasmine.createSpyObj<AdminOrderService>('AdminOrderService',['search']);
    service.search.and.returnValue(of(page()));
    queryParams=new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({imports:[AdminOrderListComponent],providers:[
      provideZonelessChangeDetection(),provideRouter([]),{provide:AdminOrderService,useValue:service},
      {provide:ActivatedRoute,useValue:{queryParamMap:queryParams.asObservable()}},
    ]}).compileComponents();
    fixture=TestBed.createComponent(AdminOrderListComponent);fixture.detectChanges();
  });

  it('renders server results and sends filters and pagination back to the API', () => {
    expect(fixture.nativeElement.textContent).toContain('Buyer One');
    fixture.componentInstance.buyerUserId=id(2);fixture.componentInstance.businessId=id(3);
    fixture.componentInstance.applyFilters();fixture.detectChanges();
    expect(service.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      buyerUserId:id(2),businessId:id(3),page:0,size:25,sort:'createdAt,desc',
    }));
    fixture.componentInstance.go(1);
    expect(service.search.calls.mostRecent().args[0].page).toBe(1);
  });

  it('renders a stable permission-denied state', () => {
    service.search.and.returnValue(throwError(()=>({status:403,error:{}})));
    fixture.componentInstance.applyFilters();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent)
      .toContain('You do not have permission to read orders.');
  });

  it('hydrates cancelled and delivered drill-downs and resets them on query removal', () => {
    queryParams.next(convertToParamMap({status:'CANCELLED'}));
    expect(service.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      status:'CANCELLED',fulfillmentStatus:'',page:0,
    }));
    queryParams.next(convertToParamMap({fulfillmentStatus:'DELIVERED'}));
    expect(service.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      status:'',fulfillmentStatus:'DELIVERED',page:0,
    }));
    queryParams.next(convertToParamMap({}));
    expect(service.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      status:'',fulfillmentStatus:'',page:0,
    }));
  });
});

function page():AdminOrderPage{return{content:[{orderId:id(1),buyerUserId:id(2),buyerDisplayName:'Buyer One',sellerType:'BUSINESS',
  businessIds:[id(3)],businessDisplayNames:['Camera House'],status:'CONFIRMED',paymentStatus:'SUCCEEDED',
  fulfillmentStatus:'PENDING_ACCEPTANCE',totalAmount:125,currency:'USD',itemCount:1,
  createdAt:'2026-08-16T01:00:00Z',updatedAt:'2026-08-16T01:00:00Z',version:4}],page:0,size:25,
  totalElements:26,totalPages:2,sort:'createdAt,desc'}}
function id(value:number){return `01${String(value).padStart(24,'0')}`}
