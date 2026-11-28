import { expect, test, type Page, type Route } from '@playwright/test';

// Stateful in-browser mock of the ADMIN social settings API. The state shape
// and revision semantics mirror AdminSocialSettingsController exactly enough
// to prove the console never overwrites a stale revision.

interface SocialAnnouncement {
  titleVi: string;
  bodyVi: string;
  titleEn: string;
  bodyEn: string;
}

interface SocialAdminState {
  revision: number;
  webEnabled: boolean;
  videoUploadsEnabled: boolean;
  draft: SocialAnnouncement | null;
  published: SocialAnnouncement | null;
  publishedAt: string | null;
}

interface MutationCall {
  method: string;
  path: string;
  body: unknown;
}

interface Mock {
  calls: MutationCall[];
  getSettingsCount: number;
  state: SocialAdminState;
}

function initialState(): SocialAdminState {
  return {
    revision: 1,
    webEnabled: false,
    videoUploadsEnabled: false,
    draft: null,
    published: null,
    publishedAt: null,
  };
}

function snapshot(state: SocialAdminState): SocialAdminState {
  return {
    ...state,
    draft: state.draft ? { ...state.draft } : null,
    published: state.published ? { ...state.published } : null,
  };
}

function validAnnouncement(value: unknown): value is SocialAnnouncement {
  if (!value || typeof value !== 'object') return false;
  const text = value as Record<string, unknown>;
  const validField = (key: string, max: number) =>
    typeof text[key] === 'string'
    && (text[key] as string).trim().length > 0
    && (text[key] as string).trim().length <= max;
  return validField('titleVi', 100)
    && validField('titleEn', 100)
    && validField('bodyVi', 500)
    && validField('bodyEn', 500);
}

async function mockSocialAdmin(
  page: Page,
  options: { conflictFlags?: boolean } = {},
): Promise<Mock> {
  const mock: Mock = { calls: [], getSettingsCount: 0, state: initialState() };

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
  const conflict = (route: Route) =>
    json(route, 409, { error: { code: 'SOCIAL_SETTINGS_CONFLICT', message: 'stale revision' } });
  const validationFailed = (route: Route, message: string) =>
    json(route, 400, { error: { code: 'VALIDATION_FAILED', message } });

  await page.route('**/api/v1/admin/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const path = url.pathname;

    if (path === '/api/v1/admin/me') {
      return json(route, 200, { userId: 'staff-1', role: 'ADMIN', displayName: 'Admin QA' });
    }

    if (method === 'GET' && path === '/api/v1/admin/social/settings') {
      mock.getSettingsCount += 1;
      return json(route, 200, snapshot(mock.state));
    }

    const raw = request.postData();
    const body = raw ? JSON.parse(raw) as Record<string, unknown> : {};
    if (path.startsWith('/api/v1/admin/social/settings')) {
      mock.calls.push({ method, path, body });
    }

    const stale = body.expectedRevision !== mock.state.revision;

    if (method === 'PATCH' && path === '/api/v1/admin/social/settings/flags') {
      if (options.conflictFlags || stale) return conflict(route);
      if (typeof body.webEnabled !== 'boolean' || typeof body.videoUploadsEnabled !== 'boolean') {
        return validationFailed(route, 'both flags are required');
      }
      mock.state = {
        ...mock.state,
        revision: mock.state.revision + 1,
        webEnabled: body.webEnabled,
        videoUploadsEnabled: body.videoUploadsEnabled,
      };
      return json(route, 200, snapshot(mock.state));
    }

    if (method === 'PUT' && path === '/api/v1/admin/social/settings/announcement/draft') {
      if (stale) return conflict(route);
      if (!validAnnouncement(body)) {
        return validationFailed(route, 'announcement text is required in both languages');
      }
      mock.state = {
        ...mock.state,
        revision: mock.state.revision + 1,
        draft: {
          titleVi: body.titleVi,
          bodyVi: body.bodyVi,
          titleEn: body.titleEn,
          bodyEn: body.bodyEn,
        },
      };
      return json(route, 200, snapshot(mock.state));
    }

    if (method === 'POST' && path === '/api/v1/admin/social/settings/announcement/publish') {
      if (stale) return conflict(route);
      if (!mock.state.draft) {
        return validationFailed(route, 'a saved draft is required');
      }
      mock.state = {
        ...mock.state,
        revision: mock.state.revision + 1,
        published: { ...mock.state.draft },
        publishedAt: '2026-11-28T09:00:00Z',
      };
      return json(route, 200, snapshot(mock.state));
    }

    if (method === 'POST' && path === '/api/v1/admin/social/settings/announcement/unpublish') {
      if (stale) return conflict(route);
      mock.state = {
        ...mock.state,
        revision: mock.state.revision + 1,
        published: null,
        publishedAt: null,
      };
      return json(route, 200, snapshot(mock.state));
    }

    return json(route, 404, { error: { code: 'NOT_FOUND', message: 'unmocked' } });
  });

  return mock;
}

