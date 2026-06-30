import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AdminLayoutComponent } from './admin-layout.component';

describe('AdminLayoutComponent', () => {
  let fixture: ComponentFixture<AdminLayoutComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminLayoutComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            logout: jasmine.createSpy('logout'),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminLayoutComponent);
  });

  it('shows MVP admin moderation navigation without V2 commerce links', () => {
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Business Review');
    expect(text).toContain('Listing Review');
    expect(text).not.toContain('Cart');
    expect(text).not.toContain('Checkout');
    expect(text).not.toContain('Orders');
    expect(text).not.toContain('Payments');
    expect(text).not.toContain('Inventory');
    expect(text).not.toContain('Wallet');
    expect(text).not.toContain('Notifications');
  });
});
