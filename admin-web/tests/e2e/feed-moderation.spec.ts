import { expect, test, type Page, type Route } from '@playwright/test';

// Feed moderation journey against a stateful in-browser mock of the admin API.
// The fixture mirrors the server's AdminFeedReportResponse field for field.

type Status = 'OPEN' | 'REVIEWED' | 'DISMISSED';

interface Report {
  id: string;
  targetType: 'POST' | 'COMMENT';
  targetId: string;
  reason: string;
  details: string | null;
  messageSnapshot: string | null;
  status: Status;
  createdAt: string;
  reporterId: string;
  reporterName: string | null;
  authorId: string | null;
  authorName: string | null;
  targetBody: string | null;
  targetDeleted: boolean;
  targetHidden: boolean;
  targetCreatedAt: string | null;
}

interface Call {
  method: string;
  path: string;
  body: unknown;
}

const SPAM_TEXT = 'Cheap followers, tap the link in my profile';
const ABUSE_TEXT = 'Nobody wants you in this group';
const HIDDEN_TEXT = 'Miracle cure, order today';

function report(over: Partial<Report> & Pick<Report, 'id' | 'targetId'>): Report {
  return {
    targetType: 'POST',
    reason: 'SPAM',
    details: null,
    messageSnapshot: null,
    status: 'OPEN',
    createdAt: '2026-11-17T08:00:00Z',
    reporterId: '00000000-0000-0000-0000-0000000000a1',
    reporterName: 'Quan',
    authorId: '00000000-0000-0000-0000-0000000000b1',
    authorName: 'Mai',
    targetBody: null,
    targetDeleted: false,
    targetHidden: false,
    targetCreatedAt: '2026-11-17T07:00:00Z',
    ...over,
  };
}

function fixture(): Report[] {
  return [
    report({
      id: 'r-post',
      targetId: 'p-spam',
      reason: 'SPAM',
      details: 'Posted the same link five times',
      messageSnapshot: SPAM_TEXT,
      targetBody: SPAM_TEXT,
    }),
    report({
      id: 'r-comment',
      targetType: 'COMMENT',
      targetId: 'c-abuse',
      reason: 'HARASSMENT',
      authorName: 'Bao',
      messageSnapshot: ABUSE_TEXT,
      targetBody: ABUSE_TEXT,
    }),
    report({
      id: 'r-image',
      targetId: 'p-image',
      reason: 'SEXUAL',
      authorName: 'Linh',
      messageSnapshot: null,
      targetBody: null,
    }),
    report({
      id: 'r-hidden',
      targetId: 'p-hidden',
      reason: 'SCAM',
      authorName: 'Hoa',
      messageSnapshot: HIDDEN_TEXT,
      targetBody: HIDDEN_TEXT,
      targetHidden: true,
    }),
  ];
}

interface Mock {
  calls: Call[];
  reports: Report[];
}

async function mockAdmin(
  page: Page,
  options: { reports?: Report[] } = {},
): Promise<Mock> {
  const mock: Mock = { calls: [], reports: options.reports ?? fixture() };

  await page.addInitScript(() => {
    sessionStorage.setItem('admin.accessToken', 'test-access');
    sessionStorage.setItem('admin.refreshToken', 'test-refresh');
  });

  const json = (route: Route, status: number, body?: unknown) =>
    route.fulfill(
      body === undefined
        ? { status }
        : { status, contentType: 'application/json', body: JSON.stringify(body) },
    );

  await page.route('**/api/v1/admin/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const path = url.pathname;

    if (path === '/api/v1/admin/me') {
      return json(route, 200, { userId: 'staff-1', role: 'ADMIN', displayName: 'Staff' });
    }

    if (method === 'GET' && path === '/api/v1/admin/feed/reports') {
      const status = url.searchParams.get('status');
      const items = mock.reports.filter((r) => !status || r.status === status);
      return json(route, 200, {
        items,
        page: 0,
        size: 25,
        total: items.length,
        hasMore: false,
      });
    }

    const body = request.postData() ? JSON.parse(request.postData() as string) : undefined;
    mock.calls.push({ method, path, body });

    const target = path.match(/^\/api\/v1\/admin\/feed\/(posts|comments)\/([^/]+)\/(hide|unhide)$/);
    if (method === 'POST' && target) {
      for (const r of mock.reports) {
        if (r.targetId === target[2]) r.targetHidden = target[3] === 'hide';
      }
      return json(route, 204);
    }

    const resolve = path.match(/^\/api\/v1\/admin\/feed\/reports\/([^/]+)\/resolve$/);
    if (method === 'POST' && resolve) {
      const found = mock.reports.find((r) => r.id === resolve[1]);
      if (found) found.status = (body as { status: Status }).status;
      return json(route, 204);
    }

    return json(route, 404, { error: { code: 'NOT_FOUND', message: 'unmocked' } });
  });

  return mock;
}

