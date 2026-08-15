import { Browser, expect, Page, test } from '@playwright/test';

const BUSINESS_APPLICATION_ID = '01E00000000000000000000101';
const EXISTING_LISTING_ID = '01D00000000000000000000101';
const SUPER_ADMIN = { email: 'admin.one@msb.local', password: 'AdminOne!2026' };
const OWNER = { email: 'trade.seller@msb.local', password: 'TradeSeller!2026' };
const AUDITOR = { email: 'admin.auditor@msb.local', password: 'AdminAuditor!2026' };

test('active business restriction blocks listing creation, is auditable, and can be reinstated', async ({ browser }) => {
  test.setTimeout(180_000);
  const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
  const owner = await authenticatedMarketplacePage(browser, OWNER);
  let businessId = '';
  try {
    businessId = await ensureActiveBusiness(admin);
    await admin.goto('/admin/businesses');
    await admin.getByRole('textbox', { name: 'Search' }).fill(businessId);
    await admin.getByRole('button', { name: 'Apply' }).click();
    await admin.locator(`a[href="/admin/businesses/${businessId}"]`).click();
    await expect(admin.getByRole('heading', { name: 'Harbor Workshop LLC' })).toBeVisible();

    await admin.getByLabel('Listing creation').check();
    await admin.getByLabel('Publication').check();
    await admin.getByLabel('New sales').uncheck();
    await admin.getByLabel('Reason code').fill('POLICY_VIOLATION');
    await admin.getByLabel('Required explanation').fill('Business listing boundary release verification.');
    await admin.getByRole('button', { name: 'Preview action' }).click();
    await expect(admin.getByText('Dry-run impact', { exact: true })).toBeVisible();
    await admin.getByRole('button', { name: 'Confirm enforcement' }).click();
    await expect(admin.getByRole('button', { name: 'Reinstate' })).toBeVisible();
    await expect(admin.getByText('Enforcement created', { exact: true }).last()).toBeVisible();

    const capabilities = await businessCapabilities(owner, businessId);
    expect(capabilities.listingCreationAllowed).toBe(false);
    expect(capabilities.listingPublicationAllowed).toBe(false);
    expect(capabilities.newSalesAllowed).toBe(true);

    const blocked = await createBusinessListing(owner, businessId);
    expect(blocked.status).toBe(403);
    expect(blocked.body).toContain('BUSINESS_CAPABILITY_RESTRICTED');

    await admin.getByRole('button', { name: 'Reinstate' }).first().click();
    await admin.getByLabel('Reinstatement reason').fill('Business listing boundary verification complete.');
    await admin.getByRole('button', { name: 'Preview reinstatement' }).click();
    await admin.getByRole('button', { name: 'Confirm reinstatement' }).click();
    await expect(admin.getByRole('button', { name: 'Reinstate' })).toHaveCount(0);
    await expect.poll(async () => (await businessCapabilities(owner, businessId)).listingCreationAllowed)
      .toBe(true);
    await expect(admin.getByText('Enforcement revoked', { exact: true }).last()).toBeVisible();
  } finally {
    if (businessId) await revokeFixtureEnforcements(admin, businessId);
    await admin.context().close();
    await owner.context().close();
  }
});

test('auditor can inspect business history but direct mutation is forbidden', async ({ browser }) => {
  test.setTimeout(180_000);
  const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
  const auditor = await authenticatedAdminPage(browser, AUDITOR);
  let businessId = '';
  try {
    businessId = await ensureActiveBusiness(admin);
    await auditor.goto(`/admin/businesses/${businessId}`);
    await expect(auditor.getByRole('heading', { name: 'Harbor Workshop LLC' })).toBeVisible();
    await expect(auditor.getByText('Audit timeline', { exact: true })).toBeVisible();
    await expect(auditor.getByText('Apply business marketplace enforcement', { exact: true })).toHaveCount(0);
    expect(await directCreateStatus(auditor, businessId)).toBe(403);
  } finally {
    if (businessId) await revokeFixtureEnforcements(admin, businessId);
    await admin.context().close();
    await auditor.context().close();
  }
});

async function ensureActiveBusiness(page: Page): Promise<string> {
  let application = await apiData<any>(page, `/api/v1/admin/business-applications/${BUSINESS_APPLICATION_ID}`);
  if (application.status !== 'APPROVED') {
    const session = await csrfSession(page);
    const response = await page.request.post(
      `/api/v1/admin/business-applications/${BUSINESS_APPLICATION_ID}/decision`,
      {
        headers: {
          'Content-Type': 'application/json',
          'If-Match': String(application.version),
          Cookie: await cookieHeader(page),
          [session.headerName]: session.token,
        },
        data: { decision: 'APPROVE', reason: 'Business enforcement browser fixture approval.' },
      },
    );
    expect(response.ok()).toBe(true);
    application = await apiData<any>(page, `/api/v1/admin/business-applications/${BUSINESS_APPLICATION_ID}`);
  }
  expect(application.approvedBusinessId).toBeTruthy();
  return application.approvedBusinessId;
}

