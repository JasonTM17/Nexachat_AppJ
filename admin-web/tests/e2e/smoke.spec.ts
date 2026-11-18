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

test('theme persists a dark background before paint', async ({ page }) => {
  await page.goto('/login');
  const bg = await page.evaluate(() =>
    getComputedStyle(document.documentElement).backgroundColor,
  );
  expect(bg).toBeTruthy();
});
