export const ADMIN_PERMISSIONS = {
  DASHBOARD_READ: 'admin.dashboard.read',
  AUDIT_READ: 'admin.audit.read',
  BUSINESS_APPLICATION_READ: 'admin.business.application.read',
  BUSINESS_APPLICATION_DECIDE: 'admin.business.application.decide',
  LISTING_MODERATION_READ: 'admin.listing.moderation.read',
  LISTING_MODERATION_CLAIM: 'admin.listing.moderation.claim',
  LISTING_MODERATION_RESOLVE: 'admin.listing.moderation.resolve',
  LISTING_EDIT: 'admin.listing.edit',
  LISTING_REMOVE: 'admin.listing.remove',
  USER_READ: 'admin.user.read',
  USER_RESTRICT: 'admin.user.restrict',
  USER_SUSPEND: 'admin.user.suspend',
  USER_BAN: 'admin.user.ban',
  USER_REINSTATE: 'admin.user.reinstate',
  USER_PII_READ: 'admin.user.pii.read',
  BUSINESS_READ: 'admin.business.read',
  BUSINESS_RESTRICT: 'admin.business.restrict',
  BUSINESS_SUSPEND: 'admin.business.suspend',
  BUSINESS_BAN: 'admin.business.ban',
  BUSINESS_REINSTATE: 'admin.business.reinstate',
  LISTING_SUSPEND: 'admin.listing.suspend',
  LISTING_REINSTATE: 'admin.listing.reinstate',
  REPORT_READ: 'admin.report.read',
  REPORT_ASSIGN: 'admin.report.assign',
  REPORT_RESOLVE: 'admin.report.resolve',
  ROLE_READ: 'admin.role.read',
  ROLE_MANAGE: 'admin.role.manage',
} as const;

export type AdminPermission = typeof ADMIN_PERMISSIONS[keyof typeof ADMIN_PERMISSIONS];

export const ADMIN_ROLES = [
  'SUPER_ADMIN',
  'TRUST_AND_SAFETY_ADMIN',
  'BUSINESS_REVIEWER',
  'LISTING_MODERATOR',
  'SUPPORT_ADMIN',
  'USER_RESTRICTOR',
  'BUSINESS_RESTRICTOR',
  'AUDITOR',
  'AI_ADMIN_AGENT',
] as const;

export type AdminRole = typeof ADMIN_ROLES[number];
