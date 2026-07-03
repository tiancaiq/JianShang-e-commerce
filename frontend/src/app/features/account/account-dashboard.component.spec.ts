import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AccountDashboardComponent } from './account-dashboard.component';

describe('AccountDashboardComponent', () => {
  let fixture: ComponentFixture<AccountDashboardComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AccountDashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            user: () => ({
              id: '01USER',
              keycloakSub: 'keycloak-sub',
              email: 'alex@example.com',
              emailVerified: true,
              displayName: 'Alex Buyer',
              phone: null,
              phoneVerified: false,
              avatarUrl: null,
              status: 'ACTIVE',
              version: 0,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccountDashboardComponent);
    fixture.detectChanges();
  });

  it('renders the marketplace account dashboard shortcuts', () => {
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => ({
      text: link.textContent?.trim(),
      href: link.getAttribute('href'),
    }));

    expect(text).toContain('Welcome, Alex Buyer');
    expect(text).toContain('Marketplace seller');
    expect(text).toContain('Rating');
    expect(links).toContain(jasmine.objectContaining({ href: '/account/profile' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/seller-profile' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/listings' }));
    expect(links).toContain(jasmine.objectContaining({ href: '/account/listings/new' }));
  });

  it('keeps deferred account areas non-navigable', () => {
    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => link.getAttribute('href') || '');

    expect(text).toContain('Messages');
    expect(links.some(href => href.includes('messages'))).toBeFalse();
    expect(links.some(href => href.includes('notifications'))).toBeFalse();
    expect(links.some(href => href.includes('orders'))).toBeFalse();
    expect(links.some(href => href.includes('cart'))).toBeFalse();
    expect(links.some(href => href.includes('reviews'))).toBeFalse();
  });
});
