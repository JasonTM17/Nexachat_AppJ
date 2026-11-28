import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const SITE = new URL('../../site/', import.meta.url);
const RELEASES = 'https://github.com/JasonTM17/Nexachat_AppJ/releases';
const API = 'https://api.github.com/repos/JasonTM17/Nexachat_AppJ/releases/latest';
const APK = 'nexachat-latest.apk';
const CHECKSUM = APK + '.sha256';
const FALLBACK = {
  apk: RELEASES + '/latest/download/' + APK,
  checksum: RELEASES + '/latest/download/' + CHECKSUM,
  notes: RELEASES + '/latest',
};

function stableRelease(tag) {
  return {
    tag_name: tag,
    draft: false,
    prerelease: false,
    assets: [APK, CHECKSUM].map((name) => ({
      name,
      state: 'uploaded',
      size: 128,
      browser_download_url: RELEASES + '/download/' + tag + '/' + name,
    })),
  };
}

async function runDownloadPage(fetchRelease, scheduleTimeout = setTimeout) {
  const script = readFileSync(new URL('download.js', SITE), 'utf8');
  const locales = ['vi', 'en'].map(() => ({
    version: { textContent: '' },
    label: { hidden: true },
    links: Object.entries(FALLBACK).map(([kind, href]) => ({
      dataset: { releaseLink: kind },
      href,
    })),
  }));
  const selectors = {
    '[data-release-version]': locales.map(({ version }) => version),
    '[data-release-version-label]': locales.map(({ label }) => label),
    '[data-release-link]': locales.flatMap(({ links }) => links),
  };
  const requests = [];
  await runInNewContext(script, {
    AbortController,
    setTimeout: scheduleTimeout,
    clearTimeout,
    document: { querySelectorAll: (selector) => selectors[selector] },
    fetch(url, options) {
      requests.push({ url, options });
      return fetchRelease(url, options);
    },
  });
  return { locales, requests };
}

function response(release) {
  return { ok: true, json: async () => release };
}

function linkMap({ links }) {
  return Object.fromEntries(links.map(({ dataset, href }) => [dataset.releaseLink, href]));
}

test('the bilingual download HTML provides latest links without hard-coded version metadata', () => {
  const html = readFileSync(new URL('download.html', SITE), 'utf8');
  assert.doesNotMatch(html, /\bv\d+\.\d+\.\d+\b/);
  assert.match(html, /<script defer src="\/download\.js"><\/script>/);
  for (const lang of ['vi', 'en']) {
    const section = html.split('<section id="lang-' + lang + '"')[1].split('</section>')[0];
    assert.match(section, /data-release-version-label hidden/);
    for (const [kind, url] of Object.entries(FALLBACK)) {
      assert.ok(section.includes('href="' + url + '" data-release-link="' + kind + '"'), lang + ' ' + kind);
    }
  }
});

test('only the download page can connect to the public GitHub release endpoint', () => {
  const { headers } = JSON.parse(readFileSync(new URL('vercel.json', SITE), 'utf8'));
  const githubPolicies = headers.flatMap(({ source, headers: entries }) =>
    entries.filter(({ key, value }) =>
      key === 'Content-Security-Policy' && value.includes('api.github.com')
    ).map(({ value }) => ({ source, value }))
  );
  assert.equal(githubPolicies.length, 1);
  assert.equal(githubPolicies[0].source, '/download.html');
  const [, connectSrc] = githubPolicies[0].value.match(/(?:^|; )connect-src ([^;]+);/);
  assert.equal(connectSrc, API);
});

test('both locales show each stable tag and pin the official APK, checksum and notes to that tag', async () => {
  for (const tag of ['v1.2.32', 'v2.0.0']) {
    const { locales, requests } = await runDownloadPage(async () => response(stableRelease(tag)));
    assert.equal(requests.length, 1);
    const [{ url, options }] = requests;
    assert.equal(url, API);
    assert.equal(options.credentials, 'omit');
    assert.equal(options.redirect, 'error');
    assert.equal(options.referrerPolicy, 'no-referrer');
    for (const locale of locales) {
      assert.equal(locale.version.textContent, tag);
      assert.equal(locale.label.hidden, false);
      assert.deepEqual(linkMap(locale), {
        apk: RELEASES + '/download/' + tag + '/' + APK,
        checksum: RELEASES + '/download/' + tag + '/' + CHECKSUM,
        notes: RELEASES + '/tag/' + tag,
      });
    }
  }
});

test('unavailable or unverified metadata leaves the official fallbacks usable', async (t) => {
  const tag = 'v2.0.0';
  const base = stableRelease(tag);
  const scenarios = {
    draft: () => response({ ...base, draft: true }),
    prerelease: () => response({ ...base, prerelease: true }),
    invalidTag: () => response({ ...base, tag_name: 'v2.0.0-rc.1' }),
    trailingLine: () => response(stableRelease('v2.0.0\n')),
    missingChecksum: () => response({ ...base, assets: [base.assets[0]] }),
    incompleteUpload: () => response({ ...base, assets: [{ ...base.assets[0], size: 0 }, base.assets[1]] }),
    otherHost: () => response({
      ...base,
      assets: [{ ...base.assets[0], browser_download_url: 'https://example.org/' + APK }, base.assets[1]],
    }),
    otherRelease: () => response({ ...base, assets: stableRelease('v1.2.32').assets }),
    rateLimit: () => ({ ok: false, status: 429 }),
    invalidJson: () => ({ ok: true, json: async () => { throw new SyntaxError('invalid JSON'); } }),
    networkError: () => { throw new TypeError('network unavailable'); },
  };
  for (const [name, fetchRelease] of Object.entries(scenarios)) {
    await t.test(name, async () => {
      const { locales } = await runDownloadPage(fetchRelease);
      for (const locale of locales) {
        assert.equal(locale.label.hidden, true);
        assert.equal(locale.version.textContent, '');
        assert.deepEqual(linkMap(locale), FALLBACK);
      }
    });
  }
});

test('a slow request is aborted while both locales retain the latest download links', async () => {
  let expire;
  const { locales } = await runDownloadPage(
    (_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
      expire();
    }),
    (onTimeout, delay) => {
      assert.equal(delay, 6000);
      expire = onTimeout;
    },
  );
  for (const locale of locales) {
    assert.equal(locale.label.hidden, true);
    assert.deepEqual(linkMap(locale), FALLBACK);
  }
});
