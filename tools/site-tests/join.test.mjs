/**
 * Behaviour tests for the join invite page (`site/join/join.js`).
 *
 * check.py asserts the token contract through a Python reimplementation, which
 * can silently drift from the shipped script. This suite executes the real
 * join.js inside a minimal DOM stub and asserts the user-visible outcome:
 * valid tokens deep-link into the app, anything else shows the invalid state.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const SCRIPT = readFileSync(new URL('../../site/join/join.js', import.meta.url), 'utf8');
const VALID_TOKEN = 'A'.repeat(43);

function stubEl() {
  return {
    attrs: new Map(),
    setAttribute(k, v) { this.attrs.set(k, v); },
    removeAttribute(k) { this.attrs.delete(k); },
    hasAttribute(k) { return this.attrs.has(k); },
    getAttribute(k) { return this.attrs.get(k) ?? null; },
  };
}

function runJoinPage({ pathname, search = '', hash = '', readyState = 'complete' }) {
  const els = {
    'invite-valid': stubEl(),
    'invite-invalid': stubEl(),
    'btn-open-app': stubEl(),
  };
  els['invite-valid'].setAttribute('hidden', '');
  els['invite-invalid'].setAttribute('hidden', '');
  const listeners = [];
  const documentStub = {
    readyState,
    getElementById: (id) => els[id] ?? null,
    addEventListener: (type, fn) => listeners.push([type, fn]),
  };
  const windowStub = { location: { pathname, search, hash } };
  const fn = new Function('document', 'window', SCRIPT);
  fn(documentStub, windowStub);
  for (const [, listener] of listeners) listener();
  return els;
}

test('valid 43-char token reveals the valid state and deep-links the token', () => {
  const els = runJoinPage({ pathname: `/join/${VALID_TOKEN}` });
  assert.equal(els['invite-valid'].hasAttribute('hidden'), false);
  assert.equal(els['invite-invalid'].hasAttribute('hidden'), true);
  assert.equal(els['btn-open-app'].getAttribute('href'), `nexachat://join/${VALID_TOKEN}`);
  assert.equal(els['btn-open-app'].getAttribute('rel'), 'noreferrer');
});

test('invalid token shapes reveal only the invalid state', () => {
  for (const pathname of [
    '/join/',                    // missing token
    '/join/short',               // too short
    `/join/${VALID_TOKEN}x`,     // too long
    `/join/${'!'.repeat(43)}`,   // bad charset
    '/other/abc',                // wrong prefix
  ]) {
    const els = runJoinPage({ pathname });
    assert.equal(els['invite-valid'].hasAttribute('hidden'), true, pathname);
    assert.equal(els['invite-invalid'].hasAttribute('hidden'), false, pathname);
    assert.notEqual(els['btn-open-app'].getAttribute('href'), `nexachat://join/${VALID_TOKEN}`);
  }
});

test('query string or fragment suppresses the token (deep-link hygiene)', () => {
  for (const extra of [{ search: '?x=1' }, { hash: '#frag' }]) {
    const els = runJoinPage({ pathname: `/join/${VALID_TOKEN}`, ...extra });
    assert.equal(els['invite-valid'].hasAttribute('hidden'), true);
    assert.equal(els['invite-invalid'].hasAttribute('hidden'), false);
  }
});

test('loading documents defer init until DOMContentLoaded', () => {
  const els = runJoinPage({ pathname: `/join/${VALID_TOKEN}`, readyState: 'loading' });
  assert.equal(els['invite-valid'].hasAttribute('hidden'), false);
});
