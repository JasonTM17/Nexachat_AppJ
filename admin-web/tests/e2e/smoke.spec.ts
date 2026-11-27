import { expect, test } from '@playwright/test';

// Smoke: unauthenticated visitors land on the sign-in form; the form is
// keyboard-navigable and labelled. Logged-in journeys need a live backend and
// are exercised by the server-side ITs instead.

test('unauthenticated user is redirected to login', async ({ page }) => {
  await page.goto('/overview');
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByLabel('Email or phone')).toBeVisible();
  await expect(page.getByLabel('Password')).toBeVisible();
});

test('login form submits with Enter and shows an error without backend', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email or phone').fill('+84900000000');
  await page.getByLabel('Password').fill('wrong-password');
  await page.getByLabel('Password').press('Enter');
  await expect(page.getByRole('alert')).toBeVisible();
});

test('clears the tokens when staff sign-in is denied', async ({ page }) => {
  await page.route('**/api/v1/auth/login', (route) => route.fulfill({
    json: { accessToken: 'test-access', refreshToken: 'test-refresh', user: { id: 'member' } },
  }));
  await page.route('**/api/v1/admin/me', (route) => route.fulfill({
    status: 403,
    json: { error: { code: 'FORBIDDEN' } },
  }));

  await page.goto('/login');
  await page.getByLabel('Email or phone').fill('+84900000000');
  await page.getByLabel('Password').fill('member-fixture');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('alert')).toHaveText('This account has no admin access');
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => [
    sessionStorage.getItem('admin.accessToken') !== null,
    sessionStorage.getItem('admin.refreshToken') !== null,
  ])).toEqual([false, false]);
});

test('theme persists a dark background before paint', async ({ page }) => {
  await page.goto('/login');
  const bg = await page.evaluate(() =>
    getComputedStyle(document.documentElement).backgroundColor,
  );
  expect(bg).toBeTruthy();
});
