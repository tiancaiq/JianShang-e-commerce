import { analyticsDrillDownTarget } from './admin-analytics-drill-down';

describe('admin analytics drill-down allowlist', () => {
  it('maps only current-state and owned-page signals to exact workflows', () => {
    expect(analyticsDrillDownTarget('UNASSIGNED_REPORTS')).toEqual({
      commands: ['/admin/reports'], queryParams: { assignment: 'UNASSIGNED', unresolved: true },
    });
    expect(analyticsDrillDownTarget('READY_FOR_ACTION_CASES')).toEqual({
      commands: ['/admin/cases'], queryParams: { status: 'READY_FOR_ACTION', assignment: 'ALL' },
    });
    expect(analyticsDrillDownTarget('PROCESSING_REFUNDS')).toEqual({
      commands: ['/admin/refunds'], queryParams: { status: 'PROCESSING' },
    });
    expect(analyticsDrillDownTarget('WAITING_FOR_USER_SUPPORT_TICKETS')).toEqual({
      commands: ['/admin/support'], queryParams: { status: 'WAITING_FOR_USER', assignment: 'ALL' },
    });
    expect(analyticsDrillDownTarget('UNASSIGNED_LISTING_CASES')).toEqual({
      commands: ['/admin/listings/moderation'], queryParams: { filter: 'unassigned' },
    });
    expect(analyticsDrillDownTarget('FAILED_JOBS')).toEqual({
      commands: ['/admin/system/jobs'], queryParams: { status: 'FAILURE' },
    });
    expect(analyticsDrillDownTarget('FAILED_OUTBOX_EVENTS')).toEqual({
      commands: ['/admin/system/outbox'], queryParams: { status: 'FAILURE' },
    });
    expect(analyticsDrillDownTarget('DEAD_LETTER_OUTBOX_EVENTS')).toEqual({
      commands: ['/admin/system/outbox'], queryParams: { status: 'DEAD_LETTER' },
    });
    expect(analyticsDrillDownTarget('RECONCILIATION_ISSUES')).toEqual({
      commands: ['/admin/system/reconciliation'],
    });
  });

  it('rejects arbitrary server-provided navigation values', () => {
    expect(analyticsDrillDownTarget('/admin/users?pii=true')).toBeNull();
    expect(analyticsDrillDownTarget('UNKNOWN_KEY')).toBeNull();
    expect(analyticsDrillDownTarget(null)).toBeNull();
  });

  it('does not link retired or inexact queue aggregates', () => {
    const inexact = [
      'READY_FOR_INVESTIGATION_REPORTS',
      'OPEN_CASES',
      'UNASSIGNED_CASES',
      'PENDING_APPEALS',
      'OPEN_DISPUTES',
      'UNASSIGNED_DISPUTES',
      'FAILED_REFUNDS',
      'OPEN_SUPPORT_TICKETS',
      'UNASSIGNED_SUPPORT_TICKETS',
      'PENDING_BUSINESS_APPLICATIONS',
      'CANCELLED_ORDERS',
      'COMPLETED_ORDERS',
      'FAILED_PAYMENTS',
    ];
    for (const key of inexact) expect(analyticsDrillDownTarget(key)).toBeNull();
  });

  it('filters governance approvals to the exact pending state', () => {
    expect(analyticsDrillDownTarget('PENDING_GOVERNANCE_APPROVALS')).toEqual({
      commands: ['/admin/governance/approvals'], queryParams: { status: 'PENDING' },
    });
    expect(analyticsDrillDownTarget('ACTIVE_TEMPORARY_ELEVATIONS')).toEqual({
      commands: ['/admin/governance/admins'], queryParams: { hasTemporaryElevation: true },
    });
  });
});
