import { expect, Page, test } from '@playwright/test';

const ADMIN = '01KANALYTICSADMIN0000000001';

test('analytics reader inspects a partial snapshot and drills into the owned report queue', async ({ page }) => {
  await mockAdmin(page, ['admin.analytics.read', 'admin.report.read']);
  let overviewQuery: URLSearchParams | null = null;
  let trendQuery: URLSearchParams | null = null;
  let reportAssignment: string | null = null;
  let reportUnresolved: string | null = null;
  let nonGetAnalyticsCalls = 0;

  await page.route('**/api/v1/admin/analytics/overview?**', route => {
    overviewQuery = new URL(route.request().url()).searchParams;
    if (route.request().method() !== 'GET') nonGetAnalyticsCalls++;
    return route.fulfill({ json: overview() });
  });
  await page.route('**/api/v1/admin/analytics/trends?**', route => {
    trendQuery = new URL(route.request().url()).searchParams;
    if (route.request().method() !== 'GET') nonGetAnalyticsCalls++;
    return route.fulfill({ json: trend() });
  });
  await page.route('**/api/v1/admin/reports?**', route => {
    const query = new URL(route.request().url()).searchParams;
    reportAssignment = query.get('assignment');
    reportUnresolved = query.get('unresolved');
    return route.fulfill({ json: { data: {
      items: [], page: 0, size: 25, totalElements: 0, totalPages: 0, sort: 'createdAt,desc',
    } } });
  });

  await page.goto('/admin/analytics');

  await expect(page.getByRole('heading', { name: 'Operational analytics' })).toBeVisible();
  await expect(page.getByText('Support source is temporarily unavailable.')).toBeVisible();
  const trust = page.locator('article.analytics-section').filter({
    has: page.getByRole('heading', { name: 'Trust & Safety' }),
  });
  await expect(trust.locator('.metric').filter({ hasText: 'Finalized appeals' }).locator('strong')).toHaveText('4');
  await expect(trust.locator('.metric').filter({ hasText: 'Upheld' }).locator('strong')).toHaveText('2');
  await expect(trust.locator('.metric').filter({ hasText: 'Modified' }).locator('strong')).toHaveText('1');
  await expect(trust.locator('.metric').filter({ hasText: 'Revoked' }).locator('strong')).toHaveText('1');
  await expect(trust.locator('.metric').filter({ hasText: 'Appeals changed/reversed' }).locator('strong'))
    .toHaveText('50%');
  await expect(trust.getByText(/recommendation.*degraded/i)).toHaveCount(0);
  const restrictedNotices = page.getByText('This section is outside your current analytics visibility.');
  await expect(restrictedNotices).toHaveCount(2);
  await expect(restrictedNotices.first()).toBeVisible();
  await expect(page.getByText('Succeeded refund amount')).toHaveCount(0);
  await expect(page.getByRole('table', { name: 'Refund succeeded / failed amounts' })).toHaveCount(0);
  await expect(page.getByRole('table', { name: 'Pending and processing refund amounts' })).toHaveCount(0);
  await expect(page.locator('caption', { hasText: /complete accessible data/ })).toBeVisible();
  expect(overviewQuery?.get('timezone')).toBe('UTC');
  expect(overviewQuery?.get('range')).toBe('CUSTOM');
  expect(overviewQuery?.get('compare')).toBe('true');
  expect(trendQuery?.get('range')).toBe('CUSTOM');
  expect(Date.parse(overviewQuery?.get('to') || '') - Date.parse(overviewQuery?.get('from') || ''))
    .toBe(30 * 24 * 60 * 60 * 1000);

  await page.getByRole('link', { name: /Unassigned reports/ }).click();

  await expect(page).toHaveURL(/\/admin\/reports\?assignment=UNASSIGNED&unresolved=true/);
  await expect.poll(() => reportAssignment).toBe('UNASSIGNED');
  await expect.poll(() => reportUnresolved).toBe('true');
  await expect(page.getByRole('button', { name: 'Unassigned' })).toHaveAttribute('aria-pressed', 'true');
  expect(nonGetAnalyticsCalls).toBe(0);
});

