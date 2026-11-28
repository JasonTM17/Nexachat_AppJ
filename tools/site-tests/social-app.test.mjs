import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { avatarPaletteIndex, initSocialApp } from '../../site/social/social-app.js';

const HTML = readFileSync(new URL('../../site/social/index.html', import.meta.url), 'utf8');
const CSS = readFileSync(new URL('../../site/social/social.css', import.meta.url), 'utf8');

const VOID_TAGS = new Set(['meta', 'link', 'input', 'img', 'br', 'hr', 'path', 'circle']);

function matchesSimple(el, sel) {
  let rest = sel.trim();
  if (!rest) return false;
  const tagMatch = /^[a-zA-Z][\w-]*/.exec(rest);
  if (tagMatch) {
    if (el.tagName !== tagMatch[0].toUpperCase()) return false;
    rest = rest.slice(tagMatch[0].length);
  }
  while (rest.length > 0) {
    if (rest.startsWith('#')) {
      const m = /^#([\w-]+)/.exec(rest);
      if (!m || el.id !== m[1]) return false;
      rest = rest.slice(m[0].length);
    } else if (rest.startsWith('.')) {
      const m = /^\.([\w-]+)/.exec(rest);
      if (!m || !el.classList.contains(m[1])) return false;
      rest = rest.slice(m[0].length);
    } else if (rest.startsWith('[')) {
      const m = /^\[([\w-]+)(?:=(?:"([^"]*)"|'([^']*)'))?\]/.exec(rest);
      if (!m) throw new Error(`Unsupported selector part: ${sel}`);
      const attr = m[1];
      const hasVal = m[2] !== undefined || m[3] !== undefined;
      const val = m[2] !== undefined ? m[2] : m[3];
      if (!el.hasAttribute(attr)) return false;
      if (hasVal && el.getAttribute(attr) !== val) return false;
      rest = rest.slice(m[0].length);
    } else {
      throw new Error(`Unsupported selector: ${sel}`);
    }
  }
  return true;
}

function matchesSelector(el, selector) {
  return selector.split(',').some((branch) => {
    const parts = branch.trim().split(/\s+/).filter(Boolean);
    if (!parts.length || !matchesSimple(el, parts[parts.length - 1])) {
      return false;
    }
    let cur = el.parentElement;
    for (let i = parts.length - 2; i >= 0; i -= 1) {
      while (cur && !matchesSimple(cur, parts[i])) {
        cur = cur.parentElement;
      }
      if (!cur) return false;
      cur = cur.parentElement;
    }
    return true;
  });
}

class DomNode {
  constructor(doc, tagName) {
    this.ownerDocument = doc;
    this.tagName = tagName.toUpperCase();
    this.parentElement = null;
    this._attrs = new Map();
    this._children = [];
    this._listeners = new Map();
    this._text = '';
    this.value = '';
    this.files = [];
    this.open = false;
    this.classList = {
      add: (...names) => {
        const set = new Set(this.className.split(/\s+/).filter(Boolean));
        for (const n of names) if (n) set.add(n);
        this.className = [...set].join(' ');
      },
      remove: (...names) => {
        const set = new Set(this.className.split(/\s+/).filter(Boolean));
        for (const n of names) set.delete(n);
        this.className = [...set].join(' ');
      },
      toggle: (name, force) => {
        const has = this.classList.contains(name);
        const want = force === undefined ? !has : Boolean(force);
        if (want) this.classList.add(name);
        else this.classList.remove(name);
        return want;
      },
      contains: (name) => this.className.split(/\s+/).filter(Boolean).includes(name),
    };
    this.dataset = new Proxy({}, {
      get: (_, prop) => {
        const attr = 'data-' + String(prop).replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
        return this.getAttribute(attr) ?? undefined;
      },
      set: (_, prop, value) => {
        const attr = 'data-' + String(prop).replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
        this.setAttribute(attr, String(value));
        return true;
      },
    });
  }

  get id() { return this.getAttribute('id') || ''; }
  set id(v) { this.setAttribute('id', v); }
  get className() { return this.getAttribute('class') || ''; }
  set className(v) { this.setAttribute('class', v); }
  get htmlFor() { return this.getAttribute('for') || ''; }
  set htmlFor(v) { this.setAttribute('for', v); }
  get hidden() { return this.hasAttribute('hidden'); }
  set hidden(v) { v ? this.setAttribute('hidden', '') : this.removeAttribute('hidden'); }
  get disabled() { return this.hasAttribute('disabled'); }
  set disabled(v) { v ? this.setAttribute('disabled', '') : this.removeAttribute('disabled'); }
  get children() { return this._children; }
  get firstElementChild() { return this._children[0] || null; }
  get nextElementSibling() {
    if (!this.parentElement) return null;
    const idx = this.parentElement._children.indexOf(this);
    return idx >= 0 ? (this.parentElement._children[idx + 1] || null) : null;
  }
  get isConnected() {
    let cur = this;
    while (cur) {
      if (cur === this.ownerDocument.documentElement) return true;
      cur = cur.parentElement;
    }
    return false;
  }
  get textContent() {
    if (this._children.length === 0) return this._text;
    return this._children.map((c) => c.textContent).join('');
  }
  set textContent(v) {
    this._children = [];
    this._text = v == null ? '' : String(v);
  }

  getAttribute(name) {
    const key = String(name).toLowerCase();
    return this._attrs.has(key) ? this._attrs.get(key) : null;
  }
  setAttribute(name, val) {
    this._attrs.set(String(name).toLowerCase(), String(val));
  }
  removeAttribute(name) {
    this._attrs.delete(String(name).toLowerCase());
  }
  hasAttribute(name) {
    return this._attrs.has(String(name).toLowerCase());
  }

  appendChild(child) {
    if (child.parentElement) child.remove();
    child.parentElement = this;
    this._children.push(child);
    return child;
  }
  append(...nodes) {
    for (const n of nodes) {
      if (typeof n === 'string') {
        this._text += n;
      } else {
        this.appendChild(n);
      }
    }
  }
  insertBefore(newNode, refNode) {
    if (!refNode) return this.appendChild(newNode);
    if (newNode.parentElement) newNode.remove();
    const idx = this._children.indexOf(refNode);
    if (idx === -1) return this.appendChild(newNode);
    newNode.parentElement = this;
    this._children.splice(idx, 0, newNode);
    return newNode;
  }
  replaceChildren(...nodes) {
    for (const child of this._children) child.parentElement = null;
    this._children = [];
    this._text = '';
    for (const n of nodes) this.appendChild(n);
  }
  remove() {
    if (!this.parentElement) return;
    const idx = this.parentElement._children.indexOf(this);
    if (idx >= 0) this.parentElement._children.splice(idx, 1);
    this.parentElement = null;
  }
  contains(node) {
    let cur = node;
    while (cur) {
      if (cur === this) return true;
      cur = cur.parentElement;
    }
    return false;
  }
  closest(selector) {
    let cur = this;
    while (cur) {
      if (matchesSelector(cur, selector)) return cur;
      cur = cur.parentElement;
    }
    return null;
  }
  querySelectorAll(selector) {
    const out = [];
    const visit = (node) => {
      for (const child of node._children) {
        if (matchesSelector(child, selector)) out.push(child);
        visit(child);
      }
    };
    visit(this);
    return out;
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  addEventListener(type, fn) {
    const key = String(type);
    if (!this._listeners.has(key)) this._listeners.set(key, new Set());
    this._listeners.get(key).add(fn);
  }
  dispatchEvent(event) {
    const ev = { ...event, target: this, currentTarget: this };
    ev.type = String(event.type);
    if (typeof ev.preventDefault !== 'function') {
      ev.defaultPrevented = false;
      ev.preventDefault = () => { ev.defaultPrevented = true; };
    }
    for (const fn of [...(this._listeners.get(ev.type) || [])]) {
      fn.call(this, ev);
    }
    return !ev.defaultPrevented;
  }
  click() {
    if (this.disabled) return false;
    return this.dispatchEvent({ type: 'click' });
  }
  focus() {
    if (this.disabled) return false;
    this.ownerDocument.activeElement = this;
    return true;
  }
  showModal() {
    this.open = true;
    this.setAttribute('open', '');
  }
  close() {
    this.open = false;
    this.removeAttribute('open');
    this.dispatchEvent({ type: 'close' });
  }
}

class DomDoc {
  constructor() {
    this.hidden = false;
    this.documentElement = new DomNode(this, 'html');
    this.activeElement = null;
    this._listeners = new Map();
  }
  createElement(tag) {
    return new DomNode(this, tag);
  }
  getElementById(id) {
    const visit = (node) => {
      if (node.id === id) return node;
      for (const child of node._children) {
        const hit = visit(child);
        if (hit) return hit;
      }
      return null;
    };
    return visit(this.documentElement);
  }
  querySelectorAll(selector) {
    return this.documentElement.querySelectorAll(selector);
  }
  querySelector(selector) {
    return this.documentElement.querySelector(selector);
  }
  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, new Set());
    this._listeners.get(type).add(fn);
  }
}

