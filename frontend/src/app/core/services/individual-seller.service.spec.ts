import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IndividualSellerService } from './individual-seller.service';
import { IndividualSellerProfile } from '../models/individual-seller.model';

describe('IndividualSellerService', () => {
  let service: IndividualSellerService;
  let httpMock: HttpTestingController;

  const profile: IndividualSellerProfile = {
    id: '01JY0000000000000000000001',
    userId: '01JY0000000000000000000000',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    completedSalesCount: 0,
    termsVersion: '2026-01',
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

    service = TestBed.inject(IndividualSellerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('activates individual seller through the gateway without browser tokens', () => {
    service.activate({
      publicCity: 'Irvine',
      publicRegion: 'CA',
      termsVersion: '2026-01',
    }).subscribe(response => {
      expect(response.data).toEqual(profile);
    });

    const request = httpMock.expectOne('http://localhost:9000/api/v1/individual-seller/activation');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      publicCity: 'Irvine',
      publicRegion: 'CA',
      termsVersion: '2026-01',
    });
    request.flush({ data: profile });
  });

  it('loads current individual seller profile through the gateway', () => {
    service.getMe().subscribe(response => {
      expect(response.data).toEqual(profile);
    });

    const request = httpMock.expectOne('http://localhost:9000/api/v1/individual-seller/me');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: profile });
  });
});
