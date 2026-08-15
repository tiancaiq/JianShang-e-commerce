import { Browser, expect, Page, test } from '@playwright/test';

const BUSINESS_APPLICATION_ID = '01E00000000000000000000101';
const LISTING_CASE_ID = '01E00000000000000000000202';

const ADMIN_ONE = {
  email: 'admin.one@msb.local',
  password: 'AdminOne!2026',
  displayName: 'Avery Stone',
};

const ADMIN_TWO = {
  email: 'admin.two@msb.local',
  password: 'AdminTwo!2026',
};

const BUSINESS_REVIEWER = {
  email: 'business.reviewer@msb.local',
  password: 'BusinessReviewer!2026',
};

const LISTING_MODERATOR = {
  email: 'listing.moderator@msb.local',
  password: 'ListingModerator!2026',
};

const AUDITOR = {
  email: 'admin.auditor@msb.local',
  password: 'AdminAuditor!2026',
};

test('limited admin roles expose only authorized workflows and backend reads and commands remain protected', async ({ browser }) => {
  test.setTimeout(180_000);
  const businessReviewer = await authenticatedAdminPage(
    browser, BUSINESS_REVIEWER.email, BUSINESS_REVIEWER.password);
  const listingModerator = await authenticatedAdminPage(
    browser, LISTING_MODERATOR.email, LISTING_MODERATOR.password);
  const auditor = await authenticatedAdminPage(browser, AUDITOR.email, AUDITOR.password);

  try {
    await expect.poll(() => directAdminSession(businessReviewer)).toMatchObject({
      roles: ['BUSINESS_REVIEWER'],
      permissions: expect.arrayContaining([
        'admin.dashboard.read',
        'admin.business.application.read',
        'admin.business.application.decide',
      ]),
    });
    expect((await directAdminSession(businessReviewer)).permissions)
      .not.toContain('admin.listing.moderation.read');
    await expect(businessReviewer.getByRole('link', { name: 'Business Review', exact: true })).toBeVisible();
    await expect(businessReviewer.getByRole('link', { name: 'Listing Review', exact: true })).toHaveCount(0);
    expect(await directApiStatus(businessReviewer, '/api/v1/admin/moderation/listing-cases')).toBe(403);
    expect(await directApiCommandStatus(
      businessReviewer,
      `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}/claim`,
      '0',
    )).toBe(403);
    await businessReviewer.goto('/admin/listings/moderation');
    await expect(businessReviewer.getByRole('heading', { name: 'Admin access denied.' })).toBeVisible();

    await expect(listingModerator.getByRole('link', { name: 'Listing Review', exact: true })).toBeVisible();
    await expect(listingModerator.getByRole('link', { name: 'Business Review', exact: true })).toHaveCount(0);
    expect((await directAdminSession(listingModerator)).permissions)
      .not.toContain('admin.business.application.decide');
    expect(await directApiStatus(listingModerator, '/api/v1/admin/business-applications')).toBe(403);
    expect(await directApiCommandStatus(
      listingModerator,
      `/api/v1/admin/business-applications/${BUSINESS_APPLICATION_ID}/decision`,
      '1',
      { decision: 'REJECT', reason: 'Restricted command must not apply.' },
    )).toBe(403);
    await listingModerator.goto('/admin/business-applications');
    await expect(listingModerator.getByRole('heading', { name: 'Admin access denied.' })).toBeVisible();

    await expect(auditor.getByRole('link', { name: 'Business Review', exact: true })).toBeVisible();
    await expect(auditor.getByRole('link', { name: 'Listing Review', exact: true })).toBeVisible();
    const auditorPermissions = (await directAdminSession(auditor)).permissions;
    expect(auditorPermissions).not.toContain('admin.business.application.decide');
    expect(auditorPermissions).not.toContain('admin.listing.moderation.claim');
    expect(await directApiCommandStatus(
      auditor,
      `/api/v1/admin/business-applications/${BUSINESS_APPLICATION_ID}/decision`,
      '1',
      { decision: 'REJECT', reason: 'Read-only auditor command must not apply.' },
    )).toBe(403);
    expect(await directApiCommandStatus(
      auditor,
      `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}/claim`,
      '0',
    )).toBe(403);
    await auditor.goto(`/admin/business-applications/${BUSINESS_APPLICATION_ID}`);
    await expect(auditor.getByRole('heading', { name: 'Harbor Workshop LLC' })).toBeVisible();
    await expect(auditor.getByTestId('decision-submit')).toHaveCount(0);
    await auditor.goto('/admin/listings/moderation');
    const listingCase = auditor.getByRole('article').filter({ hasText: 'Restored oak writing desk' });
    await expect(listingCase).toBeVisible();
    await expect(listingCase.getByRole('button', { name: 'Claim' })).toHaveCount(0);
  } finally {
    await businessReviewer.context().close();
    await listingModerator.context().close();
    await auditor.context().close();
  }
});