function buildDocumentFromHtml(html) {
  const doc = new DomDoc();
  const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  const bodyHtml = bodyMatch ? bodyMatch[1] : html;
  const body = doc.createElement('body');
  doc.documentElement.appendChild(body);
  const stack = [body];
  const tokenRe = /<!--[\s\S]*?-->|<\/([a-zA-Z][\w-]*)>|<([a-zA-Z][\w-]*)([^>]*?)\s*(\/?)>|([^<]+)/g;
  let m;
  while ((m = tokenRe.exec(bodyHtml)) !== null) {
    const parent = stack[stack.length - 1];
    if (m[1]) {
      const closing = m[1].toUpperCase();
      while (stack.length > 1 && stack[stack.length - 1].tagName !== closing) {
        stack.pop();
      }
      if (stack.length > 1) stack.pop();
    } else if (m[2]) {
      const tag = m[2].toLowerCase();
      const node = doc.createElement(tag);
      const attrRe = /([a-zA-Z_:][\w:.-]*)(?:=(?:"([^"]*)"|'([^']*)'))?/g;
      let am;
      while ((am = attrRe.exec(m[3])) !== null) {
        const val = am[2] !== undefined ? am[2] : (am[3] !== undefined ? am[3] : '');
        node.setAttribute(am[1], val);
      }
      parent.appendChild(node);
      if (!m[4] && !VOID_TAGS.has(tag)) {
        stack.push(node);
      }
    } else if (m[5]) {
      const txt = m[5];
      if (txt.trim()) {
        parent._text += txt.replace(/\s+/g, ' ').trim();
      }
    }
  }
  return doc;
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 15));

