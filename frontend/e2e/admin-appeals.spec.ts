import { Browser, expect, Locator, Page, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const SELLER_ID = '01D00000000000000000000001';
const BUYER_ID = '01D00000000000000000000002';
const LISTING_ID = '01E00000000000000000000204';
const BUSINESS_ID = '01KZCARTB00000000000000002';
const SELLER = { email: 'trade.seller@msb.local', password: 'TradeSeller!2026' };
const BUYER = { email: 'trade.buyer@msb.local', password: 'TradeBuyer!2026' };
const ADMIN_ONE = { email: 'admin.one@msb.local', password: 'AdminOne!2026' };
const ADMIN_TWO = { email: 'admin.two@msb.local', password: 'AdminTwo!2026' };

// These workflows intentionally append immutable appeal history. Retrying the
// serial group without rerunning global setup would duplicate the 2/1/1 ledger.
test.describe.configure({ mode: 'serial', retries: 0 });

test('resolution UI requires a bound dry run and explicit confirmation', async ({ page }) => {
  const appealId = '01KAPL00000000000000000001';
  let detail = mockedAppealDetail(appealId);
  let previewRequest: Record<string, unknown> | null = null;
  let executionRequest: Record<string, unknown> | null = null;
  let executionKey: string | undefined;
  let executionCount = 0;
  await mockAppealAdmin(page);
  await page.route(`**/api/v1/admin/appeals/${appealId}**`, async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path.endsWith(`/${appealId}`)) {
      return route.fulfill({ json: { data: detail } });
    }
    if (request.method() === 'POST' && path.endsWith('/resolution/dry-run')) {
      previewRequest = request.postDataJSON();
      return route.fulfill({ json: { data: {
        outcome: 'REVOKED', appealVersion: 4, enforcementVersion: 2, targetVersion: 7,
        originalEnforcementActionId: detail.enforcementActionId, replacementProposal: null,
        predictedEffectiveEnforcementState: 'CLEAR', effectiveRestrictionsAfter: [],
        impactSummary: ['The original enforcement will be revoked without deleting its history.'],
        warnings: ['A weaker overlapping enforcement may remain effective.'],
        previewToken: 'bound-preview-token', expiresAt: '2026-08-24T00:10:00Z',
      } } });
    }
    if (request.method() === 'POST' && path.endsWith('/resolution')) {
      executionCount++;
      executionRequest = request.postDataJSON();
      executionKey = request.headers()['idempotency-key'];
      detail = mockedAppealDetail(appealId, true);
      return route.fulfill({ json: { data: detail } });
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unexpected appeal request.' } } });
  });

  await page.goto(`/admin/appeals/${appealId}`);
  await expect(page.getByRole('heading', { name: 'Resolve as Revoked' })).toBeVisible();
  await page.getByRole('button', { name: 'Preview Revoked resolution' }).click();

  await expect(page.getByRole('heading', { name: 'Revoked impact' })).toBeVisible();
  expect(previewRequest).toEqual({ expectedAppealVersion: 4, expectedEnforcementVersion: 2, expectedTargetVersion: 7 });
  expect(executionCount).toBe(0);
  const confirm = page.getByRole('button', { name: 'Confirm Revoked' });
  await expect(confirm).toBeDisabled();
  await page.getByRole('checkbox', { name: /I confirm this exact Revoked resolution/ }).check();
  await confirm.click();

  await expect(page.getByRole('heading', { name: 'Revoked', exact: true }).last()).toBeVisible();
  await expect(page.getByText('This final outcome is immutable.')).toBeVisible();
  expect(executionCount).toBe(1);
  expect(executionRequest).toEqual(expect.objectContaining({
    expectedAppealVersion: 4, expectedEnforcementVersion: 2, expectedTargetVersion: 7,
    previewToken: 'bound-preview-token', confirmed: true,
  }));
  expect(executionRequest?.['idempotencyKey']).toBe(executionKey);
  expect(executionKey).toBeTruthy();
});

