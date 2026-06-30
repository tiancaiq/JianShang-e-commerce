import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, RouterLink } from '@angular/router';
import { By } from '@angular/platform-browser';
import { NEVER, of, throwError } from 'rxjs';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { AdminBusinessApplicationQueueComponent } from './admin-business-application-queue.component';

describe('AdminBusinessApplicationQueueComponent', () => {
  let fixture: ComponentFixture<AdminBusinessApplicationQueueComponent>;
  let component: AdminBusinessApplicationQueueComponent;
  let businessApplicationService: jasmine.SpyObj<BusinessApplicationService>;

  const pendingApplication: BusinessApplication = {
    id: '01JY0000000000000000000001',
    applicantUserId: '01JY0000000000000000000000',
    legalName: 'Queue Pending LLC',
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
      ['listAdminReviewQueue']
    );
    businessApplicationService.listAdminReviewQueue.and.returnValue(of({ data: [pendingApplication] }));

    await TestBed.configureTestingModule({
      imports: [AdminBusinessApplicationQueueComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessApplicationService, useValue: businessApplicationService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminBusinessApplicationQueueComponent);
    component = fixture.componentInstance;
  });

  it('loads and displays reviewable business applications', () => {
    fixture.detectChanges();

    expect(businessApplicationService.listAdminReviewQueue).toHaveBeenCalledWith(null);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Queue Pending LLC');
    expect(text).toContain('Pending verification');
    expect(text).not.toContain('2026-06-16T12:00:00Z');
    expect(text).toContain('owner@example.com');
  });

  it('links queue rows to the review detail route', () => {
    fixture.detectChanges();

    const rowLink = fixture.debugElement.queryAll(By.directive(RouterLink))[0];
    expect(rowLink.injector.get(RouterLink).href).toBe(`/admin/business-applications/${pendingApplication.id}`);
  });

  it('reloads the queue when status filter changes', () => {
    fixture.detectChanges();
    businessApplicationService.listAdminReviewQueue.calls.reset();

    component.statusFilter = 'UNDER_REVIEW';
    component.loadQueue();

    expect(businessApplicationService.listAdminReviewQueue).toHaveBeenCalledWith('UNDER_REVIEW');
  });

  it('shows an error state when the queue cannot be loaded', () => {
    businessApplicationService.listAdminReviewQueue.and.returnValue(throwError(() => new Error('forbidden')));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Business applications could not be loaded.');
  });

  it('lets admins retry loading the queue after an error', () => {
    businessApplicationService.listAdminReviewQueue.and.returnValues(
      throwError(() => new Error('forbidden')),
      of({ data: [pendingApplication] })
    );

    fixture.detectChanges();
    const retryButton = fixture.nativeElement.querySelector('[data-testid="queue-retry"]') as HTMLButtonElement;
    retryButton.click();
    fixture.detectChanges();

    expect(businessApplicationService.listAdminReviewQueue).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Queue Pending LLC');
  });

  it('keeps the status filter disabled while the queue is loading', () => {
    businessApplicationService.listAdminReviewQueue.and.returnValue(NEVER);

    fixture.detectChanges();

    const filter = fixture.nativeElement.querySelector('select[name="statusFilter"]') as HTMLSelectElement;
    expect(filter.disabled).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Loading business applications...');
  });
});
