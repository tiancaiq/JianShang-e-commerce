import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { OrderDisputeService } from './order-dispute.service';

describe('OrderDisputeService',()=>{let service:OrderDisputeService;let http:HttpTestingController;
 beforeEach(()=>{TestBed.configureTestingModule({providers:[provideZonelessChangeDetection(),provideHttpClient(),provideHttpClientTesting()]});service=TestBed.inject(OrderDisputeService);http=TestBed.inject(HttpTestingController);});afterEach(()=>http.verify());
 it('creates a buyer dispute with a retry key and server-scoped business group',()=>{service.buyerCreate('ORDER',{businessGroupId:'GROUP',reasonCode:'ITEM_DAMAGED',description:'Arrived damaged',itemIds:[],evidence:[]}).subscribe();const req=http.expectOne('/api/v1/orders/ORDER/disputes');expect(req.request.method).toBe('POST');expect(req.request.headers.get('Idempotency-Key')).toContain('buyer-dispute:');expect(req.request.body.businessGroupId).toBe('GROUP');req.flush({});});
 it('uses server-side admin queue filters',()=>{service.adminSearch({status:'OPEN',assignment:'UNASSIGNED',page:2,size:25}).subscribe();const req=http.expectOne(r=>r.url==='/api/v1/admin/disputes');expect(req.request.params.get('status')).toBe('OPEN');expect(req.request.params.get('assignment')).toBe('UNASSIGNED');expect(req.request.params.get('page')).toBe('2');req.flush({content:[],page:2,size:25,totalElements:0,totalPages:0,sort:'createdAt,desc'});});
 it('records refund recommendation through dispute endpoint only',()=>{service.resolve('DSP',{resolutionType:'REFUND_RECOMMENDED',expectedVersion:3,recommendedRefundAmount:20}).subscribe();const req=http.expectOne('/api/v1/admin/disputes/DSP/resolve');expect(req.request.method).toBe('POST');expect(req.request.headers.get('Idempotency-Key')).toContain('dispute-resolution:');expect(req.request.url).not.toContain('/refund');req.flush({});});
 it('posts seller information only through the scoped business route',()=>{service.sellerStatement('BUS','DSP','Tracking confirms delivery').subscribe();const req=http.expectOne('/api/v1/businesses/BUS/disputes/DSP/statements');expect(req.request.method).toBe('POST');req.flush({});});
});
