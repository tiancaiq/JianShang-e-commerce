import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BusinessStore } from '../../core/models/business-store.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { PublicStoreProfileComponent } from './public-store-profile.component';

describe('PublicStoreProfileComponent', () => {
  let fixture: ComponentFixture<PublicStoreProfileComponent>;
  let storeService: jasmine.SpyObj<BusinessStoreService>;

  const store: BusinessStore = {
    id: '01S00000000000000000000001',
    businessId: '01B00000000000000000000001',
    slug: 'mochi-store',
    name: 'Mochi Store',
    description: 'Verified local collectibles shop.',
    logoUrl: null,
    bannerUrl: null,
    supportEmail: 'support@example.com',
    supportPhone: '+19495551234',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-07-01T12:00:00Z',
    updatedAt: '2026-07-18T12:00:00Z',
  };

  beforeEach(async () => {
    storeService = jasmine.createSpyObj<BusinessStoreService>('BusinessStoreService', ['getPublicStore']);
    storeService.getPublicStore.and.returnValue(of(store));

    await TestBed.configureTestingModule({
      imports: [PublicStoreProfileComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: BusinessStoreService, useValue: storeService },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => store.slug } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PublicStoreProfileComponent);
  });

  it('renders the public identity of an active verified store', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(storeService.getPublicStore).toHaveBeenCalledOnceWith('mochi-store');
    expect(text).toContain('Mochi Store');
    expect(text).toContain('Verified business');
    expect(text).toContain('Irvine, CA');
    expect(text).toContain('Verified local collectibles shop.');
    expect(text).toContain('support@example.com');
    expect(fixture.nativeElement.querySelector('a[href="/stores"]')).not.toBeNull();
  });

  it('shows a public not-found state when the store is unavailable', () => {
    storeService.getPublicStore.and.returnValue(throwError(() => ({ status: 404 })));

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Store unavailable');
    expect(fixture.nativeElement.textContent).toContain('This store profile is not available.');
  });
});
