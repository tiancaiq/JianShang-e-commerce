import { expect, Page, test } from '@playwright/test';

const ADMIN = '01KSYSADMIN000000000000001';
const EVENT = '01KSYSEVENT000000000000001';
const LISTING = '01KSYSLISTING0000000000001';

test('operations admin sees safe partial health without internal details', async ({ page }) => {
  await mockAdmin(page, ['admin.system.read', 'admin.system.retry', 'admin.search.maintenance', 'admin.feature.read']);
  await mockSummary(page);

  await page.goto('/admin/system');

  await expect(page.getByRole('heading', { name: 'System operations' })).toBeVisible();
  await expect(page.getByText('Runtime attention required')).toBeVisible();
  await expect(page.getByText('Payment')).toBeVisible();
  await expect(page.getByText('UNAVAILABLE', { exact: true })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('jdbc:');
  await expect(page.locator('body')).not.toContainText('password=');
  await expect(page.locator('body')).not.toContainText('10.0.');
});

test('failed outbox recovery requires preview and reports accepted rather than success', async ({ page }) => {
  await mockAdmin(page, ['admin.system.read', 'admin.system.retry', 'admin.feature.read']);
  let previewed = false;
  let accepted = 0;
  await page.route('**/api/v1/admin/system/outbox*', route => route.fulfill({ json: pageOf([outbox()]) }));
  await page.route(`**/api/v1/admin/system/outbox/${EVENT}/retry/dry-run`, route => {
    previewed = true;
    return route.fulfill({ json: preview('OUTBOX_EVENT', EVENT, 'RETRY_OUTBOX') });
  });
  await page.route(`**/api/v1/admin/system/outbox/${EVENT}/retry`, route => {
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    expect(JSON.parse(route.request().postData() || '{}').reason).toContain('transport');
    accepted++;
    return route.fulfill({ status: 202, json: result('OUTBOX_EVENT', EVENT, 'RETRY_OUTBOX') });
  });

  await page.goto('/admin/system/outbox');
  await page.getByRole('button', { name: 'Review retry' }).click();
  await page.getByLabel('Operational reason').fill('Retry after the transport dependency recovered');
  await page.getByRole('button', { name: 'Run safe preview' }).click();
  await expect(page.getByText('Schedule the existing event for delivery.')).toBeVisible();
  await page.getByRole('button', { name: 'Request retry' }).click();

  expect(previewed).toBe(true);
  expect(accepted).toBe(1);
  await expect(page.getByText('ACCEPTED', { exact: true })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('SUCCESS');
});

test('bounded search reindex queues current listing state and exposes legacy gate', async ({ page }) => {
  await mockAdmin(page, ['admin.system.read', 'admin.search.maintenance', 'admin.feature.read']);
  let reindexed = false;
  await page.route('**/api/v1/admin/system/search', route => route.fulfill({ json: [{
    ownerService: 'PRODUCT', status: 'DEGRADED', pendingOperations: 1, failedOperations: 1,
    lastSuccessfulIndexingAt: '2026-08-19T00:00:00Z', indexVersion: 'marketplace-public-listing-v1',
    safeSummary: 'Some listing projection work requires attention.', issues: [],
  }] }));
  await page.route(`**/api/v1/admin/system/search/listings/${LISTING}/reindex/dry-run`, route =>
    route.fulfill({ json: preview('SEARCH_LISTING', LISTING, 'REINDEX_LISTING') }));
  await page.route(`**/api/v1/admin/system/search/listings/${LISTING}/reindex`, route => {
    reindexed = true;
    return route.fulfill({ status: 202, json: result('SEARCH_LISTING', LISTING, 'REINDEX_LISTING') });
  });

  await page.goto('/admin/system/search');
  await expect(page.getByText('Legacy Search Maintenance remains gated')).toBeVisible();
  await page.getByLabel('Listing ID').fill(LISTING);
  await page.getByRole('button', { name: 'Review reindex' }).click();
  await page.getByLabel('Operational reason').fill('Repair one failed listing search projection');
  await page.getByRole('button', { name: 'Run safe preview' }).click();
  await page.getByRole('button', { name: 'Request reindex' }).click();
  await expect.poll(() => reindexed).toBe(true);
  await expect(page.getByText('ACCEPTED', { exact: true })).toBeVisible();
});

test('system reader can inspect but cannot request recovery', async ({ page }) => {
  await mockAdmin(page, ['admin.system.read', 'admin.feature.read'], ['AUDITOR']);
  await page.route('**/api/v1/admin/system/outbox*', route => route.fulfill({ json: pageOf([outbox()]) }));

  await page.goto('/admin/system/outbox');

  await expect(page.getByText('Operations access is read-only for this role.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review retry' })).toHaveCount(0);
});

async function mockAdmin(page: Page, permissions: string[], roles = ['OPERATIONS_ADMIN']) {
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true,
    user: { subject: ADMIN, email: 'operations@msb.local', displayName: 'Operations Admin', roles },
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
  } }));
  await page.route('**/api/v1/users/me', route => route.fulfill({ status: 503, json: {
    error: { message: 'Use session fallback.' },
  } }));
  await page.route('**/api/v1/admin/me', route => route.fulfill({ json: { data: {
    userId: ADMIN, role: roles[0], roles, permissions, accountState: 'ACTIVE',
  } } }));
}

