import { AdminPermission, AdminRole } from '../security/admin-permissions';

export interface PlatformAdmin {
  userId: string;
  role: 'PLATFORM_ADMIN';
  roles: AdminRole[];
  permissions: AdminPermission[];
  accountState: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
}

export interface AdminBusinessSummary {
  pendingBusinessApplications: number;
}

export interface AdminListingModerationSummary {
  pendingListingReviews: number;
  assignedToMeListingReviews: number;
}

export interface AdminDashboardSummary {
  pendingBusinessApplications: number;
  pendingListingReviews: number;
  assignedToMeListingReviews: number;
}
