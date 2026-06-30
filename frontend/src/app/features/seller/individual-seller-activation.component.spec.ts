import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { IndividualSellerProfile } from '../../core/models/individual-seller.model';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { ToastService } from '../../core/services/toast.service';
import { IndividualSellerActivationComponent } from './individual-seller-activation.component';

describe('IndividualSellerActivationComponent', () => {
  let fixture: ComponentFixture<IndividualSellerActivationComponent>;
  let component: IndividualSellerActivationComponent;
  let individualSellerService: jasmine.SpyObj<IndividualSellerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;

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

  beforeEach(async () => {
    individualSellerService = jasmine.createSpyObj<IndividualSellerService>('IndividualSellerService', ['getMe', 'activate']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    individualSellerService.getMe.and.returnValue(throwError(() => ({ status: 404 })));
    individualSellerService.activate.and.returnValue(of(profile));

    await TestBed.configureTestingModule({
      imports: [IndividualSellerActivationComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: IndividualSellerService, useValue: individualSellerService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(IndividualSellerActivationComponent);
    component = fixture.componentInstance;
  });

  it('loads active individual seller profile on init', () => {
    individualSellerService.getMe.and.returnValue(of(profile));

    fixture.detectChanges();

    expect(individualSellerService.getMe).toHaveBeenCalled();
    expect(component.profile()).toEqual(profile);
  });

  it('submits only public location and the accepted terms version', () => {
    fixture.detectChanges();
    component.publicCity = '  Irvine  ';
    component.publicRegion = ' CA ';
    component.acceptedTerms = true;

    component.activate();

    expect(individualSellerService.activate).toHaveBeenCalledOnceWith({
      publicCity: 'Irvine',
      publicRegion: 'CA',
      termsVersion: '2026-01',
    });
    expect(component.profile()).toEqual(profile);
    expect(toastService.success).toHaveBeenCalledWith('Individual seller profile activated.');
  });

  it('requires accepting individual-selling terms before calling the API', () => {
    fixture.detectChanges();
    component.publicCity = 'Irvine';
    component.publicRegion = 'CA';
    component.acceptedTerms = false;

    component.activate();

    expect(individualSellerService.activate).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Accept the individual-selling terms to continue.');
  });

  it('requires city and region before calling the API', () => {
    fixture.detectChanges();
    component.acceptedTerms = true;
    component.publicCity = '';
    component.publicRegion = 'CA';

    component.activate();

    expect(individualSellerService.activate).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Public city is required.');
  });

  it('redirects unauthorized activation to login', () => {
    individualSellerService.activate.and.returnValue(throwError(() => ({ status: 401 })));
    fixture.detectChanges();
    component.publicCity = 'Irvine';
    component.publicRegion = 'CA';
    component.acceptedTerms = true;

    component.activate();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/login']);
  });

  it('shows duplicate activation conflicts', () => {
    individualSellerService.activate.and.returnValue(throwError(() => ({ status: 409 })));
    fixture.detectChanges();
    component.publicCity = 'Irvine';
    component.publicRegion = 'CA';
    component.acceptedTerms = true;

    component.activate();

    expect(component.errorMsg()).toBe('Individual seller profile is already active.');
  });
});
