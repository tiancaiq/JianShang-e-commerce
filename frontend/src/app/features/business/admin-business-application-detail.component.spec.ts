import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminBusinessApplicationDetailComponent } from './admin-business-application-detail.component';
import { AdminService } from '../../core/services/admin.service';

describe('AdminBusinessApplicationDetailComponent', () => {
  let fixture: ComponentFixture<AdminBusinessApplicationDetailComponent>;
  let component: AdminBusinessApplicationDetailComponent;
  let businessApplicationService: jasmine.SpyObj<BusinessApplicationService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let adminService: jasmine.SpyObj<AdminService>;

  const application: BusinessApplication = {
    id: '01JY0000000000000000000001',
    applicantUserId: '01JY0000000000000000000000',
    legalName: 'Detail Review LLC',
    businessType: 'LLC',
    country: 'US',
    contactEmail: 'owner@example.com',
    contactPhone: '+19495551234',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    websiteUrl: 'https://example.com',
    description: 'Local seller',
    status: 'PENDING_VERIFICATION',
    submittedAt: '2026-06-16T12:00:00Z',
    reviewerUserId: null,
    approvedBusinessId: null,
    decisionReason: null,
    decidedAt: null,
    version: 1,
    createdAt: '2026-06-16T11:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(async () => {
    businessApplicationService = jasmine.createSpyObj<BusinessApplicationService>(
      'BusinessApplicationService',
      ['getAdminApplication', 'getAdminTimeline', 'decide']
    );
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    adminService = jasmine.createSpyObj<AdminService>('AdminService', ['hasPermission']);
    adminService.hasPermission.and.returnValue(true);
    businessApplicationService.getAdminApplication.and.returnValue(of({ data: application }));
    businessApplicationService.getAdminTimeline.and.returnValue(of({ data: [{
      eventId: '01EV0000000000000000000001',
      occurredAt: '2026-06-16T12:00:00Z',
      eventType: 'BUSINESS_APPLICATION_SUBMITTED',
      actorType: 'MARKETPLACE_USER',
      actorId: application.applicantUserId,
      actorDisplay: 'Application Owner',
      source: 'SYSTEM',
      targetType: 'BUSINESS_APPLICATION',
      targetId: application.id,
      moderationCaseId: null,
      previousState: 'DRAFT',
      newState: 'PENDING_VERIFICATION',
      reason: null,
      correlationId: 'business-submit-correlation',
      metadata: {},
    }] }));
    businessApplicationService.decide.and.returnValue(of({
      data: {
        ...application,
        status: 'APPROVED',
        decisionReason: 'Business information verified',
        version: 2,
      },
    }));
    businessApplicationService.getAdminApplication.and.returnValues(
      of({ data: application }),
      of({ data: { ...application, status: 'APPROVED', version: 2 } })
    );

    await TestBed.configureTestingModule({
      imports: [AdminBusinessApplicationDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: application.id }) } },
        },
        { provide: BusinessApplicationService, useValue: businessApplicationService },
        { provide: ToastService, useValue: toastService },
        { provide: AdminService, useValue: adminService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminBusinessApplicationDetailComponent);
    component = fixture.componentInstance;
  });

  it('loads and displays the selected business application', () => {
    fixture.detectChanges();

    expect(businessApplicationService.getAdminApplication).toHaveBeenCalledWith(application.id);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Detail Review LLC');
    expect(text).toContain('owner@example.com');
    expect(text).toContain('Irvine, CA');
    expect(text).toContain('PENDING_VERIFICATION');
    expect(text).toContain('Version 1');
    expect(text).toContain('Business application submitted');
    expect(text).toContain('Application Owner');
    expect(text).toContain('business-submit-correlation');
    const submittedTime = fixture.nativeElement.querySelector(`time[datetime="${application.submittedAt}"]`) as HTMLTimeElement;
    expect(submittedTime).toBeTruthy();
    expect(submittedTime.title).toBe(application.submittedAt!);
    expect(submittedTime.textContent).not.toContain('T12:00:00Z');
  });

  it('does not present decision controls without decide permission', () => {
    adminService.hasPermission.and.returnValue(false);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="decision-submit"]')).toBeNull();
  });

  it('shows an error state when the detail cannot be loaded', () => {
    businessApplicationService.getAdminApplication.and.returnValue(throwError(() => new Error('not found')));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business application could not be loaded.');
  });

  it('lets admins retry loading the detail after an error', () => {
    businessApplicationService.getAdminApplication.and.returnValues(
      throwError(() => new Error('not found')),
      of({ data: application })
    );

    fixture.detectChanges();
    const retryButton = fixture.nativeElement.querySelector('[data-testid="detail-retry"]') as HTMLButtonElement;
    retryButton.click();
    fixture.detectChanges();

    expect(businessApplicationService.getAdminApplication).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Detail Review LLC');
  });

  it('requires a decision reason before calling the API', () => {
    fixture.detectChanges();
    component.decision = 'APPROVE';
    component.reason = ' ';

    component.submitDecision();
    fixture.detectChanges();

    expect(businessApplicationService.decide).not.toHaveBeenCalled();
    expect(component.decisionErrorMsg()).toBe('Decision reason is required.');
    const reasonField = fixture.nativeElement.querySelector('[data-testid="decision-reason"]') as HTMLTextAreaElement;
    expect(reasonField.getAttribute('aria-invalid')).toBe('true');
    expect(reasonField.getAttribute('aria-describedby')).toBe('decision-reason-error');
    expect(fixture.nativeElement.querySelector('#decision-reason-error')?.getAttribute('role')).toBe('alert');
    expect(fixture.nativeElement.textContent).toContain('Decision reason is required.');
  });

  it('shows saving state while a decision is in progress', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    businessApplicationService.decide.and.returnValue(NEVER);
    fixture.detectChanges();
    component.reason = 'Business information verified';

    component.submitDecision();
    fixture.detectChanges();

    const submitButton = fixture.nativeElement.querySelector('[data-testid="decision-submit"]') as HTMLButtonElement;
    expect(submitButton.disabled).toBeTrue();
    expect(submitButton.textContent).toContain('Saving decision');
  });

  it('submits a confirmed decision with the loaded application version', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    fixture.detectChanges();
    component.decision = 'APPROVE';
    component.reason = ' Business information verified ';

    component.submitDecision();

    expect(window.confirm).toHaveBeenCalled();
    expect(businessApplicationService.decide).toHaveBeenCalledOnceWith(application.id, {
      decision: 'APPROVE',
      reason: 'Business information verified',
    }, 1);
    expect(component.application()?.status).toBe('APPROVED');
    expect(toastService.success).toHaveBeenCalledWith('Business application decision saved.');
  });

  it('does not submit when the admin cancels confirmation', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    fixture.detectChanges();
    component.decision = 'REJECT';
    component.reason = 'Not enough verification evidence';

    component.submitDecision();

    expect(businessApplicationService.decide).not.toHaveBeenCalled();
  });

  it('shows stale version guidance when decision conflicts', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    businessApplicationService.decide.and.returnValue(throwError(() => ({ status: 409 })));
    fixture.detectChanges();
    component.decision = 'REJECT';
    component.reason = 'Not enough verification evidence';

    component.submitDecision();

    expect(component.decisionErrorMsg()).toBe('The application changed. Refresh and try again.');
  });
});
