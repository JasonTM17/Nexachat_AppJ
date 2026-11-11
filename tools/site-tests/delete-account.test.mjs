/**
 * Behaviour tests for the static site's lost-device account deletion form
 * (`site/delete-account.js`).
 *
 * The suite runs on Node's built-in test runner with the in-repo DOM shim, so
 * it needs no npm packages, network, browser or running server. It covers the
 * contract the W4 review found untested: validation, the OTP step flow,
 * error/rate-limit messages, focus/busy handling, phone-change reset and the
 * guarantee that the phone number and one-time code never travel in a URL.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  createHarness,
  flush,
  fixtureIds,
  keydown,
  type,
} from './deletion-page.mjs';

const SITE_DIR = new URL('../../site/', import.meta.url);
const HTML = readFileSync(new URL('delete-account.html', SITE_DIR), 'utf8');
const SCRIPT = readFileSync(new URL('delete-account.js', SITE_DIR), 'utf8');

const BASE = 'https://api.nexachat.io.vn';
const PHONE = '0912345678';
const CODE = '123456';

function htmlIds() {
  return new Set([...HTML.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]));
}

function checkConfirm(page) {
  page.els.confirm.checked = true;
  page.els.confirm.dispatchEvent({ type: 'change' });
}

async function requestCode(page) {
  type(page.els.phone, PHONE);
  page.els.sendButton.click();
  await flush();
}

async function confirmDeletion(page) {
  type(page.els.code, CODE);
  checkConfirm(page);
  page.els.deleteButton.click();
  await flush();
}

function assertFocused(page, element) {
  // Compare ids, not DOM objects: a failed strictEquality diff over cyclic DOM
  // nodes can hang Node's assertion reporter instead of printing a failure.
  const active = page.document.activeElement;
  assert.equal(active ? active.id : null, element.id, `expected focus on #${element.id}`);
}

test('default page keeps web deletion disabled and wires no requests', async () => {
  const page = createHarness({ apiBase: '' });

  assert.equal(page.els.unavailable.hidden, false);
  assert.equal(page.els.formWrap.hidden, true);

  page.els.sendButton.click();
  await flush();
  assert.equal(page.calls.length, 0);
});

test('rejects API bases that are not http(s) origins', async () => {
  for (const value of ['ftp://api.example.com', 'api.example.com', 'javascript:alert(1)', '   ']) {
    const page = createHarness({ apiBase: value });
    assert.equal(page.els.unavailable.hidden, false, `expected unavailable for ${value}`);
    assert.equal(page.els.formWrap.hidden, true, `expected hidden form for ${value}`);
  }
});

test('accepts a configured HTTPS API base and trims whitespace and trailing slashes', async () => {
  const page = createHarness({ apiBase: `  ${BASE}/  ` });

  assert.equal(page.els.unavailable.hidden, true);
  assert.equal(page.els.formWrap.hidden, false);

  await requestCode(page);
  assert.equal(page.calls.length, 1);
  assert.equal(page.calls[0].url, `${BASE}/api/v1/account-deletion/request`);
});

test('drops query strings and fragments from the configured API base', async () => {
  const page = createHarness({ apiBase: 'https://api.example.com/?token=s3cr3t#fragment' });

  await requestCode(page);
  assert.equal(page.calls.length, 1);
  assert.equal(page.calls[0].url, 'https://api.example.com/api/v1/account-deletion/request');
  assert.equal(page.calls[0].url.includes('s3cr3t'), false);
  assert.equal(page.calls[0].url.includes('?'), false);
  assert.equal(page.calls[0].url.includes('#'), false);
});

test('validation: an empty or whitespace-only phone shows its error, focuses it, and sends nothing', async () => {
  const page = createHarness({ apiBase: BASE });

  type(page.els.phone, '   ');
  page.els.sendButton.click();
  await flush();

  assert.equal(page.calls.length, 0);
  assert.equal(page.els.phone.getAttribute('aria-invalid'), 'true');
  assert.equal(page.els.phoneError.hidden, false);
  assert.equal(page.els.sendError.hidden, true);
  assertFocused(page, page.els.phone);

  type(page.els.phone, PHONE);
  assert.equal(page.els.phone.getAttribute('aria-invalid'), null);
  assert.equal(page.els.phoneError.hidden, true);
});

test('request step posts JSON, reveals the OTP fields, and focuses the code field after the busy period', async () => {
  const page = createHarness({ apiBase: BASE });

  type(page.els.phone, PHONE);
  page.els.sendButton.click();
  assert.equal(page.els.sendButton.disabled, true);

  await flush();

  assert.equal(page.calls.length, 1);
  assert.deepEqual(page.calls[0].init, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: PHONE }),
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    mode: 'cors',
  });
  assert.equal(page.els.sendButton.disabled, false);
  assert.equal(page.els.feedback.hidden, false);
  assert.equal(page.els.postSend.hidden, false);
  assert.equal(page.els.deleteButton.disabled, true);
  assertFocused(page, page.els.code);
});

test('OTP code input keeps digits only and the delete button needs code plus checkbox', async () => {
  const page = createHarness({ apiBase: BASE });
  await requestCode(page);

  type(page.els.code, '12a34b56789');
  assert.equal(page.els.code.value, '123456');
  assert.equal(page.els.deleteButton.disabled, true);

  checkConfirm(page);
  assert.equal(page.els.deleteButton.disabled, false);

  type(page.els.code, '12345');
  assert.equal(page.els.deleteButton.disabled, true);

  type(page.els.code, CODE);
  assert.equal(page.els.deleteButton.disabled, false);

  page.els.confirm.checked = false;
  page.els.confirm.dispatchEvent({ type: 'change' });
  assert.equal(page.els.deleteButton.disabled, true);
});

test('confirm step sends phone and code in the JSON body and shows the success state', async () => {
  const page = createHarness({
    apiBase: BASE,
    respond: (index) => (index === 0 ? 202 : 204),
  });

  await requestCode(page);
  await confirmDeletion(page);

  assert.equal(page.calls.length, 2);
  assert.equal(page.calls[1].url, `${BASE}/api/v1/account-deletion/confirm`);
  assert.equal(page.calls[1].init.method, 'POST');
  assert.equal(page.calls[1].init.body, JSON.stringify({ phone: PHONE, code: CODE }));

  assert.equal(page.els.form.hidden, true);
  assert.equal(page.els.success.hidden, false);
  assertFocused(page, page.els.success);
  assert.equal(page.els.phone.disabled, false);
  assert.equal(page.els.code.disabled, false);
  assert.equal(page.els.sendButton.disabled, false);
  assert.equal(page.els.deleteButton.disabled, false);
});

test('request step shows and focuses the rate-limit message on 429', async () => {
  const page = createHarness({ apiBase: BASE, respond: () => 429 });
  await requestCode(page);

  assert.equal(page.els.rateError.hidden, false);
  assertFocused(page, page.els.rateError);
  assert.equal(page.els.sendError.hidden, true);
  assert.equal(page.els.formError.hidden, true);
  assert.equal(page.els.feedback.hidden, true);
  assert.equal(page.els.postSend.hidden, true);
  assert.equal(page.els.sendButton.disabled, false);
  assert.equal(page.els.deleteButton.disabled, true);
});

test('request step shows the send-specific error on 5xx and network failure', async () => {
  for (const respond of [() => 500, () => new Error('network down')]) {
    const page = createHarness({ apiBase: BASE, respond });
    await requestCode(page);

    assert.equal(page.calls.length, 1);
    assert.equal(page.els.sendError.hidden, false);
    assertFocused(page, page.els.sendError);
    assert.equal(page.els.rateError.hidden, true);
    assert.equal(page.els.formError.hidden, true);
    assert.equal(page.els.postSend.hidden, true);
  }
});

test('confirm step shows the code-field error on 400 and focuses the field', async () => {
  const page = createHarness({
    apiBase: BASE,
    respond: (index) => (index === 0 ? 202 : 400),
  });

  await requestCode(page);
  await confirmDeletion(page);

  assert.equal(page.els.codeError.hidden, false);
  assert.equal(page.els.code.getAttribute('aria-invalid'), 'true');
  assertFocused(page, page.els.code);
  assert.equal(page.els.rateError.hidden, true);
  assert.equal(page.els.formError.hidden, true);
  assert.equal(page.els.success.hidden, true);
  assert.equal(page.els.form.hidden, false);
});

test('confirm step shows and focuses the rate-limit message on 429', async () => {
  const page = createHarness({
    apiBase: BASE,
    respond: (index) => (index === 0 ? 202 : 429),
  });

  await requestCode(page);
  await confirmDeletion(page);

  assert.equal(page.els.rateError.hidden, false);
  assertFocused(page, page.els.rateError);
  assert.equal(page.els.codeError.hidden, true);
  assert.equal(page.els.formError.hidden, true);
  assert.equal(page.els.success.hidden, true);
});

test('confirm step shows the global error on other statuses and network failure', async () => {
  for (const status of [500, new Error('network down')]) {
    const page = createHarness({
      apiBase: BASE,
      respond: (index) => (index === 0 ? 202 : status),
    });

    await requestCode(page);
    await confirmDeletion(page);

    assert.equal(page.els.formError.hidden, false);
    assertFocused(page, page.els.formError);
    assert.equal(page.els.rateError.hidden, true);
    assert.equal(page.els.success.hidden, true);
    assert.equal(page.els.form.hidden, false);
  }
});

test('changing the phone after a send resets the OTP state and requires a new code', async () => {
  const page = createHarness({ apiBase: BASE });

  await requestCode(page);
  type(page.els.code, CODE);
  checkConfirm(page);
  assert.equal(page.els.deleteButton.disabled, false);

  type(page.els.phone, '0999999999');

  assert.equal(page.els.feedback.hidden, true);
  assert.equal(page.els.postSend.hidden, true);
  assert.equal(page.els.code.value, '');
  assert.equal(page.els.deleteButton.disabled, true);
  assert.equal(page.els.codeError.hidden, true);

  type(page.els.code, CODE);
  assert.equal(page.els.deleteButton.disabled, true);
  type(page.els.code, '');

  page.els.sendButton.click();
  await flush();
  assert.equal(page.calls.length, 2);
  assert.equal(page.els.postSend.hidden, false);
  assert.equal(page.els.deleteButton.disabled, true);

  type(page.els.code, CODE);
  assert.equal(page.els.deleteButton.disabled, false);
});

test('Enter in the phone field sends one request and Enter in the code field confirms only when enabled', async () => {
  const page = createHarness({
    apiBase: BASE,
    respond: (index) => (index === 0 ? 202 : 204),
  });

  type(page.els.phone, PHONE);
  const phoneEnter = keydown('Enter');
  page.els.phone.dispatchEvent(phoneEnter);
  await flush();
  assert.equal(page.calls.length, 1);
  assert.equal(phoneEnter.defaultPrevented, true);

  const disabledCodeEnter = keydown('Enter');
  page.els.code.dispatchEvent(disabledCodeEnter);
  await flush();
  assert.equal(page.calls.length, 1);

  type(page.els.code, CODE);
  checkConfirm(page);
  const codeEnter = keydown('Enter');
  page.els.code.dispatchEvent(codeEnter);
  await flush();
  assert.equal(page.calls.length, 2);
  assert.equal(codeEnter.defaultPrevented, true);
});

test('a second click while the request is in flight does not post again', async () => {
  let resolveFetch;
  const page = createHarness({
    apiBase: BASE,
    respond: () => new Promise((resolve) => {
      resolveFetch = resolve;
    }),
  });

  type(page.els.phone, PHONE);
  page.els.sendButton.click();
  assert.equal(page.calls.length, 1);
  assert.equal(page.els.sendButton.disabled, true);

  page.els.sendButton.click();
  page.els.sendButton.click();
  assert.equal(page.calls.length, 1);

  resolveFetch({ status: 202 });
  await flush();
  assert.equal(page.els.sendButton.disabled, false);
  assert.equal(page.els.postSend.hidden, false);
});

test('phone and one-time code never appear in request URLs', async () => {
  const page = createHarness({
    apiBase: `${BASE}/base/`,
    respond: (index) => (index === 0 ? 202 : 204),
  });

  await requestCode(page);
  await confirmDeletion(page);

  assert.equal(page.calls.length, 2);
  for (const { url } of page.calls) {
    assert.match(
      url,
      /^https:\/\/api\.nexachat\.io\.vn\/base\/api\/v1\/account-deletion\/(request|confirm)$/,
    );
    assert.equal(url.includes(PHONE), false);
    assert.equal(url.includes(CODE), false);
    assert.equal(url.includes('?'), false);
    assert.equal(url.includes('#'), false);
  }
});

test('native form submission is prevented so credentials are not serialised into the page URL', () => {
  const page = createHarness({ apiBase: BASE });
  const submit = { type: 'submit' };
  submit.preventDefault = () => {
    submit.defaultPrevented = true;
  };

  page.els.form.dispatchEvent(submit);
  assert.equal(submit.defaultPrevented, true);
});

test('every element id used by the script exists on the page and in the fixture', () => {
  const idsInPage = htmlIds();
  const harness = createHarness({ apiBase: BASE });
  const idsInFixture = new Set(fixtureIds(harness));

  const scriptIds = new Set(
    [...SCRIPT.matchAll(/getElementById\('([^']+)'\)/g)].map((match) => match[1]),
  );
  assert.ok(scriptIds.size > 0);

  for (const id of scriptIds) {
    assert.ok(idsInPage.has(id), `#${id} is missing from site/delete-account.html`);
    assert.ok(idsInFixture.has(id), `#${id} is missing from the test fixture`);
  }
});

test('the fixture mirrors the page hidden/disabled flags and aria-describedby wiring', () => {
  const page = createHarness({ apiBase: '' });
  const tagsById = new Map();
  for (const match of HTML.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
    tagsById.set(match[1], match[0]);
  }

  for (const id of fixtureIds(page)) {
    const tag = tagsById.get(id);
    assert.ok(tag, `#${id} is missing from site/delete-account.html`);
    const element = page.document.getElementById(id);

    for (const attribute of ['hidden', 'disabled']) {
      const inPage = new RegExp(`\\s${attribute}(?=[\\s>])`).test(tag);
      assert.equal(
        element.hasAttribute(attribute),
        inPage,
        `#${id} ${attribute} does not match the page`,
      );
    }

    const describedBy = /aria-describedby="([^"]+)"/.exec(tag);
    assert.equal(
      element.getAttribute('aria-describedby'),
      describedBy ? describedBy[1] : null,
      `#${id} aria-describedby does not match the page`,
    );
  }
});

test('every aria-describedby reference on the page resolves to an element', () => {
  const idsInPage = htmlIds();
  for (const match of HTML.matchAll(/aria-describedby="([^"]+)"/g)) {
    for (const reference of match[1].split(/\s+/)) {
      assert.ok(idsInPage.has(reference), `aria-describedby target #${reference} is missing`);
    }
  }
});

test('the step, rate-limit and global error messages exist in both locales with alert semantics', () => {
  for (const id of [
    'web-deletion-send-error',
    'web-deletion-rate-error',
    'web-deletion-form-error',
  ]) {
    const start = HTML.indexOf(`id="${id}"`);
    assert.notEqual(start, -1, `missing #${id}`);
    const openTag = HTML.slice(start, HTML.indexOf('>', start) + 1);
    const block = HTML.slice(start, HTML.indexOf('</p>', start));

    assert.match(openTag, /role="alert"/);
    assert.match(openTag, /\bhidden\b/);
    assert.match(block, /lang="vi"/);
    assert.match(block, /lang="en"/);
  }
});

test('the checked-in page targets the production API over HTTPS without a trailing slash', () => {
  assert.match(HTML, /<meta name="nexachat-api-base" content="https:\/\/api\.nexachat\.io\.vn">/);
});