async function mockSummary(page: Page) {
  await page.route('**/api/v1/admin/system/summary', route => route.fulfill({ json: {
    generatedAt: '2026-08-20T00:00:00Z', serviceHealth: { primary: 1, secondary: 1 },
    jobs: { primary: 1, secondary: 1 }, outbox: { primary: 1, secondary: 0 },
    reconciliationRequiresAttention: 1, inventoryPotentiallyStuck: 1,
    failedIndexOperations: 1, featureWarnings: 0, sourceWarnings: [], recentOperations: [],
  } }));
  await page.route('**/api/v1/admin/system/health', route => route.fulfill({ json: [
    { serviceKey: 'AUTH', displayName: 'Auth', status: 'HEALTHY', checkedAt: '2026-08-20T00:00:00Z', responseLatencyMs: 10, safeSummary: 'Health endpoint responded normally.' },
    { serviceKey: 'PAYMENT', displayName: 'Payment', status: 'UNAVAILABLE', checkedAt: '2026-08-20T00:00:00Z', responseLatencyMs: 2000, safeSummary: 'Health endpoint could not be reached within the bounded check.' },
  ] }));
}

function outbox() {
  return { eventId: EVENT, ownerService: 'PAYMENT', eventType: 'refund.requested', aggregateType: 'REFUND',
    aggregateId: 'refund-1', status: 'FAILED', attemptCount: 2, createdAt: '2026-08-19T00:00:00Z',
    lastAttemptAt: '2026-08-19T01:00:00Z', nextAttemptAt: null, correlationId: 'safe-correlation',
    safeFailureCode: 'TRANSPORT_UNAVAILABLE', safeFailureSummary: 'The transport was unavailable.', retryable: true };
}
function pageOf<T>(items: T[]) { return { items, page: 0, size: 25, totalElements: items.length, totalPages: 1 }; }
function preview(targetType: string, targetId: string, commandType: string) {
  return { targetType, targetId, ownerService: targetType === 'SEARCH_LISTING' ? 'PRODUCT' : 'PAYMENT',
    commandType, currentState: 'FAILED', allowed: true, denialReason: null,
    knownDependencies: ['Existing owning worker'], warnings: ['Consumer idempotency remains authoritative.'],
    expectedOperation: targetType === 'SEARCH_LISTING' ? 'Queue one current-state listing projection.' : 'Schedule the existing event for delivery.',
    customerImpact: 'No marketplace domain state is directly edited.' };
}
function result(targetType: string, targetId: string, commandType: string) {
  return { commandId: '01KSYSCOMMAND0000000000001', targetType, targetId,
    ownerService: targetType === 'SEARCH_LISTING' ? 'PRODUCT' : 'PAYMENT', commandType,
    result: 'ACCEPTED', replay: false, requestedAt: '2026-08-20T00:00:00Z',
    safeSummary: 'The owning worker accepted the recovery request.' };
}
