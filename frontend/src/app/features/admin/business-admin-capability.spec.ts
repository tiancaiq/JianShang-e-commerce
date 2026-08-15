import { AdminBusinessCapabilities } from '../../core/models/admin-business.model';
import { canCreateBusinessEnforcement, operationalBusinessScopes } from './business-admin-capability';

describe('business admin capability model',()=>{
  const value:AdminBusinessCapabilities={canReadBusiness:true,canRestrict:true,canSuspend:false,canBan:false,canReinstate:true,protectedBusiness:false,readOnlyReason:null,operationalScopes:['BUSINESS_LISTING_CREATION','BUSINESS_NEW_SALES']};
  it('keeps the backend action permissions authoritative',()=>{expect(canCreateBusinessEnforcement(value,'RESTRICT')).toBeTrue();expect(canCreateBusinessEnforcement(value,'SUSPEND')).toBeFalse();expect(canCreateBusinessEnforcement(value,'BAN')).toBeFalse();});
  it('never offers the reserved payouts scope',()=>{expect(operationalBusinessScopes(value)).toEqual(['BUSINESS_LISTING_CREATION','BUSINESS_NEW_SALES']);expect(operationalBusinessScopes(value)).not.toContain('BUSINESS_PAYOUTS' as never);});
});
