import { execFileSync } from 'node:child_process';

const SELLER = {
  email: 'trade.seller@msb.local',
  password: 'TradeSeller!2026',
  firstName: 'Mira',
  lastName: 'Chen',
};

const BUYER = {
  email: 'trade.buyer@msb.local',
  password: 'TradeBuyer!2026',
  firstName: 'Jon',
  lastName: 'Bell',
};

const ADMIN_ONE = {
  email: 'admin.one@msb.local',
  password: 'AdminOne!2026',
  firstName: 'Avery',
  lastName: 'Stone',
  identityId: '01E00000000000000000000001',
};

const ADMIN_TWO = {
  email: 'admin.two@msb.local',
  password: 'AdminTwo!2026',
  firstName: 'Riley',
  lastName: 'Park',
  identityId: '01E00000000000000000000002',
};

const BUSINESS_REVIEWER = {
  email: 'business.reviewer@msb.local',
  password: 'BusinessReviewer!2026',
  firstName: 'Bailey',
  lastName: 'Reed',
  identityId: '01E00000000000000000000003',
};

const LISTING_MODERATOR = {
  email: 'listing.moderator@msb.local',
  password: 'ListingModerator!2026',
  firstName: 'Logan',
  lastName: 'Moss',
  identityId: '01E00000000000000000000004',
};

const AUDITOR = {
  email: 'admin.auditor@msb.local',
  password: 'AdminAuditor!2026',
  firstName: 'Ari',
  lastName: 'Quinn',
  identityId: '01E00000000000000000000005',
};

const SUPPORT_ADMIN = {
  email: 'support.admin@msb.local',
  password: 'SupportAdmin!2026',
  firstName: 'Sam',
  lastName: 'Rivera',
  identityId: '01E00000000000000000000006',
};

const USER_RESTRICTOR = {
  email: 'user.restrictor@msb.local',
  password: 'UserRestrictor!2026',
  firstName: 'Robin',
  lastName: 'Vale',
  identityId: '01E00000000000000000000007',
};

const DEMO_USERS = [
  SELLER,
  BUYER,
  ADMIN_ONE,
  ADMIN_TWO,
  BUSINESS_REVIEWER,
  LISTING_MODERATOR,
  AUDITOR,
  SUPPORT_ADMIN,
  USER_RESTRICTOR,
];

export default async function globalSetup(): Promise<void> {
  const subjects = await ensureKeycloakDemoUsers();
  resetDemoData(subjects);
}

// Keeps the browser test repeatable even when an existing Keycloak volume predates the realm seed users.
async function ensureKeycloakDemoUsers(): Promise<Map<string, string>> {
  const baseUrl = process.env['E2E_KEYCLOAK_URL'] || 'http://localhost:8181';
  const token = await keycloakAdminToken(baseUrl);
  const subjects = new Map<string, string>();
  for (const user of DEMO_USERS) {
    let matches = await fetch(
      `${baseUrl}/admin/realms/msb-local/users?username=${encodeURIComponent(user.email)}&exact=true`,
      { headers: { Authorization: `Bearer ${token}` } },
    ).then(response => checkedJson<Array<{ id: string }>>(response));

    if (matches.length === 0) {
      await checked(fetch(`${baseUrl}/admin/realms/msb-local/users`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username: user.email,
          email: user.email,
          emailVerified: true,
          enabled: true,
          firstName: user.firstName,
          lastName: user.lastName,
          attributes: { displayName: [`${user.firstName} ${user.lastName}`] },
        }),
      }));
      matches = await fetch(
        `${baseUrl}/admin/realms/msb-local/users?username=${encodeURIComponent(user.email)}&exact=true`,
        { headers: { Authorization: `Bearer ${token}` } },
      ).then(response => checkedJson<Array<{ id: string }>>(response));
    }

    const subject = matches[0]?.id;
    if (!subject) {
      throw new Error(`Keycloak did not return the created demo user ${user.email}.`);
    }
    subjects.set(user.email, subject);
    await checked(fetch(`${baseUrl}/admin/realms/msb-local/users/${subject}/reset-password`, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ type: 'password', value: user.password, temporary: false }),
    }));
  }
  return subjects;
}

