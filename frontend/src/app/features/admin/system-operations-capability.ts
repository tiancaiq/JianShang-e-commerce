import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
export interface SystemOperationsCapability {canReadSystem:boolean;canRetry:boolean;canReconcile:boolean;canMaintainSearch:boolean;canReadFeatures:boolean;canManageFeatures:boolean;isReadOnly:boolean;readOnlyReason:string|null;}
export function systemOperationsCapability(permissions:readonly string[]):SystemOperationsCapability{
 const has=(permission:string)=>permissions.includes(permission);
 const canRetry=has(ADMIN_PERMISSIONS.SYSTEM_RETRY),canSearch=has(ADMIN_PERMISSIONS.SEARCH_MAINTENANCE);
 return {canReadSystem:has(ADMIN_PERMISSIONS.SYSTEM_READ),canRetry,canReconcile:false,canMaintainSearch:canSearch,canReadFeatures:has(ADMIN_PERMISSIONS.FEATURE_READ),canManageFeatures:false,isReadOnly:!canRetry&&!canSearch,readOnlyReason:!canRetry&&!canSearch?'Operations access is read-only for this role.':null};
}
