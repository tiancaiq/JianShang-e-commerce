import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { CartService } from '../../core/services/cart.service';
import { ToastService } from '../../core/services/toast.service';
import { MarketplaceLayoutComponent } from './marketplace-layout.component';

describe('MarketplaceLayoutComponent', () => {
  let fixture: ComponentFixture<MarketplaceLayoutComponent>;
  let authenticated = false;
  let cartService: jasmine.SpyObj<CartService>;
  let conversationRead: Subject<string>;

  beforeEach(async () => {
    cartService = jasmine.createSpyObj<CartService>('CartService', ['count', 'load', 'reset']);
    cartService.count.and.returnValue(0);
    cartService.load.and.returnValue(of({
      version: 0,
      expiresAt: null,
      itemCount: 0,
      totalQuantity: 0,
      totals: [],
      items: [],
    }));
    conversationRead = new Subject<string>();

    await TestBed.configureTestingModule({
      imports: [MarketplaceLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: CartService, useValue: cartService },
        {
          provide: ChatService,
          useValue: {
            conversationRead$: conversationRead.asObservable(),
            getConversations: jasmine.createSpy('getConversations').and.returnValue(of({ items: [], nextCursor: null })),
            getConversation: jasmine.createSpy('getConversation'),
            getMessages: jasmine.createSpy('getMessages'),
            sendMessage: jasmine.createSpy('sendMessage'),
            markRead: jasmine.createSpy('markRead'),
            markDone: jasmine.createSpy('markDone'),
            confirmCompletion: jasmine.createSpy('confirmCompletion'),
            notifyConversationRead: jasmine.createSpy('notifyConversationRead'),
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
            logout: jasmine.createSpy('logout').and.returnValue(true),
          },
        },
        {
          provide: ToastService,
          useValue: {
            success: jasmine.createSpy('success'),
            dismiss: jasmine.createSpy('dismiss'),
            toasts: () => [],
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

  it('keeps authenticated commerce navigation hidden from guests', () => {
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
    const cartLink = links.find(link => link.textContent?.trim() === 'Cart');
    const inboxLink = links.find(link => link.textContent?.trim() === 'Inbox');

    expect(accountLink?.getAttribute('href')).toBe('/account');
    expect(tradesLink?.getAttribute('href')).toBe('/account/listings');
    expect(favoritesLink?.getAttribute('href')).toBe('/account/liked');
    expect(cartLink).toBeUndefined();
    expect(inboxLink?.getAttribute('href')).toBe('/account/messages');
  });

  it('does not load or reset cart state while the deferred capability is disabled', () => {
    authenticated = true;
    fixture.detectChanges();

    expect(cartService.load).not.toHaveBeenCalled();
    expect(cartService.reset).not.toHaveBeenCalled();
  });

  it('navigates to the inbox from an ordinary click', () => {
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigateByUrl');
    authenticated = true;
    fixture.detectChanges();

    const inboxLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.trim() === 'Inbox') as HTMLAnchorElement | undefined;

    expect(inboxLink).toBeTruthy();
    inboxLink?.click();

    expect(navigateSpy).toHaveBeenCalledTimes(1);
    expect(navigateSpy.calls.mostRecent().args[0].toString()).toBe('/account/messages');
  });

  it('starts logout once from the direct header action', () => {
    const authService = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
    authenticated = true;
    fixture.detectChanges();

    const logoutButton = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('button[aria-label="Logout"]');

    expect(logoutButton).toBeTruthy();
    logoutButton?.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    logoutButton?.click();
    expect(authService.logout).toHaveBeenCalledWith('marketplace');
    expect(authService.logout).toHaveBeenCalledTimes(1);
    expect(cartService.reset).not.toHaveBeenCalled();
  });

  it('shows signed-out confirmation when returning to marketplace after logout', () => {
    const router = TestBed.inject(Router);
    const toastService = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
    spyOn(router, 'navigateByUrl');
    Object.defineProperty(router, 'url', {
      configurable: true,
      value: '/?signedOut=1',
    });

    fixture.detectChanges();

    expect(toastService.success).toHaveBeenCalledOnceWith('Signed out successfully');
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/', { replaceUrl: true });
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
