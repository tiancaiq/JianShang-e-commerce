import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { MarketplaceLayoutComponent } from './marketplace-layout.component';

describe('MarketplaceLayoutComponent', () => {
  let fixture: ComponentFixture<MarketplaceLayoutComponent>;
  let authenticated = false;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarketplaceLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ChatService,
          useValue: {
            getConversations: jasmine.createSpy('getConversations').and.returnValue(of({ items: [], nextCursor: null })),
            getConversation: jasmine.createSpy('getConversation'),
            getMessages: jasmine.createSpy('getMessages'),
            sendMessage: jasmine.createSpy('sendMessage'),
            markRead: jasmine.createSpy('markRead'),
          },
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
            user: () => authenticated ? {
              id: '01USER',
              keycloakSub: 'keycloak-sub',
              email: 'alex@example.com',
              emailVerified: true,
              displayName: 'Alex Buyer',
              phone: null,
              phoneVerified: false,
              avatarUrl: '/api/v1/public/user-avatars/01USER?v=4',
              status: 'ACTIVE',
              version: 4,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            } : null,
            ensureSession: () => of({ authenticated: false, user: null }),
            login: jasmine.createSpy('login'),
            loginWithPopup: jasmine.createSpy('loginWithPopup').and.returnValue(of({ authenticated: false, user: null })),
            loginWithGooglePopup: jasmine.createSpy('loginWithGooglePopup').and.returnValue(of({ authenticated: false, user: null })),
            registerWithPopup: jasmine.createSpy('registerWithPopup').and.returnValue(of({ authenticated: false, user: null })),
            nativeLogin: jasmine.createSpy('nativeLogin').and.returnValue(of({ authenticated: true, user: null })),
            nativeRegister: jasmine.createSpy('nativeRegister').and.returnValue(of({ authenticated: true, user: null })),
            logout: jasmine.createSpy('logout'),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MarketplaceLayoutComponent);
  });

  beforeEach(() => {
    authenticated = false;
  });

  it('does not expose admin navigation on the public marketplace', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const text = host.textContent || '';
    const links = Array.from(host.querySelectorAll('a')).map(link => ({
      text: link.textContent?.trim(),
      href: link.getAttribute('href'),
    }));

    expect(text).toContain('Marketplace');
    expect(text).toContain('Stores');
    expect(links).toContain(jasmine.objectContaining({ text: 'Marketplace', href: '/marketplace' }));
    expect(links).toContain(jasmine.objectContaining({ text: 'Stores', href: '/stores' }));
    expect(text).not.toContain('Admin');
    expect(text).not.toContain('Business Review');
  });

  it('does not expose V2 commerce navigation on the public marketplace', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });

  it('gates direct listing creation behind authentication in the public navigation', () => {
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const loggedOutLinks = Array.from(host.querySelectorAll('a'));
    const tradesButton = Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Trades') as HTMLButtonElement | undefined;

    expect(loggedOutLinks.some(link => link.getAttribute('href') === '/account/listings')).toBeFalse();
    expect(loggedOutLinks.some(link => link.getAttribute('href') === '/account/listings/new')).toBeFalse();
    expect(tradesButton).toBeTruthy();
  });

  it('links authenticated account navigation to the account dashboard shell', () => {
    authenticated = true;
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'));
    const accountLink = links.find(link => link.textContent?.includes('Alex Buyer'));
    const tradesLink = links.find(link => link.textContent?.trim() === 'Trades');
    const favoritesLink = links.find(link => link.textContent?.trim() === 'Favorites');
    const inboxLink = links.find(link => link.textContent?.trim() === 'Inbox');

    expect(accountLink?.getAttribute('href')).toBe('/account');
    expect(tradesLink?.getAttribute('href')).toBe('/account/listings');
    expect(favoritesLink?.getAttribute('href')).toBe('/account/liked');
    expect(inboxLink?.getAttribute('href')).toBe('/account/messages');
  });

  it('exposes logout from the authenticated account menu', () => {
    const authService = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
    authenticated = true;
    fixture.detectChanges();

    const logoutButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Logout') as HTMLButtonElement | undefined;

    expect(logoutButton).toBeTruthy();
    logoutButton?.click();
    expect(authService.logout).toHaveBeenCalled();
  });

  it('uses the global marketplace nav on the account dashboard route', () => {
    const router = TestBed.inject(Router);
    Object.defineProperty(router, 'url', { value: '/account' });
    authenticated = true;
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('app-marketplace-navbar')).not.toBeNull();
    expect(host.querySelector('.marketplace-main')?.classList).toContain('account-dashboard-main');
  });

  it('opens a marketplace-themed auth dialog from the login button', () => {
    fixture.detectChanges();

    const loginButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Login') as HTMLButtonElement | undefined;

    loginButton?.click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('MSB marketplace account');
    expect(text).toContain('Sign in to keep trading local.');
    expect(text).toContain('Create account');
  });

  it('submits marketplace-native registration from the auth dialog', () => {
    const authService = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
    fixture.detectChanges();

    fixture.componentInstance.openAuthDialog();
    fixture.componentInstance.setAuthMode('register');
    fixture.componentInstance.authEmail = 'new@example.com';
    fixture.componentInstance.authPassword = 'password-123';
    fixture.componentInstance.authDisplayName = 'New Buyer';
    fixture.componentInstance.submitNativeAuth();

    expect(authService.nativeRegister).toHaveBeenCalledWith({
      email: 'new@example.com',
      password: 'password-123',
      displayName: 'New Buyer',
    });
  });

  it('keeps Google login hidden until the provider is configured', () => {
    fixture.detectChanges();

    fixture.componentInstance.openAuthDialog();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).not.toContain('Continue with Google');
  });
});
