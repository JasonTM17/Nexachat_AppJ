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
  dispatchEvent(event) {
    const type = String(event.type);
    for (const fn of [...(this._listeners.get(type) || [])]) {
      fn.call(this, event);
    }
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

function makeHarness({ config = { webEnabled: true, videoUploadsEnabled: true, announcement: null }, darkSystem = false, hash = '', search = '', handler } = {}) {
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
    location: { origin: 'https://nexachat.io.vn', pathname: '/social/', hash, search },
    matchMedia: (q) => ({ matches: darkSystem && q.includes('dark') }),
    confirm: () => true,
    addEventListener: () => {},
  };

  const historyStub = {
    lastUrl: null,
    replaceState: (_s, _t, nextUrl) => {
      historyStub.lastUrl = nextUrl;
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

  return { doc, storage, requests, copied, revokedUrls, windowStub, historyStub, ready };
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

async function signInHarness(h, id = 'one@example.com', pass = 'pass-one') {
  await h.ready;
  h.doc.getElementById('login-identifier').value = id;
  h.doc.getElementById('login-password').value = pass;
  h.doc.getElementById('login-form').dispatchEvent({ type: 'submit' });
  await flush();
}

test('inline post & comment edits validate whitespace/maxLength, survive feed refresh without stale closure, localize aria-label, and restore focus', async () => {
  const authorId = '11111111-1111-1111-1111-111111111111';
  const postId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const commentId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  let postObj = {
    id: postId,
    author: { id: authorId, displayName: 'An Nguyen', avatarUrl: null },
    body: 'Initial post',
    image: null,
    createdAt: '2026-11-28T10:00:00Z',
    editedAt: null,
    commentCount: 1,
    reactions: [],
    mine: true,
  };
  let commentObj = {
    id: commentId,
    postId,
    author: { id: authorId, displayName: 'An Nguyen', avatarUrl: null },
    body: 'Initial comment',
    createdAt: '2026-11-28T10:01:00Z',
    editedAt: null,
    mine: true,
  };
  let failNextPatch = false;

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url, init) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: authorId, displayName: 'An Nguyen' }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        return jsonRes(200, { items: [{ ...postObj }], nextCursor: null });
      }
      if (url.startsWith(`/api/social/v1/posts/${postId}/comments`)) {
        return jsonRes(200, { items: [{ ...commentObj }], nextCursor: null });
      }
      if (url === `/api/social/v1/posts/${postId}` && init.method === 'PATCH') {
        if (failNextPatch) {
          failNextPatch = false;
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        const body = JSON.parse(init.body);
        postObj = { ...postObj, body: body.body, editedAt: '2026-11-28T10:05:00Z' };
        return jsonRes(200, postObj);
      }
      if (url === `/api/social/v1/comments/${commentId}` && init.method === 'PATCH') {
        const body = JSON.parse(init.body);
        commentObj = { ...commentObj, body: body.body, editedAt: '2026-11-28T10:06:00Z' };
        return jsonRes(200, commentObj);
      }
      return null;
    },
  });

  await signInHarness(h);
  const card = h.doc.getElementById(`post-${postId}`);

  // Whitespace-only post edit -> editEmpty, focuses textarea, no PATCH
  card.querySelector('[data-action="edit"]').click();
  const ta = card.querySelector('.post-body-wrap textarea');
  assert.equal(ta.getAttribute('aria-label'), 'Post body');
  ta.value = '   ';
  ta.dispatchEvent({ type: 'input' });
  card.querySelector('.post-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(card.querySelector('.post-body-wrap .status-message').dataset.statusKey, 'editEmpty');
  assert.equal(h.doc.activeElement, ta);
  assert.equal(h.requests.filter((r) => r.init.method === 'PATCH').length, 0);

  // Over-limit post edit -> postTooLong, no PATCH
  ta.value = 'x'.repeat(1001);
  ta.dispatchEvent({ type: 'input' });
  card.querySelector('.post-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(card.querySelector('.post-body-wrap .status-message').dataset.statusKey, 'postTooLong');
  assert.equal(h.requests.filter((r) => r.init.method === 'PATCH').length, 0);

  // Switch language while editor is open -> aria-label updates to VI
  h.doc.getElementById('lang-vi-button').click();
  assert.equal(ta.getAttribute('aria-label'), 'Nội dung bài viết');
  h.doc.getElementById('lang-en-button').click();

  // Refresh feed while editing post -> newer postObj is adopted in article._post and posts[]
  postObj = { ...postObj, body: 'Server refreshed post' };
  h.doc.getElementById('refresh-button').click();
  await flush();

  // Cancel restores refreshed post body (not stale initial postObj!) and focuses [data-action="edit"]
  card.querySelector('.post-body-wrap [data-edit-action="cancel"]').click();
  assert.equal(card.querySelector('.post-body').textContent, 'Server refreshed post');
  assert.equal(h.doc.activeElement, card.querySelector('[data-action="edit"]'));

  // Re-open, refresh again, then save -> updates refreshed post in posts[] and restores focus
  card.querySelector('[data-action="edit"]').click();
  const ta2 = card.querySelector('.post-body-wrap textarea');
  postObj = { ...postObj, body: 'Refreshed before save', reactions: [{ emoji: '👍', count: 2, mine: false }] };
  h.doc.getElementById('refresh-button').click();
  await flush();

  // Failed PATCH shows editFailed and re-enables controls
  failNextPatch = true;
  ta2.value = 'Updated after refresh';
  ta2.dispatchEvent({ type: 'input' });
  card.querySelector('.post-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(card.querySelector('.post-body-wrap .status-message').dataset.statusKey, 'editFailed');
  assert.equal(ta2.disabled, false);

  // Retry save succeeds -> mutates refreshed posts[] object & restores focus
  card.querySelector('.post-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(card.querySelector('.post-body').textContent, 'Updated after refresh');
  assert.equal(h.doc.activeElement, card.querySelector('[data-action="edit"]'));

  // Comment edit validation (whitespace + >500) + aria-label + focus restore
  card.querySelector('[data-action="comments"]').click();
  await flush();
  const cCard = card.querySelector('.comment-card');
  cCard.querySelector('[data-action="edit-comment"]').click();
  const cTa = cCard.querySelector('.comment-body-wrap textarea');
  assert.equal(cTa.getAttribute('aria-label'), 'Write a comment');
  cTa.value = '   ';
  cTa.dispatchEvent({ type: 'input' });
  cCard.querySelector('.comment-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(cCard.querySelector('.comment-body-wrap .status-message').dataset.statusKey, 'editEmpty');
  cTa.value = 'y'.repeat(501);
  cTa.dispatchEvent({ type: 'input' });
  cCard.querySelector('.comment-body-wrap [data-edit-action="save"]').click();
  await flush();
  assert.equal(cCard.querySelector('.comment-body-wrap .status-message').dataset.statusKey, 'commentTooLong');
  cCard.querySelector('.comment-body-wrap [data-edit-action="cancel"]').click();
  assert.equal(h.doc.activeElement, cCard.querySelector('[data-action="edit-comment"]'));

  // Composer & comment over-limit client validation
  const postBodyEl = h.doc.getElementById('post-body');
  postBodyEl.value = 'z'.repeat(1001);
  h.doc.getElementById('post-submit').click();
  await flush();
  assert.equal(h.doc.getElementById('composer-status').dataset.statusKey, 'postTooLong');

  const commentInput = h.doc.getElementById(`comment-input-${postId}`);
  commentInput.value = 'c'.repeat(501);
  cCard.closest('.comments-region').querySelector('.comment-composer .button-primary').click();
  await flush();
  assert.equal(h.doc.getElementById('feed-status').dataset.statusKey, 'commentTooLong');
});

test('search + authorId reset cursor, failed reset clears stale nextCursor, and comment-author filter keeps focus connected', async () => {
  const authorA = '11111111-1111-1111-1111-111111111111';
  const authorB = '22222222-2222-2222-2222-222222222222';
  const postA = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const postB = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  let failSearch = false;

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: authorA, displayName: 'Author A' }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        const parsed = new URL(`https://x${url}`);
        const q = parsed.searchParams.get('q');
        const aid = parsed.searchParams.get('authorId');
        if (failSearch && q === 'boom') {
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        if (aid === authorB) {
          return jsonRes(200, {
            items: [{ id: postB, author: { id: authorB, displayName: 'Author B' }, body: 'B post', image: null, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false }],
            nextCursor: null,
          });
        }
        return jsonRes(200, {
          items: [{ id: postA, author: { id: authorA, displayName: 'Author A' }, body: 'A post', image: null, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 1, reactions: [], mine: true }],
          nextCursor: 'cursor-page-2',
        });
      }
      if (url.startsWith(`/api/social/v1/posts/${postA}/comments`)) {
        return jsonRes(200, {
          items: [{ id: 'c1', postId: postA, author: { id: authorB, displayName: 'Author B' }, body: 'Comment by B', createdAt: '2026-11-28T10:01:00Z', editedAt: null, mine: false }],
          nextCursor: null,
        });
      }
      if (url === `/api/social/v1/authors/${authorB}`) {
        return jsonRes(200, { userId: authorB, displayName: 'Author B', avatarUrl: null, postCount: 1 });
      }
      return null;
    },
  });

  await signInHarness(h);
  assert.equal(h.doc.getElementById('load-more-button').hidden, false);

  // Failed reset search must clear nextCursor & hide loadMoreButton immediately
  failSearch = true;
  const searchInput = h.doc.getElementById('feed-search-input');
  searchInput.value = 'boom';
  searchInput.dispatchEvent({ type: 'keydown', key: 'Enter' });
  await flush();
  assert.equal(h.doc.getElementById('load-more-button').hidden, true);
  assert.equal(h.doc.getElementById('feed-status').dataset.statusKey, 'feedError');

  // Clear failSearch so combined q + authorId succeeds and removes postA
  failSearch = false;
  // Open comments on postA, open Author B modal from comment card, filter by Author B -> postA disappears & focus stays connected on author-filter-clear
  const cardA = h.doc.getElementById(`post-${postA}`);
  cardA.querySelector('[data-action="comments"]').click();
  await flush();
  const commentTrigger = cardA.querySelector('.comment-card .author-trigger');
  commentTrigger.click();
  await flush();
  h.doc.getElementById('author-dialog-filter').click();
  await flush();
  assert.equal(h.doc.getElementById(`post-${postA}`), null);
  assert.equal(h.doc.activeElement.isConnected, true);
  assert.equal(h.doc.activeElement, h.doc.getElementById('author-filter-clear'));

  // Combined q + authorId query sends both params with no stale cursor
  const combinedCall = h.requests.find((r) => r.url.includes(`authorId=${authorB}`) && r.url.includes('q=boom') && !r.url.includes('cursor='));
  assert.ok(combinedCall, 'expected combined authorId + q request without stale cursor');
});