test('workflow 1: affected user appeals case-linked enforcement and revokes it after confirmation', async ({ browser }) => {
  requireDisposableAppealSetup();
  test.setTimeout(300_000);
  const reporter = await marketplacePage(browser, BUYER);
  const admin = await adminPage(browser, ADMIN_ONE);
  let seller: Page | undefined;
  let actionId = '';
  try {
    actionId = await createCaseLinkedUserEnforcement(admin, reporter);
    seller = await marketplacePage(browser, SELLER);
    const appealId = await submitAppealInAccount(seller, actionId, 'DECISION_INCORRECT',
      'The original investigation did not include the full account context.');

    await admin.goto(`/admin/appeals/${appealId}`);
    await expect(admin.getByRole('link', { name: '[E2E] Original investigation for user appeal' })).toBeVisible();
    await expect(admin.getByText('Linked reports and evidence')).toBeVisible();
    await review(admin, 'REVOKE_RECOMMENDED', 'WRONG_DECISION',
      'The restriction should be revoked only after a separate authorized resolution.');

    let current = await apiData<any>(admin, `/api/v1/admin/users/${SELLER_ID}`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(true);
    await expect(admin.getByText('The recommendation is immutable and did not itself change enforcement.')).toBeVisible();

    await resolveRecommendedAppeal(admin, 'REVOKED');
    await expect(admin.getByText('This final outcome is immutable.')).toBeVisible();
    await expect(admin.getByText('Closed actioned · HIGH')).toBeVisible();
    current = await apiData<any>(admin, `/api/v1/admin/users/${SELLER_ID}`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(false);

    await seller.goto('/account/appeals');
    await expect(seller.getByText('Revoked', { exact: true }).first()).toBeVisible();
    await expect(seller.getByText(/Current effective enforcement:/)).toBeVisible();
    await expect(seller.locator('time').filter({ hasText: /Decision / })).toBeVisible();
  } finally {
    if (actionId) await revokeUser(admin, SELLER_ID, actionId).catch(() => undefined);
    if (seller) await seller.context().close();
    await reporter.context().close(); await admin.context().close();
  }
});

test('workflow 2: business owner appeals while active staff remains ineligible', async ({ browser }) => {
  requireDisposableAppealSetup();
  test.setTimeout(240_000);
  const owner = await marketplacePage(browser, BUYER);
  const staff = await marketplacePage(browser, SELLER);
  const admin = await adminPage(browser, ADMIN_ONE);
  let actionId = '';
  try {
    setBusinessStaff(true);
    actionId = await createBusinessEnforcement(admin);
    expect(await appealCommandStatus(staff, actionId, 'DECISION_INCORRECT', 'Staff should not be authoritative.')).toBe(403);

    const appealId = await submitAppealInAccount(owner, actionId, 'POLICY_MISAPPLIED',
      'The business restriction should be reviewed against the current operating context.');
    await admin.goto(`/admin/appeals/${appealId}`);
    await review(admin, 'UPHOLD_RECOMMENDED', 'POLICY_CONFIRMED',
      'The original business restriction remains proportionate based on reviewed evidence.');
    await resolveRecommendedAppeal(admin, 'UPHELD');

    const current = await apiData<any>(admin, `/api/v1/admin/businesses/${BUSINESS_ID}`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(true);
    await owner.goto('/account/appeals');
    await expect(owner.getByText('Upheld', { exact: true }).first()).toBeVisible();
    await expect(owner.getByText(/Current effective enforcement:/)).toBeVisible();
  } finally {
    setBusinessStaff(false);
    if (actionId) await revokeBusiness(admin, actionId).catch(() => undefined);
    await owner.context().close(); await staff.context().close(); await admin.context().close();
  }
});

test('workflow 3: listing modification revokes the original and creates a replacement', async ({ browser }) => {
  requireDisposableAppealSetup();
  test.setTimeout(240_000);
  const seller = await marketplacePage(browser, SELLER);
  const admin = await adminPage(browser, ADMIN_ONE);
  let actionId = '';
  let replacementId = '';
  try {
    actionId = await createListingEnforcement(admin);
    const appealId = await submitAppealInAccount(seller, actionId, 'ACTION_TOO_SEVERE',
      'A narrower temporary visibility restriction would be proportionate.');
    await admin.goto(`/admin/appeals/${appealId}`);
    await admin.getByRole('button', { name: 'Claim appeal' }).click();
    await admin.getByRole('button', { name: 'Begin review' }).click();
    await admin.getByRole('button', { name: 'Modify' }).click();
    await admin.getByLabel('Review reason code').fill('NARROWER_ACTION');
    await admin.getByLabel('Review reason', { exact: true }).fill('Recommend a narrower temporary listing restriction.');
    await admin.getByLabel('Action').selectOption('RESTRICT');
    await admin.getByText('Listing public visibility', { exact: true }).locator('input').check();
    await admin.getByLabel('Expires').fill(new Date(Date.now() + 86_400_000).toISOString().slice(0, 16));
    await admin.getByLabel('Replacement reason').fill('Temporarily limit discovery while retaining the audit record.');
    await admin.getByRole('button', { name: 'Record recommendation' }).click();

    await expect(admin.getByText('Modify recommended', { exact: true }).first()).toBeVisible();
    await expect(admin.getByText('The recommendation is immutable and did not itself change enforcement.')).toBeVisible();
    let current = await apiJson<any>(admin, `/api/v1/admin/listings/${LISTING_ID}/enforcements`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(true);

    await resolveRecommendedAppeal(admin, 'MODIFIED');
    const appeal = await apiData<any>(admin, `/api/v1/admin/appeals/${appealId}`);
    replacementId = appeal.replacementEnforcementSummary.enforcementActionId;
    expect(replacementId).toBeTruthy();
    expect(replacementId).not.toBe(actionId);
    current = await apiJson<any>(admin, `/api/v1/admin/listings/${LISTING_ID}/enforcements`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(false);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === replacementId)).toBe(true);
    await expect(admin.getByText(replacementId, { exact: false })).toBeVisible();
  } finally {
    if (replacementId) await revokeListing(admin, replacementId).catch(() => undefined);
    if (actionId) await revokeListing(admin, actionId).catch(() => undefined);
    await seller.context().close(); await admin.context().close();
  }
});

test('workflow 4: two admins cannot silently take over the same appeal', async ({ browser }) => {
  requireDisposableAppealSetup();
  test.setTimeout(240_000);
  const adminA = await adminPage(browser, ADMIN_ONE);
  const adminB = await adminPage(browser, ADMIN_TWO);
  let seller: Page | undefined;
  let actionId = '';
  try {
    actionId = await createUserEnforcement(adminA, SELLER_ID, 'USER_BUYING');
    seller = await marketplacePage(browser, SELLER);
    const appealId = await submitAppealInAccount(seller, actionId, 'NEW_EVIDENCE',
      'New account evidence is available for the second reviewer.');
    await adminA.goto(`/admin/appeals/${appealId}`);
    await adminA.getByRole('button', { name: 'Claim appeal' }).click();

    await adminB.goto(`/admin/appeals/${appealId}`);
    await expect(adminB.getByText('This appeal is assigned to another reviewer.')).toBeVisible();
    await expect(adminB.getByRole('button', { name: 'Begin review' })).toHaveCount(0);

    await adminA.getByRole('button', { name: 'Release' }).click();
    await adminB.reload();
    await adminB.getByRole('button', { name: 'Claim appeal' }).click();
    await adminB.getByRole('button', { name: 'Begin review' }).click();
    await expect(adminB.getByText('Under review', { exact: true }).first()).toBeVisible();
    await adminB.getByRole('button', { name: 'Uphold' }).click();
    await adminB.getByLabel('Review reason code').fill('ACTION_CONFIRMED');
    await adminB.getByLabel('Review reason', { exact: true }).fill('The original buying restriction remains supported.');
    await adminB.getByRole('button', { name: 'Record recommendation' }).click();
    await resolveRecommendedAppeal(adminB, 'UPHELD');
    const current = await apiData<any>(adminB, `/api/v1/admin/users/${SELLER_ID}`);
    expect(current.activeEnforcementActions.some((action: any) => action.enforcementActionId === actionId)).toBe(true);
  } finally {
    if (actionId) await revokeUser(adminA, SELLER_ID, actionId).catch(() => undefined);
    if (seller) await seller.context().close();
    await adminA.context().close(); await adminB.context().close();
  }
});

test('workflow 5: analytics counts only 2 upheld, 1 modified, and 1 revoked final appeal', async ({ browser }) => {
  requireDisposableAppealSetup();
  test.setTimeout(300_000);
  const admin = await adminPage(browser, ADMIN_ONE);
  const seller = await marketplacePage(browser, SELLER);
  let recommendationOnlyAction = '';
  let pendingAction = '';
  try {
    recommendationOnlyAction = await createUserEnforcement(admin, SELLER_ID, 'USER_BUYING');
    pendingAction = await createUserEnforcement(admin, SELLER_ID, 'USER_SELLING');
    const recommendationOnlyAppeal = await submitAppealInAccount(seller, recommendationOnlyAction,
      'POLICY_MISAPPLIED', 'This appeal remains at recommendation for denominator verification.');
    await submitAppealInAccount(seller, pendingAction, 'NEW_EVIDENCE',
      'This appeal remains pending for denominator verification.');
    await admin.goto(`/admin/appeals/${recommendationOnlyAppeal}`);
    await review(admin, 'UPHOLD_RECOMMENDED', 'ANALYTICS_EXCLUSION',
      'Keep this recommendation-only appeal outside the finalized denominator.');

    await admin.goto('/admin/analytics');
    await expect(admin.getByRole('heading', { name: 'Operational analytics' })).toBeVisible();
    const trust = admin.locator('article.analytics-section').filter({
      has: admin.getByRole('heading', { name: 'Trust & Safety' }),
    });
    await expect(trust).toHaveAttribute('data-status', 'AVAILABLE');
    await expect(analyticsMetric(trust, admin, 'Finalized appeals')).toHaveText('4');
    await expect(analyticsMetric(trust, admin, 'Upheld')).toHaveText('2');
    await expect(analyticsMetric(trust, admin, 'Modified')).toHaveText('1');
    await expect(analyticsMetric(trust, admin, 'Revoked')).toHaveText('1');
    await expect(analyticsMetric(trust, admin, 'Appeals changed/reversed')).toHaveText('50%');
    await expect(trust.getByText(/recommendations only/i)).toHaveCount(0);

    await trust.locator('a.metric').filter({ hasText: 'Unassigned reports' }).click();
    await expect(admin).toHaveURL(/\/admin\/reports\?assignment=UNASSIGNED&unresolved=true/);
    await expect(admin.getByRole('button', { name: 'Unassigned' })).toHaveAttribute('aria-pressed', 'true');
  } finally {
    if (pendingAction) await revokeUser(admin, SELLER_ID, pendingAction).catch(() => undefined);
    if (recommendationOnlyAction) await revokeUser(admin, SELLER_ID, recommendationOnlyAction).catch(() => undefined);
    await seller.context().close(); await admin.context().close();
  }
});

async function createCaseLinkedUserEnforcement(admin: Page, reporter: Page): Promise<string> {
  const report = await apiDataCommand<any>(reporter, '/api/v1/reports', {
    targetType: 'LISTING', targetId: LISTING_ID, reasonCode: 'OTHER',
    description: '[E2E] Appeal verification report for a related user action.',
  });
  let reportDetail = await apiData<any>(admin, `/api/v1/admin/reports/${report.reportId}`);
  reportDetail = await apiDataCommand<any>(admin, `/api/v1/admin/reports/${report.reportId}/claim`,
    { expectedVersion: reportDetail.version });
  reportDetail = await apiDataCommand<any>(admin,
    `/api/v1/admin/reports/${report.reportId}/ready-for-investigation`,
    { expectedVersion: reportDetail.version, reason: 'Human investigation is required.' });
  const created = await apiDataCommand<any>(admin, `/api/v1/admin/reports/${report.reportId}/investigation-case`, {
    title: '[E2E] Original investigation for user appeal', severity: 'HIGH', expectedReportVersion: reportDetail.version,
  });
  const caseId = created.caseId as string;
  let detail = await apiData<any>(admin, `/api/v1/admin/cases/${caseId}`);
  detail = await apiDataCommand<any>(admin, `/api/v1/admin/cases/${caseId}/claim`, { expectedVersion: detail.version });
  detail = await apiDataCommand<any>(admin, `/api/v1/admin/cases/${caseId}/start`, {
    expectedVersion: detail.version, reason: 'Review the related user conduct before any action.',
  });
  detail = await apiDataCommand<any>(admin, `/api/v1/admin/cases/${caseId}/targets`, {
    targetType: 'USER', targetId: SELLER_ID, relationshipType: 'RELATED', expectedCaseVersion: detail.version,
  });
  detail = await apiDataCommand<any>(admin, `/api/v1/admin/cases/${caseId}/ready-for-action`, {
    expectedVersion: detail.version, reason: 'Human review supports a reversible user restriction.',
  });
  const target = detail.linkedTargets.find((value: any) => value.targetType === 'USER' && value.targetId === SELLER_ID);
  detail = await apiDataCommand<any>(admin, `/api/v1/admin/cases/${caseId}/enforcement-proposals`, {
    targetType: 'USER', targetId: SELLER_ID, actionType: 'SUSPEND', scopes: ['USER_SELLING'],
    reasonCode: 'POLICY_VIOLATION', reason: 'Case-reviewed user selling restriction for appeal verification.',
    effectiveAt: null, expiresAt: null, expectedCaseVersion: detail.version,
    expectedTargetVersion: Number(target.currentState.version),
  });
  let proposal = detail.enforcementProposals.find((value: any) => value.targetType === 'USER');
  detail = await apiDataCommand<any>(admin,
    `/api/v1/admin/cases/${caseId}/enforcement-proposals/${proposal.proposalId}/dry-run`,
    { expectedCaseVersion: detail.version, expectedProposalVersion: proposal.version });
  proposal = detail.enforcementProposals.find((value: any) => value.proposalId === proposal.proposalId);
  detail = await apiDataCommand<any>(admin,
    `/api/v1/admin/cases/${caseId}/enforcement-proposals/${proposal.proposalId}/execute`,
    { expectedCaseVersion: detail.version, expectedProposalVersion: proposal.version,
      idempotencyKey: `e2e-appeal-user-${Date.now()}` });
  proposal = detail.enforcementProposals.find((value: any) => value.proposalId === proposal.proposalId);
  await apiDataCommand(admin, `/api/v1/admin/cases/${caseId}/close-actioned`, {
    expectedCaseVersion: detail.version, reason: 'The reversible action is recorded for appeal verification.',
  });
  return proposal.resultingEnforcementActionId as string;
}

async function createUserEnforcement(admin: Page, userId: string, scope: 'USER_BUYING' | 'USER_SELLING') {
  const detail = await apiData<any>(admin, `/api/v1/admin/users/${userId}`);
  const result = await apiDataCommand<any>(admin, `/api/v1/admin/users/${userId}/enforcements`, {
    actionType: 'RESTRICT', scopes: [scope], reasonCode: 'POLICY_VIOLATION',
    reason: 'Reversible appeal assignment verification.', effectiveAt: null, expiresAt: null,
    expectedUserVersion: detail.version, idempotencyKey: `e2e-appeal-user-direct-${Date.now()}`,
    safeMetadata: { origin: 'PLAYWRIGHT' },
  });
  return result.enforcementActionId as string;
}

async function createBusinessEnforcement(admin: Page) {
  const detail = await apiData<any>(admin, `/api/v1/admin/businesses/${BUSINESS_ID}`);
  const result = await apiDataCommand<any>(admin, `/api/v1/admin/businesses/${BUSINESS_ID}/enforcements`, {
    actionType: 'SUSPEND', scopes: ['BUSINESS_NEW_SALES'], reasonCode: 'POLICY_VIOLATION',
    reason: 'Reversible business appeal verification.', effectiveAt: null, expiresAt: null,
    expectedBusinessVersion: detail.version, idempotencyKey: `e2e-appeal-business-${Date.now()}`,
    safeMetadata: { origin: 'PLAYWRIGHT' },
  });
  return result.enforcementActionId as string;
}

async function createListingEnforcement(admin: Page) {
  const listing = await apiJson<any>(admin, `/api/v1/admin/listings/${LISTING_ID}`);
  const result = await apiCommand<any>(admin, `/api/v1/admin/listings/${LISTING_ID}/enforcements`, {
    actionType: 'SUSPEND', scopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
    reasonCode: 'POLICY_VIOLATION', reason: 'Reversible listing appeal verification.',
    effectiveAt: null, expiresAt: null, expectedListingVersion: listing.version,
    idempotencyKey: `e2e-appeal-listing-${Date.now()}`, safeMetadata: { origin: 'PLAYWRIGHT' },
  });
  return result.enforcementActionId as string;
}

async function submitAppealInAccount(page: Page, actionId: string, reason: string, explanation: string) {
  await page.goto('/account/appeals');
  await expect(page.getByRole('heading', { name: 'Appeals', exact: true })).toBeVisible();
  const supportReference = `ENF-${actionId.slice(-8).toUpperCase()}`;
  const notice = page.locator('article.notice').filter({ hasText: supportReference });
  await notice.getByRole('button', { name: 'Appeal this action' }).click();
  await page.getByLabel('Reason').selectOption(reason);
  await page.getByLabel('Explanation').fill(explanation);
  await page.getByRole('button', { name: 'Submit appeal' }).click();
  await expect(notice.getByText('Submitted', { exact: true })).toBeVisible();
  const mine = await apiData<any[]>(page, '/api/v1/appeals/mine');
  const appeal = mine.find(value => value.enforcementActionId === actionId);
  expect(appeal, `submitted appeal for enforcement ${actionId}`).toBeDefined();
  return appeal.appealId as string;
}

async function review(admin: Page, outcome: 'UPHOLD_RECOMMENDED' | 'REVOKE_RECOMMENDED', code: string, reason: string) {
  await admin.getByRole('button', { name: 'Claim appeal' }).click();
  await admin.getByRole('button', { name: 'Begin review' }).click();
  await admin.getByRole('button', { name: outcome === 'UPHOLD_RECOMMENDED' ? 'Uphold' : 'Revoke' }).click();
  await admin.getByLabel('Review reason code').fill(code);
  await admin.getByLabel('Review reason', { exact: true }).fill(reason);
  await admin.getByRole('button', { name: 'Record recommendation' }).click();
  await expect(admin.getByText(outcome === 'UPHOLD_RECOMMENDED' ? 'Uphold recommended' : 'Revoke recommended',
    { exact: true }).first()).toBeVisible();
}

async function resolveRecommendedAppeal(admin: Page, outcome: 'UPHELD' | 'MODIFIED' | 'REVOKED') {
  const label = outcome.charAt(0) + outcome.slice(1).toLowerCase();
  await admin.getByRole('button', { name: `Preview ${label} resolution` }).click();
  await expect(admin.getByRole('heading', { name: `${label} impact` })).toBeVisible();
  const confirm = admin.getByRole('button', { name: `Confirm ${label}` });
  await expect(confirm).toBeDisabled();
  await admin.getByRole('checkbox', { name: new RegExp(`I confirm this exact ${label} resolution`) }).check();
  await confirm.click();
  await expect(admin.getByRole('heading', { name: label, exact: true }).last()).toBeVisible();
  await expect(admin.getByText('This final outcome is immutable.')).toBeVisible();
}

function analyticsMetric(section: Locator, page: Page, label: string): Locator {
  return section.locator('.metric').filter({ has: page.getByText(label, { exact: true }) }).locator('strong');
}

function requireDisposableAppealSetup() {
  test.skip(process.env['E2E_SKIP_GLOBAL_SETUP'] === 'true',
    'Live appeal workflows require the disposable global reset; only the mocked UI test may skip setup.');
}

async function revokeUser(admin: Page, userId: string, actionId: string) {
  const detail = await apiData<any>(admin, `/api/v1/admin/users/${userId}`);
  const action = detail.activeEnforcementActions.find((value: any) => value.enforcementActionId === actionId);
  if (action) await apiDataCommand(admin, `/api/v1/admin/users/${userId}/enforcements/${actionId}/revoke`, {
    expectedEnforcementVersion: action.version, reasonCode: 'E2E_CLEANUP', reason: 'Restore user fixture.',
    idempotencyKey: `e2e-appeal-cleanup-${actionId}`, safeMetadata: { origin: 'PLAYWRIGHT' },
  });
}

async function revokeBusiness(admin: Page, actionId: string) {
  const detail = await apiData<any>(admin, `/api/v1/admin/businesses/${BUSINESS_ID}`);
  const action = detail.activeEnforcementActions.find((value: any) => value.enforcementActionId === actionId);
  if (action) await apiDataCommand(admin, `/api/v1/admin/businesses/${BUSINESS_ID}/enforcements/${actionId}/revoke`, {
    expectedEnforcementVersion: action.version, reasonCode: 'E2E_CLEANUP', reason: 'Restore business fixture.',
    idempotencyKey: `e2e-appeal-cleanup-${actionId}`, safeMetadata: { origin: 'PLAYWRIGHT' },
  });
}

async function revokeListing(admin: Page, actionId: string) {
  const detail = await apiJson<any>(admin, `/api/v1/admin/listings/${LISTING_ID}/enforcements`);
  const action = detail.activeEnforcementActions.find((value: any) => value.enforcementActionId === actionId);
  if (action) await apiCommand(admin, `/api/v1/admin/listings/${LISTING_ID}/enforcements/${actionId}/revoke`, {
    expectedEnforcementVersion: action.version, reasonCode: 'E2E_CLEANUP', reason: 'Restore listing fixture.',
    idempotencyKey: `e2e-appeal-cleanup-${actionId}`, safeMetadata: { origin: 'PLAYWRIGHT' },
  });
}

async function appealCommandStatus(page: Page, actionId: string, reasonCode: string, explanation: string) {
  return commandStatus(page, `/api/v1/enforcements/${actionId}/appeals`,
    { reasonCode, explanation, safeEvidenceReferences: [] });
}

async function apiData<T>(page: Page, path: string): Promise<T> {
  return (await apiJson<{ data: T }>(page, path)).data;
}
async function apiJson<T>(page: Page, path: string): Promise<T> {
  return page.evaluate(async endpoint => {
    const response = await fetch(endpoint, { credentials: 'include' });
    if (!response.ok) throw new Error(`GET ${endpoint} failed: ${response.status}`);
    return response.json();
  }, path) as Promise<T>;
}
async function apiDataCommand<T>(page: Page, path: string, body: unknown): Promise<T> {
  return (await apiCommand<{ data: T }>(page, path, body)).data;
}
async function apiCommand<T>(page: Page, path: string, body: unknown): Promise<T> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' }).then(response => response.json());
    const response = await fetch(request.path, { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token },
      body: JSON.stringify(request.body) });
    if (!response.ok) throw new Error(`POST ${request.path} failed: ${response.status} ${await response.text()}`);
    return response.json();
  }, { path, body }) as Promise<T>;
}
async function commandStatus(page: Page, path: string, body: unknown) {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' }).then(response => response.json());
    return (await fetch(request.path, { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token },
      body: JSON.stringify(request.body) })).status;
  }, { path, body });
}

