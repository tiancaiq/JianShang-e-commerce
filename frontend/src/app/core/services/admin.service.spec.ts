import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminService } from './admin.service';

describe('AdminService', () => {
  let service: AdminService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(AdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads current platform admin through the gateway', () => {
    service.getCurrentAdmin().subscribe(response => {
      expect(response.data.userId).toBe('01ADMIN');
      expect(response.data.role).toBe('PLATFORM_ADMIN');
      expect(response.data.roles).toEqual(['SUPER_ADMIN']);
      expect(response.data.permissions).toContain('admin.listing.moderation.resolve');
      expect(response.data.accountState).toBe('ACTIVE');
    });

    const request = httpMock.expectOne('/api/v1/admin/me');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: { userId: '01ADMIN', role: 'PLATFORM_ADMIN' } });
  });

  it('loads dashboard summary from admin endpoints', () => {
    service.getDashboardSummary().subscribe(summary => {
      expect(summary).toEqual({
        pendingBusinessApplications: 2,
        pendingListingReviews: 5,
        assignedToMeListingReviews: 0,
      });
    });

    const business = httpMock.expectOne('/api/v1/admin/dashboard-summary');
    expect(business.request.method).toBe('GET');
    expect(business.request.withCredentials).toBeTrue();
    business.flush({ data: { pendingBusinessApplications: 2 } });

    const listings = httpMock.expectOne('/api/v1/admin/listings/moderation/summary');
    expect(listings.request.method).toBe('GET');
    expect(listings.request.withCredentials).toBeTrue();
    listings.flush({ pendingListingReviews: 5, assignedToMeListingReviews: 0 });
  });
});
