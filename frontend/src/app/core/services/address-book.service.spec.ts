import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AddressBookService } from './address-book.service';

describe('AddressBookService', () => {
  let service: AddressBookService;
  let httpMock: HttpTestingController;

  const address = {
    id: '01ADDRESS000000000000000001',
    label: 'Home',
    recipientName: 'Alex Buyer',
    phone: '+19495550123',
    line1: '100 Main Street',
    line2: null,
    city: 'Irvine',
    region: 'CA',
    postalCode: '92618',
    countryCode: 'US',
    isDefault: true,
    version: 2,
    createdAt: '2026-07-19T00:00:00Z',
    updatedAt: '2026-07-19T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(AddressBookService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists and creates addresses through the current-user route', () => {
    service.list().subscribe(result => expect(result).toEqual([address]));
    const list = httpMock.expectOne('/api/v1/users/me/addresses');
    expect(list.request.method).toBe('GET');
    expect(list.request.withCredentials).toBeTrue();
    list.flush({ data: [address] });

    const createBody = {
      label: 'Home',
      recipientName: 'Alex Buyer',
      phone: '+19495550123',
      line1: '100 Main Street',
      city: 'Irvine',
      region: 'CA',
      postalCode: '92618',
      countryCode: 'US',
    };
    service.create(createBody).subscribe(result => expect(result).toEqual(address));
    const create = httpMock.expectOne('/api/v1/users/me/addresses');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(createBody);
    expect(create.request.withCredentials).toBeTrue();
    create.flush({ data: address });
  });

  it('uses the current version for patch, default, and delete commands', () => {
    service.patch(address.id, 2, { city: 'Tustin' }).subscribe();
    const patch = httpMock.expectOne(`/api/v1/users/me/addresses/${address.id}`);
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ city: 'Tustin' });
    expect(patch.request.headers.get('If-Match')).toBe('2');
    patch.flush({ data: { ...address, city: 'Tustin', version: 3 } });

    service.setDefault(address.id, 3).subscribe();
    const makeDefault = httpMock.expectOne(`/api/v1/users/me/addresses/${address.id}/default`);
    expect(makeDefault.request.method).toBe('POST');
    expect(makeDefault.request.body).toBeNull();
    expect(makeDefault.request.headers.get('If-Match')).toBe('3');
    makeDefault.flush({ data: { ...address, version: 4 } });

    service.delete(address.id, 4).subscribe();
    const remove = httpMock.expectOne(`/api/v1/users/me/addresses/${address.id}`);
    expect(remove.request.method).toBe('DELETE');
    expect(remove.request.headers.get('If-Match')).toBe('4');
    remove.flush(null);
  });
});