async function businessCapabilities(page: Page, businessId: string): Promise<{
  listingCreationAllowed: boolean;
  listingPublicationAllowed: boolean;
  newSalesAllowed: boolean;
}> {
  return apiData(page, `/api/v1/businesses/${businessId}/marketplace-capabilities`);
}

async function createBusinessListing(page: Page, businessId: string): Promise<{ status: number; body: string }> {
  return page.evaluate(async ({ businessId, existingListingId }) => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const listing = await fetch(`/api/v1/listings/${existingListingId}`, { credentials: 'include' })
      .then(response => response.json()) as { categoryId: string };
    const response = await fetch(`/api/v1/businesses/${businessId}/store/items`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token },
      body: JSON.stringify({
        sellerType: 'BUSINESS', businessId, categoryId: listing.categoryId,
        title: 'Blocked business fixture listing', description: 'Must not be persisted.',
        condition: 'GOOD', conditionNotes: 'Browser boundary verification.',
        price: { amount: 25, currency: 'USD' }, negotiable: false,
        location: { city: 'Seattle', region: 'WA' }, sku: 'E2E-BLOCKED-001', quantity: 1,
      }),
    });
    return { status: response.status, body: await response.text() };
  }, { businessId, existingListingId: EXISTING_LISTING_ID });
}

async function directCreateStatus(page: Page, businessId: string): Promise<number> {
  const detail = await apiData<any>(page, `/api/v1/admin/businesses/${businessId}`);
  const session = await csrfSession(page);
  const response = await page.request.post(`/api/v1/admin/businesses/${businessId}/enforcements`, {
    headers: {
      'Content-Type': 'application/json', Cookie: await cookieHeader(page),
      [session.headerName]: session.token,
    },
    data: {
      actionType: 'RESTRICT', scopes: ['BUSINESS_LISTING_CREATION'],
      reasonCode: 'POLICY_VIOLATION', reason: 'Forbidden command verification.',
      effectiveAt: null, expiresAt: null, expectedBusinessVersion: detail.version,
      idempotencyKey: 'auditor-business-command-denied', safeMetadata: { origin: 'playwright' },
    },
  });
  return response.status();
}

async function revokeFixtureEnforcements(page: Page, businessId: string): Promise<void> {
  const detail = await apiData<any>(page, `/api/v1/admin/businesses/${businessId}`);
  for (const action of detail.activeEnforcementActions ?? []) {
    const session = await csrfSession(page);
    await page.request.post(
      `/api/v1/admin/businesses/${businessId}/enforcements/${action.enforcementActionId}/revoke`,
      {
        headers: {
          'Content-Type': 'application/json', Cookie: await cookieHeader(page),
          [session.headerName]: session.token,
        },
        data: {
          expectedEnforcementVersion: action.version,
          reasonCode: 'E2E_CLEANUP', reason: 'Restore the fixed business fixture.',
          idempotencyKey: `cleanup-${action.enforcementActionId}`,
          safeMetadata: { origin: 'playwright-cleanup' },
        },
      },
    );
  }
}

async function apiData<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(path, { headers: { Cookie: await cookieHeader(page) } });
  expect(response.ok()).toBe(true);
  return (await response.json() as { data: T }).data;
}

async function csrfSession(page: Page): Promise<{ headerName: string; token: string }> {
  return page.evaluate(async () => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    return session.csrf;
  });
}

async function cookieHeader(page: Page): Promise<string> {
  return (await page.context().cookies()).map(cookie => `${cookie.name}=${cookie.value}`).join('; ');
}

async function authenticatedAdminPage(
  browser: Browser,
  user: { email: string; password: string },
): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/admin/dashboard');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page.locator('#username').fill(user.email);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();
  await page.waitForURL(/\/admin\/dashboard/);
  return page;
}

async function authenticatedMarketplacePage(
  browser: Browser,
  user: { email: string; password: string },
): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/marketplace');
  await page.getByRole('button', { name: 'Login' }).click();
  const dialog = page.getByRole('dialog', { name: 'Sign in to keep trading local.' });
  await dialog.getByLabel('Email').fill(user.email);
  await dialog.getByLabel('Password').fill(user.password);
  await dialog.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();
  return page;
}
