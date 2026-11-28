import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createSocialClient, SocialSessionError } from '../../site/social/social-client.js';
import { canonicalImageRequestPath, imageUuid } from '../../site/social/social-media.js';

function jsonResponse(status, payload) {
  if (status === 204) {
    return new Response(null, { status });
  }
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function authPayload(accessToken, refreshToken) {
  return {
    user: { id: '11111111-1111-1111-1111-111111111111', displayName: 'Test User' },
    accessToken,
    accessTokenExpiresIn: 3600,
    refreshToken,
    recallWindowSeconds: 0,
    groupAdminMayRecallAfterWindow: false,
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function signedInClient(fetchImpl) {
  const client = createSocialClient(fetchImpl);
  await client.login('user@example.com', 'correct horse battery staple');
  return client;
}

test('login uses the same-origin session path without a bearer and stores nothing durably', async () => {
  const calls = [];
  const client = createSocialClient(async (url, init) => {
    calls.push({ url, init });
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    return jsonResponse(200, { items: [], nextCursor: null });
  });

  await client.login('user@example.com', 'secret');
  await client.request('/posts?limit=20');

  assert.equal(calls[0].url, '/api/social/v1/session/login');
  assert.equal(calls[0].url.startsWith('/api/social/v1/'), true);
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.redirect, 'error');
  assert.equal(calls[0].init.headers.Authorization, undefined);
  assert.equal(calls[1].url, '/api/social/v1/posts?limit=20');
  assert.equal(calls[1].init.headers.Authorization, 'Bearer access-1');

  const source = readFileSync(new URL('../../site/social/social-client.js', import.meta.url), 'utf8');
  for (const forbidden of ['localStorage', 'sessionStorage', 'document.cookie', 'indexedDB']) {
    assert.equal(source.includes(forbidden), false, `${forbidden} must not be used`);
  }
  assert.equal(calls[0].url.includes('secret'), false);
  assert.equal(calls[0].url.includes('user@example.com'), false);
});

test('simultaneous and delayed 401s share one refresh and replay each request once', async () => {
  const calls = [];
  const endpointCounts = new Map();
  let refreshCount = 0;
  let releaseDelayed;
  const delayedGate = new Promise((resolve) => {
    releaseDelayed = resolve;
  });

  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    if (url === '/api/social/v1/session/refresh') {
      refreshCount += 1;
      return jsonResponse(200, authPayload('access-2', 'refresh-2'));
    }
    if (url.startsWith('/api/social/v1/posts/')) {
      const count = (endpointCounts.get(url) || 0) + 1;
      endpointCounts.set(url, count);
      if (count === 1) {
        if (url.endsWith('/c')) {
          await delayedGate;
        }
        return jsonResponse(401, { error: { code: 'UNAUTHORIZED' } });
      }
      return jsonResponse(200, { ok: url });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const client = await signedInClient(fetchImpl);
  const first = client.request('/posts/a');
  const second = client.request('/posts/b');
  const delayed = client.request('/posts/c');
  await Promise.all([first, second]);
  releaseDelayed();
  await delayed;

  assert.equal(refreshCount, 1);
  for (const path of ['/api/social/v1/posts/a', '/api/social/v1/posts/b', '/api/social/v1/posts/c']) {
    assert.equal(endpointCounts.get(path), 2, `${path} must be replayed exactly once`);
  }
  const replays = calls.filter((call) => call.url.startsWith('/api/social/v1/posts/') && call.init.headers.Authorization === 'Bearer access-2');
  assert.equal(replays.length, 3);
  const refresh = calls.find((call) => call.url === '/api/social/v1/session/refresh');
  assert.match(refresh.init.headers['Idempotency-Key'], /^[0-9a-f-]{36}$/i);
  assert.deepEqual(JSON.parse(refresh.init.body), { refreshToken: 'refresh-1' });
});

test('logout during a deferred refresh signs out locally and prevents request resurrection', async () => {
  const calls = [];
  const refreshGate = deferred();
  let refreshStarted;
  const refreshIsStarted = new Promise((resolve) => {
    refreshStarted = resolve;
  });
  let postCount = 0;

  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    if (url === '/api/social/v1/session/refresh') {
      refreshStarted();
      await refreshGate.promise;
      return jsonResponse(200, authPayload('access-2', 'refresh-2'));
    }
    if (url === '/api/social/v1/session/logout') {
      return jsonResponse(204, null);
    }
    if (url === '/api/social/v1/posts') {
      postCount += 1;
      return jsonResponse(401, { error: { code: 'UNAUTHORIZED' } });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const client = await signedInClient(fetchImpl);
  const pending = client.request('/posts').then(
    () => null,
    (error) => error,
  );
  await refreshIsStarted;
  const logoutResult = await client.logout();
  refreshGate.resolve();
  const failure = await pending;

  assert.equal(failure instanceof SocialSessionError, true);
  assert.equal(postCount, 1, 'the original request must not replay after logout');
  assert.equal(logoutResult.local, true);
  assert.equal(logoutResult.revoked, true);
  const logout = calls.find((call) => call.url === '/api/social/v1/session/logout');
  assert.equal(logout.init.headers.Authorization, 'Bearer access-1');
  assert.deepEqual(JSON.parse(logout.init.body), { refreshToken: 'refresh-1' });
});

test('a write retried after one 401 preserves its JSON idempotency key and headers', async () => {
  const bodies = [];
  const writeCalls = [];
  let postCount = 0;
  const clientPostId = '22222222-2222-4222-8222-222222222222';
  const fetchImpl = async (url, init) => {
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    if (url === '/api/social/v1/session/refresh') {
      return jsonResponse(200, authPayload('access-2', 'refresh-2'));
    }
    if (url === '/api/social/v1/posts' && init.method === 'POST') {
      postCount += 1;
      bodies.push(JSON.parse(init.body));
      writeCalls.push({ url, init });
      return postCount === 1
        ? jsonResponse(401, { error: { code: 'UNAUTHORIZED' } })
        : jsonResponse(201, { id: '33333333-3333-3333-3333-333333333333' });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const client = await signedInClient(fetchImpl);
  await client.request('/posts', {
    method: 'POST',
    body: { body: 'hello', clientPostId },
  });

  assert.equal(postCount, 2);
  assert.equal(bodies.length, 2);
  assert.deepEqual(bodies[0], bodies[1]);
  assert.equal(bodies[0].clientPostId, clientPostId);
  assert.equal(writeCalls[0].init.headers['Content-Type'], 'application/json');
  assert.equal(writeCalls[1].init.headers.Authorization, 'Bearer access-2');
});

test('foreign, blocked-shaped, or malformed image URLs are never turned into fetch paths', async () => {
  const valid = '44444444-4444-4444-4444-444444444444';
  assert.equal(canonicalImageRequestPath(`/api/v1/feed/images/${valid}`), `/images/${valid}`);
  assert.equal(canonicalImageRequestPath(`/api/social/v1/images/${valid}`), `/images/${valid}`);
  assert.equal(imageUuid(`/api/v1/feed/images/${valid}`), valid);
  for (const bad of [
    'https://evil.example/image.png',
    '//evil.example/image.png',
    `/api/v1/feed/images/${valid}/extra`,
    `/api/v1/files/${valid}`,
    `/api/v1/feed/images/not-a-uuid`,
    '',
    null,
  ]) {
    assert.equal(canonicalImageRequestPath(bad), null, String(bad));
  }

  let fetchCount = 0;
  const client = createSocialClient(async () => {
    fetchCount += 1;
    return jsonResponse(200, {});
  });
  await assert.rejects(() => client.request('https://evil.example/image.png'), TypeError);
  await assert.rejects(() => client.request('/../image.png'), TypeError);
  assert.equal(fetchCount, 0);
});

test('an ambiguous refresh transport failure fails closed once and does not reuse the predecessor token', async () => {
  const refreshBodies = [];
  let refreshCount = 0;
  const fetchImpl = async (url, init) => {
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    if (url === '/api/social/v1/session/refresh') {
      refreshCount += 1;
      refreshBodies.push(JSON.parse(init.body));
      throw new TypeError('network outcome unknown');
    }
    if (url === '/api/social/v1/posts') {
      return jsonResponse(401, { error: { code: 'UNAUTHORIZED' } });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const client = await signedInClient(fetchImpl);
  await assert.rejects(() => client.request('/posts'), SocialSessionError);
  await assert.rejects(() => client.request('/posts'), SocialSessionError);

  assert.equal(refreshCount, 1);
  assert.deepEqual(refreshBodies, [{ refreshToken: 'refresh-1' }]);
});

test('a response body resolving after logout cannot escape the request boundary', async () => {
  const responseGate = deferred();
  let releaseBody;
  const bodyGate = new Promise((resolve) => {
    releaseBody = resolve;
  });
  const fetchImpl = async (url) => {
    if (url === '/api/social/v1/session/login') {
      return jsonResponse(200, authPayload('access-1', 'refresh-1'));
    }
    if (url === '/api/social/v1/session/logout') {
      return jsonResponse(204, null);
    }
    if (url === '/api/social/v1/posts') {
      await responseGate.promise;
      return new Response(JSON.stringify({ items: [{ id: 'late' }], nextCursor: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    throw new Error(`unexpected URL ${url}`);
  };

  const client = await signedInClient(fetchImpl);
  const originalText = Response.prototype.text;
  Response.prototype.text = async function delayedText() {
    await bodyGate;
    return originalText.call(this);
  };
  try {
    const pending = client.request('/posts').then(
      () => null,
      (error) => error,
    );
    responseGate.resolve();
    await client.logout();
    releaseBody();
    const failure = await pending;
    assert.equal(failure instanceof SocialSessionError, true);
    assert.equal(failure && failure.items, undefined);
  } finally {
    Response.prototype.text = originalText;
  }
});

test('the no-JS login fallback cannot serialize credentials and hidden UI wins over display rules', () => {
  const html = readFileSync(new URL('../../site/social/index.html', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../../site/social/social.css', import.meta.url), 'utf8');
  assert.match(html, /<form id="login-form" method="post" novalidate>/);
  assert.match(html, /<input class="text-input" id="login-identifier"(?!.*name=)/);
  assert.match(html, /<input class="text-input" id="login-password"(?!.*name=)/);
  assert.match(css, /\[hidden\] \{ display: none !important; \}/);
});
