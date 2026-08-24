import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { OrderDisputeService } from '../../core/services/order-dispute.service';
import { OrderDisputeComponent } from './order-dispute.component';

describe('OrderDisputeComponent seller detail errors', () => {
  let fixture: ComponentFixture<OrderDisputeComponent>;
  let service: jasmine.SpyObj<OrderDisputeService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<OrderDisputeService>('OrderDisputeService', [
      'sellerDetail', 'sellerStatement', 'buyerDetail', 'buyerStatement', 'buyerCreate',
    ]);
    await TestBed.configureTestingModule({
      imports: [OrderDisputeComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: OrderDisputeService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ businessId: 'BUSINESS', disputeId: 'DISPUTE' }),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
      ],
    }).compileComponents();
  });

  for (const status of [403, 404]) {
    it(`shows a safe unavailable state for seller detail HTTP ${status}`, () => {
      service.sellerDetail.and.returnValue(throwError(() => new HttpErrorResponse({
        status,
        error: { error: { message: 'An unexpected error occurred.' } },
      })));
      fixture = TestBed.createComponent(OrderDisputeComponent);
      fixture.detectChanges();

      const alert = fixture.nativeElement.querySelector('[role="alert"]');
      expect(alert.textContent).toContain('Dispute unavailable or not found.');
      expect(alert.textContent).not.toContain('An unexpected error occurred.');
    });
  }
});