test('finance-authorized analytics exposes order and refund counts with permitted money breakdowns', async ({ page }) => {
  await mockAdmin(page, ['admin.analytics.read', 'admin.finance.read']);
  const analyticsMethods: string[] = [];

  await page.route('**/api/v1/admin/analytics/overview?**', route => {
    analyticsMethods.push(route.request().method());
    return route.fulfill({ json: financeOverview() });
  });
  await page.route('**/api/v1/admin/analytics/trends?**', route => {
    analyticsMethods.push(route.request().method());
    return route.fulfill({ json: trend() });
  });

  await page.goto('/admin/analytics');

  const commerce = page.locator('article.analytics-section').filter({
    has: page.getByRole('heading', { name: 'Commerce outcomes' }),
  });
  const orders = commerce.locator('.metric').filter({ hasText: 'Orders created' });
  const refunds = commerce.locator('.metric').filter({ hasText: 'Refunds succeeded' });
  await expect(orders.locator('strong')).toHaveText('18');
  await expect(refunds.locator('strong')).toHaveText('3');

  const orderAmounts = page.getByRole('table', { name: 'Gross order amounts created' });
  await expect(orderAmounts.getByRole('columnheader')).toHaveText([
    'Category', 'Selected period', 'Previous period',
  ]);
  await expect(orderAmounts.getByRole('row', { name: /USD/ }).getByRole('cell')).toHaveText(['2,450', '1,980']);

  const refundAmounts = page.getByRole('table', { name: 'Refund succeeded / failed amounts' });
  await expect(refundAmounts.getByRole('columnheader')).toHaveText([
    'Category', 'Succeeded amount', 'Failed amount',
  ]);
  await expect(refundAmounts.getByRole('row', { name: /USD/ }).getByRole('cell')).toHaveText(['125', '8']);

  const activeRefunds = page.getByRole('table', { name: 'Pending and processing refund amounts' });
  await expect(activeRefunds.getByRole('columnheader')).toHaveText([
    'Category', 'Pending and processing amount',
  ]);
  await expect(activeRefunds.getByRole('link')).toHaveCount(0);
  expect(analyticsMethods).not.toContain('POST');
  expect(analyticsMethods.every(method => method === 'GET')).toBe(true);
});

test('operations analytics drills into exact read-only outbox failure queues', async ({ page }) => {
  await mockAdmin(page, ['admin.analytics.read', 'admin.system.read']);
  const outboxStatuses: Array<string | null> = [];
  const systemRequests: Array<{ method: string; pathname: string }> = [];

  await page.route('**/api/v1/admin/analytics/overview?**', route =>
    route.fulfill({ json: operationsOverview() }));
  await page.route('**/api/v1/admin/analytics/trends?**', route =>
    route.fulfill({ json: trend() }));
  await page.route('**/api/v1/admin/system/**', route => {
    const request = route.request();
    const url = new URL(request.url());
    systemRequests.push({ method: request.method(), pathname: url.pathname });
    if (url.pathname.endsWith('/outbox')) {
      outboxStatuses.push(url.searchParams.get('status'));
      return route.fulfill({ json: {
        items: [], page: 0, size: 25, totalElements: 0, totalPages: 0,
      } });
    }
    return route.fulfill({ status: 404, json: { error: { message: 'Unexpected system request.' } } });
  });

  await page.goto('/admin/analytics');

  const operations = page.locator('article.analytics-section').filter({
    has: page.getByRole('heading', { name: 'System operations' }),
  });
  const failedOutbox = operations.locator('a.metric').filter({ hasText: 'Failed outbox events' });
  const deadLetter = operations.locator('a.metric').filter({ hasText: 'Dead-letter events' });
  await expect(failedOutbox).toHaveAttribute('href', '/admin/system/outbox?status=FAILURE');
  await expect(deadLetter).toHaveAttribute('href', '/admin/system/outbox?status=DEAD_LETTER');

  await failedOutbox.click();
  await expect(page).toHaveURL(/\/admin\/system\/outbox\?status=FAILURE$/);
  await expect(page.getByRole('combobox', { name: 'Status' })).toHaveValue('FAILURE');
  await expect.poll(() => outboxStatuses.at(-1)).toBe('FAILURE');

  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Operational analytics' })).toBeVisible();
  await operations.locator('a.metric').filter({ hasText: 'Dead-letter events' }).click();
  await expect(page).toHaveURL(/\/admin\/system\/outbox\?status=DEAD_LETTER$/);
  await expect(page.getByRole('combobox', { name: 'Status' })).toHaveValue('DEAD_LETTER');
  await expect.poll(() => outboxStatuses.at(-1)).toBe('DEAD_LETTER');

  expect(outboxStatuses).toEqual(['FAILURE', 'DEAD_LETTER']);
  expect(systemRequests.every(request => request.method === 'GET')).toBe(true);
  expect(systemRequests.some(request => request.pathname.includes('/retry'))).toBe(false);
});

test('admin without analytics permission is stopped by the child route guard', async ({ page }) => {
  await mockAdmin(page, ['admin.report.read']);

  await page.goto('/admin/analytics');

  await expect(page).toHaveURL(/\/admin-access-denied\?returnUrl=/);
  await expect(page.getByRole('heading', { name: 'Admin access denied.' })).toBeVisible();
});

async function mockAdmin(page: Page, permissions: string[]) {
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true,
    user: { subject: ADMIN, email: 'analytics@msb.local', displayName: 'Analytics Admin', roles: ['AUDITOR'] },
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
  } }));
  await page.route('**/api/v1/users/me', route => route.fulfill({ status: 503, json: {
    error: { message: 'Use session fallback.' },
  } }));
  await page.route('**/api/v1/admin/me', route => route.fulfill({ json: { data: {
    userId: ADMIN, role: 'AUDITOR', roles: ['AUDITOR'], permissions, accountState: 'ACTIVE',
  } } }));
}