function jsonRes(status, body) {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function createStorage() {
  const map = new Map();
  return {
    get length() { return map.size; },
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(String(k), String(v)),
    removeItem: (k) => map.delete(k),
  };
}

function makeHarness({ config = { webEnabled: true, videoUploadsEnabled: true, announcement: null }, darkSystem = false, hash = '', handler } = {}) {
  const doc = buildDocumentFromHtml(HTML);
  const storage = createStorage();
  const requests = [];
  const copied = [];
  const revokedUrls = [];
  let urlCounter = 0;

  const fetchImpl = async (url, init = {}) => {
    requests.push({ url, init });
    if (handler) {
      const res = await handler(url, init, requests);
      if (res) return res;
    }
    if (url === '/api/social/v1/config') {
      return jsonRes(200, config);
    }
    return jsonRes(404, { error: { code: 'NOT_FOUND' } });
  };

  const windowStub = {
    location: { origin: 'https://nexachat.io.vn', pathname: '/social/', hash },
    matchMedia: (q) => ({ matches: darkSystem && q.includes('dark') }),
    confirm: () => true,
    addEventListener: () => {},
  };

  const historyStub = {
    replaceState: (_s, _t, nextUrl) => {
      windowStub.location.hash = nextUrl.includes('#') ? nextUrl.slice(nextUrl.indexOf('#')) : '';
    },
  };

  const urlStub = {
    createObjectURL: () => `blob:test-${++urlCounter}`,
    revokeObjectURL: (u) => revokedUrls.push(u),
  };

  const navigatorStub = {
    clipboard: {
      writeText: async (txt) => { copied.push(txt); },
    },
  };

  const ready = initSocialApp({
    document: doc,
    window: windowStub,
    fetch: fetchImpl,
    localStorage: storage,
    navigator: navigatorStub,
    history: historyStub,
    URL: urlStub,
    crypto: globalThis.crypto,
    FormData: globalThis.FormData,
  });

  return { doc, storage, requests, copied, revokedUrls, windowStub, ready };
}

test('design tokens in social.css match Tonal Plum Light/Dark contract and hidden rule', () => {
  assert.match(CSS, /--canvas:\s*#fff7fc;/);
  assert.match(CSS, /--surface-lowest:\s*#ffffff;/);
  assert.match(CSS, /--surface-low:\s*#f9f1f6;/);
  assert.match(CSS, /--surface-container:\s*#f3ecf0;/);
  assert.match(CSS, /--surface-high:\s*#ede6eb;/);
  assert.match(CSS, /--surface-highest:\s*#e8e0e5;/);
  assert.match(CSS, /--primary:\s*#733f68;/);
  assert.match(CSS, /--primary-tonal:\s*#fcd7f4;/);
  assert.match(CSS, /--accent:\s*#246b57;/);
  assert.match(CSS, /--canvas:\s*#151316;/);
  assert.match(CSS, /--surface-lowest:\s*#100d11;/);
  assert.match(CSS, /--surface-low:\s*#1d1b1e;/);
  assert.match(CSS, /--surface-container:\s*#211f22;/);
  assert.match(CSS, /--surface-high:\s*#2c292d;/);
  assert.match(CSS, /--surface-highest:\s*#383337;/);
  assert.match(CSS, /--primary:\s*#f4b2e2;/);
  assert.match(CSS, /--primary-tonal:\s*#67355d;/);
  assert.match(CSS, /--accent:\s*#8cd6b7;/);
  assert.match(CSS, /\[hidden\]\s*\{\s*display:\s*none !important;\s*\}/);
  assert.match(HTML, /class="brand__badge">SOCIAL<\/span>/);
  assert.match(HTML, /id="theme-toggle"/);
});

test('avatarPaletteIndex matches Android AvatarPalette determinism across case and whitespace', () => {
  assert.equal(avatarPaletteIndex(''), 0);
  assert.equal(avatarPaletteIndex('  An Nguyen '), avatarPaletteIndex('an nguyen'));
  const idx = avatarPaletteIndex('Binh Tran');
  assert.ok(idx >= 0 && idx < 8);
});

test('theme toggle persists only on explicit user toggle and respects system default', async () => {
  const h = makeHarness({ darkSystem: true });
  await h.ready;
  assert.equal(h.storage.length, 0, 'must not write localStorage before user toggles');
  assert.equal(h.doc.documentElement.getAttribute('data-theme'), null);
  assert.equal(h.doc.getElementById('theme-toggle').getAttribute('aria-pressed'), 'true');

  h.doc.getElementById('theme-toggle').click();
  assert.equal(h.storage.getItem('social.theme'), 'light');
  assert.equal(h.doc.documentElement.getAttribute('data-theme'), 'light');
  assert.equal(h.doc.getElementById('theme-toggle').getAttribute('aria-pressed'), 'false');

  h.doc.getElementById('theme-toggle').click();
  assert.equal(h.storage.getItem('social.theme'), 'dark');
  assert.equal(h.doc.documentElement.getAttribute('data-theme'), 'dark');
  assert.equal(h.doc.getElementById('theme-toggle').getAttribute('aria-pressed'), 'true');
});

test('lead copy is updated and does not mention existing images in EN or VI', async () => {
  const h = makeHarness({ hash: '#lang-en' });
  await h.ready;
  assert.equal(h.doc.getElementById('social-lead').textContent.includes('existing images'), false);
  h.doc.getElementById('lang-vi-button').click();
  assert.equal(h.doc.getElementById('social-lead').textContent.includes('ảnh đã có'), false);
});

test('signIn handles SOCIAL_FEATURE_DISABLED by switching to unavailable state', async () => {
  let webEnabled = true;
  const h = makeHarness({
    handler: async (url) => {
      if (url === '/api/social/v1/config') {
        return jsonRes(200, { webEnabled, videoUploadsEnabled: false, announcement: null });
      }
      if (url === '/api/social/v1/session/login') {
        webEnabled = false;
        return jsonRes(403, { error: { code: 'SOCIAL_FEATURE_DISABLED' } });
      }
      return null;
    },
  });
  await h.ready;
  h.doc.getElementById('login-identifier').value = 'one@example.com';
  h.doc.getElementById('login-password').value = 'pass-one';
  h.doc.getElementById('login-form').dispatchEvent({ type: 'submit' });
  await flush();

  assert.equal(h.doc.getElementById('unavailable-panel').hidden, false);
  assert.equal(h.doc.getElementById('signed-out-panel').hidden, true);
  assert.equal(h.doc.getElementById('unavailable-body').dataset.statusKey, 'socialDisabled');
});

test('multi-emoji picker, inline post/comment edit, author profile/filter, search, copy, report details, comments retry, and targeted DOM preservation', async () => {
  const authorId = '11111111-1111-1111-1111-111111111111';
  const otherId = '22222222-2222-2222-2222-222222222222';
  const postId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const otherPostId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const commentId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  const videoId = '66666666-6666-6666-6666-666666666666';

  let post1 = {
    id: postId,
    author: { id: authorId, displayName: 'An Nguyen', avatarUrl: `/api/v1/files/avatars/${authorId}.png` },
    body: 'Hello world',
    image: null,
    video: { url: `/api/social/v1/videos/${videoId}`, mimeType: 'video/mp4' },
    createdAt: '2026-11-28T10:00:00Z',
    editedAt: null,
    commentCount: 1,
    reactions: [],
    mine: true,
  };
  const post2 = {
    id: otherPostId,
    author: { id: otherId, displayName: 'Binh Tran', avatarUrl: null },
    body: 'Bánh mì Sài Gòn',
    image: null,
    createdAt: '2026-11-28T09:00:00Z',
    editedAt: null,
    commentCount: 0,
    reactions: [],
    mine: false,
  };
  let comment1 = {
    id: commentId,
    postId,
    author: { id: authorId, displayName: 'An Nguyen', avatarUrl: `/api/v1/files/avatars/${authorId}.png` },
    body: 'First comment',
    createdAt: '2026-11-28T10:01:00Z',
    editedAt: null,
    mine: true,
  };

  let failCommentsOnce = true;
  let failPostOnce = true;

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url, init) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, {
          user: { id: authorId, displayName: 'An Nguyen', avatarUrl: `/api/v1/files/avatars/${authorId}.png` },
          accessToken: 'acc-1',
          accessTokenExpiresIn: 3600,
          refreshToken: 'ref-1',
        });
      }
      if (url === `/api/v1/files/avatars/${authorId}.png`) {
        return new Response(new Blob(['png'], { type: 'image/png' }), { status: 200 });
      }
      if (url === `/api/social/v1/videos/${videoId}`) {
        return new Response(new Blob(['mp4'], { type: 'video/mp4' }), { status: 200 });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        const parsed = new URL(`https://x${url}`);
        const q = (parsed.searchParams.get('q') || '').toLowerCase();
        const aid = parsed.searchParams.get('authorId');
        let items = [post1, post2];
        if (aid) items = items.filter((p) => p.author.id === aid);
        if (q) items = items.filter((p) => p.body.toLowerCase().includes(q));
        return jsonRes(200, { items, nextCursor: null });
      }
      if (url === `/api/social/v1/posts/${postId}` && (init.method || 'GET') === 'GET') {
        return jsonRes(200, post1);
      }
      if (url === `/api/social/v1/posts/${postId}` && init.method === 'PATCH') {
        const body = JSON.parse(init.body);
        post1 = { ...post1, body: body.body, editedAt: '2026-11-28T10:05:00Z' };
        return jsonRes(200, post1);
      }
      if (url === `/api/social/v1/posts/${postId}/reactions/${encodeURIComponent('🔥')}` && init.method === 'PUT') {
        post1 = { ...post1, reactions: [{ emoji: '🔥', count: 1, mine: true }] };
        return jsonRes(200, { reactions: post1.reactions });
      }
      if (url === `/api/social/v1/posts/${postId}/reactions/${encodeURIComponent('🔥')}` && init.method === 'DELETE') {
        post1 = { ...post1, reactions: [] };
        return jsonRes(200, { reactions: [] });
      }
      if (url.startsWith(`/api/social/v1/posts/${postId}/comments`)) {
        if (failCommentsOnce) {
          failCommentsOnce = false;
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        return jsonRes(200, { items: [comment1], nextCursor: null });
      }
      if (url === `/api/social/v1/comments/${commentId}` && init.method === 'PATCH') {
        const body = JSON.parse(init.body);
        comment1 = { ...comment1, body: body.body, editedAt: '2026-11-28T10:06:00Z' };
        return jsonRes(200, comment1);
      }
      if (url === `/api/social/v1/authors/${otherId}`) {
        return jsonRes(200, { userId: otherId, displayName: 'Binh Tran', avatarUrl: null, postCount: 1 });
      }
      if (url === '/api/social/v1/reports' && init.method === 'POST') {
        return jsonRes(201, { id: 'rep-1' });
      }
      if (url === '/api/social/v1/images' && init.method === 'POST') {
        return jsonRes(201, { imageId: '55555555-5555-5555-5555-555555555555' });
      }
      if (url === '/api/social/v1/posts' && init.method === 'POST') {
        if (failPostOnce) {
          failPostOnce = false;
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        return jsonRes(201, {
          id: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
          author: post1.author,
          body: 'With photo',
          image: null,
          createdAt: '2026-11-28T10:10:00Z',
          editedAt: null,
          commentCount: 0,
          reactions: [],
          mine: true,
        });
      }
      return null;
    },
  });

  await h.ready;
  h.doc.getElementById('login-identifier').value = 'one@example.com';
  h.doc.getElementById('login-password').value = 'pass-one';
  h.doc.getElementById('login-form').dispatchEvent({ type: 'submit' });
  await flush();

  // Signed-in header session pill and feed rendered
  assert.equal(h.doc.getElementById('session-panel').hidden, false);
  assert.equal(h.doc.getElementById('session-user').textContent, 'An Nguyen');
  const card1 = h.doc.getElementById(`post-${postId}`);
  const card2 = h.doc.getElementById(`post-${otherPostId}`);
  assert.ok(card1 && card2);

  // Real avatarUrl loaded into img.avatar-img
  const avatarImg = card1.querySelector('.author-trigger img.avatar-img');
  assert.ok(avatarImg && avatarImg.src.startsWith('blob:'));

  // Load video so <video> node exists, then verify targeted DOM updates preserve it + comment draft/focus
  card1.querySelector('.video-load-button').click();
  await flush();
  const videoEl = card1.querySelector('video.post-video');
  assert.ok(videoEl);

  // Open comments -> first load fails -> inline .comments-error-banner + retry succeeds
  card1.querySelector('[data-action="comments"]').click();
  await flush();
  const errBanner = card1.querySelector('.comments-error-banner');
  assert.equal(errBanner.hidden, false);
  card1.querySelector('.comments-retry-button').click();
  await flush();
  assert.equal(errBanner.hidden, true);
  assert.ok(card1.querySelector('.comment-card'));

  // Type draft in comment box & focus it
  const commentInput = h.doc.getElementById(`comment-input-${postId}`);
  commentInput.value = 'Draft stays intact';
  commentInput.dispatchEvent({ type: 'input' });
  commentInput.focus();

  // Multi-emoji picker -> react with 🔥 -> preserves videoEl, commentInput value & focus!
  card1.querySelector('[data-action="reaction-picker"]').click();
  const fireOpt = card1.querySelector('.reaction-emoji-option[data-emoji="🔥"]');
  assert.ok(fireOpt);
  fireOpt.click();
  await flush();
  assert.equal(card1.querySelector('video.post-video'), videoEl, 'video element must not be recreated on react');
  assert.equal(commentInput.value, 'Draft stays intact');
  assert.equal(h.doc.activeElement, commentInput);
  const firePill = card1.querySelector('.reaction-pill[data-emoji="🔥"]');
  assert.ok(firePill && firePill.classList.contains('active'));
  firePill.click(); // Toggle off via pill
  await flush();
  assert.equal(card1.querySelector('.reaction-pill[data-emoji="🔥"]'), null);

  // Inline post edit -> shows (edited) badge & updates text while preserving videoEl
  card1.querySelector('[data-action="edit"]').click();
  const postEditTa = card1.querySelector('.post-body-wrap textarea');
  postEditTa.value = 'Edited post text';
  postEditTa.dispatchEvent({ type: 'input' });
  card1.querySelector('.post-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(card1.querySelector('.post-body').textContent, 'Edited post text');
  assert.equal(card1.querySelector('.post-header .edited-badge').hidden, false);
  assert.equal(card1.querySelector('video.post-video'), videoEl);

  // Inline comment edit -> shows (edited) badge
  const cCard = card1.querySelector('.comment-card');
  cCard.querySelector('[data-action="edit-comment"]').click();
  const cEditTa = cCard.querySelector('.comment-body-wrap textarea');
  cEditTa.value = 'Edited comment text';
  cEditTa.dispatchEvent({ type: 'input' });
  cCard.querySelector('.comment-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(cCard.querySelector('.comment-body').textContent, 'Edited comment text');
  assert.equal(cCard.querySelector('.edited-badge').hidden, false);

  // Refresh feed -> preserves videoEl + commentInput draft & focus!
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.equal(card1.querySelector('video.post-video'), videoEl);
  assert.equal(commentInput.value, 'Draft stays intact');

  // Copy post text
  card1.querySelector('[data-action="copy"]').click();
  await flush();
  assert.deepEqual(h.copied, ['Edited post text']);

  // Report dialog sends optional details
  card2.querySelector('[data-action="report"]').click();
  h.doc.getElementById('report-details').value = 'Spam details here';
  h.doc.getElementById('report-submit').click();
  await flush();
  const reportCall = h.requests.find((r) => r.url === '/api/social/v1/reports');
  assert.deepEqual(JSON.parse(reportCall.init.body), {
    targetType: 'POST',
    targetId: otherPostId,
    reason: 'SPAM',
    details: 'Spam details here',
  });

  // Author Profile modal & filter by author
  card2.querySelector('.author-trigger').click();
  await flush();
  assert.equal(h.doc.getElementById('author-dialog').open, true);
  assert.equal(h.doc.getElementById('author-dialog-title').textContent, 'Binh Tran');
  assert.equal(h.doc.getElementById('author-dialog-count').textContent, '1 post');
  h.doc.getElementById('author-dialog-filter').click();
  await flush();
  assert.equal(h.doc.getElementById('author-filter-chip').hidden, false);
  assert.equal(h.doc.getElementById('feed-list').children.length, 1);

  // Clear author filter
  h.doc.getElementById('author-filter-clear').click();
  await flush();
  assert.equal(h.doc.getElementById('author-filter-chip').hidden, true);
  assert.equal(h.doc.getElementById('feed-list').children.length, 2);

  // Keyword search + empty search state + clear
  const searchInput = h.doc.getElementById('feed-search-input');
  searchInput.value = 'no-match-xyz';
  searchInput.dispatchEvent({ type: 'keydown', key: 'Enter' });
  await flush();
  assert.equal(h.doc.getElementById('feed-list').children.length, 0);
  assert.equal(h.doc.getElementById('feed-status').dataset.statusKey, 'noSearchResults');
  h.doc.getElementById('feed-search-clear').click();
  await flush();
  assert.equal(h.doc.getElementById('feed-list').children.length, 2);

  // Selected media cleared ONLY after post succeeds
  const photoFile = new File(['abc'], 'photo.png', { type: 'image/png' });
  h.doc.getElementById('post-media').files = [photoFile];
  h.doc.getElementById('post-media').dispatchEvent({ type: 'change' });
  h.doc.getElementById('post-body').value = 'With photo';
  h.doc.getElementById('post-submit').click();
  await flush();
  assert.equal(h.doc.getElementById('media-selected').hidden, false, 'media selection must remain after failed post');
  h.doc.getElementById('post-submit').click();
  await flush();
  assert.equal(h.doc.getElementById('media-selected').hidden, true, 'media selection clears after post succeeds');
});
