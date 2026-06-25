import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BusinessApplication } from '../models/business-application.model';
import { BusinessApplicationService } from './business-application.service';

describe('BusinessApplicationService', () => {
  let service: BusinessApplicationService;
  let httpMock: HttpTestingController;

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

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(BusinessApplicationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('creates a draft business application through the gateway', () => {
    const body = {
      legalName: 'Acme Trading LLC',
      businessType: 'LLC',
      country: 'US',
      contactEmail: 'owner@example.com',
      contactPhone: '+19495551234',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      websiteUrl: 'https://example.com',
      description: 'Local seller',
    };

    service.createDraft(body).subscribe(response => {
      expect(response.data).toEqual(application);
    });

    const request = httpMock.expectOne('/api/v1/business-applications');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual(body);
    request.flush({ data: application });
  });

  it('updates a draft with If-Match version', () => {
    service.updateDraft(application.id, {
      legalName: 'Acme Trading LLC',
      businessType: 'LLC',
      country: 'US',
      contactEmail: 'owner@example.com',
      contactPhone: '+19495551234',
      publicCity: 'Irvine',
      publicRegion: 'CA',
      websiteUrl: 'https://example.com',
      description: 'Local seller',
    }, 0).subscribe(response => {
      expect(response.data).toEqual(application);
    });

    const request = httpMock.expectOne(`/api/v1/business-applications/${application.id}`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('If-Match')).toBe('0');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: application });
  });

  it('submits a draft with If-Match version', () => {
    service.submit(application.id, 0).subscribe(response => {
      expect(response.data).toEqual(application);
    });

    const request = httpMock.expectOne(`/api/v1/business-applications/${application.id}/submit`);
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('If-Match')).toBe('0');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toBeNull();
    request.flush({ data: application });
  });

  it('sends an admin decision through the gateway', () => {
    service.decide(application.id, {
      decision: 'APPROVE',
      reason: 'Business information verified',
    }).subscribe(response => {
      expect(response.data).toEqual(application);
    });

    const request = httpMock.expectOne(
      `/api/v1/admin/business-applications/${application.id}/decision`
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      decision: 'APPROVE',
      reason: 'Business information verified',
    });
    request.flush({ data: application });
  });
});