test('author modal handles 404/500, 401 session expiry, and Escape cancel ignoring stale fetch resolution', async () => {
  const authorId = '11111111-1111-1111-1111-111111111111';
  const postId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  let authorMode = 'error500';
  let releaseAuthor;
  const authorGate = new Promise((resolve) => {
    releaseAuthor = resolve;
  });

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: authorId, displayName: 'An' }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        return jsonRes(200, {
          items: [{ id: postId, author: { id: authorId, displayName: 'An' }, body: 'Hi', image: null, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false }],
          nextCursor: null,
        });
      }
      if (url === `/api/social/v1/authors/${authorId}`) {
        if (authorMode === 'error500') return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        if (authorMode === 'delayed') {
          await authorGate;
          return jsonRes(200, { userId: authorId, displayName: 'An', avatarUrl: null, postCount: 9 });
        }
        return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
      }
      if (url === '/api/social/v1/session/refresh') {
        return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
      }
      return null;
    },
  });

  await signInHarness(h);
  const trigger = h.doc.getElementById(`post-${postId}`).querySelector('.author-trigger');

  // 500 error shows authorProfileError and keeps filter button hidden
  trigger.click();
  await flush();
  assert.equal(h.doc.getElementById('author-dialog-status').dataset.statusKey, 'authorProfileError');
  assert.equal(h.doc.getElementById('author-dialog-filter').hidden, true);
  h.doc.getElementById('author-dialog-close').click();

  // Delayed fetch cancelled by Escape/close must not repopulate dialog
  authorMode = 'delayed';
  trigger.click();
  h.doc.getElementById('author-dialog').close();
  releaseAuthor();
  await flush();
  assert.equal(h.doc.getElementById('author-dialog').open, false);
  assert.equal(h.doc.getElementById('author-dialog-filter').hidden, true);

  // 401 closes dialog and expires session
  authorMode = 'unauth';
  trigger.click();
  await flush();
  assert.equal(h.doc.getElementById('author-dialog').open, false);
  assert.equal(h.doc.getElementById('login-status').dataset.statusKey, 'sessionExpired');
});

