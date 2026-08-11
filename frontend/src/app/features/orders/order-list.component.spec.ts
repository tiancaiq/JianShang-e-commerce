import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OrderService } from '../../core/services/order.service';
import { OrderListComponent } from './order-list.component';

describe('OrderListComponent', () => {
  let fixture: ComponentFixture<OrderListComponent>;
  let service: jasmine.SpyObj<OrderService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<OrderService>('OrderService', ['list']);
    service.list.and.returnValue(of({ items: [], page: { nextCursor: null, hasMore: false } }));
    await TestBed.configureTestingModule({
      imports: [OrderListComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: OrderService, useValue: service },
      ],
    }).compileComponents();
  });

  it('renders an empty state for an available account with no confirmed orders', () => {
    fixture = TestBed.createComponent(OrderListComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No confirmed orders yet.');
  });

  it('does not render a generic backend error when the order service is unavailable', () => {
    service.list.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 500,
      error: { error: { message: 'An unexpected error occurred.' } },
    })));
    fixture = TestBed.createComponent(OrderListComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('The order service is temporarily unavailable. Try again.');
    expect(text).not.toContain('An unexpected error occurred.');
  });

  it('uses a safe account-scoped message for hidden order history', () => {
    service.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    fixture = TestBed.createComponent(OrderListComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Order history is unavailable for this account.');
  });
});