async function marketplacePage(browser: Browser, user: { email: string; password: string }) {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto('/marketplace'); await page.getByRole('button', { name: 'Login' }).click();
  const dialog = page.getByRole('dialog', { name: 'Sign in to keep trading local.' });
  await dialog.getByLabel('Email').fill(user.email); await dialog.getByLabel('Password').fill(user.password);
  const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/auth/native/login');
  await dialog.locator('button[type="submit"]').click();
  const response = await loginResponse;
  if (!response.ok()) throw new Error(`Marketplace login failed: ${response.status()} ${await response.text()}`);
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible(); return page;
}
async function adminPage(browser: Browser, user: { email: string; password: string }) {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto('/admin/dashboard'); await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page.locator('#username').fill(user.email); await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click(); await page.waitForURL(/\/admin\/dashboard/); return page;
}

async function mockAppealAdmin(page: Page) {
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true,
    user: { subject: '01KAPLADMIN000000000000001', email: 'appeals@msb.local',
      displayName: 'Appeal Executor', roles: ['PLATFORM_ADMIN'] },
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
  } }));
  await page.route('**/api/v1/users/me', route => route.fulfill({ status: 503, json: {
    error: { message: 'Use session fallback.' },
  } }));
  await page.route('**/api/v1/admin/me', route => route.fulfill({ json: { data: {
    userId: '01KAPLADMIN000000000000001', role: 'PLATFORM_ADMIN', roles: ['PLATFORM_ADMIN'],
    permissions: ['admin.appeal.read', 'admin.appeal.resolve', 'admin.user.reinstate'], accountState: 'ACTIVE',
  } } }));
}

