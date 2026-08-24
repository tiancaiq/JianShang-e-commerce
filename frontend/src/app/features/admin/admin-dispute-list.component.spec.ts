import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { DisputePage } from '../../core/models/order-dispute.model';
import { OrderDisputeService } from '../../core/services/order-dispute.service';
import { AdminDisputeListComponent } from './admin-dispute-list.component';

describe('AdminDisputeListComponent', () => {
  let fixture: ComponentFixture<AdminDisputeListComponent>;
  let service: jasmine.SpyObj<OrderDisputeService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<OrderDisputeService>('OrderDisputeService', ['adminSearch']);
    service.adminSearch.and.returnValue(of(emptyPage()));
    await TestBed.configureTestingModule({
      imports: [AdminDisputeListComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: OrderDisputeService, useValue: service },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminDisputeListComponent);
    fixture.detectChanges();
  });

  it('loads all assignments by default so assigned disputes remain visible', () => {
    expect(fixture.componentInstance.assignment).toBe('ALL');
    expect(fixture.nativeElement.querySelector('select[name="assignment"]').value).toBe('ALL');
    expect(service.adminSearch).toHaveBeenCalledWith(jasmine.objectContaining({
      assignment: 'ALL',
      page: 0,
      size: 25,
    }));
  });
});

function emptyPage(): DisputePage {
  return { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0, sort: 'priority,desc' };
}
