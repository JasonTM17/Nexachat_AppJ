import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

type SocialConfig = {
  webEnabled: boolean;
  videoUploadsEnabled: boolean;
  announcement: { titleVi: string; bodyVi: string; titleEn: string; bodyEn: string } | null;
};

type ConfigFixture = Window & {
  socialConfigRequests: () => number;
  respondToSocialConfig: (index: number, config: SocialConfig | null) => Promise<void>;
};

const origin = 'https://nexachat-social.test';
const site = new URL('../../../site/', import.meta.url);
const enabled: SocialConfig = {
  webEnabled: true,
  videoUploadsEnabled: true,
  announcement: { titleVi: 'Thông báo', bodyVi: 'Nội dung thử', titleEn: 'Notice', bodyEn: 'Fixture body' },
};
const disabled: SocialConfig = { webEnabled: false, videoUploadsEnabled: false, announcement: null };

async function openWithOverlappingConfigReads(page: Page) {
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    const path = url.pathname === '/social/' ? '/social/index.html' : url.pathname;
    try {
      await route.fulfill({ path: fileURLToPath(new URL(path.slice(1), site)) });
    } catch {
      await route.fulfill({ status: 404, body: '' });
    }
  });
  await page.addInitScript(() => {
    const fixture = window as ConfigFixture;
    const pending: Array<(response: Response) => void> = [];
    const fetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      if (input === '/api/social/v1/config') {
        return new Promise<Response>((resolve) => pending.push(resolve));
      }
      return fetch(input, init);
    };
    fixture.socialConfigRequests = () => pending.length;
    fixture.respondToSocialConfig = (index, config) => new Promise<void>((done) => {
      if (config === null) {
        pending[index](new Response(null, { status: 503 }));
        window.setTimeout(done, 0);
        return;
      }
      const response = Response.json(config);
      const text = response.text.bind(response);
      response.text = async () => {
        const body = await text();
        // Wait for the app to consume the response and take its UI update turn.
        window.setTimeout(done, 0);
        return body;
      };
      pending[index](response);
    });
  });
  await page.goto(origin + '/social/');
  await page.waitForFunction(() => (window as ConfigFixture).socialConfigRequests() === 1);
  await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await page.waitForFunction(() => (window as ConfigFixture).socialConfigRequests() === 2);
}

function respond(page: Page, index: number, config: SocialConfig | null) {
  return page.evaluate(({ index, config }) => (window as ConfigFixture).respondToSocialConfig(index, config), { index, config });
}

test('an older enabled response cannot reopen a disabled Social page or restore its announcement', async ({ page }) => {
  await openWithOverlappingConfigReads(page);
  await respond(page, 1, disabled);
  await expect(page.locator('#unavailable-panel')).toBeVisible();

  await respond(page, 0, enabled);
  await expect(page.locator('#unavailable-panel')).toBeVisible();
  await expect(page.locator('#signed-out-panel')).toBeHidden();
  await expect(page.locator('#announcement-card')).toBeHidden();
});

test('an older config failure cannot hide a page enabled by the latest response', async ({ page }) => {
  await openWithOverlappingConfigReads(page);
  await respond(page, 1, enabled);
  await expect(page.locator('#signed-out-panel')).toBeVisible();

  await respond(page, 0, null);
  await expect(page.locator('#signed-out-panel')).toBeVisible();
  await expect(page.locator('#announcement-card')).toBeVisible();
  await expect(page.locator('#unavailable-panel')).toBeHidden();
});
