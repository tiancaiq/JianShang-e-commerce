export interface PlatformAdmin {
  userId: string;
  role: 'PLATFORM_ADMIN';
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
