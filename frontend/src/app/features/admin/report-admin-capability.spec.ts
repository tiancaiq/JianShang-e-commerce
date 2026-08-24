import { AdminReportDetail } from '../../core/models/report.model';
import { reportAdminCapabilities } from './report-admin-capability';

describe('reportAdminCapabilities', () => {
  it('uses the backend capability contract for assigned-to-other read-only state', () => {
    const report={availableAdminCapabilities:{canRead:true,canClaim:false,canRelease:false,canChangeSeverity:false,
      canDismiss:false,canMarkReadyForInvestigation:false,assignedToMe:false,assignedToOther:true,resolved:false,
      readOnly:true,readOnlyReason:'This report is assigned to another admin.'}} as unknown as AdminReportDetail;
    expect(reportAdminCapabilities(report).readOnly).toBeTrue();
    expect(reportAdminCapabilities(report).canDismiss).toBeFalse();
    expect(reportAdminCapabilities(report).readOnlyReason).toContain('another admin');
  });

  it('fails closed before detail capabilities are available', () => {
    expect(reportAdminCapabilities(null).canClaim).toBeFalse();
    expect(reportAdminCapabilities(null).readOnly).toBeTrue();
  });
});
