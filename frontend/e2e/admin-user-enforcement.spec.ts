import { Browser, expect, Page, test } from '@playwright/test';

const BUYER_ID = '01D00000000000000000000002';
const SELLER_ID = '01D00000000000000000000001';
const LISTING_ID = '01D00000000000000000000101';
const LISTING_TITLE = 'Walnut desktop radio with warm dial light';

const SUPER_ADMIN = { email: 'admin.one@msb.local', password: 'AdminOne!2026' };
const SUPPORT_ADMIN = { email: 'support.admin@msb.local', password: 'SupportAdmin!2026' };
const USER_RESTRICTOR = { email: 'user.restrictor@msb.local', password: 'UserRestrictor!2026' };
const AUDITOR = { email: 'admin.auditor@msb.local', password: 'AdminAuditor!2026' };
const BUYER = { email: 'trade.buyer@msb.local', password: 'TradeBuyer!2026' };
const SELLER = { email: 'trade.seller@msb.local', password: 'TradeSeller!2026' };

test.describe.serial('ADM-USER-01/02 runtime enforcement', () => {
  test('admin blocks and restores a buyer purchase commitment', async ({ browser }) => {
    test.setTimeout(180_000);
    const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
    const buyer = await authenticatedMarketplacePage(browser, BUYER);
    try {
      await openUserFromSearch(admin, BUYER_ID, 'Jon Bell');
      await applyEnforcement(admin, 'USER_BUYING', 'Buying boundary release verification.');
      await expect(admin.getByRole('region', { name: 'Enforcement preview' })).toHaveCount(0);
      await expect(admin.getByText('Enforcement created', { exact: true })).toBeVisible();
      await expect(admin.locator('.scope-row').filter({ hasText: 'Buying' }).getByText(/Blocked.*RESTRICT/)).toBeVisible();

      await buyer.goto(`/listings/${LISTING_ID}`);
      await expect(buyer.getByRole('heading', { name: LISTING_TITLE })).toBeVisible();
      const blockedCapabilities = await marketplaceCapabilities(buyer);
      expect(blockedCapabilities.buyingAllowed).toBe(false);
      expect(blockedCapabilities.sellingAllowed).toBe(true);

      const blockedCheckout = await buyer.evaluate(async () => {
        const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
          .then(response => response.json()) as { csrf: { headerName: string; token: string } };
        const response = await fetch('/api/v1/checkouts', {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': 'e2e-buying-blocked',
            [session.csrf.headerName]: session.csrf.token,
          },
          body: JSON.stringify({ cartVersion: 0, addressId: '01D00000000000000000000999' }),
        });
        return { status: response.status, body: await response.text() };
      });
      expect(blockedCheckout.status).toBe(403);
      expect(blockedCheckout.body).toContain('USER_CAPABILITY_RESTRICTED');

      await revokeActiveEnforcement(admin, 'Buying restriction release verification.');
      await expect(admin.getByText('Enforcement revoked', { exact: true })).toBeVisible();
      await expect.poll(async () => (await marketplaceCapabilities(buyer)).buyingAllowed).toBe(true);

      const restoredCheckout = await buyer.evaluate(async () => {
        const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
          .then(response => response.json()) as { csrf: { headerName: string; token: string } };
        const response = await fetch('/api/v1/checkouts', {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': 'e2e-buying-restored',
            [session.csrf.headerName]: session.csrf.token,
          },
          body: JSON.stringify({ cartVersion: 0, addressId: '01D00000000000000000000999' }),
        });
        return { status: response.status, body: await response.text() };
      });
      expect(restoredCheckout.status).not.toBe(403);
      expect(restoredCheckout.body).not.toContain('USER_CAPABILITY_RESTRICTED');
    } finally {
      await admin.context().close();
      await buyer.context().close();
    }
  });

  test('admin blocks seller mutations without changing the existing listing', async ({ browser }) => {
    test.setTimeout(180_000);
    const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
    const seller = await authenticatedMarketplacePage(browser, SELLER);
    try {
      const listingBefore = await ownedListing(seller);
      expect(listingBefore.status).toBe('ACTIVE');

      await openUserFromSearch(admin, SELLER_ID, 'Mira Chen');
      await applyEnforcement(admin, 'USER_SELLING', 'Selling boundary release verification.');

      const blockedMutation = await updateOwnedListing(seller, listingBefore);
      expect(blockedMutation.status).toBe(403);
      expect(blockedMutation.code).toBe('USER_CAPABILITY_RESTRICTED');
      expect((await ownedListing(seller)).status).toBe('ACTIVE');
      const blockedCapabilities = await marketplaceCapabilities(seller);
      expect(blockedCapabilities.sellingAllowed).toBe(false);
      expect(blockedCapabilities.buyingAllowed).toBe(true);

      await revokeActiveEnforcement(admin, 'Selling restriction release verification.');
      await expect.poll(async () => (await marketplaceCapabilities(seller)).sellingAllowed).toBe(true);
      expect((await ownedListing(seller)).status).toBe('ACTIVE');
    } finally {
      await admin.context().close();
      await seller.context().close();
    }
  });

  test('reader, restrict-only admin, and auditor remain least privileged', async ({ browser }) => {
    test.setTimeout(180_000);
    const reader = await authenticatedAdminPage(browser, SUPPORT_ADMIN);
    const restrictor = await authenticatedAdminPage(browser, USER_RESTRICTOR);
    const auditor = await authenticatedAdminPage(browser, AUDITOR);
    try {
      await reader.goto(`/admin/users/${BUYER_ID}`);
      await expect(reader.getByRole('heading', { name: 'Jon Bell' })).toBeVisible();
      await expect(reader.getByText('masked', { exact: true })).toBeVisible();
      await expect(reader.getByText('Restrict marketplace capabilities')).toHaveCount(0);
      expect(await directCreateStatus(reader, BUYER_ID, 'RESTRICT', 'reader-command-denied')).toBe(403);

      await restrictor.goto(`/admin/users/${BUYER_ID}`);
      await expect(restrictor.getByRole('heading', { name: 'Jon Bell' })).toBeVisible();
      const restrictorSession = await directAdminSession(restrictor);
      expect(restrictorSession.permissions).toContain('admin.user.restrict');
      expect(restrictorSession.permissions).not.toContain('admin.user.suspend');
      expect(restrictorSession.permissions).not.toContain('admin.user.ban');
      expect(restrictorSession.permissions).not.toContain('admin.user.pii.read');
      expect(await directCreateStatus(restrictor, BUYER_ID, 'SUSPEND', 'restrictor-suspend-denied')).toBe(403);
      expect(await directCreateStatus(restrictor, BUYER_ID, 'BAN', 'restrictor-ban-denied')).toBe(403);
      await applyEnforcement(restrictor, 'USER_BUYING', 'Least-privilege restriction verification.');
      await revokeActiveEnforcement(restrictor, 'Least-privilege reinstatement verification.');

      await auditor.goto(`/admin/users/${BUYER_ID}`);
      await expect(auditor.getByRole('heading', { name: 'Jon Bell' })).toBeVisible();
      await expect(auditor.getByText('Enforcement created', { exact: true }).last()).toBeVisible();
      await expect(auditor.getByText('Enforcement revoked', { exact: true }).last()).toBeVisible();
      await expect(auditor.getByText('Restrict marketplace capabilities')).toHaveCount(0);
      expect(await directCreateStatus(auditor, BUYER_ID, 'RESTRICT', 'auditor-command-denied')).toBe(403);
    } finally {
      await reader.context().close();
      await restrictor.context().close();
      await auditor.context().close();
    }
  });
});

