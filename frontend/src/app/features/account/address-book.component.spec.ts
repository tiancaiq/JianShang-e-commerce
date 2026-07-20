import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BuyerAddress } from '../../core/models/address.model';
import { AddressBookService } from '../../core/services/address-book.service';
import { AuthService } from '../../core/services/auth.service';
import { AddressBookComponent } from './address-book.component';

describe('AddressBookComponent', () => {
  let fixture: ComponentFixture<AddressBookComponent>;
  let component: AddressBookComponent;
  let addressBookService: jasmine.SpyObj<AddressBookService>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: Router;
  let navigateSpy: jasmine.Spy;

  const home: BuyerAddress = {
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
    version: 0,
    createdAt: '2026-07-19T00:00:00Z',
    updatedAt: '2026-07-19T00:00:00Z',
  };
  const office: BuyerAddress = {
    ...home,
    id: '01ADDRESS000000000000000002',
    label: 'Office',
    line1: '200 Market Street',
    city: 'Tustin',
    isDefault: false,
    version: 1,
  };

  beforeEach(async () => {
    addressBookService = jasmine.createSpyObj<AddressBookService>(
      'AddressBookService',
      ['list', 'create', 'patch', 'delete', 'setDefault'],
    );
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['clearUser']);
    addressBookService.list.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [AddressBookComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AddressBookService, useValue: addressBookService },
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(AddressBookComponent);
    component = fixture.componentInstance;
  });

  it('renders a usable empty state', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('No saved addresses');
    expect(host.textContent).toContain('0 of 20 saved');
    expect(host.querySelector('a[href="/account"]')).toBeTruthy();
  });

  it('creates a normalized first address and reloads it as default', () => {
    addressBookService.list.and.returnValues(of([]), of([home]));
    addressBookService.create.and.returnValue(of(home));
    fixture.detectChanges();

    component.startAdd();
    component.form = {
      label: ' Home ',
      recipientName: ' Alex Buyer ',
      phone: ' +19495550123 ',
      line1: ' 100 Main Street ',
      line2: ' ',
      city: ' Irvine ',
      region: ' CA ',
      postalCode: ' 92618 ',
      countryCode: ' us ',
    };
    component.save();
    fixture.detectChanges();

    expect(addressBookService.create).toHaveBeenCalledOnceWith({
      label: 'Home',
      recipientName: 'Alex Buyer',
      phone: '+19495550123',
      line1: '100 Main Street',
      line2: null,
      city: 'Irvine',
      region: 'CA',
      postalCode: '92618',
      countryCode: 'US',
    });
    expect(component.addresses()).toEqual([home]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Default');
  });

  it('edits with the current version and reloads after a conflict', () => {
    const refreshedOffice = { ...office, city: 'Costa Mesa', version: 2 };
    addressBookService.list.and.returnValues(of([office]), of([refreshedOffice]));
    addressBookService.patch.and.returnValue(throwError(() => ({
      status: 409,
      error: { error: { code: 'ADDRESS_VERSION_CONFLICT' } },
    })));
    fixture.detectChanges();

    component.startEdit(office);
    component.form.city = 'Newport Beach';
    component.save();

    expect(addressBookService.patch).toHaveBeenCalledWith(
      office.id,
      1,
      jasmine.objectContaining({ city: 'Newport Beach' }),
    );
    expect(component.formError()).toContain('latest version');
    expect(component.addresses()).toEqual([refreshedOffice]);
    expect(addressBookService.list).toHaveBeenCalledTimes(2);
  });

  it('sets a default and confirms deletion with current versions', () => {
    const officeDefault = { ...office, isDefault: true, version: 2 };
    const homeNonDefault = { ...home, isDefault: false, version: 1 };
    addressBookService.list.and.returnValues(
      of([home, office]),
      of([officeDefault, homeNonDefault]),
      of([home]),
    );
    addressBookService.setDefault.and.returnValue(of(officeDefault));
    addressBookService.delete.and.returnValue(of(void 0));
    fixture.detectChanges();

    component.setDefault(office);
    expect(addressBookService.setDefault).toHaveBeenCalledOnceWith(office.id, 1);
    expect(component.addresses()[0]).toEqual(officeDefault);

    component.requestDelete(officeDefault);
    expect(component.deleteTargetId()).toBe(office.id);
    component.confirmDelete(officeDefault);
    expect(addressBookService.delete).toHaveBeenCalledOnceWith(office.id, 2);
    expect(component.addresses()).toEqual([home]);
  });

  it('redirects an unauthorized address load to marketplace login', () => {
    addressBookService.list.and.returnValue(throwError(() => ({ status: 401 })));

    fixture.detectChanges();

    expect(authService.clearUser).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledOnceWith(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: '/account/addresses',
      },
    });
  });
});