test('avatars recover after transient failure, expire session on 401, update on refresh, and prune/revoke object URLs', async () => {
  const authorId = '11111111-1111-1111-1111-111111111111';
  const post1Id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const post2Id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const imgId = '55555555-5555-5555-5555-555555555555';
  const avatar1 = `/api/v1/files/avatars/${authorId}-v1.png`;
  const avatar2 = `/api/v1/files/avatars/${authorId}-v2.png`;
  let failAvatarOnce = true;
  let feedVersion = 1;
  let unauthAvatar = false;

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: authorId, displayName: 'An', avatarUrl: null }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url === '/api/social/v1/session/logout') {
        return jsonRes(204, null);
      }
      if (url === '/api/social/v1/session/refresh') {
        return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
      }
      if (url === avatar1) {
        if (unauthAvatar) return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
        if (failAvatarOnce) {
          failAvatarOnce = false;
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        return new Response(new Blob(['png1'], { type: 'image/png' }), { status: 200 });
      }
      if (url === avatar2) {
        return new Response(new Blob(['png2'], { type: 'image/png' }), { status: 200 });
      }
      if (url === `/api/social/v1/images/${imgId}`) {
        return new Response(new Blob(['img'], { type: 'image/png' }), { status: 200 });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        if (feedVersion === 1) {
          return jsonRes(200, {
            items: [
              { id: post1Id, author: { id: authorId, displayName: 'An', avatarUrl: avatar1 }, body: 'P1', image: { url: `/api/social/v1/images/${imgId}` }, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false },
            ],
            nextCursor: null,
          });
        }
        if (feedVersion === 2) {
          return jsonRes(200, {
            items: [
              { id: post1Id, author: { id: authorId, displayName: 'An', avatarUrl: avatar2 }, body: 'P1', image: null, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false },
              { id: post2Id, author: { id: authorId, displayName: 'An', avatarUrl: null }, body: 'P2', image: null, createdAt: '2026-11-28T10:01:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false },
            ],
            nextCursor: null,
          });
        }
        return jsonRes(200, {
          items: [
            { id: post2Id, author: { id: authorId, displayName: 'An', avatarUrl: avatar1 }, body: 'P2', image: null, createdAt: '2026-11-28T10:01:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false },
          ],
          nextCursor: null,
        });
      }
      return null;
    },
  });

  await signInHarness(h);
  await flush();
  const card1 = h.doc.getElementById(`post-${post1Id}`);
  assert.equal(card1.querySelector('.author-trigger img.avatar-img'), null, 'initial transient 500 keeps initials fallback');
  assert.ok(card1.querySelector('.post-image-wrap img.post-image'));

  // Refresh updates avatarUrl on existing post AND prunes removed image blob URL
  feedVersion = 2;
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.ok(card1.querySelector('.author-trigger img.avatar-img'), 'updated avatarUrl loads on refresh');
  assert.equal(h.revokedUrls.length, 1, 'pruned removed post image blob URL');

  // Switch to feedVersion 3 where post1 (with avatar2) is gone and post2 uses avatar1 (recovering from earlier transient failure!)
  feedVersion = 3;
  h.doc.getElementById('refresh-button').click();
  await flush();
  const card2 = h.doc.getElementById(`post-${post2Id}`);
  assert.ok(card2.querySelector('.author-trigger img.avatar-img'), 'transiently failed avatar1 recovers on later render');
  assert.equal(h.revokedUrls.length, 2, 'pruned unused avatar2 blob URL');

  // Sign out revokes remaining avatar1 blob URL
  h.doc.getElementById('logout-button').click();
  await flush();
  assert.equal(h.revokedUrls.length, 3, 'sign out revokes remaining blob URLs');

  // 401 on avatar expires session
  failAvatarOnce = false;
  unauthAvatar = true;
  await signInHarness(h);
  assert.equal(h.doc.getElementById('login-status').dataset.statusKey, 'sessionExpired');
});