const flagRow = (page: Page, label: string) =>
  page.locator('.check-row').filter({ hasText: label });

async function expectFlagChip(page: Page, label: string, value: 'on' | 'off') {
  await expect(flagRow(page, label).locator('.chip')).toHaveText(value);
}

test.describe('social settings', () => {
  test('saves both switches, then drafts, publishes and takes down the live announcement', async ({
    page,
  }) => {
    const mock = await mockSocialAdmin(page);
    await page.goto('/social');

    await expect(page.getByRole('heading', { name: 'Social', level: 1 })).toBeVisible();
    await expectFlagChip(page, 'Web Social feed', 'off');
    await expectFlagChip(page, 'Video uploads', 'off');

    await flagRow(page, 'Web Social feed').locator('input[type="checkbox"]').check();
    await flagRow(page, 'Video uploads').locator('input[type="checkbox"]').check();
    await page.getByRole('button', { name: 'Save switches' }).click();
    await page
      .getByRole('alertdialog', { name: 'Apply these switches?' })
      .getByRole('button', { name: 'Confirm' })
      .click();

    await expectFlagChip(page, 'Web Social feed', 'on');
    await expectFlagChip(page, 'Video uploads', 'on');
    expect(mock.calls).toEqual([
      {
        method: 'PATCH',
        path: '/api/v1/admin/social/settings/flags',
        body: { expectedRevision: 1, webEnabled: true, videoUploadsEnabled: true },
      },
    ]);

    const vi = page.getByRole('group', { name: 'Tiếng Việt' });
    await vi.locator('input').fill('Bảo trì ngắn');
    await vi.locator('textarea').fill('Bảng tin có thể chậm lúc 22h.');
    const en = page.getByRole('group', { name: 'English' });
    await en.locator('input').fill('Brief maintenance');
    await en.locator('textarea').fill('The feed may be slow at 10 PM.');

    await page.getByRole('button', { name: 'Save draft' }).click();
    await expect.poll(() => mock.state.revision).toBe(3);
    expect(mock.calls[1]).toEqual({
      method: 'PUT',
      path: '/api/v1/admin/social/settings/announcement/draft',
      body: {
        expectedRevision: 2,
        titleVi: 'Bảo trì ngắn',
        bodyVi: 'Bảng tin có thể chậm lúc 22h.',
        titleEn: 'Brief maintenance',
        bodyEn: 'The feed may be slow at 10 PM.',
      },
    });

    await page.getByRole('button', { name: 'Publish' }).click();
    await page
      .getByRole('alertdialog', { name: 'Publish this announcement?' })
      .getByRole('button', { name: 'Confirm' })
      .click();

    await expect(page.locator('.social-live')).toContainText('Bảo trì ngắn');
    await expect(page.locator('.social-live')).toContainText('Bảng tin có thể chậm lúc 22h.');
    expect(mock.calls[2]).toEqual({
      method: 'POST',
      path: '/api/v1/admin/social/settings/announcement/publish',
      body: { expectedRevision: 3 },
    });

    await page.getByRole('button', { name: 'Take down' }).click();
    await page
      .getByRole('alertdialog', { name: 'Take the live announcement down?' })
      .getByRole('button', { name: 'Confirm' })
      .click();

    await expect(page.getByText('Nothing is live right now.')).toBeVisible();
    expect(mock.calls[3]).toEqual({
      method: 'POST',
      path: '/api/v1/admin/social/settings/announcement/unpublish',
      body: { expectedRevision: 4 },
    });
    expect(mock.state.draft).toEqual({
      titleVi: 'Bảo trì ngắn',
      bodyVi: 'Bảng tin có thể chậm lúc 22h.',
      titleEn: 'Brief maintenance',
      bodyEn: 'The feed may be slow at 10 PM.',
    });
  });

  test('a revision conflict shows the reload banner and refetches settings', async ({ page }) => {
    const mock = await mockSocialAdmin(page, { conflictFlags: true });
    await page.goto('/social');
    // React StrictMode intentionally replays the mount effect in the dev
    // server, so baseline the count after the page is interactive and assert
    // the conflict adds exactly one authoritative reload.
    await expectFlagChip(page, 'Web Social feed', 'off');
    const baselineGets = mock.getSettingsCount;

    await flagRow(page, 'Web Social feed').locator('input[type="checkbox"]').check();
    await page.getByRole('button', { name: 'Save switches' }).click();
    await page
      .getByRole('alertdialog', { name: 'Apply these switches?' })
      .getByRole('button', { name: 'Confirm' })
      .click();

    await expect(
      page.getByText('Another admin saved changes first. The page reloaded the latest state — review and retry.'),
    ).toBeVisible();
    await expect.poll(() => mock.getSettingsCount).toBe(baselineGets + 1);
    await expectFlagChip(page, 'Web Social feed', 'off');
  });

  test('saving switches keeps edits to a stored draft and uses the new revision to save them', async ({ page }) => {
    const mock = await mockSocialAdmin(page);
    mock.state.draft = {
      titleVi: 'Tiêu đề đã lưu',
      bodyVi: 'Nội dung đã lưu',
      titleEn: 'Saved title',
      bodyEn: 'Saved body',
    };
    await page.goto('/social');

    const vi = page.getByRole('group', { name: 'Tiếng Việt' });
    const en = page.getByRole('group', { name: 'English' });
    await vi.locator('input').fill('Tiêu đề đang sửa');
    await en.locator('textarea').fill('New English body');

    await flagRow(page, 'Web Social feed').locator('input[type="checkbox"]').check();
    await page.getByRole('button', { name: 'Save switches' }).click();
    await page.getByRole('alertdialog', { name: 'Apply these switches?' })
      .getByRole('button', { name: 'Confirm' }).click();

    await expectFlagChip(page, 'Web Social feed', 'on');
    await expect(vi.locator('input')).toHaveValue('Tiêu đề đang sửa');
    await expect(en.locator('textarea')).toHaveValue('New English body');
    expect(mock.state.draft?.titleVi).toBe('Tiêu đề đã lưu');

    await page.getByRole('button', { name: 'Save draft' }).click();
    await expect.poll(() => mock.state.revision).toBe(3);
    expect(mock.calls[1]).toEqual({
      method: 'PUT',
      path: '/api/v1/admin/social/settings/announcement/draft',
      body: {
        expectedRevision: 2,
        titleVi: 'Tiêu đề đang sửa',
        bodyVi: 'Nội dung đã lưu',
        titleEn: 'Saved title',
        bodyEn: 'New English body',
      },
    });
  });

  test('a switch conflict reloads the revision without discarding edits to an unchanged draft', async ({ page }) => {
    const mock = await mockSocialAdmin(page);
    mock.state.draft = {
      titleVi: 'Tiêu đề đã lưu',
      bodyVi: 'Nội dung đã lưu',
      titleEn: 'Saved title',
      bodyEn: 'Saved body',
    };
    await page.goto('/social');
    const vi = page.getByRole('group', { name: 'Tiếng Việt' });
    await vi.locator('input').fill('Tiêu đề đang sửa');

    mock.state.revision += 1;
    await flagRow(page, 'Web Social feed').locator('input[type="checkbox"]').check();
    await page.getByRole('button', { name: 'Save switches' }).click();
    await page.getByRole('alertdialog', { name: 'Apply these switches?' })
      .getByRole('button', { name: 'Confirm' }).click();

    await expect(page.getByText(/Another admin saved changes first/)).toBeVisible();
    await expect(flagRow(page, 'Web Social feed').locator('input[type="checkbox"]')).not.toBeChecked();
    await expect(vi.locator('input')).toHaveValue('Tiêu đề đang sửa');
    await page.getByRole('button', { name: 'Save draft' }).click();
    await expect.poll(() => mock.state.draft?.titleVi).toBe('Tiêu đề đang sửa');
    expect(mock.calls[1].body).toMatchObject({ expectedRevision: 2, titleVi: 'Tiêu đề đang sửa' });
  });
});
