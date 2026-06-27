import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { MarketplaceLayoutComponent } from './marketplace-layout.component';

describe('MarketplaceLayoutComponent', () => {
  let fixture: ComponentFixture<MarketplaceLayoutComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarketplaceLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => false,
            ensureSession: () => of({ authenticated: false, user: null }),
            login: jasmine.createSpy('login'),
            logout: jasmine.createSpy('logout'),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MarketplaceLayoutComponent);
  });

  it('does not expose admin navigation on the public marketplace', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Browse');
    expect(text).toContain('Sell');
    expect(text).not.toContain('Admin');
    expect(text).not.toContain('Business Review');
  });
});
