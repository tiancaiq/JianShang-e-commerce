import { InvestigationCapabilities, InvestigationCaseDetail } from '../../core/models/investigation-case.model';

// Keeps templates dependent on the backend capability contract instead of reproducing ownership policy.
export function investigationCapabilities(value: InvestigationCaseDetail | null): InvestigationCapabilities {
  return value?.availableAdminCapabilities ?? {
    canRead: false, canClaim: false, canRelease: false, canStartInvestigation: false,
    canLinkReport: false, canUnlinkReport: false, canLinkTarget: false, canUnlinkTarget: false,
    canAddNote: false, canAddEvidence: false, canChangeSeverity: false,
    canMarkReadyForAction: false, canCloseNoAction: false, canCreateEnforcementProposal: false,
    canCloseActioned: false, isCaseReadyForAction: false, isAssignedToMe: false,
    isAssignedToOther: false, isClosed: false, isReadOnly: true,
    readOnlyReason: 'Investigation capabilities are unavailable.',
  };
}
