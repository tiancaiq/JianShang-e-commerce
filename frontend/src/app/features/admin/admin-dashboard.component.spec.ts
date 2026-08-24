import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminService } from '../../core/services/admin.service';
import { SupportService } from '../../core/services/support.service';
import { AdminDashboardComponent } from './admin-dashboard.component';

describe('AdminDashboardComponent', () => {
  let fixture: ComponentFixture<AdminDashboardComponent>;
  let adminService: jasmine.SpyObj<AdminService>;
  let supportService: jasmine.SpyObj<SupportService>;

  beforeEach(async () => {
    adminService = jasmine.createSpyObj<AdminService>(
      'AdminService', ['getDashboardSummary', 'hasPermission']);
    adminService.hasPermission.and.returnValue(true);
    supportService = jasmine.createSpyObj<SupportService>('SupportService', ['search']);
    supportService.search.and.returnValue(of({
      items: [],
      page: 0,
      size: 1,
      totalElements: 4,
      totalPages: 4,
      sort: 'updatedAt,desc',
    }));

    await TestBed.configureTestingModule({
      imports: [AdminDashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminService, useValue: adminService },
        { provide: SupportService, useValue: supportService },
      ],
    }).compileComponents();
  });

  it('renders admin dashboard counts', () => {
    adminService.getDashboardSummary.and.returnValue(of({
      pendingBusinessApplications: 3,
      pendingListingReviews: 7,
      assignedToMeListingReviews: 0,
    }));

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Business applications');
    expect(text).toContain('Business administration');
    expect(text).toContain('Support inbox');
    expect(text).toContain('Unassigned tickets');
    expect(text).toContain('Listing reviews');
    expect(text).toContain('Assigned to me');
    expect(text).toContain('3');
    expect(text).toContain('7');
    expect(text).toContain('4');
    expect(text).toContain('0');
    expect(supportService.search).toHaveBeenCalledWith({
      assignment: 'UNASSIGNED',
      page: 0,
      size: 1,
      sort: 'updatedAt,desc',
    });
  });

  it('renders dashboard error state', () => {
    adminService.getDashboardSummary.and.returnValue(throwError(() => ({ status: 500 })));

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Admin dashboard could not be loaded.');
  });

  it('does not render workflow links without their read permission', () => {
    adminService.getDashboardSummary.and.returnValue(of({
      pendingBusinessApplications: 3,
      pendingListingReviews: 7,
      assignedToMeListingReviews: 1,
    }));
    adminService.hasPermission.and.callFake(permission =>
      permission === 'admin.business.application.read');

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Business applications');
    expect(text).not.toContain('Business administration');
    expect(text).not.toContain('Support inbox');
    expect(text).not.toContain('Listing reviews');
    expect(text).not.toContain('Assigned to me');
    expect(supportService.search).not.toHaveBeenCalled();
  });

  it('shows business administration only with business read permission', () => {
    adminService.getDashboardSummary.and.returnValue(of({
      pendingBusinessApplications: 0,
      pendingListingReviews: 0,
      assignedToMeListingReviews: 0,
    }));
    adminService.hasPermission.and.callFake(permission => permission === 'admin.business.read');

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a[href="/admin/businesses"]');
    expect(link).not.toBeNull();
    expect(link.textContent).toContain('Business administration');
    expect(fixture.nativeElement.textContent).not.toContain('Business applications');
  });

  it('keeps the Support inbox discoverable when its count is unavailable', () => {
    adminService.getDashboardSummary.and.returnValue(of({
      pendingBusinessApplications: 0,
      pendingListingReviews: 0,
      assignedToMeListingReviews: 0,
    }));
    adminService.hasPermission.and.callFake(permission => permission === 'admin.support.read');
    supportService.search.and.returnValue(throwError(() => ({ status: 503 })));

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a[href="/admin/support"]');
    expect(link).not.toBeNull();
    expect(link.textContent).toContain('Support inbox');
    expect(link.textContent).toContain('Count temporarily unavailable');
  });
});
