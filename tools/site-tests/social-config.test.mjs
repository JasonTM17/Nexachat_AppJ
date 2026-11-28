import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fetchSocialConfig, SocialApiError } from '../../site/social/social-client.js';

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const announcement = {
  titleVi: 'Thông báo bảng tin',
  bodyVi: 'Nội dung tiếng Việt',
  titleEn: 'Feed notice',
  bodyEn: 'English body',
};

test('fetchSocialConfig reads the anonymous same-origin config with no credentials or cache', async () => {
  const calls = [];
  const config = await fetchSocialConfig(async (url, init) => {
    calls.push({ url, init });
    return jsonResponse(200, {
      webEnabled: true,
      videoUploadsEnabled: false,
      announcement,
    });
  });

  assert.deepEqual(config, {
    webEnabled: true,
    videoUploadsEnabled: false,
    announcement,
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/social/v1/config');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.redirect, 'error');
  assert.equal(calls[0].init.headers, undefined);
  assert.equal(calls[0].init.body, undefined);
});

test('fetchSocialConfig accepts a valid payload without an announcement', async () => {
  const config = await fetchSocialConfig(async () => jsonResponse(200, {
    webEnabled: false,
    videoUploadsEnabled: false,
    announcement: null,
  }));
  assert.deepEqual(config, {
    webEnabled: false,
    videoUploadsEnabled: false,
    announcement: null,
  });
});

test('fetchSocialConfig fails closed for malformed JSON and missing or non-boolean flags', async () => {
  const malformedBodies = [
    '{not-json',
    JSON.stringify(null),
    JSON.stringify({}),
    JSON.stringify({ webEnabled: true }),
    JSON.stringify({ videoUploadsEnabled: false }),
    JSON.stringify({ webEnabled: 'true', videoUploadsEnabled: false }),
    JSON.stringify({ webEnabled: true, videoUploadsEnabled: 0 }),
  ];

  for (const body of malformedBodies) {
    let fetched = 0;
    await assert.rejects(
      () => fetchSocialConfig(async () => {
        fetched += 1;
        return new Response(body, {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }),
      (error) => error instanceof SocialApiError && error.status === 200,
      body,
    );
    assert.equal(fetched, 1);
  }
});

test('fetchSocialConfig fails closed for an invalid announcement shape or blank copy', async () => {
  const invalidAnnouncements = [
    'not-an-object',
    {},
    { ...announcement, bodyVi: '   ' },
    { ...announcement, titleEn: undefined },
    { ...announcement, bodyEn: null },
  ];

  for (const invalid of invalidAnnouncements) {
    await assert.rejects(
      () => fetchSocialConfig(async () => jsonResponse(200, {
        webEnabled: true,
        videoUploadsEnabled: true,
        announcement: invalid,
      })),
      (error) => error instanceof SocialApiError && error.status === 200,
      JSON.stringify(invalid),
    );
  }
});

test('fetchSocialConfig propagates non-2xx and transport failures instead of enabling the surface', async () => {
  for (const status of [403, 404, 500]) {
    await assert.rejects(
      () => fetchSocialConfig(async () => jsonResponse(status, { error: { code: 'NOPE' } })),
      (error) => error instanceof SocialApiError && error.status === status,
    );
  }

  const transport = new TypeError('network unavailable');
  await assert.rejects(
    () => fetchSocialConfig(async () => {
      throw transport;
    }),
    (error) => error === transport,
  );
});

test('the social page keeps interactive panels behind the config arbiter and wires the video/language gates', () => {
  // tools/site-tests deliberately stays browser-free; the small DOM shim does
  // not implement the full app surface (classList, dialog, media nodes and
  // rendering). These source-level assertions pin the bootstrap contract that
  // the transport tests above cannot execute directly.
  const html = readFileSync(new URL('../../site/social/index.html', import.meta.url), 'utf8');
  const app = readFileSync(new URL('../../site/social/social-app.js', import.meta.url), 'utf8');

  assert.match(html, /<section class="card announcement-card" id="announcement-card"[^>]*hidden>/);
  assert.match(html, /<section class="card unavailable-card" id="unavailable-panel"[^>]*hidden>/);
  assert.match(html, /<section class="card auth-card" id="signed-out-panel"[^>]*hidden>/);
  assert.match(html, /<input class="text-input media-input" id="post-media" type="file" accept="image\/jpeg,image\/png,image\/webp,video\/mp4">/);

  const extractFunction = (name) => {
    const match = app.match(new RegExp(`function ${name}\\(\\) \\{[\\s\\S]*?\\n\\}`));
    assert.ok(match, `${name} must exist`);
    return match[0];
  };

  const surface = extractFunction('applySurfaceState');
  assert.match(surface, /els\.signedOut\.hidden = true/);
  assert.match(surface, /els\.session\.hidden = true/);
  assert.match(surface, /els\.composer\.hidden = true/);
  assert.match(surface, /els\.feedPanel\.hidden = true/);
  assert.match(surface, /surfaceState === 'disabled' \? 'socialDisabled'/);
  assert.match(surface, /surfaceState === 'unavailable' \? 'configUnavailable'/);

  assert.match(app, /let surfaceState = 'loading';/);
  assert.match(app, /surfaceState = config\.webEnabled \? 'enabled' : 'disabled';/);
  assert.match(app, /surfaceState = 'unavailable';/);
  assert.match(app, /document\.addEventListener\('visibilitychange', \(\) => \{/);

  const renderAnnouncement = extractFunction('renderAnnouncement');
  assert.match(renderAnnouncement, /lang === 'en' \? announcement\.titleEn : announcement\.titleVi/);
  assert.match(renderAnnouncement, /lang === 'en' \? announcement\.bodyEn : announcement\.bodyVi/);

  const videoGate = extractFunction('applyVideoGate');
  assert.match(videoGate, /'image\/jpeg,image\/png,image\/webp'/);
  assert.match(videoGate, /'image\/jpeg,image\/png,image\/webp,video\/mp4'/);
  assert.match(videoGate, /videoOff \? 'mediaHelpImagesOnly' : 'mediaHelp'/);
});
