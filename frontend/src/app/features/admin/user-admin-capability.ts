import { AdminUserCapabilities, UserEnforcementActionType, UserEnforcementScope } from '../../core/models/admin-user.model';

export interface UserAdminCapabilityModel extends AdminUserCapabilities {
  isReadOnly: boolean;
  availableOperationalScopes: UserEnforcementScope[];
}

export function userAdminCapabilities(value: AdminUserCapabilities): UserAdminCapabilityModel {
  return {
    ...value,
    isReadOnly: !value.canRestrict && !value.canSuspend && !value.canBan && !value.canReinstate,
    availableOperationalScopes: [...value.operationalScopes],
  };
}

export function canCreateUserEnforcement(
  capabilities: UserAdminCapabilityModel,
  action: UserEnforcementActionType,
): boolean {
  return action === 'RESTRICT' ? capabilities.canRestrict
    : action === 'SUSPEND' ? capabilities.canSuspend
    : capabilities.canBan;
}
