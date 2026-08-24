import {governanceCapability,isApprovalExpired} from './governance-capability';
import {ADMIN_PERMISSIONS} from '../../core/security/admin-permissions';

describe('governanceCapability',()=>{
 it('keeps readers read-only',()=>{const value=governanceCapability([ADMIN_PERMISSIONS.GOVERNANCE_READ,ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_READ]);expect(value.canReadGovernance).toBeTrue();expect(value.canManageRoles).toBeFalse();expect(value.canApprove).toBeFalse();});
 it('derives review authority and expiration explicitly',()=>{const value=governanceCapability([ADMIN_PERMISSIONS.GOVERNANCE_APPROVAL_REVIEW]);expect(value.canApprove).toBeTrue();expect(value.canReject).toBeTrue();expect(isApprovalExpired('2026-08-23T00:00:00Z',Date.parse('2026-08-24T00:00:00Z'))).toBeTrue();});
});
