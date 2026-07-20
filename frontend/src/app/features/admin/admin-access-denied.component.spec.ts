import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AdminAccessDeniedComponent } from './admin-access-denied.component';

describe('AdminAccessDeniedComponent', () => {
  let fixture: ComponentFixture<AdminAccessDeniedComponent>;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);

    await TestBed.configureTestingModule({
      imports: [AdminAccessDeniedComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminAccessDeniedComponent);
    fixture.detectChanges();
  });

  it('offers admin logout from the access denied state', () => {
    const logoutButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(button => button.textContent?.trim() === 'Logout') as HTMLButtonElement | undefined;

    expect(logoutButton).toBeTruthy();
    logoutButton?.click();

    expect(authService.logout).toHaveBeenCalledOnceWith('admin-portal');
  });
});
