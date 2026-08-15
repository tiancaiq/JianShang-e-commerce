import { AdminUserCapabilities } from '../../core/models/admin-user.model';
import { canCreateUserEnforcement, userAdminCapabilities } from './user-admin-capability';

describe('user admin capability model', () => {
  const capabilities = (overrides: Partial<AdminUserCapabilities> = {}): AdminUserCapabilities => ({
    canReadUser: true,
    canViewPii: false,
    canRestrict: true,
    canSuspend: false,
    canBan: false,
    canReinstate: true,
    self: false,
    protectedAccount: false,
    readOnlyReason: null,
    operationalScopes: ['USER_BUYING', 'USER_SELLING'],
    ...overrides,
  });

  it('keeps only backend-declared operational scopes and action permissions', () => {
    const model = userAdminCapabilities(capabilities({ operationalScopes: ['USER_BUYING'] }));

    expect(model.availableOperationalScopes).toEqual(['USER_BUYING']);
    expect(canCreateUserEnforcement(model, 'RESTRICT')).toBeTrue();
    expect(canCreateUserEnforcement(model, 'SUSPEND')).toBeFalse();
    expect(canCreateUserEnforcement(model, 'BAN')).toBeFalse();
  });

  it('makes self and protected targets read only when the backend removes mutation capabilities', () => {
    for (const protectedState of [
      capabilities({ self: true, canRestrict: false, canReinstate: false, readOnlyReason: 'Self enforcement is unavailable.' }),
      capabilities({ protectedAccount: true, canRestrict: false, canReinstate: false, readOnlyReason: 'Protected account.' }),
    ]) {
      const model = userAdminCapabilities(protectedState);
      expect(model.isReadOnly).toBeTrue();
      expect(model.readOnlyReason).toBeTruthy();
    }
  });
});