function overview() {
  return {
    range: {
      from: '2026-07-25T00:00:00Z', to: '2026-08-24T00:00:00Z', timezone: 'UTC',
      comparisonFrom: '2026-06-25T00:00:00Z', comparisonTo: '2026-07-25T00:00:00Z',
    },
    capabilities: { financialAmounts: false, operations: false, governance: false },
    marketplace: section('AVAILABLE', [metric('ordersCreated', 'Orders created', 12, 9)]),
    moderation: section(),
    trustAndSafety: section('AVAILABLE', [
      metric('unassignedReports', 'Unassigned reports', 4, 3, 'UNASSIGNED_REPORTS'),
      metric('appealsFinalized', 'Finalized appeals', 4, null),
      metric('appealsUpheld', 'Upheld', 2, null),
      metric('appealsModified', 'Modified', 1, null),
      metric('appealsRevoked', 'Revoked', 1, null),
      metric('appealAdjustmentRate', 'Appeals changed/reversed', 50, null, undefined, 'PERCENT'),
    ]),
    commerce: section('AVAILABLE', [metric('refundsSucceeded', 'Refunds succeeded', 2, 1)]),
    support: section('UNAVAILABLE', [], 'Support source is temporarily unavailable.'),
    catalog: section(),
    operations: section('RESTRICTED'),
    governance: section('RESTRICTED'),
    generatedAt: '2026-08-23T13:46:00Z',
  };
}

function financeOverview() {
  return {
    ...overview(),
    capabilities: { financialAmounts: true, operations: false, governance: false },
    marketplace: section('AVAILABLE', [metric('ordersCreated', 'Orders created', 18, 14)]),
    commerce: section('AVAILABLE', [
      metric('ordersCreated', 'Orders created', 18, 14),
      metric('refundsSucceeded', 'Refunds succeeded', 3, 2),
    ], null, [
      {
        key: 'grossOrderAmountsCreated', label: 'Gross order amounts created', unit: 'MONEY',
        primaryLabel: 'Selected period', secondaryLabel: 'Previous period',
        items: [{ key: 'USD', label: 'USD', value: 2450, secondaryValue: 1980 }],
      },
      {
        key: 'succeededPaymentAmounts', label: 'Succeeded payment amounts', unit: 'MONEY',
        primaryLabel: 'Selected period', secondaryLabel: 'Previous period',
        items: [{ key: 'USD', label: 'USD', value: 2210, secondaryValue: 1765 }],
      },
      {
        key: 'refundAmounts', label: 'Refund succeeded / failed amounts', unit: 'MONEY',
        primaryLabel: 'Succeeded amount', secondaryLabel: 'Failed amount',
        items: [{ key: 'USD', label: 'USD', value: 125, secondaryValue: 8 }],
      },
      {
        key: 'activeRefundAmounts', label: 'Pending and processing refund amounts', unit: 'MONEY',
        primaryLabel: 'Pending and processing amount', secondaryLabel: null,
        items: [{ key: 'USD', label: 'USD', value: 42 }],
      },
    ]),
  };
}

function operationsOverview() {
  return {
    ...overview(),
    capabilities: { financialAmounts: false, operations: true, governance: false },
    operations: section('AVAILABLE', [
      metric('failedOutboxEvents', 'Failed outbox events', 7, null, 'FAILED_OUTBOX_EVENTS'),
      metric('deadLetterEvents', 'Dead-letter events', 2, null, 'DEAD_LETTER_OUTBOX_EVENTS'),
      metric('reconciliationIssues', 'Reconciliation issues', 3, null, 'RECONCILIATION_ISSUES'),
    ]),
  };
}

function section(
  status = 'AVAILABLE',
  metrics: unknown[] = [],
  safeMessage: string | null = null,
  breakdowns: unknown[] = [],
) {
  return { status, safeMessage, dataAsOf: '2026-08-23T13:45:00Z', metrics, breakdowns };
}

function metric(
  key: string,
  label: string,
  currentValue: number,
  previousValue: number | null,
  drillDownKey?: string,
  unit = 'COUNT',
) {
  const comparable = previousValue !== null;
  return {
    key, label, currentValue, previousValue,
    absoluteChange: previousValue === null ? null : currentValue - previousValue,
    percentageChange: previousValue !== null && previousValue !== 0
      ? (currentValue - previousValue) / previousValue * 100
      : null,
    comparisonState: comparable ? 'VALUE' : 'NOT_APPLICABLE', unit, drillDownKey,
  };
}

function trend() {
  return {
    metric: 'REPORTS_SUBMITTED', label: 'Reports submitted', unit: 'COUNT',
    range: { from: '2026-07-25T00:00:00Z', to: '2026-08-24T00:00:00Z', timezone: 'UTC' },
    granularity: 'DAY', status: 'AVAILABLE', safeMessage: null,
    points: [
      { bucketStart: '2026-08-22T00:00:00Z', bucketEnd: '2026-08-23T00:00:00Z', value: 2 },
      { bucketStart: '2026-08-23T00:00:00Z', bucketEnd: '2026-08-24T00:00:00Z', value: 4 },
    ],
    generatedAt: '2026-08-23T13:46:00Z',
  };
}
