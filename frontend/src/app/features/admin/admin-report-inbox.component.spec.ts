import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { ReportService } from '../../core/services/report.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminReportInboxComponent } from './admin-report-inbox.component';

describe('AdminReportInboxComponent', () => {
  let fixture: ComponentFixture<AdminReportInboxComponent>;
  let reports: jasmine.SpyObj<ReportService>;
  let queryParams: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    reports = jasmine.createSpyObj<ReportService>('ReportService', ['search', 'claim']);
    reports.search.and.returnValue(of({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0, sort: 'createdAt,desc' }));
    queryParams = new BehaviorSubject(convertToParamMap({}));
    await TestBed.configureTestingModule({
      imports: [AdminReportInboxComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ReportService, useValue: reports },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParamMap: queryParams.asObservable() } },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminReportInboxComponent);
    fixture.detectChanges();
  });

  it('announces the selected assignment filter as a pressed toggle', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.assignment-tabs button')) as HTMLButtonElement[];
    expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false']);

    buttons[1].click();
    fixture.detectChanges();

    expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'false']);
  });

  it('hydrates validated analytics filters and resets them when the query clears', () => {
    queryParams.next(convertToParamMap({ assignment: 'UNASSIGNED', unresolved: 'true' }));

    expect(reports.search).toHaveBeenCalledWith(jasmine.objectContaining({
      status: '',
      assignment: 'UNASSIGNED',
      unresolved: true,
    }));

    queryParams.next(convertToParamMap({}));

    expect(reports.search).toHaveBeenCalledWith(jasmine.objectContaining({
      status: '',
      assignment: 'UNASSIGNED',
      unresolved: undefined,
    }));
  });

  it('rejects non-boolean unresolved query values', () => {
    queryParams.next(convertToParamMap({ assignment: 'UNASSIGNED', unresolved: 'yes' }));

    expect(reports.search).toHaveBeenCalledWith(jasmine.objectContaining({ unresolved: undefined }));
  });
});
