import { SupportCapabilities } from '../../core/models/support.model';
import { supportAdminCapability } from './support-admin-capability';

describe('supportAdminCapability',()=>{
  const base:SupportCapabilities={canRead:true,canClaim:false,canRelease:true,canRespond:true,canRequestInformation:true,canAddNote:true,canChangePriority:true,canLink:true,canUnlink:true,canResolve:true,canEscalate:true,isAssignedToMe:true,isAssignedToOther:false,isWaitingForUser:false,isFinal:false,isReadOnly:false,readOnlyReason:null};
  it('makes resolved tickets final and read-only',()=>{const value=supportAdminCapability({...base,isFinal:true});expect(value.isReadOnly).toBeTrue();expect(value.canRespond).toBeFalse();expect(value.canResolve).toBeFalse();expect(value.canEscalate).toBeFalse();});
  it('prevents work when another admin owns the ticket',()=>{const value=supportAdminCapability({...base,isAssignedToMe:false,isAssignedToOther:true});expect(value.isReadOnly).toBeTrue();expect(value.canAddNote).toBeFalse();expect(value.readOnlyReason).toContain('another support admin');});
});
