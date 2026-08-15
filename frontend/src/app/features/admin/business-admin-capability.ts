import { AdminBusinessCapabilities, BusinessEnforcementActionType, BusinessEnforcementScope } from '../../core/models/admin-business.model';

export function canCreateBusinessEnforcement(capabilities: AdminBusinessCapabilities, action: BusinessEnforcementActionType): boolean {
  if (capabilities.protectedBusiness) return false;
  return action==='RESTRICT'?capabilities.canRestrict:action==='SUSPEND'?capabilities.canSuspend:capabilities.canBan;
}

export function operationalBusinessScopes(capabilities: AdminBusinessCapabilities): BusinessEnforcementScope[] {
  const allowed: BusinessEnforcementScope[]=['BUSINESS_LISTING_CREATION','BUSINESS_LISTING_PUBLICATION','BUSINESS_NEW_SALES'];
  return capabilities.operationalScopes.filter(scope=>allowed.includes(scope));
}
