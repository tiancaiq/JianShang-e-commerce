import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
import { systemOperationsCapability } from './system-operations-capability';

describe('systemOperationsCapability', () => {
  it('keeps a system reader in read-only mode', () => {
    const capability = systemOperationsCapability([
      ADMIN_PERMISSIONS.SYSTEM_READ,
      ADMIN_PERMISSIONS.FEATURE_READ,
    ]);

    expect(capability.canReadSystem).toBeTrue();
    expect(capability.canRetry).toBeFalse();
    expect(capability.canMaintainSearch).toBeFalse();
    expect(capability.canManageFeatures).toBeFalse();
    expect(capability.isReadOnly).toBeTrue();
  });

  it('separates generic retry from bounded search maintenance', () => {
    expect(systemOperationsCapability([ADMIN_PERMISSIONS.SYSTEM_RETRY]).canMaintainSearch).toBeFalse();
    expect(systemOperationsCapability([ADMIN_PERMISSIONS.SEARCH_MAINTENANCE]).canRetry).toBeFalse();
    expect(systemOperationsCapability([
      ADMIN_PERMISSIONS.SYSTEM_RETRY,
      ADMIN_PERMISSIONS.SEARCH_MAINTENANCE,
    ]).isReadOnly).toBeFalse();
  });
});
