import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminUserDetail } from '../../core/models/admin-user.model';
import { AdminUserService } from '../../core/services/admin-user.service';
import { AdminService } from '../../core/services/admin.service';
import { AdminUserDetailComponent } from './admin-user-detail.component';

describe('AdminUserDetailComponent', () => {
  let fixture: ComponentFixture<AdminUserDetailComponent>;
  let userService: jasmine.SpyObj<AdminUserService>;

  const detail = (readOnly = false, fullActions = false): AdminUserDetail => ({
    userId: '01USER00000000000000000001',
    safeDisplayName: 'Privacy Target',
    safeEmail: 'p***@***.test',
    emailMasked: true,
    createdAt: '2026-08-15T01:00:00Z',
    updatedAt: '2026-08-15T01:00:00Z',
    version: 2,
    accountReference: 'OIDC_LINKED',
    authenticationState: 'ACTIVE',
    emailVerified: true,
    accountType: 'HUMAN',
    individualSellerProfile: { status: 'ACTIVE', createdAt: '2026-08-01T01:00:00Z', updatedAt: '2026-08-01T01:00:00Z', version: 0 },
    businessMemberships: [],
    platformAdmin: false,
    strongestActiveAction: 'RESTRICT',
    effectiveRestrictions: [{ scope: 'USER_BUYING', actionType: 'RESTRICT', enforcementActionId: '01ACTION' }],
    activeEnforcementActions: [{
      enforcementActionId: '01ACTION', targetType: 'USER', targetId: '01USER00000000000000000001',
      actionType: 'RESTRICT', scopes: ['USER_BUYING'], lifecycleState: 'ACTIVE',
      effectiveAt: '2026-08-15T01:00:00Z', expiresAt: null, version: 0,
      createdAt: '2026-08-15T01:00:00Z', revokedAt: null,
      reasonCode: 'POLICY', reason: 'Policy review',
      effectiveRestrictions: [{ scope: 'USER_BUYING', actionType: 'RESTRICT', enforcementActionId: '01ACTION' }],
      correlationId: 'correlation', dryRun: false,
    }],
    historicalEnforcementActions: [],
    availableAdminCapabilities: {
      canReadUser: true, canViewPii: false, canRestrict: !readOnly, canSuspend: !readOnly && fullActions,
      canBan: !readOnly && fullActions, canReinstate: !readOnly, self: readOnly, protectedAccount: readOnly,
      readOnlyReason: readOnly ? 'You cannot enforce your own marketplace account.' : null,
      operationalScopes: ['USER_BUYING', 'USER_SELLING'],
    },
  });

  beforeEach(async () => {
    userService = jasmine.createSpyObj<AdminUserService>('AdminUserService', [
      'detail', 'timeline', 'previewCreate', 'create', 'previewRevoke', 'revoke',
    ]);
    userService.timeline.and.returnValue(of({ data: [] }));
    await TestBed.configureTestingModule({
      imports: [AdminUserDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '01USER00000000000000000001' } } } },
        { provide: AdminUserService, useValue: userService },
        { provide: AdminService, useValue: { hasPermission: () => true } },
      ],
    }).compileComponents();
  });

  it('renders masked identity, per-scope restrictions, action reason, and reserved scopes', async () => {
    userService.detail.and.returnValue(of({ data: detail() }));
    fixture = TestBed.createComponent(AdminUserDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('p***@***.test');
    expect(text).not.toContain('privacy.target@example.test');
    expect(text).toContain('Blocked');
    expect(text).toContain('Policy review');
    expect(text).toContain('Login');
    expect(text).toContain('Reserved');
    expect(text).toContain('Preview action');
  });

  it('explains self-protection and removes enforcement controls', async () => {
    userService.detail.and.returnValue(of({ data: detail(true) }));
    fixture = TestBed.createComponent(AdminUserDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('You cannot enforce your own marketplace account.');
    expect(text).not.toContain('Preview action');
    expect(text).not.toContain('Reinstate');
  });

  it('shows marketplace bans as applying to every operational scope', async () => {
    userService.detail.and.returnValue(of({ data: detail(false, true) }));
    fixture = TestBed.createComponent(AdminUserDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    const actionType = (fixture.nativeElement as HTMLElement).querySelector('select') as HTMLSelectElement;
    actionType.value = 'BAN';
    actionType.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const scopes = Array.from(element.querySelectorAll('fieldset input')) as HTMLInputElement[];
    expect(element.textContent).toContain('Apply marketplace enforcement');
    expect(scopes.map(scope => scope.checked)).toEqual([true, true]);
    expect(scopes.map(scope => scope.disabled)).toEqual([true, true]);
  });

  it('disables enforcement action types outside the administrator capabilities', async () => {
    userService.detail.and.returnValue(of({ data: detail() }));
    fixture = TestBed.createComponent(AdminUserDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('select option'),
    ) as HTMLOptionElement[];
    expect(options.find(option => option.value === 'RESTRICT')?.disabled).toBeFalse();
    expect(options.find(option => option.value === 'SUSPEND')?.disabled).toBeTrue();
    expect(options.find(option => option.value === 'BAN')?.disabled).toBeTrue();
  });
});
