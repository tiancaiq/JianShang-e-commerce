import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessStore } from '../../core/models/business-store.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ToastService } from '../../core/services/toast.service';
import { BusinessStoreComponent } from './business-store.component';

describe('BusinessStoreComponent', () => {
  let fixture: ComponentFixture<BusinessStoreComponent>;
  let component: BusinessStoreComponent;
  let businessStoreService: jasmine.SpyObj<BusinessStoreService>;
  let toastService: jasmine.SpyObj<ToastService>;

  const store: BusinessStore = {
    id: '01JY0000000000000000000100',
    businessId: '01JY0000000000000000000003',
    slug: 'acme-trading',
    name: 'Acme Trading',
    description: 'Local goods',
    logoUrl: null,
    bannerUrl: null,
    supportEmail: 'help@example.com',
    supportPhone: '+19495550000',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-07-08T12:00:00Z',
    updatedAt: '2026-07-08T12:00:00Z',
  };

  beforeEach(async () => {
    businessStoreService = jasmine.createSpyObj<BusinessStoreService>(
      'BusinessStoreService',
      ['getBusinessStore', 'updateBusinessStore']
    );
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    businessStoreService.getBusinessStore.and.returnValue(of(store));
    businessStoreService.updateBusinessStore.and.returnValue(of({ ...store, version: 2 }));

    await TestBed.configureTestingModule({
      imports: [BusinessStoreComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ businessId: store.businessId }),
            },
          },
        },
        { provide: BusinessStoreService, useValue: businessStoreService },
        { provide: ToastService, useValue: toastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BusinessStoreComponent);
    component = fixture.componentInstance;
  });

  it('loads the current business store from the route business id', () => {
    fixture.detectChanges();

    expect(businessStoreService.getBusinessStore).toHaveBeenCalledOnceWith(store.businessId);
    expect(component.name).toBe('Acme Trading');
    expect(component.slug).toBe('acme-trading');
    expect(fixture.nativeElement.textContent).toContain('Acme Trading');
  });

  it('saves store profile changes with the current version', () => {
    fixture.detectChanges();

    component.name = 'Acme Goods';
    component.slug = 'acme-goods';
    component.description = '  Updated local goods ';
    component.supportEmail = 'Support@Example.com';
    component.supportPhone = '+19495550001';
    component.save();

    expect(businessStoreService.updateBusinessStore).toHaveBeenCalledOnceWith(store.businessId, {
      name: 'Acme Goods',
      slug: 'acme-goods',
      description: 'Updated local goods',
      logoUrl: null,
      bannerUrl: null,
      supportEmail: 'Support@Example.com',
      supportPhone: '+19495550001',
    }, 1);
    expect(toastService.success).toHaveBeenCalledOnceWith('Business store saved.');
  });

  it('shows a slug conflict message', () => {
    businessStoreService.updateBusinessStore.and.returnValue(throwError(() => ({
      status: 409,
      error: {
        error: {
          code: 'BUSINESS_STORE_SLUG_CONFLICT',
        },
      },
    })));
    fixture.detectChanges();

    component.name = 'Acme Goods';
    component.slug = 'acme-goods';
    component.save();

    expect(component.errorMsg()).toBe('That store slug is already in use.');
  });
});