async function openUserFromSearch(page: Page, userId: string, displayName: string): Promise<void> {
  await page.goto('/admin/users');
  await page.getByRole('textbox', { name: 'Search' }).fill(userId);
  await page.getByRole('button', { name: 'Apply' }).click();
  await page.locator(`a[href="/admin/users/${userId}"]`).click();
  await expect(page.getByRole('heading', { name: displayName })).toBeVisible();
}

async function applyEnforcement(page: Page, scope: 'USER_BUYING' | 'USER_SELLING', reason: string): Promise<void> {
  await page.getByLabel('Action type').selectOption('RESTRICT');
  const buying = page.getByLabel('Buying');
  const selling = page.getByLabel('Selling');
  if (scope === 'USER_BUYING') {
    await buying.check();
    await selling.uncheck();
  } else {
    await buying.uncheck();
    await selling.check();
  }
  await page.getByLabel('Reason code').fill('POLICY_VIOLATION');
  await page.getByLabel('Required explanation').fill(reason);
  await page.getByRole('button', { name: 'Preview action' }).click();
  const preview = page.getByRole('region', { name: 'Enforcement preview' });
  await expect(preview).toBeVisible();
  await preview.getByRole('button', { name: 'Confirm enforcement' }).click();
  await expect(page.getByRole('button', { name: 'Reinstate' })).toBeVisible();
}