test('same-card avatar retries after 500 on Refresh, deduplicates in-flight fetches, renders cached comment avatars, and expires on 401 retry', async () => {
  const author1Id = '11111111-1111-1111-1111-111111111111';
  const author3Id = '33333333-3333-3333-3333-333333333333';
  const author4Id = '44444444-4444-4444-4444-444444444444';
  const post1Id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const post3Id = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  const post4Id = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
  const comment1Id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
  const avatar1 = `/api/v1/files/avatars/${author1Id}-same.png`;
  const avatar3 = `/api/v1/files/avatars/${author3Id}-delayed.png`;
  const avatar4 = `/api/v1/files/avatars/${author4Id}-unauth.png`;

  let avatar1Mode = 'error500';
  let avatar1Fetches = 0;
  let avatar3Fetches = 0;
  let avatar4Mode = 'error500';
  let feedPhase = 1;
  let releaseAvatar3;
  const avatar3Gate = new Promise((resolve) => {
    releaseAvatar3 = resolve;
  });

  const h = makeHarness({
    hash: '#lang-en',
    handler: async (url) => {
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: author1Id, displayName: 'An', avatarUrl: null }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url === '/api/social/v1/session/refresh') {
        return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
      }
      if (url === avatar1) {
        avatar1Fetches += 1;
        if (avatar1Mode === 'error500') {
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        return new Response(new Blob(['png1'], { type: 'image/png' }), { status: 200 });
      }
      if (url === avatar3) {
        avatar3Fetches += 1;
        await avatar3Gate;
        return new Response(new Blob(['png3'], { type: 'image/png' }), { status: 200 });
      }
      if (url === avatar4) {
        if (avatar4Mode === 'error500') {
          return jsonRes(500, { error: { code: 'INTERNAL_ERROR' } });
        }
        return jsonRes(401, { error: { code: 'UNAUTHORIZED' } });
      }
      if (url.startsWith(`/api/social/v1/posts/${post1Id}/comments`)) {
        return jsonRes(200, {
          items: [
            { id: comment1Id, postId: post1Id, author: { id: author1Id, displayName: 'An', avatarUrl: avatar1 }, body: 'Cached avatar comment', createdAt: '2026-11-28T10:02:00Z', editedAt: null, mine: false },
          ],
          nextCursor: null,
        });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        const items = [
          { id: post1Id, author: { id: author1Id, displayName: 'An', avatarUrl: avatar1 }, body: 'Same card post', image: null, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 1, reactions: [], mine: false },
        ];
        if (feedPhase >= 2) {
          items.push({ id: post3Id, author: { id: author3Id, displayName: 'Chi', avatarUrl: avatar3 }, body: 'Delayed avatar post', image: null, createdAt: '2026-11-28T10:01:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false });
        }
        if (feedPhase >= 3) {
          items.push({ id: post4Id, author: { id: author4Id, displayName: 'Dung', avatarUrl: avatar4 }, body: '401 retry post', image: null, createdAt: '2026-11-28T10:02:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false });
        }
        return jsonRes(200, { items, nextCursor: null });
      }
      return null;
    },
  });

  await signInHarness(h);
  await flush();
  const card1 = h.doc.getElementById(`post-${post1Id}`);
  assert.equal(avatar1Fetches, 1);
  assert.equal(card1.querySelector('.author-trigger img.avatar-img'), null, 'initial 500 keeps initials fallback');

  // Same-card Refresh retries avatar1 and attaches img.avatar-img without creating a new card
  avatar1Mode = 'ok';
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.equal(h.doc.getElementById(`post-${post1Id}`), card1, 'refresh reuses same post card');
  assert.equal(avatar1Fetches, 2, 'refresh retries failed avatar on same card');
  assert.ok(card1.querySelector('.author-trigger img.avatar-img')?.src.startsWith('blob:'));

  // Language toggle DOM syncs must not re-fetch already loaded avatar1
  h.doc.getElementById('lang-vi-button').click();
  h.doc.getElementById('lang-en-button').click();
  await flush();
  assert.equal(avatar1Fetches, 2);

  // Expanding comments renders cached avatar1 immediately on disconnected newly built comment card
  card1.querySelector('[data-action="comments"]').click();
  await flush();
  const commentImg = card1.querySelector('.comment-card .author-trigger img.avatar-img');
  assert.ok(commentImg, 'cached avatar must render on newly created comment card');
  assert.ok(commentImg.src.startsWith('blob:'));
  assert.equal(avatar1Fetches, 2, 'cached comment avatar must not trigger extra fetch');

  // In-flight avatar fetch is deduplicated across language toggle DOM sync
  feedPhase = 2;
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.equal(avatar3Fetches, 1);
  h.doc.getElementById('lang-vi-button').click();
  h.doc.getElementById('lang-en-button').click();
  assert.equal(avatar3Fetches, 1, 'language toggle during in-flight fetch must not issue duplicate request');
  releaseAvatar3();
  await flush();
  const card3 = h.doc.getElementById(`post-${post3Id}`);
  assert.ok(card3.querySelector('.author-trigger img.avatar-img')?.src.startsWith('blob:'));
  assert.equal(avatar3Fetches, 1);

  // 401 on avatar during Refresh retry (after initial 500) expires session
  feedPhase = 3;
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.notEqual(h.doc.getElementById('login-status').dataset.statusKey, 'sessionExpired');
  avatar4Mode = 'unauth';
  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.equal(h.doc.getElementById('login-status').dataset.statusKey, 'sessionExpired');
});

test('videoUploadsEnabled=false on POST /videos keeps composer visible, video error Retry re-fetches, reaction tray closes immediately, and announcement stays visible when disabled', async () => {
  const authorId = '11111111-1111-1111-1111-111111111111';
  const postId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const videoId = '66666666-6666-6666-6666-666666666666';
  let videoUploadsEnabled = true;
  let webEnabled = true;
  let videoFetches = 0;

  const h = makeHarness({
    hash: '#lang-en',
    search: '?utm=1',
    handler: async (url, init) => {
      if (url === '/api/social/v1/config') {
        return jsonRes(200, {
          webEnabled,
          videoUploadsEnabled,
          announcement: {
            titleVi: 'Thông báo VI',
            bodyVi: 'Nội dung VI',
            titleEn: 'Notice EN',
            bodyEn: 'Body EN',
          },
        });
      }
      if (url === '/api/social/v1/session/login') {
        return jsonRes(200, { user: { id: authorId, displayName: 'An' }, accessToken: 'acc', accessTokenExpiresIn: 3600, refreshToken: 'ref' });
      }
      if (url.startsWith('/api/social/v1/posts?')) {
        return jsonRes(200, {
          items: [{ id: postId, author: { id: authorId, displayName: 'An' }, body: 'Video post', image: null, video: { url: `/api/social/v1/videos/${videoId}` }, createdAt: '2026-11-28T10:00:00Z', editedAt: null, commentCount: 0, reactions: [], mine: false }],
          nextCursor: null,
        });
      }
      if (url === `/api/social/v1/videos/${videoId}`) {
        videoFetches += 1;
        return new Response(new Blob(['mp4'], { type: 'video/mp4' }), { status: 200 });
      }
      if (url === '/api/social/v1/videos' && init.method === 'POST') {
        videoUploadsEnabled = false;
        return jsonRes(403, { error: { code: 'SOCIAL_FEATURE_DISABLED' } });
      }
      if (url.startsWith(`/api/social/v1/posts/${postId}/reactions/`)) {
        return jsonRes(200, { reactions: [{ emoji: '👍', count: 1, mine: true }] });
      }
      return null;
    },
  });

  await signInHarness(h);

  // Bilingual announcement banner displays EN, and switching to VI preserves query string
  assert.equal(h.doc.getElementById('announcement-card').hidden, false);
  assert.equal(h.doc.getElementById('announcement-title').textContent, 'Notice EN');
  h.doc.getElementById('lang-vi-button').click();
  assert.equal(h.historyStub.lastUrl, '/social/?utm=1');
  assert.equal(h.doc.getElementById('announcement-title').textContent, 'Thông báo VI');
  h.doc.getElementById('lang-en-button').click();

  // Reaction picker tray hides immediately on option click before awaiting network
  const card = h.doc.getElementById(`post-${postId}`);
  const pickerBtn = card.querySelector('[data-action="reaction-picker"]');
  pickerBtn.click();
  const tray = card.querySelector('.reaction-picker-tray');
  assert.equal(tray.hidden, false);
  tray.querySelector('.reaction-emoji-option[data-emoji="👍"]').click();
  assert.equal(tray.hidden, true);
  assert.equal(pickerBtn.getAttribute('aria-expanded'), 'false');
  await flush();

  // Language toggle updates .video-load-button text
  h.doc.getElementById('lang-vi-button').click();
  assert.equal(card.querySelector('.video-load-button').textContent, 'Tải video');
  h.doc.getElementById('lang-en-button').click();
  assert.equal(card.querySelector('.video-load-button').textContent, 'Load video');

  // Video playback error -> shows videoPlaybackFailed + Retry -> refresh preserves error UI -> Retry evicts cache, revokes old URL, guards double-click, and re-fetches
  card.querySelector('.video-load-button').click();
  await flush();
  assert.equal(videoFetches, 1);
  const videoEl = card.querySelector('video.post-video');
  videoEl.dispatchEvent({ type: 'error' });
  const errEl = card.querySelector('.video-error');
  assert.equal(errEl.dataset.videoErrorKey, 'videoPlaybackFailed');
  h.doc.getElementById('lang-vi-button').click();
  assert.equal(card.querySelector('.video-error').textContent, 'Không phát được video này. Thử lại hoặc dùng MP4 H.264.');
  h.doc.getElementById('lang-en-button').click();

  h.doc.getElementById('refresh-button').click();
  await flush();
  assert.equal(card.querySelector('.video-error')?.dataset.videoErrorKey, 'videoPlaybackFailed');
  assert.ok(card.querySelector('.video-retry-button'), 'refresh while video error is shown keeps Retry button');

  card.querySelector('.video-retry-button').click();
  assert.equal(card.querySelector('.video-retry-button'), null, 'Retry removes button synchronously to prevent double-click');
  await flush();
  assert.equal(videoFetches, 2, 'Retry must re-fetch video instead of reusing failed blob URL');
  assert.equal(h.revokedUrls.length >= 1, true, 'Retry must revoke previous failed video blob URL');

  // POST /videos returning 403 SOCIAL_FEATURE_DISABLED when webEnabled=true keeps composer visible and sets mediaVideoDisabled
  const videoFile = new File(['mp4'], 'clip.mp4', { type: 'video/mp4' });
  h.doc.getElementById('post-media').files = [videoFile];
  h.doc.getElementById('post-media').dispatchEvent({ type: 'change' });
  h.doc.getElementById('post-body').value = 'My video';
  h.doc.getElementById('post-submit').click();
  await flush();
  assert.equal(h.doc.getElementById('unavailable-panel').hidden, true);
  assert.equal(h.doc.getElementById('composer-panel').hidden, false);
  assert.equal(h.doc.getElementById('media-selected').hidden, true);
  assert.equal(h.doc.getElementById('composer-status').dataset.statusKey, 'mediaVideoDisabled');

  // Announcement stays visible when webEnabled turns false
  webEnabled = false;
  h.doc.dispatchEvent({ type: 'visibilitychange' });
  await flush();
  assert.equal(h.doc.getElementById('unavailable-panel').hidden, false);
  assert.equal(h.doc.getElementById('announcement-card').hidden, false);
});
