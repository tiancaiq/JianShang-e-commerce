import { SupportCapabilities } from '../../core/models/support.model';

export function supportAdminCapability(value:SupportCapabilities):SupportCapabilities {
  if(value.isFinal)return {...value,canClaim:false,canRelease:false,canRespond:false,canRequestInformation:false,canAddNote:false,canChangePriority:false,canLink:false,canUnlink:false,canResolve:false,canEscalate:false,isReadOnly:true,readOnlyReason:value.readOnlyReason||'Resolved tickets are read-only.'};
  if(value.isAssignedToOther)return {...value,canRespond:false,canRequestInformation:false,canAddNote:false,canChangePriority:false,canLink:false,canUnlink:false,canResolve:false,canEscalate:false,isReadOnly:true,readOnlyReason:value.readOnlyReason||'This ticket is assigned to another support admin.'};
  return value;
}
