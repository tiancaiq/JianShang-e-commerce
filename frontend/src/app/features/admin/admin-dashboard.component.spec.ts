import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminService } from '../../core/services/admin.service';
import { AdminDashboardComponent } from './admin-dashboard.component';

describe('AdminDashboardComponent', () => {
  let fixture: ComponentFixture<AdminDashboardComponent>;
  let adminService: jasmine.SpyObj<AdminService>;

  beforeEach(async () => {
    adminService = jasmine.createSpyObj<AdminService>('AdminService', ['getDashboardSummary']);

    await TestBed.configureTestingModule({
      imports: [AdminDashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminService, useValue: adminService },
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
    expect(text).toContain('Listing reviews');
    expect(text).toContain('Assigned to me');
    expect(text).toContain('3');
    expect(text).toContain('7');
    expect(text).toContain('0');
  });

  it('renders dashboard error state', () => {
    adminService.getDashboardSummary.and.returnValue(throwError(() => ({ status: 500 })));

    fixture = TestBed.createComponent(AdminDashboardComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Admin dashboard could not be loaded.');
  });
});
