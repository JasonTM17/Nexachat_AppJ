/**
 * Fixture and harness for the `site/delete-account.js` behaviour tests.
 *
 * The fixture mirrors the ids, hidden/disabled initial state and
 * aria-describedby wiring of `site/delete-account.html`. The contract test in
 * `delete-account.test.mjs` re-reads the real page and fails if the script
 * starts using an id that is missing here or in the page.
 */
import { readFileSync } from 'node:fs';
import { createDocument, listElementIds } from './dom-shim.mjs';

const SITE_DIR = new URL('../../site/', import.meta.url);

export const DELETE_ACCOUNT_SCRIPT = readFileSync(
  new URL('delete-account.js', SITE_DIR),
  'utf8',
);

function element(document, tagName, id, attributes = {}) {
  const node = document.createElement(tagName);
  if (id) {
    node.setAttribute('id', id);
  }
  for (const [name, value] of Object.entries(attributes)) {
    node.setAttribute(name, value);
  }
  return node;
}

export function buildDeletionPage({ apiBase = '' } = {}) {
  const document = createDocument();

  const meta = element(document, 'meta');
  meta.setAttribute('name', 'nexachat-api-base');
  meta.setAttribute('content', apiBase);
  document.head.appendChild(meta);

  const section = element(document, 'section', 'web-deletion-section');
  const unavailable = element(document, 'div', 'web-deletion-unavailable');
  const formWrap = element(document, 'div', 'web-deletion-form-wrap', { hidden: '' });
  section.append(unavailable, formWrap);
  document.body.appendChild(section);

  const form = element(document, 'form', 'web-deletion-form', {
    action: '#',
    method: 'post',
    novalidate: '',
  });

  const phone = element(document, 'input', 'deletion-phone', {
    type: 'tel',
    name: 'phone',
    'aria-describedby': 'deletion-phone-error',
  });
  const phoneError = element(document, 'p', 'deletion-phone-error', {
    role: 'alert',
    hidden: '',
  });

  const sendButton = element(document, 'button', 'btn-send-code', { type: 'button' });

  const sendError = element(document, 'p', 'web-deletion-send-error', {
    role: 'alert',
    tabindex: '-1',
    hidden: '',
  });

  const feedback = element(document, 'p', 'request-feedback', {
    'aria-live': 'polite',
    hidden: '',
  });

  const postSend = element(document, 'div', 'post-send-fields', { hidden: '' });

  const code = element(document, 'input', 'deletion-code', {
    type: 'text',
    name: 'code',
    inputmode: 'numeric',
    maxlength: '6',
    autocomplete: 'one-time-code',
    'aria-describedby': 'deletion-code-error',
  });
  const codeError = element(document, 'p', 'deletion-code-error', {
    role: 'alert',
    hidden: '',
  });

  const confirm = element(document, 'input', 'deletion-confirm', {
    type: 'checkbox',
    name: 'confirm',
  });

  const deleteButton = element(document, 'button', 'btn-delete-account', {
    type: 'button',
    disabled: '',
  });

  postSend.append(code, codeError, confirm, deleteButton);

  const rateError = element(document, 'p', 'web-deletion-rate-error', {
    role: 'alert',
    tabindex: '-1',
    hidden: '',
  });
  const formError = element(document, 'p', 'web-deletion-form-error', {
    role: 'alert',
    tabindex: '-1',
    hidden: '',
  });

  form.append(phone, phoneError, sendButton, sendError, feedback, postSend, rateError, formError);
  formWrap.appendChild(form);

  const success = element(document, 'div', 'web-deletion-success', {
    'aria-live': 'polite',
    tabindex: '-1',
    hidden: '',
  });
  formWrap.appendChild(success);

  return {
    document,
    els: {
      meta,
      unavailable,
      formWrap,
      form,
      phone,
      phoneError,
      sendButton,
      sendError,
      feedback,
      postSend,
      code,
      codeError,
      confirm,
      deleteButton,
      rateError,
      formError,
      success,
    },
  };
}

export function runDeleteAccountScript(document, fetch) {
  // `new Function` keeps each test isolated: the script's IIFE runs against the
  // supplied document/fetch and leaves no module-level state behind.
  const evaluate = new Function('document', 'fetch', 'URL', DELETE_ACCOUNT_SCRIPT);
  evaluate(document, fetch, URL);
}

/**
 * Build a page, install a recording fetch stub, and execute the production
 * script. `respond` receives the zero-based call index and may return a status,
 * an Error (network failure), or a promise of `{ status }` for in-flight tests.
 */
export function createHarness({ apiBase = '', respond = () => 202 } = {}) {
  const page = buildDeletionPage({ apiBase });
  const calls = [];
  let callIndex = 0;

  const fetch = (url, init) => {
    calls.push({ url, init });
    const result = respond(callIndex, url, init);
    callIndex += 1;
    if (result instanceof Error) {
      return Promise.reject(result);
    }
    if (result && typeof result.then === 'function') {
      return result;
    }
    return Promise.resolve({ status: result });
  };

  runDeleteAccountScript(page.document, fetch);
  return { ...page, calls };
}

export function fixtureIds(page) {
  return listElementIds(page.document);
}

/** Let the fetch promise chain (then/catch/finally) settle. */
export async function flush() {
  await new Promise((resolve) => setImmediate(resolve));
}

export function type(input, value) {
  input.value = value;
  input.dispatchEvent({ type: 'input' });
}

export function keydown(key) {
  const event = { type: 'keydown', key };
  event.preventDefault = () => {
    event.defaultPrevented = true;
  };
  return event;
}
