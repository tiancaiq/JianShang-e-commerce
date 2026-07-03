import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CurrentUser } from '../../core/models/user.model';
import { ToastService } from '../../core/services/toast.service';
import { UserProfileService } from '../../core/services/user-profile.service';
import { AuthService } from '../../core/services/auth.service';
import { ProfileComponent } from './profile.component';
import { Router } from '@angular/router';

describe('ProfileComponent marketplace account regression', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let userProfileService: jasmine.SpyObj<UserProfileService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  const user: CurrentUser = {
    id: '01JY0000000000000000000000',
    keycloakSub: 'keycloak-sub',
    email: 'alex@example.com',
    emailVerified: true,
    displayName: 'Alex',
    phone: null,
    phoneVerified: false,
    avatarUrl: null,
    status: 'ACTIVE',
    version: 3,
    createdAt: '2026-06-16T12:00:00Z',
    updatedAt: '2026-06-16T12:00:00Z',
  };

  beforeEach(async () => {
    userProfileService = jasmine.createSpyObj<UserProfileService>('UserProfileService', ['getMe', 'updateMe', 'uploadAvatar', 'deleteAvatar']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['clearUser']);
    Object.defineProperty(router, 'url', { value: '/account/profile' });

    userProfileService.getMe.and.returnValue(of(user));
    userProfileService.updateMe.and.returnValue(of({ ...user, displayName: 'Alex Profile', version: 4 }));
    userProfileService.uploadAvatar.and.returnValue(of({
      ...user,
      avatarUrl: '/api/v1/public/user-avatars/01JY0000000000000000000000?v=4',
      version: 4,
    }));
    userProfileService.deleteAvatar.and.returnValue(of({ ...user, avatarUrl: null, version: 4 }));

    await TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: UserProfileService, useValue: userProfileService },
        { provide: ToastService, useValue: toastService },
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
  });

  it('loads the current user profile', () => {
    fixture.detectChanges();

    expect(userProfileService.getMe).toHaveBeenCalled();
    expect(component.user()).toEqual(user);
    expect(component.displayName).toBe('Alex');
    expect(fixture.nativeElement.textContent).toContain('Marketplace account');
  });

  it('saves only allowed profile fields', () => {
    fixture.detectChanges();
    component.displayName = '  Alex Profile  ';
    component.phone = ' +19495551234 ';
    component.avatarUrl = ' https://example.com/avatar.png ';

    component.save();

    expect(userProfileService.updateMe).toHaveBeenCalledOnceWith({
      displayName: 'Alex Profile',
      phone: '+19495551234',
      avatarUrl: 'https://example.com/avatar.png',
    }, 3);
    expect(toastService.success).toHaveBeenCalledWith('Profile saved.');
  });

  it('keeps the internal avatar URL when saving profile fields', () => {
    userProfileService.getMe.and.returnValue(of({
      ...user,
      avatarUrl: '/api/v1/public/user-avatars/01JY0000000000000000000000?v=4',
      version: 4,
    }));
    fixture.detectChanges();
    component.displayName = 'Alex Profile';

    component.save();

    expect(userProfileService.updateMe).toHaveBeenCalledOnceWith({
      displayName: 'Alex Profile',
      phone: null,
      avatarUrl: '/api/v1/public/user-avatars/01JY0000000000000000000000?v=4',
    }, 4);
  });

  it('displays validation errors without calling the API', () => {
    fixture.detectChanges();
    component.phone = '9495551234';

    component.save();

    expect(userProfileService.updateMe).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Phone must use E.164 format.');
  });

  it('uploads a selected avatar with the current profile version', () => {
    fixture.detectChanges();
    const file = new File(['avatar'], 'avatar.png', { type: 'image/png' });
    component.selectedAvatarFile = file;

    component.uploadAvatar();

    expect(userProfileService.uploadAvatar).toHaveBeenCalledOnceWith(file, 3);
    expect(toastService.success).toHaveBeenCalledWith('Avatar updated.');
    expect(component.user()?.avatarUrl).toBe('/api/v1/public/user-avatars/01JY0000000000000000000000?v=4');
  });

  it('removes the current avatar with the current profile version', () => {
    userProfileService.getMe.and.returnValue(of({
      ...user,
      avatarUrl: '/api/v1/public/user-avatars/01JY0000000000000000000000?v=3',
    }));
    fixture.detectChanges();

    component.removeAvatar();

    expect(userProfileService.deleteAvatar).toHaveBeenCalledOnceWith(3);
    expect(toastService.success).toHaveBeenCalledWith('Avatar removed.');
  });

  it('rejects unsupported avatar image files before upload', () => {
    fixture.detectChanges();
    component.selectedAvatarFile = new File(['avatar'], 'avatar.gif', { type: 'image/gif' });

    component.uploadAvatar();

    expect(userProfileService.uploadAvatar).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Avatar image must be a PNG, JPEG, or WebP file.');
  });

  it('redirects unauthorized profile loads to login', () => {
    userProfileService.getMe.and.returnValue(throwError(() => ({ status: 401 })));

    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: '/account/profile',
      },
    });
    expect(authService.clearUser).toHaveBeenCalled();
  });
});
