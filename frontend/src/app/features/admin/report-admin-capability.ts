import { AdminReportDetail, ReportCapabilities } from '../../core/models/report.model';

// Keeps the template dependent on the backend capability contract instead of duplicating status/permission policy.
export function reportAdminCapabilities(report: AdminReportDetail | null): ReportCapabilities {
  return report?.availableAdminCapabilities ?? {
    canRead: false, canClaim: false, canRelease: false, canChangeSeverity: false, canDismiss: false,
    canMarkReadyForInvestigation: false, assignedToMe: false, assignedToOther: false, resolved: false,
    readOnly: true, readOnlyReason: 'Report capabilities are unavailable.',
    canCreateInvestigationCase: false, canLinkInvestigationCase: false, canOpenInvestigationCase: false,
  };
}
