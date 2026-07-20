import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';
import { BusinessApplicationComponent } from './business-application.component';

describe('BusinessApplicationComponent', () => {
  let fixture: ComponentFixture<BusinessApplicationComponent>;
  let component: BusinessApplicationComponent;
  let businessApplicationService: jasmine.SpyObj<BusinessApplicationService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: Router;

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
    status: 'DRAFT',
    submittedAt: null,
    reviewerUserId: null,
    approvedBusinessId: null,
    decisionReason: null,
    decidedAt: null,
    version: 0,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(async () => {
    businessApplicationService = jasmine.createSpyObj<BusinessApplicationService>(
      'BusinessApplicationService',
      ['getCurrentApplication', 'createDraft', 'submit']
    );
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);

    businessApplicationService.getCurrentApplication.and.returnValue(of(null));
    businessApplicationService.createDraft.and.returnValue(of(application));
    businessApplicationService.submit.and.returnValue(of({
      ...application,
      status: 'PENDING_VERIFICATION',
      submittedAt: '2026-06-16T13:00:00Z',
      reviewerUserId: null,
      approvedBusinessId: null,
      decisionReason: null,
      decidedAt: null,
      version: 1,
    }));

    await TestBed.configureTestingModule({
      imports: [BusinessApplicationComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessApplicationService, useValue: businessApplicationService },
        { provide: ToastService, useValue: toastService },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);

    fixture = TestBed.createComponent(BusinessApplicationComponent);
    component = fixture.componentInstance;
  });

  it('creates a draft with normalized allowed fields', () => {
    fixture.detectChanges();
    fillValidForm();

    component.createDraft();

    expect(businessApplicationService.createDraft).toHaveBeenCalledOnceWith({
      legalName: 'Acme Trading LLC',
      businessType: 'LLC',
      country: 'US',
      contactEmail: 'owner@example.com',
      contactPhone: '+19495551234',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      websiteUrl: 'https://example.com',
      description: 'Local seller',
    });
    expect(component.application()).toEqual(application);
    expect(toastService.success).toHaveBeenCalledWith('Business application draft saved.');
  });

  it('shows approved business account instead of a new application form', () => {
    businessApplicationService.getCurrentApplication.and.returnValue(of({
      ...application,
      status: 'APPROVED',
      approvedBusinessId: '01JY0000000000000000000999',
      decisionReason: 'Business information verified',
      decidedAt: '2026-06-17T12:00:00Z',
    }));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Business account approved');
    expect(host.textContent).toContain('This user already has an approved business account');
    expect(host.textContent).toContain('Store profile');
    expect(host.querySelector('form')).toBeNull();
    expect(businessApplicationService.createDraft).not.toHaveBeenCalled();
  });

  it('shows pending application state without showing a new application form', () => {
    businessApplicationService.getCurrentApplication.and.returnValue(of({
      ...application,
      status: 'PENDING_VERIFICATION',
      submittedAt: '2026-06-16T13:00:00Z',
      version: 1,
    }));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Application pending');
    expect(host.textContent).toContain('New applications are disabled while review is active');
    expect(host.textContent).toContain('Draft');
    expect(host.textContent).toContain('Submitted');
    expect(host.textContent).toContain('Verification');
    expect(host.textContent).toContain('Admin review');
    expect(host.textContent).toContain('Approved');
    expect(host.querySelector('[aria-current="step"]')?.textContent).toContain('Verification');
    expect(host.querySelector('form')).toBeNull();
    expect(host.textContent).not.toContain('Start new application');
  });

  it('shows rejected reason and can reveal a new application form', () => {
    businessApplicationService.getCurrentApplication.and.returnValue(of({
      ...application,
      status: 'REJECTED',
      decisionReason: 'Unable to verify business information',
      decidedAt: '2026-06-17T12:00:00Z',
      version: 2,
    }));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Application rejected');
    expect(host.textContent).toContain('Unable to verify business information');
    expect(host.textContent).toContain('Rejected');
    expect(host.querySelector('.timeline-rejected')?.textContent).toContain('Rejected');
    expect(host.querySelector('form')).toBeNull();

    const startButton = Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Start new application') as HTMLButtonElement;
    startButton.click();
    fixture.detectChanges();

    expect(component.application()).toBeNull();
    expect(host.querySelector('form')).not.toBeNull();
  });

  it('validates contact email before calling the API', () => {
    fixture.detectChanges();
    fillValidForm();
    component.contactEmail = 'not-email';

    component.createDraft();

    expect(businessApplicationService.createDraft).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Contact email is invalid.');
  });

  it('composes contact phone from selected country code and local number', () => {
    fixture.detectChanges();
    fillValidForm();
    component.contactPhoneCountryCode = '+44';
    component.contactPhoneNumber = ' 20 7946 0958 ';

    component.createDraft();

    expect(businessApplicationService.createDraft).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      contactPhone: '+442079460958',
    }));
  });

  it('submits the saved draft with current version', () => {
    fixture.detectChanges();
    component.application.set(application);

    component.submitApplication();

    expect(businessApplicationService.submit).toHaveBeenCalledOnceWith(application.id, 0);
    expect(component.application()?.status).toBe('PENDING_VERIFICATION');
    expect(toastService.success).toHaveBeenCalledWith('Business application submitted.');
  });

  it('shows stale submit conflicts', () => {
    businessApplicationService.submit.and.returnValue(throwError(() => ({ status: 409 })));
    fixture.detectChanges();
    component.application.set(application);

    component.submitApplication();

    expect(component.errorMsg()).toBe('Business application changed. Refresh and try again.');
  });

  it('redirects unauthorized create to login', () => {
    businessApplicationService.createDraft.and.returnValue(throwError(() => ({ status: 401 })));
    fixture.detectChanges();
    fillValidForm();

    component.createDraft();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/login']);
  });

  it('shows duplicate draft conflict', () => {
    businessApplicationService.createDraft.and.returnValue(throwError(() => ({ status: 409 })));
    fixture.detectChanges();
    fillValidForm();

    component.createDraft();

    expect(component.errorMsg()).toBe('A business application or approved business account already exists.');
  });

  function fillValidForm(): void {
    component.legalName = '  Acme Trading LLC  ';
    component.businessType = 'llc';
    component.country = 'us';
    component.contactEmail = 'owner@example.com';
    component.contactPhoneCountryCode = '+1';
    component.contactPhoneNumber = '9495551234';
    component.publicCity = ' Irvine ';
    component.publicRegion = ' CA ';
    component.websiteUrl = ' https://example.com ';
    component.description = ' Local seller ';
  }
});
