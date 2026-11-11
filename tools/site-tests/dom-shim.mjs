/**
 * Minimal DOM used by the static-site behaviour tests.
 *
 * It implements only the surface exercised by `site/delete-account.js`:
 * attribute access, the `hidden`/`disabled` reflected properties, focus,
 * click, event listeners and descendant queries. Real browser semantics that
 * matter for the script are modelled deliberately:
 *
 * - clicking a disabled button does nothing;
 * - focusing a disabled or hidden element does nothing;
 * - the `hidden` attribute hides an element and its descendants.
 *
 * Keeping this in-repo (instead of downloading jsdom) makes the suite runnable
 * with only Node's built-in test runner, offline.
 */

const SELECTOR_PATTERN =
  /^([a-zA-Z][\w-]*)(?:\[([\w-]+)(?:=(?:"([^"]*)"|'([^']*)'))?\])?$/;

function parseSelector(selector) {
  const trimmed = String(selector).trim();
  if (trimmed.startsWith('#')) {
    return { id: trimmed.slice(1) };
  }
  const match = SELECTOR_PATTERN.exec(trimmed);
  if (!match) {
    throw new Error(`dom-shim: unsupported selector "${selector}"`);
  }
  const hasValue = match[3] !== undefined || match[4] !== undefined;
  return {
    tag: match[1].toUpperCase(),
    attr: match[2] || null,
    value: hasValue ? (match[3] !== undefined ? match[3] : match[4]) : null,
    hasValue,
  };
}

function matches(element, selector) {
  const parsed = parseSelector(selector);
  if (parsed.id !== undefined) {
    return element.getAttribute('id') === parsed.id;
  }
  if (element.tagName !== parsed.tag) {
    return false;
  }
  if (!parsed.attr) {
    return true;
  }
  if (!element.hasAttribute(parsed.attr)) {
    return false;
  }
  return !parsed.hasValue || element.getAttribute(parsed.attr) === parsed.value;
}

function splitSelectors(selector) {
  return String(selector)
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

export class ShimElement {
  constructor(document, tagName) {
    this.ownerDocument = document;
    this.tagName = String(tagName).toUpperCase();
    this.parent = null;
    this.value = '';
    this.checked = false;
    this._attributes = new Map();
    this._listeners = new Map();
    this._children = [];
  }

  get id() {
    return this.getAttribute('id') || '';
  }

  get disabled() {
    return this.hasAttribute('disabled');
  }

  set disabled(value) {
    if (value) {
      this.setAttribute('disabled', '');
    } else {
      this.removeAttribute('disabled');
    }
  }

  get hidden() {
    return this.hasAttribute('hidden');
  }

  set hidden(value) {
    if (value) {
      this.setAttribute('hidden', '');
    } else {
      this.removeAttribute('hidden');
    }
  }

  getAttribute(name) {
    const key = String(name).toLowerCase();
    return this._attributes.has(key) ? this._attributes.get(key) : null;
  }

  setAttribute(name, value) {
    this._attributes.set(String(name).toLowerCase(), String(value));
  }

  removeAttribute(name) {
    this._attributes.delete(String(name).toLowerCase());
  }

  hasAttribute(name) {
    return this._attributes.has(String(name).toLowerCase());
  }

  appendChild(child) {
    child.parent = this;
    this._children.push(child);
    return child;
  }

  append(...children) {
    for (const child of children) {
      this.appendChild(child);
    }
  }

  isHidden() {
    let node = this;
    while (node && typeof node.hasAttribute === 'function') {
      if (node.hasAttribute('hidden')) {
        return true;
      }
      node = node.parent;
    }
    return false;
  }

  addEventListener(type, listener) {
    const key = String(type);
    if (!this._listeners.has(key)) {
      this._listeners.set(key, new Set());
    }
    this._listeners.get(key).add(listener);
  }

  removeEventListener(type, listener) {
    const listeners = this._listeners.get(String(type));
    if (listeners) {
      listeners.delete(listener);
    }
  }

  dispatchEvent(event) {
    const ev = { ...event };
    ev.type = String(event.type);
    ev.target = this;
    ev.currentTarget = this;
    if (typeof ev.preventDefault !== 'function') {
      ev.defaultPrevented = false;
      ev.preventDefault = () => {
        ev.defaultPrevented = true;
      };
    }
    for (const listener of [...(this._listeners.get(ev.type) || [])]) {
      listener.call(this, ev);
    }
    return !ev.defaultPrevented;
  }

  click() {
    if (this.disabled) {
      return false;
    }
    return this.dispatchEvent({ type: 'click' });
  }

  focus() {
    if (this.disabled || this.isHidden()) {
      return false;
    }
    this.ownerDocument.activeElement = this;
    return true;
  }

  querySelectorAll(selector) {
    const selectors = splitSelectors(selector);
    const found = [];
    const visit = (node) => {
      for (const child of node._children) {
        if (selectors.some((entry) => matches(child, entry))) {
          found.push(child);
        }
        visit(child);
      }
    };
    visit(this);
    return found;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
}

class ShimDocument {
  constructor() {
    this.readyState = 'complete';
    this._elements = [];
    this.documentElement = this.createElement('html');
    this.head = this.createElement('head');
    this.body = this.createElement('body');
    this.documentElement.append(this.head, this.body);
    this.activeElement = this.body;
    this._listeners = new Map();
  }

  createElement(tagName) {
    const element = new ShimElement(this, tagName);
    this._elements.push(element);
    return element;
  }

  getElementById(id) {
    const wanted = String(id);
    return this._elements.find((element) => element.getAttribute('id') === wanted) || null;
  }

  querySelectorAll(selector) {
    const selectors = splitSelectors(selector);
    return this._elements.filter((element) =>
      selectors.some((entry) => matches(element, entry)),
    );
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  addEventListener(type, listener) {
    const key = String(type);
    if (!this._listeners.has(key)) {
      this._listeners.set(key, new Set());
    }
    this._listeners.get(key).add(listener);
  }

  removeEventListener(type, listener) {
    const listeners = this._listeners.get(String(type));
    if (listeners) {
      listeners.delete(listener);
    }
  }

  dispatchEvent(event) {
    const ev = { ...event };
    ev.type = String(event.type);
    ev.target = this;
    if (typeof ev.preventDefault !== 'function') {
      ev.defaultPrevented = false;
      ev.preventDefault = () => {
        ev.defaultPrevented = true;
      };
    }
    for (const listener of [...(this._listeners.get(ev.type) || [])]) {
      listener.call(this, ev);
    }
    return !ev.defaultPrevented;
  }
}

export function createDocument() {
  return new ShimDocument();
}

export function listElementIds(document) {
  return document._elements
    .map((element) => element.getAttribute('id'))
    .filter((id) => id !== null);
}