const row = (page: Page, text: string) => page.getByRole('row').filter({ hasText: text });

test.describe('feed moderation', () => {
  test('report queue lists posts and comments with author, snapshot and state', async ({
    page,
  }) => {
    await mockAdmin(page);
    await page.goto('/feed');

    await expect(page.getByRole('heading', { name: 'Feed', level: 1 })).toBeVisible();
    await expect(page.getByLabel('Status')).toHaveValue('OPEN');
    await expect(page.getByRole('row')).toHaveCount(5); // header + 4 reports

    const post = row(page, SPAM_TEXT);
    await expect(post.getByRole('cell', { name: 'Post', exact: true })).toBeVisible();
    await expect(post.getByRole('cell', { name: 'Mai', exact: true })).toBeVisible();
    // reason appears in its column and in the mobile-only meta block
    await expect(post.getByText('SPAM', { exact: true })).toHaveCount(2);
    await expect(post.getByRole('cell', { name: 'Visible', exact: true })).toBeVisible();
    await expect(post.getByRole('button', { name: 'Hide', exact: true })).toBeVisible();
    await expect(post.getByRole('button', { name: 'Dismiss' })).toBeVisible();
    await expect(post.getByRole('button', { name: 'Mark reviewed' })).toBeVisible();

    const comment = row(page, ABUSE_TEXT);
    await expect(comment.getByRole('cell', { name: 'Comment', exact: true })).toBeVisible();
    await expect(comment.getByRole('cell', { name: 'Bao', exact: true })).toBeVisible();
    await expect(comment.getByText('HARASSMENT', { exact: true })).toHaveCount(2);

    const hidden = row(page, HIDDEN_TEXT);
    await expect(hidden.getByRole('cell', { name: 'Hidden', exact: true })).toBeVisible();
    await expect(hidden.getByRole('button', { name: 'Unhide' })).toBeVisible();
    await expect(hidden.getByRole('button', { name: 'Hide', exact: true })).toHaveCount(0);
  });

  test('narrow queue keeps the deleted state with the report content', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    const deletedText = 'Deleted comment was reported';
    await mockAdmin(page, {
      reports: [report({
        id: 'r-deleted',
        targetId: 'c-deleted',
        targetType: 'COMMENT',
        messageSnapshot: deletedText,
        targetDeleted: true,
      })],
    });
    await page.goto('/feed');

    const deleted = row(page, deletedText);
    const content = deleted.getByRole('cell', { name: /Deleted.*Deleted comment was reported/ });
    await expect(content).toBeVisible();
    await expect(deleted.getByRole('button', { name: 'Mark reviewed' })).toBeVisible();
  });

  test('long snapshot expands to show details and collapses again', async ({ page }) => {
    await mockAdmin(page);
    await page.goto('/feed');

    const post = row(page, SPAM_TEXT);
    const toggle = post.getByRole('button', { name: 'Show full' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(post.getByText('Posted the same link five times')).toHaveCount(0);

    await toggle.click();
    await expect(post.getByRole('button', { name: 'Show less' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    await expect(post.getByText('Posted the same link five times')).toBeVisible();

    await post.getByRole('button', { name: 'Show less' }).click();
    await expect(post.getByText('Posted the same link five times')).toHaveCount(0);
  });

  test('image-only post with no text renders a placeholder', async ({ page }) => {
    await mockAdmin(page);
    await page.goto('/feed');

    const image = row(page, 'Linh');
    await expect(image.getByRole('cell', { name: 'Post', exact: true })).toBeVisible();
    await expect(image.getByRole('cell', { name: '—', exact: true }).first()).toBeVisible();
    await expect(image.getByText('SEXUAL', { exact: true })).toHaveCount(2);
    await expect(image.getByRole('button', { name: 'Hide', exact: true })).toBeVisible();
    await expect(image.getByText(/null|undefined/)).toHaveCount(0);
  });

  test('hide asks for a reason and confirmation; cancel keeps state', async ({ page }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    const post = row(page, SPAM_TEXT);
    await post.getByRole('button', { name: 'Hide', exact: true }).click();

    const dialog = page.getByRole('alertdialog', { name: 'Hide post' });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel(/Reason/).fill('Spam ad');
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    await expect(dialog).toHaveCount(0);
    await expect(post.getByRole('cell', { name: 'Visible', exact: true })).toBeVisible();
    expect(mock.calls).toEqual([]);
  });

  test('hide requires a reason, then sends it and shows the hidden state', async ({ page }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    await row(page, SPAM_TEXT).getByRole('button', { name: 'Hide', exact: true }).click();
    const dialog = page.getByRole('alertdialog', { name: 'Hide post' });

    await dialog.getByRole('button', { name: 'Confirm' }).click();
    await expect(dialog).toBeVisible();
    expect(mock.calls).toEqual([]);

    await dialog.getByLabel(/Reason/).fill('  Spam ad  ');
    await dialog.getByRole('button', { name: 'Confirm' }).click();

    await expect(dialog).toHaveCount(0);
    const post = row(page, SPAM_TEXT);
    await expect(post.getByRole('cell', { name: 'Hidden', exact: true })).toBeVisible();
    await expect(post.getByRole('button', { name: 'Unhide' })).toBeVisible();
    expect(mock.calls).toEqual([
      { method: 'POST', path: '/api/v1/admin/feed/posts/p-spam/hide', body: { reason: 'Spam ad' } },
    ]);
  });

  test('hiding a comment uses the comments endpoint', async ({ page }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    await row(page, ABUSE_TEXT).getByRole('button', { name: 'Hide', exact: true }).click();
    const dialog = page.getByRole('alertdialog', { name: 'Hide comment' });
    await dialog.getByLabel(/Reason/).fill('Abuse');
    await dialog.getByRole('button', { name: 'Confirm' }).click();

    await expect(
      row(page, ABUSE_TEXT).getByRole('cell', { name: 'Hidden', exact: true }),
    ).toBeVisible();
    expect(mock.calls).toEqual([
      {
        method: 'POST',
        path: '/api/v1/admin/feed/comments/c-abuse/hide',
        body: { reason: 'Abuse' },
      },
    ]);
  });

  test('unhide restores a hidden post without a dialog', async ({ page }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    await row(page, HIDDEN_TEXT).getByRole('button', { name: 'Unhide' }).click();

    const hidden = row(page, HIDDEN_TEXT);
    await expect(hidden.getByRole('cell', { name: 'Visible', exact: true })).toBeVisible();
    await expect(hidden.getByRole('button', { name: 'Hide', exact: true })).toBeVisible();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(mock.calls).toEqual([
      { method: 'POST', path: '/api/v1/admin/feed/posts/p-hidden/unhide', body: undefined },
    ]);
  });

  test('mark reviewed sends REVIEWED and moves the report out of the open queue', async ({
    page,
  }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    await row(page, SPAM_TEXT).getByRole('button', { name: 'Mark reviewed' }).click();

    await expect(row(page, SPAM_TEXT)).toHaveCount(0);
    await expect(page.getByRole('row')).toHaveCount(4);
    expect(mock.calls).toEqual([
      {
        method: 'POST',
        path: '/api/v1/admin/feed/reports/r-post/resolve',
        body: { status: 'REVIEWED' },
      },
    ]);

    await page.getByLabel('Status').selectOption('REVIEWED');
    const reviewed = row(page, SPAM_TEXT);
    await expect(reviewed).toBeVisible();
    await expect(reviewed.getByRole('button', { name: 'Dismiss' })).toHaveCount(0);
    await expect(reviewed.getByRole('button', { name: 'Mark reviewed' })).toHaveCount(0);
  });

  test('dismiss sends DISMISSED and lists the report under Dismissed', async ({ page }) => {
    const mock = await mockAdmin(page);
    await page.goto('/feed');

    await row(page, ABUSE_TEXT).getByRole('button', { name: 'Dismiss' }).click();

    await expect(row(page, ABUSE_TEXT)).toHaveCount(0);
    expect(mock.calls).toEqual([
      {
        method: 'POST',
        path: '/api/v1/admin/feed/reports/r-comment/resolve',
        body: { status: 'DISMISSED' },
      },
    ]);

    await page.getByLabel('Status').selectOption('DISMISSED');
    await expect(row(page, ABUSE_TEXT)).toBeVisible();
  });

  test('a failed action shows an error and keeps the row', async ({ page }) => {
    await mockAdmin(page);
    await page.route('**/api/v1/admin/feed/reports/*/resolve', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'INTERNAL', message: 'boom' } }),
      }),
    );
    await page.goto('/feed');

    await row(page, SPAM_TEXT).getByRole('button', { name: 'Mark reviewed' }).click();
    await expect(page.getByRole('alert')).toContainText('Something went wrong');
    await expect(row(page, SPAM_TEXT)).toBeVisible();
  });

  test('empty queue shows the empty state', async ({ page }) => {
    await mockAdmin(page, { reports: [] });
    await page.goto('/feed');

    await expect(page.getByText('Nothing here')).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
  });

  test('API failure shows the error state and retry recovers', async ({ page }) => {
    await mockAdmin(page);
    let fail = true;
    await page.route('**/api/v1/admin/feed/reports?*', async (route) => {
      if (fail) {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'INTERNAL', message: 'boom' } }),
        });
      } else {
        await route.fallback();
      }
    });
    await page.goto('/feed');

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('Something went wrong');
    await expect(page.getByRole('table')).toHaveCount(0);

    fail = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(row(page, SPAM_TEXT)).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('page text carries no email address or phone number', async ({ page }) => {
    await mockAdmin(page);
    await page.goto('/feed');
    await expect(row(page, SPAM_TEXT)).toBeVisible();
    await row(page, SPAM_TEXT).getByRole('button', { name: 'Show full' }).click();

    const text = (await page.locator('main').innerText()).replace(/\s+/g, ' ');
    expect(text).not.toContain('@');
    expect(text).not.toContain('+84');
    expect(text).not.toMatch(/\b0\d{9,10}\b/);
  });

  test('Vietnamese locale renders the queue and the hide dialog', async ({ page }) => {
    await mockAdmin(page);
    await page.goto('/feed');
    await expect(row(page, SPAM_TEXT)).toBeVisible();

    await page.getByLabel('Language').selectOption('vi');

    await expect(page.getByRole('heading', { name: 'Bản tin', level: 1 })).toBeVisible();
    await expect(page.getByLabel('Tình trạng')).toHaveValue('OPEN');
    await expect(page.getByRole('columnheader', { name: 'Tác giả' })).toBeVisible();

    const post = row(page, SPAM_TEXT);
    await expect(post.getByRole('cell', { name: 'Bài viết', exact: true })).toBeVisible();
    await expect(post.getByRole('cell', { name: 'Hiển thị', exact: true })).toBeVisible();
    await expect(row(page, ABUSE_TEXT).getByRole('cell', { name: 'Bình luận', exact: true }))
      .toBeVisible();
    await expect(
      row(page, HIDDEN_TEXT).getByRole('cell', { name: 'Đã ẩn', exact: true }),
    ).toBeVisible();

    await post.getByRole('button', { name: 'Ẩn', exact: true }).click();
    const dialog = page.getByRole('alertdialog', { name: 'Ẩn bài viết' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel(/Lý do/)).toBeVisible();
    await dialog.getByRole('button', { name: 'Hủy' }).click();
    await expect(dialog).toHaveCount(0);
  });
});