async function keycloakAdminToken(baseUrl: string): Promise<string> {
  const configuredPassword = process.env['E2E_KEYCLOAK_ADMIN_PASSWORD'];
  const candidates = configuredPassword
    ? [configuredPassword]
    : ['admin', 'demo-change-me-admin', 'local-dev-only-change-me'];
  for (const password of candidates) {
    const form = new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: process.env['E2E_KEYCLOAK_ADMIN_USER'] || 'admin',
      password,
    });
    const response = await fetch(`${baseUrl}/realms/master/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    if (response.ok) {
      const body = await response.json() as { access_token: string };
      return body.access_token;
    }
  }
  throw new Error('Could not authenticate to local Keycloak. Set E2E_KEYCLOAK_ADMIN_PASSWORD.');
}

// Resets the disposable browser-demo schemas, including the global appeal ledger.
// An explicit opt-in and Compose identity check prevent accidental use on shared data.
function resetDemoData(subjects: Map<string, string>): void {
  const configuredContainer = process.env['E2E_MYSQL_CONTAINER'];
  if (process.env['E2E_ALLOW_DESTRUCTIVE_DEMO_RESET'] !== 'true') {
    throw new Error('Set E2E_ALLOW_DESTRUCTIVE_DEMO_RESET=true only for the disposable demo stack.');
  }
  if (!configuredContainer) {
    throw new Error('E2E_MYSQL_CONTAINER must explicitly name the disposable demo MySQL container.');
  }
  assertDisposableDemoMysql(configuredContainer);
  const containers = [configuredContainer];
  const sellerSubject = sqlValue(subjects.get(SELLER.email));
  const buyerSubject = sqlValue(subjects.get(BUYER.email));
  const adminOneSubject = sqlValue(subjects.get(ADMIN_ONE.email));
  const adminTwoSubject = sqlValue(subjects.get(ADMIN_TWO.email));
  const businessReviewerSubject = sqlValue(subjects.get(BUSINESS_REVIEWER.email));
  const listingModeratorSubject = sqlValue(subjects.get(LISTING_MODERATOR.email));
  const auditorSubject = sqlValue(subjects.get(AUDITOR.email));
  const supportSubject = sqlValue(subjects.get(SUPPORT_ADMIN.email));
  const userRestrictorSubject = sqlValue(subjects.get(USER_RESTRICTOR.email));
  let lastError: unknown;
  for (const container of containers) {
    const password = process.env['E2E_MYSQL_PASSWORD']
      || (container === 'msb-demo-mysql' ? 'demo-change-me-mysql' : 'mysql');
    try {
      execFileSync('docker', [
        'exec',
        container,
        'mysql',
        '-uroot',
        `-p${password}`,
        '--default-character-set=utf8mb4',
        'chat',
        '-e',
        `
          UPDATE identity.users
          SET keycloak_sub = CONCAT('e2e-detached-', id), updated_at = CURRENT_TIMESTAMP(6)
          WHERE keycloak_sub = '${sellerSubject}'
            AND id <> '01D00000000000000000000001';
          UPDATE identity.users
          SET keycloak_sub = CONCAT('e2e-detached-', id), updated_at = CURRENT_TIMESTAMP(6)
          WHERE keycloak_sub = '${buyerSubject}'
            AND id <> '01D00000000000000000000002';
          UPDATE identity.users
          SET keycloak_sub = '${sellerSubject}', email = '${SELLER.email}', display_name = '${SELLER.firstName} ${SELLER.lastName}',
              public_handle = 'mira-trades', status = 'ACTIVE', account_type = 'HUMAN', updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000001';
          UPDATE identity.users
          SET keycloak_sub = '${buyerSubject}', email = '${BUYER.email}', display_name = '${BUYER.firstName} ${BUYER.lastName}',
              public_handle = 'jon-buys', status = 'ACTIVE', account_type = 'HUMAN', updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000002';
          INSERT INTO identity.users (
            id, keycloak_sub, email, email_verified, display_name, public_handle, phone_verified,
            status, version, created_at, updated_at
          ) VALUES
            ('${ADMIN_ONE.identityId}', '${adminOneSubject}', '${ADMIN_ONE.email}', TRUE,
             '${ADMIN_ONE.firstName} ${ADMIN_ONE.lastName}', 'avery-admin', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${ADMIN_TWO.identityId}', '${adminTwoSubject}', '${ADMIN_TWO.email}', TRUE,
             '${ADMIN_TWO.firstName} ${ADMIN_TWO.lastName}', 'riley-admin', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${BUSINESS_REVIEWER.identityId}', '${businessReviewerSubject}', '${BUSINESS_REVIEWER.email}', TRUE,
             '${BUSINESS_REVIEWER.firstName} ${BUSINESS_REVIEWER.lastName}', 'bailey-reviewer', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${LISTING_MODERATOR.identityId}', '${listingModeratorSubject}', '${LISTING_MODERATOR.email}', TRUE,
             '${LISTING_MODERATOR.firstName} ${LISTING_MODERATOR.lastName}', 'logan-moderator', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${AUDITOR.identityId}', '${auditorSubject}', '${AUDITOR.email}', TRUE,
             '${AUDITOR.firstName} ${AUDITOR.lastName}', 'ari-auditor', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${SUPPORT_ADMIN.identityId}', '${supportSubject}', '${SUPPORT_ADMIN.email}', TRUE,
             '${SUPPORT_ADMIN.firstName} ${SUPPORT_ADMIN.lastName}', 'sam-support', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            ('${USER_RESTRICTOR.identityId}', '${userRestrictorSubject}', '${USER_RESTRICTOR.email}', TRUE,
             '${USER_RESTRICTOR.firstName} ${USER_RESTRICTOR.lastName}', 'robin-restrictor', FALSE, 'ACTIVE', 0,
             CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
          ON DUPLICATE KEY UPDATE
            keycloak_sub = VALUES(keycloak_sub),
            email = VALUES(email),
            email_verified = TRUE,
            display_name = VALUES(display_name),
            public_handle = VALUES(public_handle),
            status = 'ACTIVE',
            updated_at = CURRENT_TIMESTAMP(6);
          DELETE FROM identity.user_roles
          WHERE user_id IN (
            '${ADMIN_ONE.identityId}', '${ADMIN_TWO.identityId}', '${BUSINESS_REVIEWER.identityId}',
            '${LISTING_MODERATOR.identityId}', '${AUDITOR.identityId}', '${SUPPORT_ADMIN.identityId}',
            '${USER_RESTRICTOR.identityId}'
          )
            AND role_id IN (
              'PLATFORM_ADMIN', 'SUPER_ADMIN', 'TRUST_AND_SAFETY_ADMIN', 'BUSINESS_REVIEWER',
              'LISTING_MODERATOR', 'SUPPORT_ADMIN', 'USER_RESTRICTOR', 'BUSINESS_RESTRICTOR',
              'CATALOG_ADMIN', 'OPERATIONS_ADMIN', 'GOVERNANCE_ADMIN', 'AUDITOR', 'AI_ADMIN_AGENT'
            );
          INSERT INTO identity.user_roles (user_id, role_id, granted_by, granted_at)
          VALUES
            ('${ADMIN_ONE.identityId}', 'SUPER_ADMIN', NULL, CURRENT_TIMESTAMP(6)),
            ('${ADMIN_TWO.identityId}', 'SUPER_ADMIN', NULL, CURRENT_TIMESTAMP(6)),
            ('${BUSINESS_REVIEWER.identityId}', 'BUSINESS_REVIEWER', NULL, CURRENT_TIMESTAMP(6)),
            ('${LISTING_MODERATOR.identityId}', 'LISTING_MODERATOR', NULL, CURRENT_TIMESTAMP(6)),
            ('${AUDITOR.identityId}', 'AUDITOR', NULL, CURRENT_TIMESTAMP(6)),
            ('${SUPPORT_ADMIN.identityId}', 'SUPPORT_ADMIN', NULL, CURRENT_TIMESTAMP(6)),
            ('${USER_RESTRICTOR.identityId}', 'USER_RESTRICTOR', NULL, CURRENT_TIMESTAMP(6))
          ON DUPLICATE KEY UPDATE granted_at = VALUES(granted_at);

          DELETE FROM identity.admin_role_assignments
          WHERE admin_user_id IN (
            '${ADMIN_ONE.identityId}', '${ADMIN_TWO.identityId}', '${BUSINESS_REVIEWER.identityId}',
            '${LISTING_MODERATOR.identityId}', '${AUDITOR.identityId}', '${SUPPORT_ADMIN.identityId}',
            '${USER_RESTRICTOR.identityId}'
          );
          INSERT INTO identity.admin_role_assignments (
            id, admin_user_id, role_id, status, effective_at, expires_at,
            granted_by_admin_id, reason, request_idempotency_key, request_hash,
            correlation_id, version, created_at, updated_at
          ) VALUES
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${ADMIN_ONE.identityId}', '|SUPER_ADMIN'), 256), 1, 25))), '${ADMIN_ONE.identityId}', 'SUPER_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${ADMIN_TWO.identityId}', '|SUPER_ADMIN'), 256), 1, 25))), '${ADMIN_TWO.identityId}', 'SUPER_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${BUSINESS_REVIEWER.identityId}', '|BUSINESS_REVIEWER'), 256), 1, 25))), '${BUSINESS_REVIEWER.identityId}', 'BUSINESS_REVIEWER', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${LISTING_MODERATOR.identityId}', '|LISTING_MODERATOR'), 256), 1, 25))), '${LISTING_MODERATOR.identityId}', 'LISTING_MODERATOR', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${AUDITOR.identityId}', '|AUDITOR'), 256), 1, 25))), '${AUDITOR.identityId}', 'AUDITOR', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${SUPPORT_ADMIN.identityId}', '|SUPPORT_ADMIN'), 256), 1, 25))), '${SUPPORT_ADMIN.identityId}', 'SUPPORT_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
            (CONCAT('0', UPPER(SUBSTRING(SHA2(CONCAT('${USER_RESTRICTOR.identityId}', '|USER_RESTRICTOR'), 256), 1, 25))), '${USER_RESTRICTOR.identityId}', 'USER_RESTRICTOR', 'ACTIVE', CURRENT_TIMESTAMP(6), NULL, NULL, 'Deterministic E2E fixture', NULL, NULL, 'e2e-governance-fixture', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

          -- Appeal analytics is global. Reset the disposable appeal domain so the
          -- live 2/1/1 fixture has no hidden manual or prior-run denominator.
          DELETE FROM identity.appeal_resolution_commands;
          DELETE FROM identity.appeal_resolution_previews;
          DELETE FROM identity.appeal_replacement_scopes;
          DELETE FROM identity.appeal_review_notes;
          DELETE FROM identity.appeal_events;
          DELETE FROM identity.appeals;

          -- Workflow 2 temporarily grants this seller a staff membership. Make
          -- the disposable fixture authoritative and leave no prior membership
          -- for the test's upsert/finally cleanup to overwrite.
          DELETE FROM identity.business_memberships
          WHERE business_id = '01KZCARTB00000000000000002'
            AND user_id = '01D00000000000000000000001';

          DELETE link
          FROM identity.case_enforcement_links link
          JOIN identity.investigation_cases investigation ON investigation.id = link.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE scope
          FROM identity.case_enforcement_proposal_scopes scope
          JOIN identity.case_enforcement_proposals proposal ON proposal.id = scope.proposal_id
          JOIN identity.investigation_cases investigation ON investigation.id = proposal.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE proposal
          FROM identity.case_enforcement_proposals proposal
          JOIN identity.investigation_cases investigation ON investigation.id = proposal.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE event
          FROM identity.investigation_case_events event
          JOIN identity.investigation_cases investigation ON investigation.id = event.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE evidence
          FROM identity.investigation_case_evidence evidence
          JOIN identity.investigation_cases investigation ON investigation.id = evidence.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE note
          FROM identity.investigation_case_notes note
          JOIN identity.investigation_cases investigation ON investigation.id = note.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE target
          FROM identity.investigation_case_targets target
          JOIN identity.investigation_cases investigation ON investigation.id = target.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE case_report
          FROM identity.investigation_case_reports case_report
          JOIN identity.investigation_cases investigation ON investigation.id = case_report.case_id
          WHERE investigation.title LIKE '[E2E]%';
          DELETE FROM identity.investigation_cases WHERE title LIKE '[E2E]%';

          DELETE event
          FROM identity.report_events event
          JOIN identity.reports report ON report.id = event.report_id
          WHERE report.description LIKE '[E2E]%';
          DELETE dedup
          FROM identity.report_submission_dedup dedup
          JOIN identity.reports report ON report.id = dedup.report_id
          WHERE report.description LIKE '[E2E]%';
          DELETE FROM identity.reports WHERE description LIKE '[E2E]%';
          DELETE FROM identity.report_rate_limit_buckets
          WHERE reporter_user_id IN (
            '01D00000000000000000000001', '01D00000000000000000000002',
            '${ADMIN_ONE.identityId}', '${ADMIN_TWO.identityId}', '${BUSINESS_REVIEWER.identityId}',
            '${LISTING_MODERATOR.identityId}', '${AUDITOR.identityId}', '${SUPPORT_ADMIN.identityId}',
            '${USER_RESTRICTOR.identityId}'
          );

          DELETE idempotency
          FROM identity.enforcement_command_idempotency idempotency
          JOIN identity.enforcement_actions action
            ON action.id = idempotency.enforcement_action_id
          WHERE action.target_type = 'USER'
            AND action.target_id IN ('01D00000000000000000000001', '01D00000000000000000000002');
          DELETE event
          FROM identity.enforcement_events event
          JOIN identity.enforcement_actions action
            ON action.id = event.enforcement_action_id
          WHERE action.target_type = 'USER'
            AND action.target_id IN ('01D00000000000000000000001', '01D00000000000000000000002');
          DELETE scope
          FROM identity.enforcement_action_scopes scope
          JOIN identity.enforcement_actions action
            ON action.id = scope.enforcement_action_id
          WHERE action.target_type = 'USER'
            AND action.target_id IN ('01D00000000000000000000001', '01D00000000000000000000002');
          DELETE FROM identity.enforcement_actions
          WHERE target_type = 'USER'
            AND target_id IN ('01D00000000000000000000001', '01D00000000000000000000002');

          DELETE idempotency
          FROM identity.enforcement_command_idempotency idempotency
          JOIN identity.enforcement_actions action
            ON action.id = idempotency.enforcement_action_id
          WHERE action.target_type = 'BUSINESS'
            AND action.target_id = '01KZCARTB00000000000000002';
          DELETE event
          FROM identity.enforcement_events event
          JOIN identity.enforcement_actions action
            ON action.id = event.enforcement_action_id
          WHERE action.target_type = 'BUSINESS'
            AND action.target_id = '01KZCARTB00000000000000002';
          DELETE scope
          FROM identity.enforcement_action_scopes scope
          JOIN identity.enforcement_actions action
            ON action.id = scope.enforcement_action_id
          WHERE action.target_type = 'BUSINESS'
            AND action.target_id = '01KZCARTB00000000000000002';
          DELETE FROM identity.enforcement_actions
          WHERE target_type = 'BUSINESS'
            AND target_id = '01KZCARTB00000000000000002';

          UPDATE identity.business_applications
          SET approved_business_id = NULL
          WHERE id = '01E00000000000000000000101';
          DELETE s FROM identity.stores s
          JOIN identity.businesses b ON b.id = s.business_id
          WHERE b.application_id = '01E00000000000000000000101';
          DELETE bm FROM identity.business_memberships bm
          JOIN identity.businesses b ON b.id = bm.business_id
          WHERE b.application_id = '01E00000000000000000000101';
          DELETE FROM identity.businesses
          WHERE application_id = '01E00000000000000000000101';
          DELETE FROM identity.business_verification_events
          WHERE application_id = '01E00000000000000000000101';
          DELETE FROM identity.business_applications
          WHERE id = '01E00000000000000000000101';
          INSERT INTO identity.business_applications (
            id, applicant_user_id, legal_name, business_type, country,
            contact_email, contact_phone, public_city, public_region,
            website_url, description, status, submitted_at, version,
            created_at, updated_at
          ) VALUES (
            '01E00000000000000000000101', '01D00000000000000000000001',
            'Harbor Workshop LLC', 'LLC', 'US', 'harbor-workshop@msb.local',
            '+1-555-0104', 'Seattle', 'WA', 'https://harbor-workshop.example',
            'Small workshop applying for the business marketplace.',
            'PENDING_VERIFICATION', CURRENT_TIMESTAMP(6), 1,
            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
          );
          INSERT INTO identity.business_verification_events (
            id, provider_event_id, application_id, source, event_type, outcome,
            reason, payload_hash, actor_user_id, previous_state, new_state,
            correlation_id, created_at
          ) VALUES (
            '01E00000000000000000000102', NULL,
            '01E00000000000000000000101', 'APPLICANT',
            'BUSINESS_APPLICATION_SUBMITTED', 'SUBMITTED', NULL, NULL,
            '01D00000000000000000000001', 'DRAFT', 'PENDING_VERIFICATION',
            'e2e-business-submission', CURRENT_TIMESTAMP(6)
          );

          DELETE FROM catalog.moderation_case_events
          WHERE moderation_case_id IN (
            '01E00000000000000000000202', '01E00000000000000000000205'
          );
          DELETE FROM catalog.listing_moderation_decisions
          WHERE listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE resolution
          FROM catalog.listing_appeal_resolution_commands resolution
          JOIN catalog.enforcement_actions action
            ON action.id = resolution.original_enforcement_action_id
            OR action.id = resolution.replacement_enforcement_action_id
          WHERE action.target_type = 'LISTING'
            AND action.target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          UPDATE catalog.enforcement_actions
          SET parent_enforcement_action_id = NULL
          WHERE target_type = 'LISTING'
            AND target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          DELETE idempotency
          FROM catalog.enforcement_command_idempotency idempotency
          JOIN catalog.enforcement_actions action
            ON action.id = idempotency.enforcement_action_id
          WHERE action.target_type = 'LISTING'
            AND action.target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          DELETE event
          FROM catalog.enforcement_events event
          JOIN catalog.enforcement_actions action
            ON action.id = event.enforcement_action_id
          WHERE action.target_type = 'LISTING'
            AND action.target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          DELETE scope
          FROM catalog.enforcement_action_scopes scope
          JOIN catalog.enforcement_actions action
            ON action.id = scope.enforcement_action_id
          WHERE action.target_type = 'LISTING'
            AND action.target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          DELETE FROM catalog.enforcement_actions
          WHERE target_type = 'LISTING'
            AND target_id IN (
              '01E00000000000000000000201', '01E00000000000000000000204',
              '01KZ3CF59Z42DG2M0AZ8FQ1729'
            );
          DELETE vector_work
          FROM catalog.listing_search_vector_apply_work vector_work
          JOIN catalog.listing_discovery_embedding_receipts receipt
            ON receipt.request_id = vector_work.request_id
          WHERE receipt.listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.listing_discovery_embedding_receipts
          WHERE listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.listing_discovery_embedding_requests
          WHERE listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.listing_search_projection_work
          WHERE listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.outbox_events
          WHERE aggregate_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.listing_knowledge_versions
          WHERE listing_id IN (
            '01E00000000000000000000201', '01E00000000000000000000204'
          );
          DELETE FROM catalog.moderation_cases
          WHERE id IN (
            '01E00000000000000000000202', '01E00000000000000000000205'
          );
          SET @admin_category_id = (
            SELECT category_id FROM catalog.listings
            WHERE id = '01D00000000000000000000101'
            LIMIT 1
          );
          INSERT INTO catalog.listings (
            id, seller_type, individual_seller_user_id, business_id, store_id,
            category_id, title, description, condition_code, condition_notes,
            price_amount, currency, negotiable, sku, quantity, public_city,
            public_region, payment_preferences_json, delivery_preferences_json,
            status, moderation_status, publication_source, published_at, version,
            created_at, updated_at
          ) VALUES (
            '01E00000000000000000000201', 'INDIVIDUAL',
            '01D00000000000000000000001', NULL, NULL, @admin_category_id,
            'Restored oak writing desk',
            'A restored writing desk submitted for the admin release workflow.',
            'GOOD', 'Light wear on the drawer pulls.', 245.00, 'USD', TRUE,
            NULL, 1, 'Seattle', 'WA', JSON_ARRAY('CASH'),
            JSON_ARRAY('LOCAL_PICKUP'), 'PENDING_REVIEW', 'PENDING', NULL, NULL,
            0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
          )
          ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            description = VALUES(description),
            status = 'PENDING_REVIEW',
            moderation_status = 'PENDING',
            publication_source = NULL,
            published_at = NULL,
            version = 0,
            updated_at = CURRENT_TIMESTAMP(6);
          INSERT INTO catalog.moderation_cases (
            id, case_type, subject_listing_id, subject_seller_type,
            subject_individual_seller_user_id, subject_business_id,
            submitted_by_user_id, status, priority, assigned_admin_user_id,
            version, created_at, updated_at, resolved_at
          ) VALUES (
            '01E00000000000000000000202', 'LISTING_REVIEW',
            '01E00000000000000000000201', 'INDIVIDUAL',
            '01D00000000000000000000001', NULL,
            '01D00000000000000000000001', 'OPEN', 'NORMAL', NULL, 0,
            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), NULL
          );
          INSERT INTO catalog.moderation_case_events (
            id, moderation_case_id, listing_id, event_type, actor_user_id,
            previous_state, new_state, previous_assigned_admin_user_id,
            new_assigned_admin_user_id, reason, correlation_id, created_at
          ) VALUES (
            '01E00000000000000000000203',
            '01E00000000000000000000202',
            '01E00000000000000000000201', 'CASE_CREATED', NULL,
            NULL, 'OPEN', NULL, NULL, NULL, 'e2e-listing-submission',
            CURRENT_TIMESTAMP(6)
          );

          INSERT INTO catalog.listings (
            id, seller_type, individual_seller_user_id, business_id, store_id,
            category_id, title, description, condition_code, condition_notes,
            price_amount, currency, negotiable, sku, quantity, public_city,
            public_region, payment_preferences_json, delivery_preferences_json,
            status, moderation_status, publication_source, published_at, version,
            created_at, updated_at
          ) VALUES (
            '01E00000000000000000000204', 'INDIVIDUAL',
            '01D00000000000000000000001', NULL, NULL, @admin_category_id,
            'Removal boundary fixture',
            'An active fixed listing reserved for permanent-removal enforcement verification.',
            'GOOD', 'Fixed browser fixture.', 95.00, 'USD', FALSE,
            NULL, 1, 'Seattle', 'WA', JSON_ARRAY('CASH'),
            JSON_ARRAY('LOCAL_PICKUP'), 'ACTIVE', 'APPROVED', 'ADMIN_REVIEW',
            CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
          )
          ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            description = VALUES(description),
            status = 'ACTIVE',
            moderation_status = 'APPROVED',
            publication_source = 'ADMIN_REVIEW',
            published_at = CURRENT_TIMESTAMP(6),
            version = 0,
            updated_at = CURRENT_TIMESTAMP(6);
          INSERT INTO catalog.moderation_cases (
            id, case_type, subject_listing_id, subject_seller_type,
            subject_individual_seller_user_id, subject_business_id,
            submitted_by_user_id, status, priority, assigned_admin_user_id,
            version, created_at, updated_at, resolved_at
          ) VALUES (
            '01E00000000000000000000205', 'LISTING_REVIEW',
            '01E00000000000000000000204', 'INDIVIDUAL',
            '01D00000000000000000000001', NULL,
            '01D00000000000000000000001', 'RESOLVED', 'NORMAL',
            '${ADMIN_ONE.identityId}', 1, CURRENT_TIMESTAMP(6),
            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
          );
          INSERT INTO catalog.moderation_case_events (
            id, moderation_case_id, listing_id, event_type, actor_user_id,
            previous_state, new_state, previous_assigned_admin_user_id,
            new_assigned_admin_user_id, reason, correlation_id, created_at
          ) VALUES (
            '01E00000000000000000000206',
            '01E00000000000000000000205',
            '01E00000000000000000000204', 'CASE_CREATED', NULL,
            NULL, 'RESOLVED', NULL, '${ADMIN_ONE.identityId}',
            'Dedicated removal-boundary fixture.', 'e2e-removal-boundary-fixture',
            CURRENT_TIMESTAMP(6)
          );

          INSERT INTO catalog.listings (
            id, seller_type, individual_seller_user_id, business_id, store_id,
            category_id, title, description, condition_code, condition_notes,
            price_amount, currency, negotiable, sku, quantity, public_city,
            public_region, payment_preferences_json, delivery_preferences_json,
            status, moderation_status, publication_source, published_at, version,
            created_at, updated_at
          ) VALUES (
            '01E00000000000000000000207', 'INDIVIDUAL',
            '01D00000000000000000000001', NULL, NULL, @admin_category_id,
            'Reporting workflow fixture',
            'An active fixed listing reserved for report submission and triage verification.',
            'GOOD', 'Fixed browser fixture.', 110.00, 'USD', FALSE,
            NULL, 1, 'Seattle', 'WA', JSON_ARRAY('CASH'),
            JSON_ARRAY('LOCAL_PICKUP'), 'ACTIVE', 'APPROVED', 'ADMIN_REVIEW',
            CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
          )
          ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            description = VALUES(description),
            status = 'ACTIVE',
            moderation_status = 'APPROVED',
            publication_source = 'ADMIN_REVIEW',
            published_at = CURRENT_TIMESTAMP(6),
            version = 0,
            updated_at = CURRENT_TIMESTAMP(6);

          DELETE m FROM chat.messages m
          JOIN chat.conversations c ON c.id = m.conversation_id
          WHERE c.subject_listing_id = '01D00000000000000000000101';
          DELETE cp FROM chat.conversation_participants cp
          JOIN chat.conversations c ON c.id = cp.conversation_id
          WHERE c.subject_listing_id = '01D00000000000000000000101';
          DELETE FROM chat.listing_trade_completions
          WHERE listing_id = '01D00000000000000000000101';
          DELETE FROM chat.conversations
          WHERE subject_listing_id = '01D00000000000000000000101';
          UPDATE catalog.listings
          SET status = 'ACTIVE',
              moderation_status = 'APPROVED',
              publication_source = 'ADMIN_REVIEW',
              published_at = CURRENT_TIMESTAMP(6),
              version = version + 1,
              updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000101';
        `,
      ], { stdio: 'pipe' });
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error(
    `Could not reset the browser demo data in MySQL. Set E2E_MYSQL_CONTAINER and E2E_MYSQL_PASSWORD. ${String(lastError)}`,
  );
}

function assertDisposableDemoMysql(container: string): void {
  if (container !== 'msb-demo-mysql') {
    throw new Error(`Refusing destructive reset for non-demo container ${container}.`);
  }
  const identity = execFileSync('docker', [
    'inspect', '--format',
    '{{.Name}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.docker.compose.project.config_files"}}',
    container,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const [name, service, configFiles] = identity.split('|');
  if (name !== '/msb-demo-mysql' || service !== 'mysql'
      || !configFiles.toLowerCase().includes('docker-compose.demo.yml')) {
    throw new Error(`Refusing destructive reset: ${container} is not the verified disposable demo MySQL service.`);
  }
}

function sqlValue(value: string | undefined): string {
  if (!value || !/^[0-9a-f-]{36}$/i.test(value)) {
    throw new Error(`Unexpected Keycloak subject: ${String(value)}`);
  }
  return value;
}

async function checked(responsePromise: Promise<Response>): Promise<void> {
  const response = await responsePromise;
  if (!response.ok) {
    throw new Error(`Keycloak request failed with HTTP ${response.status}: ${await response.text()}`);
  }
}

async function checkedJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Keycloak request failed with HTTP ${response.status}: ${await response.text()}`);
  }
  return await response.json() as T;
}
