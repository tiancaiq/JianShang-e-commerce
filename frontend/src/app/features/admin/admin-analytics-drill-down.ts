import { Params } from '@angular/router';
import { AnalyticsDrillDownKey } from '../../core/models/admin-analytics.model';

export interface AnalyticsDrillDownTarget {
  commands: string[];
  queryParams?: Params;
}

// Only metrics with an exact destination filter belong here. Multi-state
// aggregates intentionally render without a link rather than narrowing truth.
const TARGETS: Partial<Record<AnalyticsDrillDownKey, AnalyticsDrillDownTarget>> = {
  UNASSIGNED_REPORTS: { commands: ['/admin/reports'], queryParams: { assignment: 'UNASSIGNED', unresolved: true } },
  READY_FOR_ACTION_CASES: { commands: ['/admin/cases'], queryParams: { status: 'READY_FOR_ACTION', assignment: 'ALL' } },
  PROCESSING_REFUNDS: { commands: ['/admin/refunds'], queryParams: { status: 'PROCESSING' } },
  WAITING_FOR_USER_SUPPORT_TICKETS: { commands: ['/admin/support'], queryParams: { status: 'WAITING_FOR_USER', assignment: 'ALL' } },
  FAILED_JOBS: { commands: ['/admin/system/jobs'], queryParams: { status: 'FAILURE' } },
  FAILED_OUTBOX_EVENTS: { commands: ['/admin/system/outbox'], queryParams: { status: 'FAILURE' } },
  DEAD_LETTER_OUTBOX_EVENTS: { commands: ['/admin/system/outbox'], queryParams: { status: 'DEAD_LETTER' } },
  RECONCILIATION_ISSUES: { commands: ['/admin/system/reconciliation'] },
  INVENTORY_ISSUES: { commands: ['/admin/system/inventory'] },
  SEARCH_INDEX_FAILURES: { commands: ['/admin/system/search'] },
  UNASSIGNED_LISTING_CASES: { commands: ['/admin/listings/moderation'], queryParams: { filter: 'unassigned' } },
  PENDING_GOVERNANCE_APPROVALS: { commands: ['/admin/governance/approvals'], queryParams: { status: 'PENDING' } },
  ACTIVE_TEMPORARY_ELEVATIONS: { commands: ['/admin/governance/admins'], queryParams: { hasTemporaryElevation: true } },
};

export function analyticsDrillDownTarget(key: string | null | undefined): AnalyticsDrillDownTarget | null {
  if (!key || !Object.prototype.hasOwnProperty.call(TARGETS, key)) return null;
  return TARGETS[key as AnalyticsDrillDownKey] ?? null;
}