test('admins approve a business and moderate a claimed listing with visible audit history', async ({ browser }) => {
  test.setTimeout(180_000);
  const adminOne = await authenticatedAdminPage(browser, ADMIN_ONE.email, ADMIN_ONE.password);
  const adminTwo = await authenticatedAdminPage(browser, ADMIN_TWO.email, ADMIN_TWO.password);

  try {
    await expect(adminOne.getByRole('heading', { name: 'Review workspace' })).toBeVisible();

    await adminOne.goto(`/admin/business-applications/${BUSINESS_APPLICATION_ID}`);
    await expect(adminOne.getByRole('heading', { name: 'Harbor Workshop LLC' })).toBeVisible();
    await adminOne.getByTestId('decision-reason').fill('Verified business details for the release candidate.');
    adminOne.once('dialog', dialog => dialog.accept());
    await adminOne.getByTestId('decision-submit').click();
    await expect(adminOne.locator('app-ui-status-pill').getByText('APPROVED', { exact: true })).toBeVisible();

    const businessTimeline = adminOne.getByRole('region', { name: 'Business audit timeline' });
    await expect(businessTimeline).toContainText('Business application submitted');
    await expect(businessTimeline).toContainText('Business application decision');
    await expect(businessTimeline).toContainText(ADMIN_ONE.displayName);
    await expect(businessTimeline).toContainText('Verified business details for the release candidate.');

    await adminOne.goto('/admin/listings/moderation');
    const listingCase = adminOne.getByRole('article').filter({ hasText: 'Restored oak writing desk' });
    await expect(listingCase).toBeVisible();
    await listingCase.getByRole('button', { name: 'Claim' }).click();
    await expect(listingCase).toContainText(ADMIN_ONE.displayName);
    await listingCase.getByRole('button', { name: 'Open review' }).click();

    await adminTwo.goto(`/admin/listings/moderation/${LISTING_CASE_ID}`);
    await expect(adminTwo.getByText('This case is read-only because it is assigned to another admin.')).toBeVisible();
    await expect(adminTwo.getByRole('button', { name: 'Resolve case' })).toHaveCount(0);

    await adminOne.getByLabel('Decision').selectOption('APPROVE');
    await adminOne.getByTestId('listing-decision-reason')
      .fill('Listing content and location meet marketplace policy.');
    adminOne.once('dialog', dialog => dialog.accept());
    await adminOne.getByRole('button', { name: 'Resolve case' }).click();
    await expect(adminOne.getByText('This case is read-only because it is already resolved.')).toBeVisible();

    const listingTimeline = adminOne.getByRole('region', { name: 'Case audit timeline' });
    await expect(listingTimeline).toContainText('Case created');
    await expect(listingTimeline).toContainText('Case claimed');
    await expect(listingTimeline).toContainText('Listing moderation resolved');
    await expect(listingTimeline).toContainText('Case resolved');
    await expect(listingTimeline).toContainText(ADMIN_ONE.displayName);
    await expect(listingTimeline).toContainText('Correlation ID');

    await adminOne.getByRole('button', { name: 'Logout' }).click();
    await adminOne.waitForURL(/\/login\?client=admin-portal/);
    await adminOne.getByRole('button', { name: 'Continue to sign in' }).click();
    await adminOne.locator('#username').fill(ADMIN_ONE.email);
    await adminOne.locator('#password').fill(ADMIN_ONE.password);
    await adminOne.locator('#kc-login').click();
    await adminOne.waitForURL(/\/admin\/dashboard/);
    await expect(adminOne.getByRole('heading', { name: 'Review workspace' })).toBeVisible();
  } finally {
    await adminOne.context().close();
    await adminTwo.context().close();
  }
});

async function authenticatedAdminPage(browser: Browser, email: string, password: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/admin/dashboard');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page.locator('#username').fill(email);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.waitForURL(/\/admin\/dashboard/);
  return page;
}

async function directApiStatus(page: Page, url: string): Promise<number> {
  return page.evaluate(async endpoint => {
    const response = await fetch(endpoint, { credentials: 'include' });
    return response.status;
  }, url);
}

async function directApiCommandStatus(
  page: Page,
  url: string,
  ifMatch: string,
  body?: Record<string, unknown>,
): Promise<number> {
  return page.evaluate(async request => {
    const headers: Record<string, string> = { 'If-Match': request.ifMatch };
    if (request.body) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(request.url, {
      method: 'POST',
      credentials: 'include',
      headers,
      body: request.body ? JSON.stringify(request.body) : undefined,
    });
    return response.status;
  }, { url, ifMatch, body });
}

async function directAdminSession(page: Page): Promise<{ roles: string[]; permissions: string[] }> {
  return page.evaluate(async () => {
    const response = await fetch('/api/v1/admin/me', { credentials: 'include' });
    const body = await response.json() as {
      data: { roles: string[]; permissions: string[] };
    };
    return body.data;
  });
}
