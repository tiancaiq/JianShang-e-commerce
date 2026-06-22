import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CurrentUser } from '../../core/models/user.model';
import { ToastService } from '../../core/services/toast.service';
import { UserProfileService } from '../../core/services/user-profile.service';
import { ProfileComponent } from './profile.component';
import { Router } from '@angular/router';

describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let userProfileService: jasmine.SpyObj<UserProfileService>;
  let toastService: jasmine.SpyObj<ToastService>;
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
    userProfileService = jasmine.createSpyObj<UserProfileService>('UserProfileService', ['getMe', 'updateMe']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['success']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    userProfileService.getMe.and.returnValue(of({ data: user }));
    userProfileService.updateMe.and.returnValue(of({ data: { ...user, displayName: 'Alex Profile', version: 4 } }));

    await TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: UserProfileService, useValue: userProfileService },
        { provide: ToastService, useValue: toastService },
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

  it('displays validation errors without calling the API', () => {
    fixture.detectChanges();
    component.phone = '9495551234';

    component.save();

    expect(userProfileService.updateMe).not.toHaveBeenCalled();
    expect(component.errorMsg()).toBe('Phone must use E.164 format.');
  });

  it('redirects unauthorized profile loads to login', () => {
    userProfileService.getMe.and.returnValue(throwError(() => ({ status: 401 })));

    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/login']);
  });
});
