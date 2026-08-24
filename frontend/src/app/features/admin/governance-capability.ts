import {ADMIN_PERMISSIONS} from '../../core/security/admin-permissions';

export function governanceCapability(permissions:readonly string[]){
 const has=(permission:string)=>permissions.includes(permission);
 return{canReadGovernance:has(ADMIN_PERMISSIONS.GOVERNANCE_READ),canManageRoles:has(ADMIN_PERMISSIONS.GOVERNANCE_ROLES_MANAGE),canManageElevation:has(ADMIN_PERMISSIONS.GOVERNANCE_ELEVATION_MANAGE),canReadApprovals:has(ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_READ),canApprove:has(ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_REVIEW),canReject:has(ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_REVIEW),canExecuteApprovedAction:has(ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_READ)};
}

export function isApprovalExpired(expiresAt:string,now=Date.now()){return new Date(expiresAt).getTime()<=now;}
