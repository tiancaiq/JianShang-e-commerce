import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { AuthService } from '../../core/services/auth.service';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { BusinessAccountComponent } from './business-account.component';

describe('BusinessAccountComponent', () => {
  let fixture: ComponentFixture<BusinessAccountComponent>;
  let businessStoreService: jasmine.SpyObj<BusinessStoreService>;

  const context: BusinessStoreContext = {
    businessId: '01JY0000000000000000000003',
    businessLegalName: 'Acme Trading LLC',
    businessStatus: 'ACTIVE',
    membershipRole: 'OWNER',
    permissions: ['LISTING_DRAFT_CREATE'],
    store: {
      id: '01JY0000000000000000000100',
      businessId: '01JY0000000000000000000003',
      slug: 'acme-trading',
      name: 'Acme Trading',
      description: 'Local goods',
      logoUrl: null,
      bannerUrl: null,
      supportEmail: 'help@example.com',
      supportPhone: '+19495550000',
      status: 'ACTIVE',
      version: 1,
      createdAt: '2026-07-08T12:00:00Z',
      updatedAt: '2026-07-08T12:05:00Z',
    },
  };

  beforeEach(async () => {
    businessStoreService = jasmine.createSpyObj<BusinessStoreService>(
      'BusinessStoreService',
      ['getCurrentStoreContext']
    );
    businessStoreService.getCurrentStoreContext.and.returnValue(of(context));

    await TestBed.configureTestingModule({
      imports: [BusinessAccountComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessStoreService, useValue: businessStoreService },
        {
          provide: AuthService,
          useValue: {
            user: () => ({ email: 'merchant@example.com', displayName: 'Merchant User' }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BusinessAccountComponent);
  });

  it('shows current approved business and links to its store profile', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));

    expect(text).toContain('Business account');
    expect(text).toContain('Acme Trading LLC');
    expect(text).toContain('OWNER access for an active business store.');
    expect(text).toContain('Business onboarding');
    expect(text).toContain('Application approved');
    expect(text).toContain('Store setup');
    expect(text).toContain('Store items');
    expect(links).toEqual([
      '/seller/business/apply',
      '/seller/businesses/01JY0000000000000000000003/store',
      '/seller/store/items',
    ]);
    expect(host.innerHTML).not.toContain('/account/profile');
  });

  it('sends users without approved store context back to business application', () => {
    businessStoreService.getCurrentStoreContext.and.returnValue(of(null));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href'));

    expect(host.textContent).toContain('No approved business store is connected to this account yet.');
    expect(host.textContent).toContain('Application');
    expect(links).toEqual(['/seller/business/apply', '/seller/business/apply', '/seller/business/apply']);
  });

  it('shows a context loading error without leaving the business surface', () => {
    businessStoreService.getCurrentStoreContext.and.returnValue(throwError(() => ({ status: 503 })));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('Business store context could not be loaded.');
    expect(host.innerHTML).not.toContain('/account/profile');
  });
});
