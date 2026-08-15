import { Browser, expect, Page, test } from '@playwright/test';

const LISTING_CASE_ID = '01E00000000000000000000205';
const REMOVAL_LISTING_ID = '01E00000000000000000000204';
const BUSINESS_LISTING_ID = '01KZ3CF59Z42DG2M0AZ8FQ1729';
const BUSINESS_ID = '01KZCARTB00000000000000002';
const BUYER_ADDRESS_ID = '01KZ3X3V5BC96S3J6XS70QMAA8';
const SUPER_ADMIN = { email: 'admin.one@msb.local', password: 'AdminOne!2026' };
const AUDITOR = { email: 'admin.auditor@msb.local', password: 'AdminAuditor!2026' };
const BUYER = { email: 'trade.seller@msb.local', password: 'TradeSeller!2026' };

test('super admin suspends an active listing and reinstates it without changing listing lifecycle', async ({ browser }) => {
  test.setTimeout(180_000);
  const page = await authenticatedAdminPage(browser, SUPER_ADMIN);
  let listingId = '';
  let actionId = '';
  try {
    const session = await apiJson<any>(page, '/api/v1/admin/me');
    expect(session.data.permissions).toContain('admin.listing.suspend');
    expect(session.data.permissions).toContain('admin.listing.reinstate');
    const caseDetail = await ensureActiveListing(page);
    listingId = caseDetail.listing.id;
    const originalStatus = caseDetail.listing.status;
    await page.goto(`/admin/listings/moderation/${LISTING_CASE_ID}`);
    await expect(page.getByRole('heading', { name: 'Listing enforcement' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Action' }).selectOption('SUSPEND');
    await page.getByLabel('Reason code').fill('E2E_TEMPORARY_SUSPENSION');
    await page.getByLabel('Staff reason').fill('Temporary release-candidate listing suspension.');
    await page.getByLabel('I acknowledge this action has no automatic expiry').check();
    await page.getByRole('button', { name: 'Preview impact' }).click();
    const preview = page.getByRole('region', { name: 'Predicted boundary impact' });
    await expect(preview.getByText('Public visibility blocked')).toBeVisible();
    await expect(preview.getByText('Purchasability blocked')).toBeVisible();
    const createResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/admin/listings/${listingId}/enforcements`);
    await preview.getByRole('button', { name: 'Confirm enforcement' }).click();
    const createdResponse = await createResponse;
    expect(createdResponse.ok(), await createdResponse.text()).toBe(true);

    const currentBoundaries = page.getByLabel('Current listing capability state');
    await expect(currentBoundaries.getByText('Visibility blocked')).toBeVisible();
    await expect(currentBoundaries.getByText('Purchases blocked')).toBeVisible();
    const enforced = await apiJson<any>(page, `/api/v1/admin/listings/${listingId}/enforcements`);
    actionId = enforced.activeEnforcementActions.find((value: any) => value.actionType === 'SUSPEND')
      ?.enforcementActionId ?? '';
    expect(actionId).not.toBe('');
    expect(await apiStatus(page, `/api/v1/public/listings/${listingId}`)).toBe(404);
    expect((await apiJson<any>(page, `/api/v1/admin/listings/${listingId}`)).status).toBe(originalStatus);

    page.once('dialog', dialog => dialog.accept('Release-candidate suspension check completed.'));
    await page.getByRole('button', { name: 'Preview reinstatement' }).click();
    const reinstatement = page.getByRole('region', { name: 'Predicted boundary impact' });
    await expect(reinstatement.getByText('Public visibility open')).toBeVisible();
    await expect(reinstatement.getByText('Purchasability open')).toBeVisible();
    const revokeResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname
      === `/api/v1/admin/listings/${listingId}/enforcements/${actionId}/revoke`);
    await reinstatement.getByRole('button', { name: 'Confirm reinstatement' }).click();
    const revokedResponse = await revokeResponse;
    expect(revokedResponse.ok(), await revokedResponse.text()).toBe(true);
    actionId = '';
    await expect.poll(() => apiStatus(page, `/api/v1/public/listings/${listingId}`)).toBe(200);
    const restored = await apiJson<any>(page, `/api/v1/admin/listings/${listingId}`);
    expect(restored.status).toBe(originalStatus);
  } finally {
    if (actionId && listingId) {
      const current = await apiJson<any>(page, `/api/v1/admin/listings/${listingId}/enforcements`).catch(() => null);
      const active = current?.activeEnforcementActions?.find((value: any) => value.enforcementActionId === actionId);
      if (active) {
        await apiCommand(page, `/api/v1/admin/listings/${listingId}/enforcements/${actionId}/revoke`, {
          expectedEnforcementVersion: active.version,
          reasonCode: 'E2E_CLEANUP', reason: 'Cleanup after interrupted E2E enforcement run.',
          idempotencyKey: `e2e-listing-cleanup-${Date.now()}`, safeMetadata: { origin: 'PLAYWRIGHT' },
        }).catch(() => undefined);
      }
    }
    await page.context().close();
  }
});

async function ensureActiveListing(page: Page): Promise<any> {
  let detail = await apiJson<any>(page, `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}`);
  if (detail.listing.status === 'ACTIVE') return detail;

  await page.goto('/admin/listings/moderation');
  const listingCase = page.getByRole('article').filter({ hasText: 'Restored oak writing desk' });
  await expect(listingCase).toBeVisible();
  const claim = listingCase.getByRole('button', { name: 'Claim' });
  if (await claim.count()) await claim.click();
  await listingCase.getByRole('button', { name: 'Open review' }).click();
  await page.getByLabel('Decision').selectOption('APPROVE');
  await page.getByTestId('listing-decision-reason')
    .fill('Approved as the active fixture for listing enforcement verification.');
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Resolve case' }).click();
  await expect(page.getByText('This case is read-only because it is already resolved.')).toBeVisible();
  detail = await apiJson<any>(page, `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}`);
  expect(detail.listing.status).toBe('ACTIVE');
  return detail;
}

test('auditor can inspect listing enforcement but cannot create it', async ({ browser }) => {
  const page = await authenticatedAdminPage(browser, AUDITOR);
  try {
    const caseDetail = await apiJson<any>(page, `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}`);
    const listingId = caseDetail.listing.id;
    await page.goto(`/admin/listings/moderation/${LISTING_CASE_ID}`);
    await expect(page.getByRole('heading', { name: 'Listing enforcement' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Apply temporary enforcement' })).toHaveCount(0);
    expect(await apiCommandStatus(page, `/api/v1/admin/listings/${listingId}/enforcements`, {
      actionType: 'RESTRICT', scopes: ['LISTING_PUBLIC_VISIBILITY'], reasonCode: 'DENIED',
      reason: 'Unauthorized command must not apply.', expectedListingVersion: caseDetail.listing.version,
      idempotencyKey: `e2e-denied-${Date.now()}`, safeMetadata: {},
    })).toBe(403);
  } finally {
    await page.context().close();
  }
});

test('purchasability-only enforcement keeps a business listing visible and blocks checkout until revoked',
  async ({ browser }) => {
    test.setTimeout(180_000);
    const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
    const buyer = await authenticatedMarketplacePage(browser, BUYER);
    let actionId = '';
    let actionVersion = 0;
    try {
      const listing = await apiJson<any>(admin, `/api/v1/admin/listings/${BUSINESS_LISTING_ID}`);
      expect(listing.status).toBe('ACTIVE');
      expect(await apiStatus(buyer, `/api/v1/public/listings/${BUSINESS_LISTING_ID}`)).toBe(200);

      const initialCart = await apiJson<any>(buyer, '/api/v1/cart');
      const emptyCart = initialCart.totalQuantity === 0
        ? initialCart
        : await cartCommand(buyer, 'DELETE', '/api/v1/cart', undefined, initialCart.version);
      const cart = await cartCommand(buyer, 'POST', '/api/v1/cart/items',
        { listingId: BUSINESS_LISTING_ID, quantity: 1 }, emptyCart.version);

      const preview = await apiCommand<any>(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/dry-run`, {
          actionType: 'RESTRICT', scopes: ['LISTING_PURCHASABILITY'],
          reasonCode: 'E2E_PURCHASABILITY_ONLY',
          reason: 'Verify the checkout boundary without hiding the listing.',
          effectiveAt: null, expiresAt: null, expectedListingVersion: listing.version,
          idempotencyKey: null, safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      expect(preview.predictedPublicVisibility).toBe(true);
      expect(preview.predictedPurchasability).toBe(false);

      const created = await apiCommand<any>(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements`, {
          actionType: 'RESTRICT', scopes: ['LISTING_PURCHASABILITY'],
          reasonCode: 'E2E_PURCHASABILITY_ONLY',
          reason: 'Verify the checkout boundary without hiding the listing.',
          effectiveAt: null, expiresAt: null, expectedListingVersion: listing.version,
          idempotencyKey: `e2e-purchasability-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      actionId = created.enforcementActionId;
      actionVersion = created.version;
      expect(await apiStatus(buyer, `/api/v1/public/listings/${BUSINESS_LISTING_ID}`)).toBe(200);

      const blocked = await checkoutCommand(buyer, cart.version, `e2e-blocked-${Date.now()}`);
      expect(blocked.status, blocked.body).toBe(403);
      expect(blocked.body).toContain('LISTING_PURCHASABILITY_RESTRICTED');

      await apiCommand(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/${actionId}/revoke/dry-run`, {
          expectedEnforcementVersion: actionVersion,
          reasonCode: 'E2E_RESTORED', reason: 'Purchasability-only boundary verified.',
          idempotencyKey: null, safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      await apiCommand(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/${actionId}/revoke`, {
          expectedEnforcementVersion: actionVersion,
          reasonCode: 'E2E_RESTORED', reason: 'Purchasability-only boundary verified.',
          idempotencyKey: `e2e-purchasability-revoke-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      actionId = '';

      const restored = await checkoutCommand(buyer, cart.version, `e2e-restored-${Date.now()}`);
      expect(restored.status).toBe(201);
      const checkout = JSON.parse(restored.body) as { id: string };
      const cancelled = await apiCommand<any>(buyer, `/api/v1/checkouts/${checkout.id}/cancel`, null,
        { 'Idempotency-Key': `e2e-cancel-${Date.now()}` });
      expect(cancelled.status).toBe('CANCELLED');
    } finally {
      if (actionId) {
        await apiCommand(admin,
          `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/${actionId}/revoke`, {
            expectedEnforcementVersion: actionVersion,
            reasonCode: 'E2E_CLEANUP', reason: 'Cleanup after interrupted E2E enforcement run.',
            idempotencyKey: `e2e-purchasability-cleanup-${Date.now()}`, safeMetadata: {},
          }).catch(() => undefined);
      }
      await clearCart(buyer).catch(() => undefined);
      await admin.context().close();
      await buyer.context().close();
    }
  });

test('revoking listing enforcement does not override an active business new-sales restriction',
  async ({ browser }) => {
    test.setTimeout(180_000);
    const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
    const buyer = await authenticatedMarketplacePage(browser, BUYER);
    let listingActionId = '';
    let listingActionVersion = 0;
    let businessActionId = '';
    let businessActionVersion = 0;
    try {
      const listing = await apiJson<any>(admin, `/api/v1/admin/listings/${BUSINESS_LISTING_ID}`);
      const business = await apiData<any>(admin, `/api/v1/admin/businesses/${BUSINESS_ID}`);
      expect(listing.businessId).toBe(BUSINESS_ID);
      expect(business.businessState).toBe('ACTIVE');

      const initialCart = await apiJson<any>(buyer, '/api/v1/cart');
      const emptyCart = initialCart.totalQuantity === 0
        ? initialCart
        : await cartCommand(buyer, 'DELETE', '/api/v1/cart', undefined, initialCart.version);
      const cart = await cartCommand(buyer, 'POST', '/api/v1/cart/items',
        { listingId: BUSINESS_LISTING_ID, quantity: 1 }, emptyCart.version);

      const listingAction = await apiCommand<any>(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements`, {
          actionType: 'RESTRICT', scopes: ['LISTING_PURCHASABILITY'],
          reasonCode: 'E2E_COMPOSITION_LISTING',
          reason: 'Verify independent listing and business new-sales enforcement.',
          effectiveAt: null, expiresAt: null, expectedListingVersion: listing.version,
          idempotencyKey: `e2e-composition-listing-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      listingActionId = listingAction.enforcementActionId;
      listingActionVersion = listingAction.version;
      const listingBlocked = await checkoutCommand(buyer, cart.version, `e2e-listing-blocked-${Date.now()}`);
      expect(listingBlocked.status, listingBlocked.body).toBe(403);
      expect(listingBlocked.body).toContain('LISTING_PURCHASABILITY_RESTRICTED');

      const businessAction = await apiDataCommand<any>(admin,
        `/api/v1/admin/businesses/${BUSINESS_ID}/enforcements`, {
          actionType: 'RESTRICT', scopes: ['BUSINESS_NEW_SALES'],
          reasonCode: 'E2E_COMPOSITION_BUSINESS',
          reason: 'Keep new sales blocked after listing-level reinstatement.',
          effectiveAt: null, expiresAt: null, expectedBusinessVersion: business.version,
          idempotencyKey: `e2e-composition-business-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      businessActionId = businessAction.enforcementActionId;
      businessActionVersion = businessAction.version;

      await apiCommand(admin,
        `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/${listingActionId}/revoke`, {
          expectedEnforcementVersion: listingActionVersion,
          reasonCode: 'E2E_LISTING_RESTORED', reason: 'Listing boundary verified independently.',
          idempotencyKey: `e2e-composition-listing-revoke-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      listingActionId = '';

      const businessBlocked = await checkoutCommand(buyer, cart.version, `e2e-business-blocked-${Date.now()}`);
      expect(businessBlocked.status, businessBlocked.body).toBe(403);
      expect(businessBlocked.body).toContain('BUSINESS_CAPABILITY_RESTRICTED');

      await apiDataCommand(admin,
        `/api/v1/admin/businesses/${BUSINESS_ID}/enforcements/${businessActionId}/revoke`, {
          expectedEnforcementVersion: businessActionVersion,
          reasonCode: 'E2E_BUSINESS_RESTORED', reason: 'Business composition boundary verified.',
          idempotencyKey: `e2e-composition-business-revoke-${Date.now()}`,
          safeMetadata: { origin: 'PLAYWRIGHT' },
        });
      businessActionId = '';

      const restored = await checkoutCommand(buyer, cart.version, `e2e-composition-restored-${Date.now()}`);
      expect(restored.status, restored.body).toBe(201);
      const checkout = JSON.parse(restored.body) as { id: string };
      const cancelled = await apiCommand<any>(buyer, `/api/v1/checkouts/${checkout.id}/cancel`, null,
        { 'Idempotency-Key': `e2e-composition-cancel-${Date.now()}` });
      expect(cancelled.status).toBe('CANCELLED');
    } finally {
      if (listingActionId) {
        await apiCommand(admin,
          `/api/v1/admin/listings/${BUSINESS_LISTING_ID}/enforcements/${listingActionId}/revoke`, {
            expectedEnforcementVersion: listingActionVersion,
            reasonCode: 'E2E_CLEANUP', reason: 'Cleanup listing composition enforcement.',
            idempotencyKey: `e2e-composition-listing-cleanup-${Date.now()}`, safeMetadata: {},
          }).catch(() => undefined);
      }
      if (businessActionId) {
        await apiDataCommand(admin,
          `/api/v1/admin/businesses/${BUSINESS_ID}/enforcements/${businessActionId}/revoke`, {
            expectedEnforcementVersion: businessActionVersion,
            reasonCode: 'E2E_CLEANUP', reason: 'Cleanup business composition enforcement.',
            idempotencyKey: `e2e-composition-business-cleanup-${Date.now()}`, safeMetadata: {},
          }).catch(() => undefined);
      }
      await clearCart(buyer).catch(() => undefined);
      await admin.context().close();
      await buyer.context().close();
    }
  });

test('revoking enforcement never restores a listing removed by the admin lifecycle workflow',
  async ({ browser }) => {
    test.setTimeout(180_000);
    const admin = await authenticatedAdminPage(browser, SUPER_ADMIN);
    let listingId = '';
    let actionId = '';
    let actionVersion = 0;
    try {
      const listing = await apiJson<any>(admin, `/api/v1/admin/listings/${REMOVAL_LISTING_ID}`);
      expect(listing.status).toBe('ACTIVE');
      listingId = listing.id;
      const action = await apiCommand<any>(admin, `/api/v1/admin/listings/${listingId}/enforcements`, {
        actionType: 'SUSPEND',
        scopes: ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY'],
        reasonCode: 'E2E_REMOVAL_BOUNDARY',
        reason: 'Verify temporary enforcement cannot override administrative removal.',
        effectiveAt: null, expiresAt: null, expectedListingVersion: listing.version,
        idempotencyKey: `e2e-removal-boundary-${Date.now()}`,
        safeMetadata: { origin: 'PLAYWRIGHT' },
      });
      actionId = action.enforcementActionId;
      actionVersion = action.version;

      const removed = await apiCommand<any>(admin, `/api/v1/admin/listings/${listingId}/remove`, {
        reason: 'Remove the fixed listing while temporary enforcement is active.',
      }, { 'If-Match': String(listing.version) });
      expect(removed.status).toBe('REMOVED_BY_ADMIN');
      expect(await apiStatus(admin, `/api/v1/public/listings/${listingId}`)).toBe(404);

      await apiCommand(admin, `/api/v1/admin/listings/${listingId}/enforcements/${actionId}/revoke`, {
        expectedEnforcementVersion: actionVersion,
        reasonCode: 'E2E_ENFORCEMENT_REVOKED',
        reason: 'Temporary enforcement is no longer required; admin removal must remain.',
        idempotencyKey: `e2e-removal-boundary-revoke-${Date.now()}`,
        safeMetadata: { origin: 'PLAYWRIGHT' },
      });
      actionId = '';

      expect(await apiStatus(admin, `/api/v1/public/listings/${listingId}`)).toBe(404);
      const after = await apiJson<any>(admin, `/api/v1/admin/listings/${listingId}`);
      expect(after.status).toBe('REMOVED_BY_ADMIN');
      expect(after.moderationAction).toBe('ADMIN_REMOVE');

      const enforcement = await apiJson<any>(admin, `/api/v1/admin/listings/${listingId}/enforcements`);
      expect(enforcement.activeEnforcementActions).toHaveLength(0);
      expect(enforcement.historicalEnforcementActions.some((item: any) =>
        item.enforcementActionId === action.enforcementActionId && item.lifecycleState === 'REVOKED')).toBe(true);
      expect(enforcement.availableAdminCapabilities.removedByAdmin).toBe(true);

      const enforcementTimeline = await apiJson<any>(admin,
        `/api/v1/admin/listings/${listingId}/enforcements/timeline`);
      expect(enforcementTimeline.entries.map((entry: any) => entry.eventType))
        .toEqual(expect.arrayContaining(['CREATED', 'REVOKED']));
      const moderation = await apiJson<any>(admin,
        `/api/v1/admin/moderation/listing-cases/${LISTING_CASE_ID}`);
      expect(moderation.decisions.some((decision: any) => decision.decision === 'ADMIN_REMOVE')).toBe(true);
    } finally {
      if (actionId && listingId) {
        await apiCommand(admin, `/api/v1/admin/listings/${listingId}/enforcements/${actionId}/revoke`, {
          expectedEnforcementVersion: actionVersion,
          reasonCode: 'E2E_CLEANUP', reason: 'Cleanup interrupted removal boundary enforcement.',
          idempotencyKey: `e2e-removal-boundary-cleanup-${Date.now()}`, safeMetadata: {},
        }).catch(() => undefined);
      }
      await admin.context().close();
    }
  });

async function authenticatedAdminPage(browser: Browser, account: { email: string; password: string }): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto('/admin/dashboard');
  await page.getByRole('button', { name: 'Continue to sign in' }).click();
  await page.locator('#username').fill(account.email);
  await page.locator('#password').fill(account.password);
  await page.locator('#kc-login').click();
  await page.waitForURL(/\/admin\/dashboard/);
  return page;
}

async function authenticatedMarketplacePage(
  browser: Browser,
  account: { email: string; password: string },
): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto('/marketplace');
  await page.getByRole('button', { name: 'Login' }).click();
  const dialog = page.getByRole('dialog', { name: 'Sign in to keep trading local.' });
  await dialog.getByLabel('Email').fill(account.email);
  await dialog.getByLabel('Password').fill(account.password);
  await dialog.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();
  return page;
}

async function apiJson<T>(page: Page, url: string): Promise<T> {
  return page.evaluate(async endpoint => {
    const response = await fetch(endpoint, { credentials: 'include' });
    if (!response.ok) throw new Error(`GET ${endpoint} failed: ${response.status}`);
    return response.json();
  }, url) as Promise<T>;
}

async function apiData<T>(page: Page, url: string): Promise<T> {
  const response = await apiJson<{ data: T }>(page, url);
  return response.data;
}

async function apiStatus(page: Page, url: string): Promise<number> {
  return page.evaluate(async endpoint => (await fetch(endpoint, { credentials: 'include' })).status, url);
}

async function apiCommand<T = unknown>(
  page: Page,
  url: string,
  body: unknown,
  extraHeaders: Record<string, string> = {},
): Promise<T> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const response = await fetch(request.url, { method: 'POST', credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        [session.csrf.headerName]: session.csrf.token,
        ...request.extraHeaders,
      },
      body: JSON.stringify(request.body) });
    if (!response.ok) throw new Error(`POST ${request.url} failed: ${response.status}`);
    return response.json();
  }, { url, body, extraHeaders }) as Promise<T>;
}

async function apiCommandStatus(page: Page, url: string, body: unknown): Promise<number> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    return (await fetch(request.url, { method: 'POST', credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        [session.csrf.headerName]: session.csrf.token,
      },
      body: JSON.stringify(request.body) })).status;
  }, { url, body });
}

async function apiDataCommand<T>(page: Page, url: string, body: unknown): Promise<T> {
  const response = await apiCommand<{ data: T }>(page, url, body);
  return response.data;
}

async function cartCommand(
  page: Page,
  method: 'POST' | 'DELETE',
  url: string,
  body: unknown,
  version: number,
): Promise<any> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const response = await fetch(request.url, {
      method: request.method,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': `"${request.version}"`,
        'Idempotency-Key': `e2e-cart-${crypto.randomUUID()}`,
        [session.csrf.headerName]: session.csrf.token,
      },
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
    });
    if (!response.ok) throw new Error(`${request.method} ${request.url} failed: ${response.status} ${await response.text()}`);
    return response.json();
  }, { method, url, body, version });
}

async function checkoutCommand(
  page: Page,
  cartVersion: number,
  idempotencyKey: string,
): Promise<{ status: number; body: string }> {
  return page.evaluate(async request => {
    const session = await fetch('/api/v1/auth/session', { credentials: 'include' })
      .then(response => response.json()) as { csrf: { headerName: string; token: string } };
    const response = await fetch('/api/v1/checkouts', {
      method: 'POST', credentials: 'include',
      headers: {
        'Content-Type': 'application/json', 'Idempotency-Key': request.idempotencyKey,
        [session.csrf.headerName]: session.csrf.token,
      },
      body: JSON.stringify({ cartVersion: request.cartVersion, addressId: request.addressId }),
    });
    return { status: response.status, body: await response.text() };
  }, { cartVersion, idempotencyKey, addressId: BUYER_ADDRESS_ID });
}

async function clearCart(page: Page): Promise<void> {
  const cart = await apiJson<any>(page, '/api/v1/cart');
  if (cart.totalQuantity > 0) await cartCommand(page, 'DELETE', '/api/v1/cart', undefined, cart.version);
}
