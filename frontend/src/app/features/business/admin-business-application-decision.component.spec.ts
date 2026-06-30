import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminBusinessApplicationDecisionComponent } from './admin-business-application-decision.component';

describe('AdminBusinessApplicationDecisionComponent', () => {
  let fixture: ComponentFixture<AdminBusinessApplicationDecisionComponent>;
  let component: AdminBusinessApplicationDecisionComponent;
  let businessApplicationService: jasmine.SpyObj<BusinessApplicationService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

  const application: BusinessApplication = {
    id: '01JY0000000000000000000002',
    applicantUserId: '01JY0000000000000000000000',
    legalName: 'Acme Trading LLC',
    businessType: 'LLC',
    country: 'US',
    contactEmail: 'owner@example.com',
    contactPhone: '+19495551234',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    websiteUrl: 'https://example.com',
    description: 'Local seller',
    status: 'APPROVED',
    submittedAt: '2026-06-16T13:00:00Z',
    reviewerUserId: '01JY0000000000000000000001',
    approvedBusinessId: '01JY0000000000000000000003',
    decisionReason: 'Business information verified',
    decidedAt: '2026-06-16T14:00:00Z',
    version: 2,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T14:00:00Z',
  };

  beforeEach(async () => {
    businessApplicationService = jasmine.createSpyObj<BusinessApplicationService>('BusinessApplicationService', ['decide']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    businessApplicationService.decide.and.returnValue(of({ data: application }));

    await TestBed.configureTestingModule({
      imports: [AdminBusinessApplicationDecisionComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: BusinessApplicationService, useValue: businessApplicationService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminBusinessApplicationDecisionComponent);
    component = fixture.componentInstance;
  });

  it('saves an admin approve decision', () => {
    fixture.detectChanges();
    component.applicationId = ' 01JY0000000000000000000002 ';
    component.decision = 'APPROVE';
    component.reason = ' Business information verified ';

    component.submitDecision();

    expect(businessApplicationService.decide).toHaveBeenCalledOnceWith('01JY0000000000000000000002', {
      decision: 'APPROVE',
      reason: 'Business information verified',
    });
    expect(component.application()).toEqual(application);
    expect(toastService.success).toHaveBeenCalledWith('Business application decision saved.');
  });

  it('requires a decision reason before calling the API', () => {
    fixture.detectChanges();
    component.applicationId = '01JY0000000000000000000002';
    component.reason = ' ';

    component.submitDecision();

    expect(businessApplicationService.decide).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Decision reason is required.');
  });

  it('shows missing platform admin access', () => {
    businessApplicationService.decide.and.returnValue(throwError(() => ({ status: 403 })));
    fixture.detectChanges();
    component.applicationId = '01JY0000000000000000000002';
    component.reason = 'Trying without role';

    component.submitDecision();

    expect(component.errorMsg()).toBe('You need platform admin access for this action.');
  });
});
