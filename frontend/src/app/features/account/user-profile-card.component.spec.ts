import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CurrentUser } from '../../core/models/user.model';
import { UserProfileCardComponent } from './user-profile-card.component';

describe('UserProfileCardComponent', () => {
  let fixture: ComponentFixture<UserProfileCardComponent>;

  const user: CurrentUser = {
    id: '01USER',
    keycloakSub: 'keycloak-sub',
    email: 'hana@example.com',
    emailVerified: true,
    displayName: 'Hana',
    phone: null,
    phoneVerified: false,
    avatarUrl: 'https://example.com/hana.png',
    status: 'ACTIVE',
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserProfileCardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(UserProfileCardComponent);
  });

  it('renders marketplace profile display with avatar and placeholder stats', () => {
    fixture.componentInstance.user = user;
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const image = host.querySelector('img') as HTMLImageElement | null;
    const sellLink = Array.from(host.querySelectorAll('a'))
      .find(link => link.textContent?.includes('Sell an Item'));

    expect(text).toContain('Hana');
    expect(text).toContain('@hana');
    expect(text).toContain('Marketplace seller');
    expect(text).toContain('Listings');
    expect(text).toContain('Trades');
    expect(text).toContain('Rating');
    expect(text).toContain('Soon');
    expect(text).toContain('New');
    expect(image?.getAttribute('src')).toBe('https://example.com/hana.png');
    expect(sellLink?.getAttribute('href')).toBe('/account/listings/new');
  });

  it('uses initials when no avatar URL exists', () => {
    fixture.componentInstance.user = { ...user, displayName: 'Hana Sakura', avatarUrl: null };
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('img')).toBeNull();
    expect(host.textContent || '').toContain('HS');
  });
});
