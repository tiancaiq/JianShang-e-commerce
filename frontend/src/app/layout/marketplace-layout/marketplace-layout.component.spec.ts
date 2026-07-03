import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
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
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
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
    expect(text).not.toContain('Sell');
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

  it('does not expose direct listing creation in public navigation', () => {
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'));

    expect(links.some(link => link.getAttribute('href') === '/account/listings/new')).toBeFalse();
  });

  it('links authenticated account navigation to the account dashboard shell', () => {
    authenticated = true;
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'));
    const accountLink = links.find(link => link.textContent?.trim() === 'Account');

    expect(accountLink?.getAttribute('href')).toBe('/account');
  });

  it('opens a marketplace-themed auth dialog from the login button', () => {
    fixture.detectChanges();

    const loginButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Login') as HTMLButtonElement | undefined;

    loginButton?.click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('MSB marketplace account');
    expect(text).toContain('Sign in to keep shopping local.');
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
