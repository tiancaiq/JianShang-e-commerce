import { expect, Page, test } from '@playwright/test';

const LISTING_ID = '01D00000000000000000000101';
const LISTING_TITLE = 'Walnut desktop radio with warm dial light';

test('buyer and seller complete one individual trade through the browser', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, 'trade.buyer@msb.local', 'TradeBuyer!2026');
  await page.goto(`/listings/${LISTING_ID}`);
  await expect(page.getByRole('heading', { name: LISTING_TITLE })).toBeVisible();

  await page.getByRole('button', { name: 'Message seller' }).click();
  const listingChat = page.getByRole('dialog', { name: 'Listing chat' });
  await expect(listingChat).toBeVisible();
  await expect(listingChat).toContainText('Mira Chen');
  await listingChat.getByRole('textbox', { name: 'Write a message' })
    .fill('I can meet at the public library this afternoon.');
  await listingChat.getByRole('button', { name: 'Send' }).click();
  await expect(listingChat).toContainText('I can meet at the public library this afternoon.');

  await logout(page);
  await login(page, 'trade.seller@msb.local', 'TradeSeller!2026');
  await page.goto('/account/messages');
  await expect(page.getByText('@jon-buys', { exact: false })).toBeVisible();
  await expect(page.getByText('Discussing', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Mark as done' }).click();
  await expect(page.getByText('Waiting for buyer confirmation')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Mark as done' })).toHaveCount(0);

  await page.reload();
  await expect(page.getByText('Waiting for buyer confirmation')).toBeVisible();
  await expect(page.getByText('Awaiting buyer', { exact: true })).toBeVisible();

  await logout(page);
  await login(page, 'trade.buyer@msb.local', 'TradeBuyer!2026');
  await page.goto('/account/messages');
  await page.getByRole('button', { name: 'Review confirmation' }).click();
  const review = page.getByRole('region', { name: 'Review trade confirmation' });
  await expect(review).toContainText(LISTING_TITLE);
  await expect(review).toContainText('Quantity');
  await expect(review).toContainText('1');
  await expect(review).toContainText('Mira Chen');
  await expect(review).toContainText('@mira-trades');
  await expect(review).toContainText('does not verify or protect that payment');
  await review.getByRole('button', { name: 'Confirm completed' }).click();

  await expect(page.getByText('This conversation is now read-only.')).toBeVisible();
  await expect(page.locator('.composer')).toHaveCount(0);
  await page.reload();
  await expect(page.locator('.completed-footer').getByText('Completed', { exact: true })).toBeVisible();
  await expect(page.locator('.composer')).toHaveCount(0);

  await page.goto(`/listings/${LISTING_ID}`);
  await expect(page.getByText('This listing is not available.')).toBeVisible();

  await logout(page);
  await login(page, 'trade.seller@msb.local', 'TradeSeller!2026');
  await page.goto('/account/messages');
  await expect(page.getByText('This conversation is now read-only.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'View completed listing history' })).toHaveAttribute('href', '/account/listings');
});

async function login(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/marketplace');
  await page.getByRole('button', { name: 'Login' }).click();
  const dialog = page.getByRole('dialog', { name: 'Sign in to keep trading local.' });
  await dialog.getByLabel('Email').fill(email);
  await dialog.getByLabel('Password').fill(password);
  await dialog.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();
}

async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Logout' }).click();
  await page.waitForURL(/\/(?:marketplace)?$/);
  await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();
}
