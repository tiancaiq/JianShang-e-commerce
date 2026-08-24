import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AppealPage } from '../../core/models/appeal.model';
import { AppealService } from '../../core/services/appeal.service';
import { AdminAppealInboxComponent } from './admin-appeal-inbox.component';

describe('AdminAppealInboxComponent', () => {
  let fixture: ComponentFixture<AdminAppealInboxComponent>;
  let component: AdminAppealInboxComponent;
  let service: jasmine.SpyObj<AppealService>;
  const result: AppealPage = {
    items: [{ appealId: '01ARZ3NDEKTSV4RRFFQ69G5AAD', enforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5AAC',
      targetType: 'USER', targetId: '01ARZ3NDEKTSV4RRFFQ69G5AAA', safeTargetLabel: 'Marketplace account',
      actionType: 'SUSPEND', appealReasonCode: 'DECISION_INCORRECT', status: 'SUBMITTED',
      assignedAdminId: null, submittedAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z', version: 0 }],
    page: 0, size: 25, totalElements: 1, totalPages: 1, sort: 'submittedAt,desc',
  };

  beforeEach(async () => {
    service = jasmine.createSpyObj<AppealService>('AppealService', ['search']);
    service.search.and.returnValue(of(result));
    await TestBed.configureTestingModule({
      imports: [AdminAppealInboxComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), { provide: AppealService, useValue: service }],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminAppealInboxComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the queue and exposes its selected assignment filter', () => {
    const host = fixture.nativeElement as HTMLElement;
    const tabs = Array.from(host.querySelectorAll<HTMLButtonElement>('.tabs button'));
    expect(host.textContent).toContain('Marketplace account');
    expect(tabs.map(button => button.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false', 'false']);

    tabs[1].click();
    fixture.detectChanges();

    expect(component.assignment).toBe('ASSIGNED_TO_ME');
    expect(tabs[1].getAttribute('aria-pressed')).toBe('true');
    expect(service.search).toHaveBeenCalledWith(jasmine.objectContaining({ assignment: 'ASSIGNED_TO_ME' }));
    const statuses = Array.from(host.querySelectorAll<HTMLSelectElement>('select'))[0];
    expect(Array.from(statuses.options).map(option => option.value)).toContain('UPHELD');
    expect(Array.from(statuses.options).map(option => option.value)).toContain('MODIFIED');
    expect(Array.from(statuses.options).map(option => option.value)).toContain('REVOKED');
  });

  it('shows a permission-specific message for a 403 response', () => {
    service.search.and.returnValue(throwError(() => ({ status: 403 })));
    component.load();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('You do not have permission to read appeals.');
  });
});
