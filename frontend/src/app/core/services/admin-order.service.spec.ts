import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AdminOrderService } from './admin-order.service';

describe('AdminOrderService', () => {
  let service: AdminOrderService; let http: HttpTestingController;
  beforeEach(() => { TestBed.configureTestingModule({providers:[provideZonelessChangeDetection(),provideHttpClient(),provideHttpClientTesting()]});
    service=TestBed.inject(AdminOrderService);http=TestBed.inject(HttpTestingController); });
  afterEach(() => http.verify());

  it('sends order filters and server-side pagination through the gateway', () => {
    service.search({q:'01ORDER',buyerUserId:'01BUYER',businessId:'01BUSINESS',listingId:'01LISTING',
      status:'CONFIRMED',paymentStatus:'SUCCEEDED',fulfillmentStatus:'PENDING_ACCEPTANCE',
      createdFrom:'2026-08-01T00:00:00Z',createdTo:'2026-09-01T00:00:00Z',page:2,size:25,sort:'createdAt,asc'}).subscribe();
    const request=http.expectOne(value=>value.url==='/api/v1/admin/orders');
    expect(request.request.withCredentials).toBeTrue();expect(request.request.params.get('businessId')).toBe('01BUSINESS');
    expect(request.request.params.get('listingId')).toBe('01LISTING');expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('sort')).toBe('createdAt,asc');request.flush({content:[],page:2,size:25,totalElements:0,totalPages:0,sort:'createdAt,asc'});
  });

  it('uses distinct dry-run and execution commands with the same expected version', () => {
    const body={reasonCode:'FRAUD_PREVENTION',reason:'Risk review',expectedOrderVersion:4,idempotencyKey:null};
    service.previewCancellation('01ORDER',body).subscribe();
    const preview=http.expectOne('/api/v1/admin/orders/01ORDER/cancel/dry-run');expect(preview.request.method).toBe('POST');
    expect(preview.request.body.expectedOrderVersion).toBe(4);preview.flush({allowed:true});
    service.cancel('01ORDER',{...body,idempotencyKey:'admin-order-key'}).subscribe();
    const execute=http.expectOne('/api/v1/admin/orders/01ORDER/cancel');expect(execute.request.body.idempotencyKey).toBe('admin-order-key');
    execute.flush({status:'CANCELLATION_REQUESTED'});
  });
});