function mockedAppealDetail(appealId: string, resolved = false) {
  const enforcementActionId = '01KAPLENFORCEMENT0000000001';
  return {
    appealId, enforcementActionId, targetType: 'USER', targetId: SELLER_ID,
    safeTargetLabel: 'Marketplace account', status: resolved ? 'REVOKED' : 'REVOKE_RECOMMENDED',
    submittedAt: '2026-08-24T00:00:00Z', updatedAt: resolved ? '2026-08-24T00:05:00Z' : '2026-08-24T00:02:00Z',
    version: resolved ? 5 : 4,
    appellantSummary: { appellantType: 'USER', userId: SELLER_ID, safeDisplayName: 'Affected user' },
    reasonCode: 'DECISION_INCORRECT', explanation: 'The original action used incomplete context.',
    safeEvidenceReferences: [], assignedAdmin: { userId: '01KAPLADMIN000000000000001', safeDisplayName: 'Reviewer' },
    reviewStartedAt: '2026-08-24T00:01:00Z', reviewedAt: '2026-08-24T00:02:00Z',
    reviewOutcome: 'REVOKE_RECOMMENDED', reviewReasonCode: 'WRONG_DECISION',
    reviewReason: 'The original action should be revoked.', replacementProposal: null,
    resolvedAt: resolved ? '2026-08-24T00:05:00Z' : null, finalOutcome: resolved ? 'REVOKED' : null,
    resolutionSummary: resolved ? 'The original enforcement was revoked after final review.' : null,
    replacementEnforcementSummary: null,
    enforcementSummary: { enforcementActionId, targetType: 'USER', targetId: SELLER_ID,
      actionType: 'SUSPEND', scopes: ['USER_SELLING'], lifecycleState: resolved ? 'REVOKED' : 'ACTIVE',
      effectiveAt: '2026-08-23T00:00:00Z', expiresAt: null, version: 2,
      createdAt: '2026-08-23T00:00:00Z', reasonCode: 'POLICY', reason: 'Reviewed policy violation.', caseId: null },
    currentEnforcementState: resolved ? 'CLEAR' : 'SUSPENDED', originalCaseSummary: null, linkedReports: [],
    originalTargetSnapshotContext: { status: 'ACTIVE', version: 6 },
    currentTargetSummary: { status: 'ACTIVE', version: 7 }, enforcementTimeline: [], caseTimeline: [],
    appealTimeline: [{ eventId: '01KAPLEVENT000000000000001', occurredAt: '2026-08-24T00:02:00Z',
      eventType: 'APPEAL_REVOKE_RECOMMENDED', actorType: 'ADMIN', actorId: null,
      actorDisplayName: 'Reviewer', source: 'HUMAN_ADMIN', previousState: 'UNDER_REVIEW',
      newState: 'REVOKE_RECOMMENDED', reasonCode: null, reason: null, correlationId: null,
      requestId: null, safeMetadata: {} }], internalReviewNotes: [], createdAt: '2026-08-24T00:00:00Z',
    availableAdminCapabilities: { canRead: true, canClaim: false, canRelease: false, canStartReview: false,
      canAddNote: false, canReview: false, canRecommendUphold: false, canRecommendModify: false,
      canRecommendRevoke: false, canResolveUphold: false, canResolveModify: false,
      canResolveRevoke: !resolved, isAssignedToMe: true, isAssignedToOther: false,
      isFinalRecommendation: !resolved, isFinalResolution: resolved, isReadOnly: true,
      readOnlyReason: resolved ? 'Final appeals are read-only.' : 'Recommendation recorded.' },
  };
}

function setBusinessStaff(enabled: boolean) {
  const container = process.env['E2E_MYSQL_CONTAINER'] || 'msb-demo-mysql';
  const password = process.env['E2E_MYSQL_PASSWORD'] || 'demo-change-me-mysql';
  const sql = enabled
    ? `INSERT INTO identity.business_memberships (business_id,user_id,role,status,invited_by,created_at,updated_at)
       VALUES ('${BUSINESS_ID}','${SELLER_ID}','STAFF','ACTIVE',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
       ON DUPLICATE KEY UPDATE role='STAFF',status='ACTIVE',updated_at=CURRENT_TIMESTAMP(6)`
    : `DELETE FROM identity.business_memberships WHERE business_id='${BUSINESS_ID}' AND user_id='${SELLER_ID}' AND role='STAFF'`;
  execFileSync('docker', ['exec', container, 'mysql', '-uroot', `-p${password}`, 'identity', '-e', sql], { stdio: 'pipe' });
}
