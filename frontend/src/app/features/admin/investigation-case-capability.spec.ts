import { InvestigationCaseDetail } from '../../core/models/investigation-case.model';
import { investigationCapabilities } from './investigation-case-capability';

describe('investigationCapabilities', () => {
  it('honors assigned-to-other read-only authority from the backend', () => {
    const value={availableAdminCapabilities:{canRead:true,canClaim:false,canRelease:false,canStartInvestigation:false,
      canLinkReport:false,canUnlinkReport:false,canLinkTarget:false,canUnlinkTarget:false,canAddNote:false,
      canAddEvidence:false,canChangeSeverity:false,canMarkReadyForAction:false,canCloseNoAction:false,
      isAssignedToMe:false,isAssignedToOther:true,isClosed:false,isReadOnly:true,
      readOnlyReason:'This case is assigned to another admin.'}} as unknown as InvestigationCaseDetail;
    expect(investigationCapabilities(value).isReadOnly).toBeTrue();
    expect(investigationCapabilities(value).canAddNote).toBeFalse();
  });

  it('fails closed while detail is unavailable', () => {
    expect(investigationCapabilities(null).canClaim).toBeFalse();
    expect(investigationCapabilities(null).isReadOnly).toBeTrue();
  });
});