async function revokeActiveEnforcement(page: Page, reason: string): Promise<void> {
  await page.getByRole('button', { name: 'Reinstate' }).first().click();
  await page.getByLabel('Reinstatement reason').fill(reason);
  await page.getByRole('button', { name: 'Preview reinstatement' }).click();
  await expect(page.getByText('Revocation preview', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Confirm reinstatement' }).click();
  await expect(page.getByRole('button', { name: 'Reinstate' })).toHaveCount(0);
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

async function marketplaceCapabilities(page: Page): Promise<{ buyingAllowed: boolean; sellingAllowed: boolean }> {
  return page.evaluate(async () => {
    const response = await fetch('/api/v1/users/me/marketplace-capabilities', { credentials: 'include' });
    const body = await response.json() as { data: { buyingAllowed: boolean; sellingAllowed: boolean } };
    return body.data;
  });
}

async function ownedListing(page: Page): Promise<Record<string, any>> {
  return page.evaluate(async listingId => {
    const response = await fetch(`/api/v1/listings/${listingId}`, { credentials: 'include' });
    if (!response.ok) throw new Error(`Owned listing request failed with ${response.status}.`);
    return await response.json();
  }, LISTING_ID);
}

async function updateOwnedListing(
  page: Page,
  listing: Record<string, any>,
): Promise<{ status: number; code: string | null }> {
  return page.evaluate(async input => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const response = await fetch(`/api/v1/listings/${input.id}`, {
      method: 'PATCH',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': String(input.version),
        [session.csrf.headerName]: session.csrf.token,
      },
      body: JSON.stringify({
        sellerType: 'INDIVIDUAL',
        businessId: null,
        categoryId: input.categoryId,
        title: input.title,
        description: input.description,
        condition: input.condition,
        conditionNotes: input.conditionNotes,
        price: { amount: input.priceAmount, currency: input.currency },
        negotiable: input.negotiable,
        location: { city: input.publicCity, region: input.publicRegion },
        sku: null,
        quantity: input.quantity,
      }),
    });
    const body = await response.json();
    return { status: response.status, code: body?.error?.code ?? null };
  }, listing);
}

async function directCreateStatus(
  page: Page,
  userId: string,
  actionType: 'RESTRICT' | 'SUSPEND' | 'BAN',
  idempotencyKey: string,
): Promise<number> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const detailResponse = await fetch(`/api/v1/admin/users/${request.userId}`, { credentials: 'include' });
    const detail = await detailResponse.json() as { data: { version: number } };
    const response = await fetch(`/api/v1/admin/users/${request.userId}/enforcements`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token },
      body: JSON.stringify({
        actionType: request.actionType,
        scopes: ['USER_BUYING'],
        reasonCode: 'POLICY_VIOLATION',
        reason: 'Forbidden command verification.',
        effectiveAt: null,
        expiresAt: null,
        expectedUserVersion: detail.data.version,
        idempotencyKey: request.idempotencyKey,
        safeMetadata: { origin: 'playwright' },
      }),
    });
    return response.status;
  }, { userId, actionType, idempotencyKey });
}

async function directAdminSession(page: Page): Promise<{ permissions: string[] }> {
  return page.evaluate(async () => {
    const response = await fetch('/api/v1/admin/me', { credentials: 'include' });
    const body = await response.json() as { data: { permissions: string[] } };
    return body.data;
  });
}
