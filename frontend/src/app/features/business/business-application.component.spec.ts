import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
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
    businessApplicationService = jasmine.createSpyObj<BusinessApplicationService>('BusinessApplicationService', ['createDraft', 'submit']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

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
        { provide: BusinessApplicationService, useValue: businessApplicationService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

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

    expect(component.errorMsg()).toBe('A draft business application already exists.');
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
