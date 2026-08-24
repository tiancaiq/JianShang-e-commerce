import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminReportDetail } from '../../core/models/report.model';
import { ReportService } from '../../core/services/report.service';
import { InvestigationCaseService } from '../../core/services/investigation-case.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminReportDetailComponent } from './admin-report-detail.component';

describe('AdminReportDetailComponent', () => {
  let fixture: ComponentFixture<AdminReportDetailComponent>;

  beforeEach(async () => {
    const reports = jasmine.createSpyObj<ReportService>('ReportService', ['detail']);
    reports.detail.and.returnValue(of(detail()));
    await TestBed.configureTestingModule({
      imports: [AdminReportDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '01R00000000000000000000001' } } } },
        { provide: ReportService, useValue: reports },
        { provide: InvestigationCaseService, useValue: jasmine.createSpyObj<InvestigationCaseService>('InvestigationCaseService', ['search','linkReport']) },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminReportDetailComponent);
    fixture.detectChanges();
  });

  it('renders friendly evidence labels and localized timestamps with raw tooltips', () => {
    const labelElements = fixture.nativeElement.querySelectorAll('.evidence dt') as NodeListOf<HTMLElement>;
    const labels = Array.from(labelElements).map(element => element.textContent?.trim());
    expect(labels).toContain('Business ID');
    expect(labels).toContain('Captured at');
    expect(labels).toContain('Seller type');
    expect(labels).not.toContain('Businessid');
    expect(labels).not.toContain('Capturedat');

    const timestamp = fixture.nativeElement.querySelector('dd[title="2026-08-15T14:30:00Z"]') as HTMLElement;
    expect(timestamp).not.toBeNull();
    expect(timestamp.textContent).not.toContain('2026-08-15T14:30:00Z');
  });
});

function detail(): AdminReportDetail {
  return {
    reportId: '01R00000000000000000000001', targetType: 'LISTING', targetId: '01L00000000000000000000001',
    safeTargetLabel: 'Walnut radio', reasonCode: 'SPAM', severity: 'LOW', status: 'UNDER_TRIAGE',
    reporterUserId: '01U00000000000000000000001', assignedAdminId: null, createdAt: '2026-08-15T14:30:00Z',
    updatedAt: '2026-08-15T14:30:00Z', version: 0, relatedReportCount: 0,
    reporterSummary: { userId: '01U00000000000000000000001', safeDisplayName: 'Reporter' },
    description: 'Evidence context', assignedAdmin: null,
    targetSnapshot: { businessId: '01B00000000000000000000001', capturedAt: '2026-08-15T14:30:00Z', sellerType: 'BUSINESS' },
    currentTargetSummary: { businessId: '01B00000000000000000000001', capturedAt: '2026-08-15T15:30:00Z' },
    relatedReports: [], currentEnforcementSummary: [], auditTimeline: [],
    investigationCase: null,
    availableAdminCapabilities: { canRead: true, canClaim: true, canRelease: false, canChangeSeverity: false,
      canDismiss: false, canMarkReadyForInvestigation: false, assignedToMe: false, assignedToOther: false,
      resolved: false, readOnly: false, readOnlyReason: null, canCreateInvestigationCase: false,
      canLinkInvestigationCase: false, canOpenInvestigationCase: false },
  };
}
