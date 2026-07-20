import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CategoryGuidanceService } from './category-guidance.service';

describe('CategoryGuidanceService', () => {
  let service: CategoryGuidanceService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CategoryGuidanceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('publishes with the current immutable source version', () => {
    service.publish('category-1', 'en', '3', {
      title: 'Buying electronics',
      body: 'Check the model.',
    }).subscribe();

    const request = httpMock.expectOne(
      req => req.url.endsWith('/api/v1/admin/categories/category-1/guidance/en/versions'),
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('3');
    expect(request.request.withCredentials).toBeTrue();
    request.flush({});
  });

  it('retires with If-Match and loads bounded history', () => {
    service.retire('category-1', 'en-us', '4').subscribe();
    const retire = httpMock.expectOne(
      req => req.url.endsWith('/api/v1/admin/categories/category-1/guidance/en-us/retire'),
    );
    expect(retire.request.headers.get('If-Match')).toBe('4');
    retire.flush({});

    service.history('category-1', 'en-us', 'opaque', 20).subscribe();
    const history = httpMock.expectOne(
      req => req.url.endsWith('/guidance/en-us/versions')
        && req.params.get('cursor') === 'opaque'
        && req.params.get('limit') === '20',
    );
    expect(history.request.method).toBe('GET');
    history.flush({ items: [], nextCursor: null, hasMore: false, exportWatermark: null });
  });
});
